package dev.tlang.packages;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Safe Git acquisition into immutable, project-local commit caches. */
final class GitPackageStore {
    private static final Duration GIT_TIMEOUT = Duration.ofMinutes(2);
    private final ProjectLayout project;

    GitPackageStore(ProjectLayout project) {
        this.project = project;
    }

    GitSource resolve(String packageName, String repository, String revision) {
        Path temporary = cloneRepository(packageName, repository);
        try {
            String commit = git(temporary, packageName, List.of(
                "rev-parse", "--verify", "--end-of-options", revision + "^{commit}"),
                "Git revision '" + revision + "' was not found for dependency '" + packageName + "'").trim();
            if (!commit.matches("[0-9a-fA-F]{40}")) {
                throw new PackageException("Git returned an invalid commit for dependency '" + packageName + "'");
            }
            commit = commit.toLowerCase();
            rejectCheckoutCode(temporary, packageName, commit);
            git(temporary, packageName, List.of("checkout", "--detach", "--force", commit),
                "could not check out Git dependency '" + packageName + "'");
            PackageFiles.deleteTree(temporary.resolve(".git"));
            Path destination = cachePath(repository, commit);
            project.validateStatePath(destination.getParent());
            Files.createDirectories(destination.getParent());
            if (Files.exists(destination)) PackageFiles.deleteTree(destination);
            PackageFiles.replaceDirectory(temporary, destination);
            return new GitSource(destination, commit, PackageHashes.tree(destination));
        } catch (IOException e) {
            PackageFiles.deleteTree(temporary);
            throw new PackageException("could not cache Git dependency '" + packageName + "'", e);
        } catch (RuntimeException e) {
            PackageFiles.deleteTree(temporary);
            throw e;
        }
    }

    Path acquireLocked(PackageRecord record, boolean offline) {
        Path cached = cachePath(record.location(), record.commit());
        if (valid(cached, record.contentSha256())) return cached;
        if (Files.exists(cached)) PackageFiles.deleteTree(cached);
        if (offline) {
            throw new PackageException("offline install is missing cached Git dependency '" + record.name()
                + "' at commit " + record.commit());
        }
        Path temporary = cloneRepository(record.name(), record.location());
        try {
            rejectCheckoutCode(temporary, record.name(), record.commit());
            git(temporary, record.name(), List.of("checkout", "--detach", "--force", record.commit()),
                "locked Git commit " + record.commit() + " is unavailable for dependency '" + record.name() + "'");
            PackageFiles.deleteTree(temporary.resolve(".git"));
            String actualHash = PackageHashes.tree(temporary);
            if (!actualHash.equals(record.contentSha256())) {
                throw new PackageException("cached content for Git dependency '" + record.name() + "' does not match tlang.lock");
            }
            project.validateStatePath(cached.getParent());
            Files.createDirectories(cached.getParent());
            PackageFiles.replaceDirectory(temporary, cached);
            return cached;
        } catch (IOException e) {
            PackageFiles.deleteTree(temporary);
            throw new PackageException("could not cache locked Git dependency '" + record.name() + "'", e);
        } catch (RuntimeException e) {
            PackageFiles.deleteTree(temporary);
            throw e;
        }
    }

    private Path cloneRepository(String packageName, String repository) {
        Path temporary = project.gitCache().resolve(".tmp-" + UUID.randomUUID()).toAbsolutePath().normalize();
        PackageFiles.requireWithin(project.gitCache(), temporary, "Git temporary directory");
        try {
            project.validateStatePath(project.gitCache());
            Files.createDirectories(project.gitCache());
            try (var entries = Files.newDirectoryStream(project.gitCache(), ".tmp-*")) {
                for (Path stale : entries) PackageFiles.deleteTree(stale);
            }
        } catch (IOException e) {
            throw new PackageException("could not create Git cache", e);
        }
        try {
            run(List.of("git", "-c", "core.hooksPath=" + project.state().resolve("no-hooks"),
                    "clone", "--quiet", "--no-checkout", "--", repository, temporary.toString()),
                project.root(), "dependency '" + packageName + "' could not be cloned");
            return temporary;
        } catch (RuntimeException failure) {
            PackageFiles.deleteTree(temporary);
            throw failure;
        }
    }

    private String git(Path repository, String packageName, List<String> arguments, String failure) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-c"); command.add("advice.detachedHead=false");
        command.add("-c"); command.add("core.hooksPath=" + project.state().resolve("no-hooks"));
        command.add("-C"); command.add(repository.toString());
        command.addAll(arguments);
        return run(command, project.root(), failure);
    }

    private void rejectCheckoutCode(Path repository, String packageName, String commit) {
        String files = git(repository, packageName,
            List.of("ls-tree", "-r", "--name-only", commit),
            "could not inspect Git dependency '" + packageName + "'");
        for (String path : files.lines().toList()) {
            if (path.equals(".gitmodules")) {
                throw new PackageException("Git dependency '" + packageName + "' uses unsupported submodules");
            }
            if (!path.equals(".gitattributes") && !path.endsWith("/.gitattributes")) continue;
            String attributes = git(repository, packageName,
                List.of("show", "--end-of-options", commit + ":" + path),
                "could not inspect attributes in Git dependency '" + packageName + "'");
            boolean externalFilter = attributes.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .anyMatch(line -> line.matches(".*(?:^|\\s)(?:-?filter|filter=[^\\s]+)(?:\\s|$).*$"));
            if (externalFilter) {
                throw new PackageException("Git dependency '" + packageName
                    + "' requests an external checkout filter; package code is never executed during install");
            }
        }
    }

    private static String run(List<String> command, Path workingDirectory, String failure) {
        try {
            Process process = new ProcessBuilder(command).directory(workingDirectory.toFile()).redirectErrorStream(true).start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try { process.getInputStream().transferTo(output); } catch (IOException ignored) {}
            });
            if (!process.waitFor(GIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new PackageException(failure + " (Git timed out)");
            }
            reader.join();
            String message = output.toString(StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                String safe = message.lines().findFirst().orElse("Git exited with status " + process.exitValue());
                throw new PackageException(failure + ": " + safe);
            }
            return message;
        } catch (IOException e) {
            throw new PackageException(failure + ": could not start Git", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PackageException(failure + ": interrupted", e);
        }
    }

    private Path cachePath(String repository, String commit) {
        String repositoryHash = PackageHashes.text(repository).substring(0, 24);
        Path path = project.gitCache().resolve(repositoryHash).resolve(commit).toAbsolutePath().normalize();
        PackageFiles.requireWithin(project.gitCache(), path, "Git cache path");
        return path;
    }

    private static boolean valid(Path path, String expectedHash) {
        return Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            && !Files.isSymbolicLink(path) && PackageHashes.tree(path).equals(expectedHash);
    }

    record GitSource(Path root, String commit, String contentSha256) {}
}
