package dev.tlang.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Validation and directory setup shared by benchmark launchers and tests. */
public final class BenchmarkOutput {
    private BenchmarkOutput() {}

    public static Path prepare(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalArgumentException("Benchmark result path must not be blank");
        }
        Path result = Path.of(configuredPath).toAbsolutePath().normalize();
        if (!result.getFileName().toString().endsWith(".json")) {
            throw new IllegalArgumentException("Benchmark result path must end in .json: " + result);
        }
        Path parent = result.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Benchmark result path must have a parent directory");
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not create benchmark result directory: " + parent, exception);
        }
        return result;
    }
}
