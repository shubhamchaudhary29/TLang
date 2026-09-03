package dev.tlang.runtime.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class MigrationDiscoveryTest {
    @TempDir Path temporary;

    @Test
    void ordersNumericVersionsAndPreservesUnicodeNames() throws Exception {
        write("0010_ten.sql", "SELECT 10;");
        write("0002_தமிழ்.sql", "SELECT 'வணக்கம்';");
        write("1_first.sql", "SELECT 1;");
        Files.createDirectory(temporary.resolve("nested"));
        Files.writeString(temporary.resolve(".DS_Store"), "ignored");

        List<MigrationFile> files = MigrationDiscovery.discover(
            temporary.toString(), SqlScriptParser.Dialect.SQLITE);
        assertEquals(List.of(1, 2, 10), files.stream().map(MigrationFile::version).toList());
        assertEquals("தமிழ்", files.get(1).name());
        assertEquals(64, files.getFirst().checksum().length());
    }

    @Test
    void checksumUsesExactBytesIncludingLineEndings() throws Exception {
        Path file = write("0001_lines.sql", "SELECT 1;\n");
        String lf = MigrationDiscovery.discover(
            temporary.toString(), SqlScriptParser.Dialect.SQLITE).getFirst().checksum();
        Files.write(file, "SELECT 1;\r\n".getBytes(StandardCharsets.UTF_8));
        String crlf = MigrationDiscovery.discover(
            temporary.toString(), SqlScriptParser.Dialect.SQLITE).getFirst().checksum();
        assertNotEquals(lf, crlf);
    }

    @Test
    void rejectsDuplicateMalformedEmptyInvalidUtf8AndTraversal() throws Exception {
        write("0001_one.sql", "SELECT 1;");
        write("1_duplicate.sql", "SELECT 2;");
        assertFailureContains("Duplicate migration version", temporary.toString());

        Files.delete(temporary.resolve("1_duplicate.sql"));
        write("README.md", "never execute me");
        assertFailureContains("Invalid migration filename", temporary.toString());

        Files.delete(temporary.resolve("README.md"));
        write("0002_empty.sql", " -- comments only ;\n");
        assertFailureContains("Migration is empty", temporary.toString());

        Files.delete(temporary.resolve("0002_empty.sql"));
        Files.write(temporary.resolve("0002_invalid.sql"), new byte[] {(byte) 0xc3, 0x28});
        assertFailureContains("not valid UTF-8", temporary.toString());

        assertFailureContains("parent traversal", temporary.resolve("nested/../").toString());
    }

    @Test
    void rejectsZeroAndOutOfRangeVersions() throws Exception {
        write("0000_zero.sql", "SELECT 1;");
        assertFailureContains("supported integer range", temporary.toString());
        Files.delete(temporary.resolve("0000_zero.sql"));
        write("2147483648_large.sql", "SELECT 1;");
        assertFailureContains("supported integer range", temporary.toString());
    }

    @Test
    void rejectsFilePathsAndMissingPaths() throws Exception {
        Path file = write("0001_one.sql", "SELECT 1;");
        assertFailureContains("must be a directory", file.toString());
        assertFailureContains("does not exist", temporary.resolve("missing").toString());
    }

    @Test
    void rejectsSymbolicMigrationFilesAndDirectoryPathsWhenSupported() throws Exception {
        Path realDirectory = Files.createDirectory(temporary.resolve("real"));
        Path realFile = Files.writeString(temporary.resolve("real.sql"), "SELECT 1;");
        try {
            Files.createSymbolicLink(realDirectory.resolve("0001_link.sql"), realFile);
            assertFailureContains("Symbolic links are not allowed", realDirectory.toString());

            Path directoryLink = temporary.resolve("directory-link");
            Files.createSymbolicLink(directoryLink, realDirectory);
            assertFailureContains("Symbolic links are not allowed", directoryLink.toString());
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            // The remaining path tests still run on platforms without symlink privileges.
        }
    }

    private Path write(String name, String value) throws Exception {
        return Files.writeString(temporary.resolve(name), value, StandardCharsets.UTF_8);
    }

    private static void assertFailureContains(String expected, String path) {
        DatabaseFailure failure = assertThrows(DatabaseFailure.class, () ->
            MigrationDiscovery.discover(path, SqlScriptParser.Dialect.SQLITE));
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }
}
