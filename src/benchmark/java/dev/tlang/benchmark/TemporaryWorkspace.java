package dev.tlang.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Isolated, recoverable benchmark state, including paths containing spaces. */
public final class TemporaryWorkspace implements AutoCloseable {
    private final Path directory;

    private TemporaryWorkspace(Path directory) {
        this.directory = directory;
    }

    public static TemporaryWorkspace create() {
        try {
            return new TemporaryWorkspace(Files.createTempDirectory("tlang benchmark "));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create benchmark workspace", exception);
        }
    }

    public Path directory() {
        return directory;
    }

    @Override
    public void close() {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not clean benchmark workspace: " + directory, exception);
        }
    }
}
