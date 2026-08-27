package dev.tlang.packages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

/** Encapsulates package filesystem operations and their safety checks. */
final class PackageFiles {
    private PackageFiles() {}

    static String read(Path path, String description) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PackageException("could not read " + description + " '" + path + "'", e);
        }
    }

    static void atomicWrite(Path target, String content) {
        Path parent = target.toAbsolutePath().normalize().getParent();
        Path temporary = parent.resolve("." + target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.createDirectories(parent);
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            atomicMove(temporary, target);
        } catch (IOException e) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
            throw new PackageException("could not write '" + target + "'", e);
        }
    }

    static void copyPackageTree(Path source, Path destination) {
        Path canonical;
        try {
            canonical = source.toRealPath();
            Files.createDirectories(destination);
            Files.walkFileTree(canonical, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path relative = canonical.relativize(dir);
                    if (relative.getNameCount() > 0) {
                        String first = relative.getName(0).toString();
                        if (first.equals(".git") || first.equals(".tlang")) return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (Files.isSymbolicLink(dir)) throw new PackageException("symbolic links are not allowed in package content: " + relative);
                    Files.createDirectories(destination.resolve(relative));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path relative = canonical.relativize(file);
                    if (Files.isSymbolicLink(file)) throw new PackageException("symbolic links are not allowed in package content: " + relative);
                    if (!relative.toString().equals(PackageHashes.METADATA_FILE)) {
                        Path target = destination.resolve(relative).normalize();
                        requireWithin(destination, target, "package copy");
                        Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new PackageException("could not copy package source '" + source + "'", e);
        }
    }

    static void replaceDirectory(Path temporary, Path destination) {
        Path backup = destination.resolveSibling(destination.getFileName() + ".old-" + UUID.randomUUID());
        boolean hadDestination = Files.exists(destination, LinkOption.NOFOLLOW_LINKS);
        try {
            if (hadDestination) Files.move(destination, backup);
            atomicMove(temporary, destination);
            if (hadDestination) deleteTree(backup);
        } catch (IOException | RuntimeException e) {
            try {
                if (!Files.exists(destination) && Files.exists(backup)) Files.move(backup, destination);
            } catch (IOException ignored) {}
            if (e instanceof PackageException packageException) throw packageException;
            throw new PackageException("could not install package directory '" + destination + "'", e);
        }
    }

    static void atomicMove(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void deleteTree(Path path) {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                    if (error != null) throw error;
                    Files.delete(dir); return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new PackageException("could not remove temporary package state '" + path + "'", e);
        }
    }

    static void requireWithin(Path root, Path candidate, String subject) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        if (!normalizedCandidate.startsWith(normalizedRoot)) {
            throw new PackageException(subject + " escapes protected directory '" + root + "'");
        }
    }
}
