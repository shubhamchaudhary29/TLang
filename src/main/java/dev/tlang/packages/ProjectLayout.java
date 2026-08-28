package dev.tlang.packages;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Canonical paths belonging to one TLang project. */
public record ProjectLayout(Path root) {
    public ProjectLayout {
        try {
            root = root.toRealPath();
        } catch (IOException e) {
            throw new PackageException("could not canonicalize project root '" + root + "'", e);
        }
    }

    public Path manifest() { return root.resolve("tlang.toml"); }
    public Path lockfile() { return root.resolve("tlang.lock"); }
    public Path state() { return root.resolve(".tlang"); }
    public Path packages() { return state().resolve("packages"); }
    public Path gitCache() { return state().resolve("cache").resolve("git"); }
    public Path installLock() { return state().resolve("install.lock"); }

    /** Reject symlinked state ancestors before package state is read or written. */
    public void validateStatePath(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(state())) throw new PackageException("package state path escapes .tlang");
        Path current = state();
        int stateNames = state().getNameCount();
        for (int index = stateNames; index <= normalized.getNameCount(); index++) {
            if (index > stateNames) current = current.resolve(normalized.getName(index - 1));
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new PackageException("symbolic links are not allowed in package state: " + root.relativize(current));
            }
        }
    }

    public static ProjectLayout find(Path start) {
        Path candidate = start.toAbsolutePath().normalize();
        if (!Files.isDirectory(candidate)) candidate = candidate.getParent();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("tlang.toml"), LinkOption.NOFOLLOW_LINKS)) return new ProjectLayout(candidate);
            candidate = candidate.getParent();
        }
        throw new PackageException("no tlang.toml found in this directory or its ancestors");
    }

    public PackageManifest readManifest() {
        return ManifestCodec.parse(PackageFiles.read(manifest(), "manifest"), manifest().toString());
    }

    public PackageLock readLock() {
        return LockfileCodec.parse(PackageFiles.read(lockfile(), "lockfile"), lockfile().toString());
    }

    public Path resolveLocalPath(Path ownerRoot, String declaredPath, String dependencyName) {
        Path lexical = ownerRoot.resolve(declaredPath).normalize();
        try {
            if (!Files.exists(lexical, LinkOption.NOFOLLOW_LINKS)) {
                throw new PackageException("path dependency '" + dependencyName + "' does not exist: " + declaredPath);
            }
            Path canonical = lexical.toRealPath();
            if (!Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)) {
                throw new PackageException("path dependency '" + dependencyName + "' is not a directory");
            }
            Path stateRoot = state().toAbsolutePath().normalize();
            if (canonical.startsWith(stateRoot)) {
                throw new PackageException("path dependency '" + dependencyName + "' may not point inside .tlang");
            }
            return canonical;
        } catch (IOException e) {
            throw new PackageException("could not resolve path dependency '" + dependencyName + "'", e);
        }
    }

    public String lockedPath(Path canonical) {
        Path relative;
        try {
            relative = root.relativize(canonical);
        } catch (IllegalArgumentException differentFileSystem) {
            throw new PackageException("path dependencies must be on the same filesystem as the project");
        }
        return PackageHashes.unix(relative.normalize());
    }
}
