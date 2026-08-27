package dev.tlang.packages;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Verifies sources and atomically materializes .tlang/packages. */
final class PackageInstaller {
    private final ProjectLayout project;
    private final GitPackageStore git;

    PackageInstaller(ProjectLayout project) {
        this.project = project;
        this.git = new GitPackageStore(project);
    }

    void install(PackageLock lock, Map<String, Path> resolvedRoots, boolean offline) {
        try {
            project.validateStatePath(project.packages());
            Files.createDirectories(project.packages());
            cleanStaleDirectories();
            for (PackageRecord record : lock.packages().values()) {
                Path destination = destination(record.name());
                boolean installed = validInstalled(destination, record);
                if (installed && record.sourceType() == DependencySpec.SourceType.GIT) continue;
                Path source = resolvedRoots.get(record.name());
                if (source == null) source = sourceForLocked(record, offline);
                validateSource(record, source);
                if (!installed) installOne(record, source, destination);
            }
            removeUnreferenced(lock.packages().keySet());
        } catch (IOException e) {
            throw new PackageException("could not prepare package installation directory", e);
        }
    }

    private Path sourceForLocked(PackageRecord record, boolean offline) {
        if (record.sourceType() == DependencySpec.SourceType.GIT) return git.acquireLocked(record, offline);
        Path source = project.resolveLocalPath(project.root(), record.location(), record.name());
        if (!project.lockedPath(source).equals(record.location())) {
            throw new PackageException("locked path for dependency '" + record.name() + "' is not normalized");
        }
        return source;
    }

    private void validateSource(PackageRecord record, Path source) {
        String actual = PackageHashes.tree(source);
        if (!actual.equals(record.contentSha256())) {
            String advice = record.sourceType() == DependencySpec.SourceType.PATH
                ? "; local source changed, run 'tlang install --update'"
                : "";
            throw new PackageException("content for dependency '" + record.name() + "' does not match tlang.lock" + advice);
        }
        Path manifestPath = source.resolve("tlang.toml");
        if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new PackageException("dependency '" + record.name() + "' has no valid tlang.toml");
        }
        PackageManifest manifest = ManifestCodec.parse(PackageFiles.read(manifestPath, "dependency manifest"), manifestPath.toString());
        if (!manifest.name().equals(record.name())) throw new PackageException("installed package identity mismatch for '" + record.name() + "'");
        if (!new HashSet<>(manifest.dependencies().keySet()).equals(new HashSet<>(record.dependencies()))) {
            throw new PackageException("dependency graph for '" + record.name() + "' does not match tlang.lock");
        }
    }

    private void installOne(PackageRecord record, Path source, Path destination) {
        Path temporary = project.packages().resolve(".tmp-" + record.name() + "-" + UUID.randomUUID()).normalize();
        PackageFiles.requireWithin(project.packages(), temporary, "package temporary directory");
        try {
            PackageFiles.copyPackageTree(source, temporary);
            Files.writeString(temporary.resolve(PackageHashes.METADATA_FILE), metadata(record));
            if (!PackageHashes.tree(temporary).equals(record.contentSha256())) {
                throw new PackageException("copied package '" + record.name() + "' failed integrity verification");
            }
            PackageFiles.replaceDirectory(temporary, destination);
        } catch (IOException | RuntimeException e) {
            PackageFiles.deleteTree(temporary);
            if (e instanceof PackageException packageException) throw packageException;
            throw new PackageException("could not install dependency '" + record.name() + "'", e);
        }
    }

    private boolean validInstalled(Path destination, PackageRecord record) {
        if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(destination)) return false;
        Path marker = destination.resolve(PackageHashes.METADATA_FILE);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            return Files.readString(marker).equals(metadata(record))
                && PackageHashes.tree(destination).equals(record.contentSha256());
        } catch (IOException | PackageException e) {
            return false;
        }
    }

    private Path destination(String name) {
        if (!ManifestCodec.DEPENDENCY_NAME.matcher(name).matches()) throw new PackageException("unsafe package destination name '" + name + "'");
        Path destination = project.packages().resolve(name).toAbsolutePath().normalize();
        PackageFiles.requireWithin(project.packages(), destination, "package destination");
        return destination;
    }

    private static String metadata(PackageRecord record) {
        return "name=" + record.name() + "\ncontent-sha256=" + record.contentSha256() + "\n";
    }

    private void cleanStaleDirectories() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(project.packages())) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (name.startsWith(".tmp-") || name.contains(".old-")) PackageFiles.deleteTree(path);
            }
        }
    }

    private void removeUnreferenced(Set<String> retained) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(project.packages())) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (!name.startsWith(".") && !retained.contains(name)) PackageFiles.deleteTree(path);
            }
        }
    }
}
