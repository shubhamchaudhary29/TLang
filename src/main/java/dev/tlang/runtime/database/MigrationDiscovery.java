package dev.tlang.runtime.database;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Secure, deterministic discovery and snapshotting of migration files. */
final class MigrationDiscovery {
    private static final String NAME_GRAMMAR =
        "[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}_.-]*";
    private static final Pattern FILENAME = Pattern.compile(
        "^([0-9]+)_(" + NAME_GRAMMAR + ")\\.sql$");
    private static final Pattern NAME = Pattern.compile("^" + NAME_GRAMMAR + "$");
    private static final BigInteger MAX_VERSION = BigInteger.valueOf(Integer.MAX_VALUE);

    private MigrationDiscovery() {}

    static List<MigrationFile> discover(String suppliedPath, SqlScriptParser.Dialect dialect) {
        if (suppliedPath == null || suppliedPath.isBlank()
                || suppliedPath.chars().anyMatch(Character::isISOControl)) {
            throw new DatabaseFailure("Migration path is invalid.");
        }
        final Path lexical;
        try {
            lexical = Path.of(suppliedPath);
        } catch (RuntimeException failure) {
            throw new DatabaseFailure("Migration path is invalid.");
        }
        for (Path component : lexical) {
            if (component.toString().equals("..")) {
                throw new DatabaseFailure("Migration path must not contain parent traversal.");
            }
        }
        Path directory = lexical.toAbsolutePath().normalize();
        rejectSymbolicComponents(directory);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new DatabaseFailure("Migration path does not exist.");
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new DatabaseFailure("Migration path must be a directory.");
        }
        if (!Files.isReadable(directory)) {
            throw new DatabaseFailure("Migration directory is not readable.");
        }

        List<MigrationFile> migrations = new ArrayList<>();
        Set<Integer> versions = new HashSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String filename = entry.getFileName().toString();
                if (Files.isSymbolicLink(entry)) {
                    throw new DatabaseFailure(
                        "Symbolic links are not allowed in migration directories: " + safe(filename) + ".");
                }
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) continue;
                if (filename.startsWith(".")) continue;
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw new DatabaseFailure("Unsupported migration directory entry: " + safe(filename) + ".");
                }
                Matcher matcher = FILENAME.matcher(filename);
                if (!matcher.matches()) {
                    throw new DatabaseFailure("Invalid migration filename: " + safe(filename) + ".");
                }
                BigInteger parsed = new BigInteger(matcher.group(1));
                if (parsed.signum() <= 0 || parsed.compareTo(MAX_VERSION) > 0) {
                    throw new DatabaseFailure("Migration version is outside the supported integer range: "
                        + safe(filename) + ".");
                }
                int version = parsed.intValueExact();
                if (!versions.add(version)) {
                    throw new DatabaseFailure("Duplicate migration version: " + version + ".");
                }
                byte[] bytes = read(entry, filename);
                String sql = decode(bytes, filename);
                List<String> statements = SqlScriptParser.split(sql, dialect);
                if (statements.isEmpty()) {
                    throw new DatabaseFailure("Migration is empty: " + safe(filename) + ".");
                }
                if (statements.stream().anyMatch(SqlScriptParser::isTransactionControl)) {
                    throw new DatabaseFailure(
                        "Migration contains forbidden transaction control: " + safe(filename) + ".");
                }
                migrations.add(new MigrationFile(
                    version, matcher.group(1), matcher.group(2), filename, entry,
                    checksum(bytes), statements));
            }
        } catch (DatabaseFailure failure) {
            throw failure;
        } catch (IOException failure) {
            throw new DatabaseFailure("Migration directory could not be read.", failure);
        }
        migrations.sort(Comparator.comparingInt(MigrationFile::version));
        return List.copyOf(migrations);
    }

    static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    private static byte[] read(Path path, String filename) {
        if (!Files.isReadable(path)) {
            throw new DatabaseFailure("Migration file is not readable: " + safe(filename) + ".");
        }
        try {
            try (SeekableByteChannel channel = Files.newByteChannel(
                    path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);
                while (channel.read(buffer) != -1) {
                    buffer.flip();
                    byte[] chunk = new byte[buffer.remaining()];
                    buffer.get(chunk);
                    output.writeBytes(chunk);
                    buffer.clear();
                }
                return output.toByteArray();
            }
        } catch (IOException failure) {
            throw new DatabaseFailure("Migration file could not be read: " + safe(filename) + ".", failure);
        }
    }

    private static String decode(byte[] bytes, String filename) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new DatabaseFailure(
                "Migration file is not valid UTF-8: " + safe(filename) + ".", failure);
        }
    }

    private static String checksum(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private static void rejectSymbolicComponents(Path path) {
        Path current = path.getRoot();
        for (Path component : path) {
            current = current == null ? component : current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new DatabaseFailure("Symbolic links are not allowed in migration paths.");
            }
        }
    }

    private static String safe(String value) {
        StringBuilder result = new StringBuilder();
        value.codePoints().limit(120).forEach(codePoint ->
            result.appendCodePoint(Character.isISOControl(codePoint) ? '?' : codePoint));
        return result.toString();
    }
}
