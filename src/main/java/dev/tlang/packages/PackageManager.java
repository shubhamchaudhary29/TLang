package dev.tlang.packages;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/** Transactional orchestration for package CLI commands. */
public final class PackageManager {
    private final BiConsumer<Path, String> metadataWriter;

    public PackageManager() {
        this(PackageFiles::atomicWrite);
    }

    PackageManager(BiConsumer<Path, String> metadataWriter) {
        this.metadataWriter = Objects.requireNonNull(metadataWriter, "metadataWriter");
    }

    public Path init(Path directory, String requestedName) {
        Path root = directory.toAbsolutePath().normalize();
        Path manifestPath = root.resolve("tlang.toml");
        if (Files.exists(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new PackageException("tlang.toml already exists; refusing to overwrite it");
        }
        String name = requestedName == null ? inferName(root) : requestedName;
        PackageManifest manifest = ManifestCodec.parse(
            "[package]\nname = \"" + ManifestCodec.escape(name) + "\"\nversion = \"0.1.0\"\n\n[dependencies]\n",
            manifestPath.toString());
        PackageFiles.atomicWrite(manifestPath, ManifestCodec.write(manifest));
        return manifestPath;
    }

    public PackageLock add(Path start, DependencySpec dependency) {
        ProjectLayout project = ProjectLayout.find(start);
        try (ProjectInstallLock ignored = ProjectInstallLock.acquire(project)) {
            PackageManifest current = project.readManifest();
            if (current.dependencies().containsKey(dependency.name())) {
                throw new PackageException("dependency '" + dependency.name() + "' is already declared");
            }
            PackageManifest proposed = validateRoundTrip(current.withDependency(dependency), project.manifest());
            PackageLock previous = readOptionalLock(project);
            if (previous != null) LockfileCodec.verifyManifest(current, previous);
            DependencyResolution resolution = new DependencyResolver(project, previous, false, false).resolve(proposed);
            mutate(project, proposed, resolution);
            return resolution.lock();
        }
    }

    public PackageLock remove(Path start, String dependencyName) {
        ProjectLayout project = ProjectLayout.find(start);
        try (ProjectInstallLock ignored = ProjectInstallLock.acquire(project)) {
            PackageManifest current = project.readManifest();
            if (!current.dependencies().containsKey(dependencyName)) {
                throw new PackageException("dependency '" + dependencyName + "' is not declared");
            }
            PackageManifest proposed = validateRoundTrip(current.withoutDependency(dependencyName), project.manifest());
            PackageLock previous = readOptionalLock(project);
            if (previous != null) LockfileCodec.verifyManifest(current, previous);
            DependencyResolution resolution = new DependencyResolver(project, previous, false, false).resolve(proposed);
            mutate(project, proposed, resolution);
            return resolution.lock();
        }
    }

    public PackageLock install(Path start, boolean offline, boolean update) {
        ProjectLayout project = ProjectLayout.find(start);
        try (ProjectInstallLock ignored = ProjectInstallLock.acquire(project)) {
            PackageManifest manifest = project.readManifest();
            PackageLock existing = readOptionalLock(project);
            if (existing != null && !update) {
                LockfileCodec.verifyManifest(manifest, existing);
                new PackageInstaller(project).install(existing, Map.of(), offline);
                return existing;
            }
            if (existing == null && offline) {
                throw new PackageException("offline install requires an existing tlang.lock");
            }
            DependencyResolution resolution = new DependencyResolver(project, existing, update, offline).resolve(manifest);
            new PackageInstaller(project).install(resolution.lock(), resolution.sourceRoots(), offline);
            PackageFiles.atomicWrite(project.lockfile(), LockfileCodec.write(resolution.lock()));
            return resolution.lock();
        }
    }

    public String list(Path start) {
        ProjectLayout project = ProjectLayout.find(start);
        PackageManifest manifest = project.readManifest();
        PackageLock lock = project.readLock();
        LockfileCodec.verifyManifest(manifest, lock);
        StringBuilder output = new StringBuilder(manifest.name()).append(" ").append(manifest.version()).append('\n');
        java.util.Set<String> shown = new java.util.HashSet<>();
        int index = 0;
        for (String dependency : manifest.dependencies().keySet()) {
            appendGraph(output, lock, dependency, "", ++index == manifest.dependencies().size(), shown);
        }
        return output.toString();
    }

    private static void appendGraph(StringBuilder output, PackageLock lock, String name, String prefix,
                                    boolean last, java.util.Set<String> shown) {
        PackageRecord record = lock.packages().get(name);
        if (record == null) throw new PackageException("lockfile is missing package '" + name + "'");
        output.append(prefix).append(last ? "└── " : "├── ").append(name);
        if (record.sourceType() == DependencySpec.SourceType.GIT) output.append(" @ ").append(record.commit(), 0, 12);
        if (!shown.add(name)) { output.append(" (shared)\n"); return; }
        output.append('\n');
        String childPrefix = prefix + (last ? "    " : "│   ");
        for (int i = 0; i < record.dependencies().size(); i++) {
            appendGraph(output, lock, record.dependencies().get(i), childPrefix,
                i == record.dependencies().size() - 1, shown);
        }
    }

    private static PackageManifest validateRoundTrip(PackageManifest manifest, Path path) {
        return ManifestCodec.parse(ManifestCodec.write(manifest), path.toString());
    }

    private static PackageLock readOptionalLock(ProjectLayout project) {
        return Files.exists(project.lockfile(), LinkOption.NOFOLLOW_LINKS) ? project.readLock() : null;
    }

    private void mutate(ProjectLayout project, PackageManifest manifest, DependencyResolution resolution) {
        // The project lock remains held while all three state components commit or roll back.
        try (PackageMutationTransaction transaction = PackageMutationTransaction.begin(project)) {
            new PackageInstaller(project).install(resolution.lock(), resolution.sourceRoots(), false);
            transaction.commit(manifest, resolution.lock(), metadataWriter);
        }
    }

    private static String inferName(Path root) {
        Path fileName = root.getFileName();
        if (fileName == null) throw new PackageException("could not infer a project name; use 'tlang init --name <name>'");
        String inferred = fileName.toString().toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        if (!ManifestCodec.PACKAGE_NAME.matcher(inferred).matches()) {
            throw new PackageException("could not infer a valid package name; use 'tlang init --name <name>'");
        }
        return inferred;
    }
}
