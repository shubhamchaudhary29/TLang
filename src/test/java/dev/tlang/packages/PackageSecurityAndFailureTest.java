package dev.tlang.packages;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PackageSecurityAndFailureTest {
    @TempDir Path temporary;

    @Test void rejectsStateDirectoryAsSourceAndMissingEntrypoint() throws Exception {
        Path app = initializedApp();
        Path statePackage = app.resolve(".tlang/evil");
        PackageTestSupport.writePackage(statePackage, "evil", Map.of(), null);
        PackageManager manager = new PackageManager();
        assertTrue(assertThrows(PackageException.class,
            () -> manager.add(app, DependencySpec.path("evil", ".tlang/evil"))).getMessage().contains("inside .tlang"));

        Path missingEntry = temporary.resolve("missing_entry"); Files.createDirectories(missingEntry);
        Files.writeString(missingEntry.resolve("tlang.toml"), ManifestCodec.write(
            new PackageManifest("missing_entry", "1.0.0", Map.of())));
        assertTrue(assertThrows(PackageException.class,
            () -> manager.add(app, DependencySpec.path("missing_entry", "../missing_entry"))).getMessage().contains("entry module"));
    }

    @Test void rejectsUnsafeLockDestinationsNamesAndGitArguments() {
        PackageLock absolute = new PackageLock(1, "1".repeat(64), Map.of(
            "safe", PackageRecord.path("safe", temporary.resolve("outside").toString(), "2".repeat(64), List.of())));
        assertThrows(PackageException.class, () -> LockfileCodec.parse(LockfileCodec.write(absolute), "lock"));
        PackageLock traversalName = new PackageLock(1, "1".repeat(64), Map.of(
            "../escape", PackageRecord.path("../escape", "../source", "2".repeat(64), List.of())));
        assertThrows(PackageException.class, () -> LockfileCodec.parse(LockfileCodec.write(traversalName), "lock"));
        assertThrows(PackageException.class, () -> ManifestCodec.parse("""
            [package]
            name = "app"
            version = "1.0.0"
            [dependencies]
            dep = { git = "https://example.test/dep.git", rev = "--upload-pack=evil" }
            """, "manifest"));
    }

    @Test void sourceConflictIsTransactional() throws Exception {
        Path c1 = temporary.resolve("c1"); Path c2 = temporary.resolve("c2");
        PackageTestSupport.writePackage(c1, "common", Map.of(), null);
        PackageTestSupport.writePackage(c2, "common", Map.of(), null);
        Path a = temporary.resolve("a"); Path b = temporary.resolve("b");
        PackageTestSupport.writePackage(a, "a", Map.of("common", DependencySpec.path("common", "../c1")), null);
        PackageTestSupport.writePackage(b, "b", Map.of("common", DependencySpec.path("common", "../c2")), null);
        Path app = initializedApp(); PackageManager manager = new PackageManager();
        manager.add(app, DependencySpec.path("a", "../a"));
        String manifest = Files.readString(app.resolve("tlang.toml"));
        String lock = Files.readString(app.resolve("tlang.lock"));
        assertTrue(assertThrows(PackageException.class,
            () -> manager.add(app, DependencySpec.path("b", "../b"))).getMessage().contains("conflicting sources"));
        assertEquals(manifest, Files.readString(app.resolve("tlang.toml")));
        assertEquals(lock, Files.readString(app.resolve("tlang.lock")));
    }

    @Test void failedAddCommitRestoresMetadataAndInstalledPackages() throws Exception {
        Path a = temporary.resolve("a"); Path b = temporary.resolve("b");
        PackageTestSupport.writePackage(a, "a", Map.of(), null);
        PackageTestSupport.writePackage(b, "b", Map.of(), null);
        Path app = initializedApp(); PackageManager manager = new PackageManager();
        manager.add(app, DependencySpec.path("a", "../a"));
        byte[] manifest = Files.readAllBytes(app.resolve("tlang.toml"));
        byte[] lock = Files.readAllBytes(app.resolve("tlang.lock"));
        Map<String, String> installed = installedHashes(app);

        PackageManager failing = failOnMetadataWrite(2, app.resolve(".tlang/packages/b"));
        assertThrows(PackageException.class,
            () -> failing.add(app, DependencySpec.path("b", "../b")));

        assertArrayEquals(manifest, Files.readAllBytes(app.resolve("tlang.toml")));
        assertArrayEquals(lock, Files.readAllBytes(app.resolve("tlang.lock")));
        assertEquals(installed, installedHashes(app));
        assertTrue(Files.exists(app.resolve(".tlang/packages/a/.tlang-package-meta")));
        assertFalse(Files.exists(app.resolve(".tlang/packages/b")));
        assertNoTransactionDirectories(app);
        assertEquals(Set.of("a"), manager.install(app, false, false).packages().keySet());
    }

    @Test void failedRemoveCommitRestoresMetadataAndInstalledPackages() throws Exception {
        Path a = temporary.resolve("a"); Path b = temporary.resolve("b");
        PackageTestSupport.writePackage(a, "a", Map.of(), null);
        PackageTestSupport.writePackage(b, "b", Map.of(), null);
        Path app = initializedApp(); PackageManager manager = new PackageManager();
        manager.add(app, DependencySpec.path("a", "../a"));
        manager.add(app, DependencySpec.path("b", "../b"));
        byte[] manifest = Files.readAllBytes(app.resolve("tlang.toml"));
        byte[] lock = Files.readAllBytes(app.resolve("tlang.lock"));
        Map<String, String> installed = installedHashes(app);

        PackageManager failing = failOnMetadataWrite(2, app.resolve(".tlang/packages/b"));
        assertThrows(PackageException.class, () -> failing.remove(app, "a"));

        assertArrayEquals(manifest, Files.readAllBytes(app.resolve("tlang.toml")));
        assertArrayEquals(lock, Files.readAllBytes(app.resolve("tlang.lock")));
        assertEquals(installed, installedHashes(app));
        assertInstalledMatchesLock(app);
        assertNoTransactionDirectories(app);
        assertEquals(Set.of("a", "b"), manager.install(app, false, false).packages().keySet());
    }

    @Test void failedRemoveRestoresSharedTransitiveGraph() throws Exception {
        Path common = temporary.resolve("common"); Path a = temporary.resolve("a"); Path b = temporary.resolve("b");
        PackageTestSupport.writePackage(common, "common", Map.of(), null);
        PackageTestSupport.writePackage(a, "a", Map.of("common", DependencySpec.path("common", "../common")), null);
        PackageTestSupport.writePackage(b, "b", Map.of("common", DependencySpec.path("common", "../common")), null);
        Path app = initializedApp(); PackageManager manager = new PackageManager();
        manager.add(app, DependencySpec.path("a", "../a"));
        manager.add(app, DependencySpec.path("b", "../b"));
        byte[] manifest = Files.readAllBytes(app.resolve("tlang.toml"));
        byte[] lock = Files.readAllBytes(app.resolve("tlang.lock"));
        Map<String, String> installed = installedHashes(app);

        PackageManager failing = failOnMetadataWrite(1, app.resolve(".tlang/packages/common"));
        assertThrows(PackageException.class, () -> failing.remove(app, "a"));

        assertArrayEquals(manifest, Files.readAllBytes(app.resolve("tlang.toml")));
        assertArrayEquals(lock, Files.readAllBytes(app.resolve("tlang.lock")));
        assertEquals(installed, installedHashes(app));
        assertEquals(Set.of("a", "b", "common"), installed.keySet());
        assertInstalledMatchesLock(app);
        assertNoTransactionDirectories(app);
    }

    @Test void failedFirstAddRestoresManifestAndLockfileAbsence() throws Exception {
        Path b = temporary.resolve("b"); PackageTestSupport.writePackage(b, "b", Map.of(), null);
        Path app = initializedApp();
        byte[] manifest = Files.readAllBytes(app.resolve("tlang.toml"));
        assertFalse(Files.exists(app.resolve("tlang.lock")));

        PackageManager failing = failOnMetadataWrite(1, app.resolve(".tlang/packages/b"));
        assertThrows(PackageException.class,
            () -> failing.add(app, DependencySpec.path("b", "../b")));

        assertArrayEquals(manifest, Files.readAllBytes(app.resolve("tlang.toml")));
        assertFalse(Files.exists(app.resolve("tlang.lock")));
        assertFalse(Files.exists(app.resolve(".tlang/packages/b")));
        assertNoTransactionDirectories(app);
    }

    @Test void normalizedPathRoundTripsAcrossInstall() throws Exception {
        Path dep = temporary.resolve("dep"); PackageTestSupport.writePackage(dep, "dep", Map.of(), null);
        Path app = initializedApp(); PackageManager manager = new PackageManager();
        manager.add(app, DependencySpec.path("dep", "nested/../../dep"));
        PackageLock lock = manager.install(app, false, false);
        assertEquals("../dep", lock.packages().get("dep").location());
    }

    @Test void failedCloneCleansTemporaryGitState() throws Exception {
        Path app = initializedApp();
        assertThrows(PackageException.class, () -> new PackageManager().add(app,
            DependencySpec.git("missing", temporary.resolve("absent.git").toUri().toString(), "main")));
        Path cache = app.resolve(".tlang/cache/git");
        if (Files.exists(cache)) {
            try (var files = Files.list(cache)) {
                assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".tmp-")));
            }
        }
    }

    @Test void corruptGitCacheIsRepairedOnline() throws Exception {
        Path repository = temporary.resolve("dep"); PackageTestSupport.initGitPackage(repository, "dep", Map.of(), null);
        Path app = initializedApp(); PackageManager manager = new PackageManager();
        manager.add(app, DependencySpec.git("dep", repository.toUri().toString(), PackageTestSupport.git(repository, "rev-parse", "HEAD")));
        Path cachedFile;
        try (var paths = Files.walk(app.resolve(".tlang/cache/git"))) {
            cachedFile = paths.filter(path -> path.getFileName().toString().equals("dep.tiny")).findFirst().orElseThrow();
        }
        Files.writeString(cachedFile, "corrupt\n");
        PackageFiles.deleteTree(app.resolve(".tlang/packages/dep"));
        manager.install(app, false, false);
        assertTrue(Files.readString(app.resolve(".tlang/packages/dep/dep.tiny")).contains("package_name"));
    }

    @Test void rejectsDependencyRequestedCheckoutFilters() throws Exception {
        Path repository = temporary.resolve("filtered");
        PackageTestSupport.initGitPackage(repository, "filtered", Map.of(), null);
        Files.writeString(repository.resolve(".gitattributes"), "*.tiny filter=dependency_code\n");
        PackageTestSupport.git(repository, "add", ".gitattributes");
        PackageTestSupport.git(repository, "commit", "--quiet", "-m", "attributes");
        Path app = initializedApp();
        PackageException error = assertThrows(PackageException.class, () -> new PackageManager().add(app,
            DependencySpec.git("filtered", repository.toUri().toString(),
                PackageTestSupport.git(repository, "rev-parse", "HEAD"))));
        assertTrue(error.getMessage().contains("external checkout filter"), error.getMessage());
        assertFalse(Files.exists(app.resolve("tlang.lock")));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void installedSymlinkCannotEscapeOrOverwriteProjectFiles() throws Exception {
        Path dep = temporary.resolve("dep"); PackageTestSupport.writePackage(dep, "dep", Map.of(), null);
        Path app = initializedApp(); PackageManager manager = new PackageManager();
        manager.add(app, DependencySpec.path("dep", "../dep"));
        Path protectedFile = app.resolve("protected.txt"); Files.writeString(protectedFile, "safe");
        PackageFiles.deleteTree(app.resolve(".tlang/packages/dep"));
        Files.createSymbolicLink(app.resolve(".tlang/packages/dep"), app);
        manager.install(app, false, false);
        assertEquals("safe", Files.readString(protectedFile));
        assertFalse(Files.isSymbolicLink(app.resolve(".tlang/packages/dep")));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void symlinkedPackageStateRootIsRejected() throws Exception {
        Path dep = temporary.resolve("dep"); PackageTestSupport.writePackage(dep, "dep", Map.of(), null);
        Path app = initializedApp();
        Path outside = temporary.resolve("outside-state"); Files.createDirectories(outside);
        Files.createDirectories(app.resolve(".tlang"));
        Files.createSymbolicLink(app.resolve(".tlang/packages"), outside);
        PackageException error = assertThrows(PackageException.class,
            () -> new PackageManager().add(app, DependencySpec.path("dep", "../dep")));
        assertTrue(error.getMessage().contains("symbolic links"), error.getMessage());
        try (var files = Files.list(outside)) { assertEquals(0, files.count()); }
    }

    private Path initializedApp() throws Exception {
        Path app = temporary.resolve("app"); Files.createDirectories(app); new PackageManager().init(app, "app"); return app;
    }

    private static PackageManager failOnMetadataWrite(int failedWrite, Path materializedPackage) {
        AtomicInteger writes = new AtomicInteger();
        return new PackageManager((target, content) -> {
            assertTrue(Files.isDirectory(materializedPackage), "packages must be materialized before metadata commit");
            if (writes.incrementAndGet() == failedWrite) throw new PackageException("injected metadata commit failure");
            PackageFiles.atomicWrite(target, content);
        });
    }

    private static Map<String, String> installedHashes(Path app) throws Exception {
        Path packages = app.resolve(".tlang/packages");
        if (!Files.exists(packages)) return Map.of();
        Map<String, String> hashes = new java.util.TreeMap<>();
        try (var paths = Files.list(packages)) {
            for (Path path : paths.filter(Files::isDirectory).toList()) {
                if (!path.getFileName().toString().startsWith(".")) {
                    hashes.put(path.getFileName().toString(), PackageHashes.tree(path));
                }
            }
        }
        return hashes;
    }

    private static void assertInstalledMatchesLock(Path app) throws Exception {
        PackageLock lock = LockfileCodec.parse(Files.readString(app.resolve("tlang.lock")), "lock");
        assertEquals(lock.packages().keySet(), installedHashes(app).keySet());
        for (PackageRecord record : lock.packages().values()) {
            Path installed = app.resolve(".tlang/packages").resolve(record.name());
            assertEquals(record.contentSha256(), PackageHashes.tree(installed));
            assertEquals("name=" + record.name() + "\ncontent-sha256=" + record.contentSha256() + "\n",
                Files.readString(installed.resolve(PackageHashes.METADATA_FILE)));
        }
    }

    private static void assertNoTransactionDirectories(Path app) throws Exception {
        Path state = app.resolve(".tlang");
        if (!Files.exists(state)) return;
        try (var paths = Files.walk(state)) {
            Set<String> stale = new HashSet<>();
            paths.map(path -> path.getFileName().toString())
                .filter(name -> name.startsWith(".tmp-") || name.contains(".old-"))
                .forEach(stale::add);
            assertTrue(stale.isEmpty(), "stale transaction paths: " + stale);
        }
    }
}
