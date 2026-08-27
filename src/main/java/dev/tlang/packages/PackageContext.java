package dev.tlang.packages;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Immutable package graph used by one module loader. */
public final class PackageContext {
    private final ProjectLayout project;
    private final PackageManifest manifest;
    private final PackageLock lock;

    private PackageContext(ProjectLayout project, PackageManifest manifest, PackageLock lock) {
        this.project = project;
        this.manifest = manifest;
        this.lock = lock;
    }

    public static PackageContext discover(Path scriptDirectory) {
        ProjectLayout project;
        try {
            project = ProjectLayout.find(scriptDirectory);
        } catch (PackageException absent) {
            if (absent.getMessage().startsWith("no tlang.toml")) return null;
            throw absent;
        }
        PackageManifest manifest = project.readManifest();
        if (!Files.isRegularFile(project.lockfile(), LinkOption.NOFOLLOW_LINKS)) {
            if (manifest.dependencies().isEmpty()) {
                return new PackageContext(project, manifest,
                    new PackageLock(PackageLock.CURRENT_VERSION, PackageHashes.manifest(manifest), Map.of()));
            }
            throw new PackageException("project has dependencies but no tlang.lock; run 'tlang install'");
        }
        PackageLock lock = project.readLock();
        LockfileCodec.verifyManifest(manifest, lock);
        project.validateStatePath(project.packages());
        return new PackageContext(project, manifest, lock);
    }

    public Path projectRoot() { return project.root(); }

    /** Returns an installed entry module if the importer is allowed to use it. */
    public Path resolveDependency(String moduleName, Path importingFile) {
        Set<String> allowed = manifest.dependencies().keySet();
        String owner = packageContaining(importingFile);
        if (owner != null) {
            PackageRecord ownerRecord = lock.packages().get(owner);
            allowed = ownerRecord == null ? Set.of() : Set.copyOf(ownerRecord.dependencies());
        }
        if (!allowed.contains(moduleName)) return null;
        PackageRecord record = lock.packages().get(moduleName);
        if (record == null) throw new PackageException("lockfile is missing package '" + moduleName + "'");
        Path packageRoot = project.packages().resolve(moduleName).toAbsolutePath().normalize();
        PackageFiles.requireWithin(project.packages(), packageRoot, "installed package path");
        Path entry = packageRoot.resolve(moduleName + ".tiny").normalize();
        if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(entry)) {
            throw new PackageException("dependency '" + moduleName + "' is not installed; run 'tlang install'");
        }
        return entry;
    }

    public boolean isProjectSource(Path file) {
        if (file == null) return true;
        Path normalized = file.toAbsolutePath().normalize();
        return normalized.startsWith(project.root()) && !normalized.startsWith(project.packages());
    }

    private String packageContaining(Path file) {
        if (file == null) return null;
        Path normalized = file.toAbsolutePath().normalize();
        for (String name : lock.packages().keySet()) {
            Path root = project.packages().resolve(name).toAbsolutePath().normalize();
            if (normalized.startsWith(root)) return name;
        }
        return null;
    }
}
