package dev.tlang.packages;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** JVM- and process-safe exclusive lock for package mutations. */
final class ProjectInstallLock implements AutoCloseable {
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final ReentrantLock jvmLock;
    private final FileChannel channel;
    private final FileLock fileLock;

    private ProjectInstallLock(ReentrantLock jvmLock, FileChannel channel, FileLock fileLock) {
        this.jvmLock = jvmLock;
        this.channel = channel;
        this.fileLock = fileLock;
    }

    static ProjectInstallLock acquire(ProjectLayout project) {
        Path path = project.installLock().toAbsolutePath().normalize();
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(path, ignored -> new ReentrantLock(true));
        jvmLock.lock();
        FileChannel channel = null;
        try {
            project.validateStatePath(path);
            Files.createDirectories(path.getParent());
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock fileLock = channel.lock();
            return new ProjectInstallLock(jvmLock, channel, fileLock);
        } catch (IOException | RuntimeException e) {
            if (channel != null) try { channel.close(); } catch (IOException ignored) {}
            jvmLock.unlock();
            throw new PackageException("could not acquire package installation lock", e);
        }
    }

    @Override
    public void close() {
        try {
            fileLock.release();
            channel.close();
        } catch (IOException e) {
            throw new PackageException("could not release package installation lock", e);
        } finally {
            jvmLock.unlock();
        }
    }
}
