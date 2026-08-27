package dev.tlang.packages;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class PackageTestSupport {
    private PackageTestSupport() {}

    static void writePackage(Path root, String name, Map<String, DependencySpec> dependencies, String module) throws IOException {
        Files.createDirectories(root);
        PackageManifest manifest = new PackageManifest(name, "1.0.0", dependencies);
        Files.writeString(root.resolve("tlang.toml"), ManifestCodec.write(manifest));
        Files.writeString(root.resolve(name + ".tiny"), module == null ? "let package_name be \"" + name + "\"\n" : module);
    }

    static String relative(Path from, Path to) {
        return PackageHashes.unix(from.toAbsolutePath().normalize().relativize(to.toAbsolutePath().normalize()));
    }

    static String git(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git"); command.add("-C"); command.add(directory.toString());
        command.addAll(List.of(arguments));
        ProcessResult result = process(directory, command);
        if (result.exitCode() != 0) throw new AssertionError("Git failed: " + result.output());
        return result.output().trim();
    }

    static void initGitPackage(Path root, String name, Map<String, DependencySpec> dependencies, String module) throws Exception {
        writePackage(root, name, dependencies, module);
        process(root, List.of("git", "init", "--quiet", root.toString()));
        git(root, "config", "user.email", "tests@tlang.dev");
        git(root, "config", "user.name", "TLang Tests");
        git(root, "add", ".");
        git(root, "commit", "--quiet", "-m", "initial");
    }

    static String fileUri(Path repository) {
        return repository.toUri().toString();
    }

    static ProcessResult cli(Path workingDirectory, String... arguments) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        String classpath = absoluteClasspath(System.getProperty("java.class.path"));
        List<String> command = new ArrayList<>(List.of(java.toString(), "-cp", classpath, "dev.tlang.Main"));
        command.addAll(List.of(arguments));
        return process(workingDirectory, command);
    }

    private static String absoluteClasspath(String raw) {
        String[] entries = raw.split(java.io.File.pathSeparator);
        Path base = Path.of(System.getProperty("user.dir"));
        for (int i = 0; i < entries.length; i++) entries[i] = base.resolve(entries[i]).normalize().toString();
        return String.join(java.io.File.pathSeparator, entries);
    }

    private static ProcessResult process(Path workingDirectory, List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).directory(workingDirectory.toFile()).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try { process.getInputStream().transferTo(output); } catch (IOException ignored) {}
        });
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("process timed out: " + command);
        }
        reader.join();
        return new ProcessResult(process.exitValue(), output.toString(StandardCharsets.UTF_8).replace("\r", ""));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    record ProcessResult(int exitCode, String output) {}
}
