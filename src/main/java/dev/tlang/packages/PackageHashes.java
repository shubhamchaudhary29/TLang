package dev.tlang.packages;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Deterministic SHA-256 helpers for manifests and package source trees. */
public final class PackageHashes {
    public static final String METADATA_FILE = ".tlang-package-meta";

    private PackageHashes() {}

    public static String manifest(PackageManifest manifest) {
        return bytes(ManifestCodec.write(manifest).getBytes(StandardCharsets.UTF_8));
    }

    public static String text(String text) {
        return bytes(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String bytes(byte[] input) {
        MessageDigest digest = digest();
        digest.update(input);
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String tree(Path root) {
        try {
            Path canonicalRoot = root.toRealPath();
            if (!Files.isDirectory(canonicalRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new PackageException("package source is not a directory: " + root);
            }
            List<Path> files = new ArrayList<>();
            try (var stream = Files.walk(canonicalRoot)) {
                stream.forEach(path -> {
                    Path relative = canonicalRoot.relativize(path);
                    if (relative.getNameCount() > 0) {
                        String first = relative.getName(0).toString();
                        if (first.equals(".git") || first.equals(".tlang")) return;
                    }
                    if (Files.isSymbolicLink(path)) {
                        throw new PackageException("symbolic links are not allowed in package content: " + relative);
                    }
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            && !relative.toString().equals(METADATA_FILE)) files.add(path);
                });
            }
            files.sort(Comparator.comparing(path -> unix(canonicalRoot.relativize(path))));
            MessageDigest digest = digest();
            byte[] buffer = new byte[8192];
            for (Path file : files) {
                String relative = unix(canonicalRoot.relativize(file));
                digest.update(relative.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Long.toString(Files.size(file)).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) 0);
                try (InputStream input = Files.newInputStream(file)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
                }
                digest.update((byte) 0xff);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new PackageException("could not hash package source '" + root + "'", e);
        }
    }

    static String unix(Path path) {
        return path.toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
