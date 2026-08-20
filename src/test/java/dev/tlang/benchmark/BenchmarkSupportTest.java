package dev.tlang.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BenchmarkSupportTest {
    @Test
    void loadsAndValidatesARealFixture() {
        BenchmarkProgram program = BenchmarkProgram.load("arithmetic");
        program.validateResult(17);
        assertEquals(17, program.execute());
    }

    @Test
    void rejectsMissingAndInvalidFixtures() {
        assertThrows(IllegalArgumentException.class,
            () -> BenchmarkProgram.load("does-not-exist"));
        assertThrows(IllegalArgumentException.class,
            () -> BenchmarkProgram.compile("invalid", "let result be @\n", Path.of(".")));
        assertThrows(IllegalArgumentException.class,
            () -> BenchmarkProgram.compile("semantic", "let result be missing\n", Path.of(".")));
    }

    @Test
    void surfacesFailedExecutionAndMissingResults() {
        BenchmarkProgram failed = BenchmarkProgram.compile(
            "failed", "let result be 10 / 0\n", Path.of("."));
        assertThrows(RuntimeException.class, failed::execute);

        BenchmarkProgram missing = BenchmarkProgram.compile(
            "missing-result", "let value be 10\n", Path.of("."));
        assertThrows(IllegalStateException.class, missing::execute);
    }

    @Test
    void validatesConfigurationAndCreatesOutputDirectories(@TempDir Path directory) {
        assertThrows(IllegalArgumentException.class, () -> BenchmarkOutput.prepare(" "));
        assertThrows(IllegalArgumentException.class,
            () -> BenchmarkOutput.prepare(directory.resolve("result.txt").toString()));

        Path output = BenchmarkOutput.prepare(
            directory.resolve("reports with spaces/jmh/results.json").toString());
        assertTrue(Files.isDirectory(output.getParent()));
        assertTrue(output.toString().contains("reports with spaces"));
    }

    @Test
    void temporaryWorkspaceRemovesDatabaseAndNestedState() throws Exception {
        TemporaryWorkspace workspace = TemporaryWorkspace.create();
        Path root = workspace.directory();
        Path database = root.resolve("database files/state.sqlite");
        Files.createDirectories(database.getParent());
        Files.writeString(database, "temporary");
        assertTrue(Files.exists(database));
        assertTrue(root.toString().contains("tlang benchmark "));

        workspace.close();
        assertFalse(Files.exists(root));
        // Cleanup is idempotent so failed benchmark teardown can be retried safely.
        workspace.close();
    }
}
