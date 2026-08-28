package dev.tlang.packages;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Coordinates rollback of manifest, lockfile, and installed package state. */
final class PackageMutationTransaction implements AutoCloseable {
    private final ProjectLayout project;
    private final String oldManifest;
    private final String oldLock;
    private final Path packageBackup;
    private final boolean hadPackages;
    private boolean committed;

    private PackageMutationTransaction(ProjectLayout project, String oldManifest, String oldLock,
                                       Path packageBackup, boolean hadPackages) {
        this.project = project;
        this.oldManifest = oldManifest;
        this.oldLock = oldLock;
        this.packageBackup = packageBackup;
        this.hadPackages = hadPackages;
    }

    static PackageMutationTransaction begin(ProjectLayout project) {
        String oldManifest = PackageFiles.read(project.manifest(), "manifest");
        String oldLock = Files.exists(project.lockfile(), LinkOption.NOFOLLOW_LINKS)
            ? PackageFiles.read(project.lockfile(), "lockfile") : null;
        Path backup = project.state().resolve(".packages-old-" + UUID.randomUUID()).normalize();
        project.validateStatePath(project.packages());
        project.validateStatePath(backup);
        boolean hadPackages = Files.exists(project.packages(), LinkOption.NOFOLLOW_LINKS);
        try {
            if (hadPackages) PackageFiles.atomicMove(project.packages(), backup);
            return new PackageMutationTransaction(project, oldManifest, oldLock, backup, hadPackages);
        } catch (IOException e) {
            throw new PackageException("could not begin package mutation transaction", e);
        }
    }

    void commit(PackageManifest manifest, PackageLock lock, BiConsumer<Path, String> metadataWriter) {
        metadataWriter.accept(project.manifest(), ManifestCodec.write(manifest));
        metadataWriter.accept(project.lockfile(), LockfileCodec.write(lock));
        if (hadPackages) PackageFiles.deleteTree(packageBackup);
        committed = true;
    }

    @Override
    public void close() {
        if (committed) return;
        RuntimeException rollbackFailure = null;
        try {
            restorePackages();
        } catch (RuntimeException failure) {
            rollbackFailure = failure;
        }
        try {
            restoreMetadata();
        } catch (RuntimeException failure) {
            if (rollbackFailure == null) rollbackFailure = failure;
            else rollbackFailure.addSuppressed(failure);
        }
        if (rollbackFailure != null) {
            throw new PackageException("could not roll back package mutation", rollbackFailure);
        }
    }

    private void restorePackages() {
        PackageFiles.deleteTree(project.packages());
        if (!hadPackages) {
            PackageFiles.deleteTree(packageBackup);
            return;
        }
        if (!Files.exists(packageBackup, LinkOption.NOFOLLOW_LINKS)) {
            throw new PackageException("installed package backup is missing during rollback");
        }
        try {
            PackageFiles.atomicMove(packageBackup, project.packages());
        } catch (IOException e) {
            throw new PackageException("could not restore installed packages", e);
        }
    }

    private void restoreMetadata() {
        PackageFiles.atomicWrite(project.manifest(), oldManifest);
        if (oldLock != null) {
            PackageFiles.atomicWrite(project.lockfile(), oldLock);
            return;
        }
        try {
            Files.deleteIfExists(project.lockfile());
        } catch (IOException e) {
            throw new PackageException("could not remove lockfile during rollback", e);
        }
    }
}
