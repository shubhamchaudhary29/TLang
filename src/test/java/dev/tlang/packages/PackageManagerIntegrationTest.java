package dev.tlang.packages;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PackageManagerIntegrationTest {
    @TempDir Path temporary;

    @Test void resolvesInstallsAndListsDeterministicDiamondGraph() throws Exception {
        Path app = temporary.resolve("app");
        Path a = temporary.resolve("a");
        Path b = temporary.resolve("b");
        Path c = temporary.resolve("nested").resolve("c");
        PackageTestSupport.writePackage(c, "c", Map.of(), "let value be \"c\"\n");
        PackageTestSupport.writePackage(a, "a", Map.of("c", DependencySpec.path("c", PackageTestSupport.relative(a, c))), null);
        PackageTestSupport.writePackage(b, "b", Map.of("c", DependencySpec.path("c", PackageTestSupport.relative(b, c))), null);
        Files.createDirectories(app);
        PackageManager manager = new PackageManager();
        manager.init(app, "app");
        manager.add(app, DependencySpec.path("a", PackageTestSupport.relative(app, a)));
        PackageLock lock = manager.add(app, DependencySpec.path("b", PackageTestSupport.relative(app, b)));

        assertEquals(java.util.Set.of("a", "b", "c"), lock.packages().keySet());
        assertEquals(LockfileCodec.write(lock), Files.readString(app.resolve("tlang.lock")));
        assertTrue(Files.isRegularFile(app.resolve(".tlang/packages/c/c.tiny")));
        assertTrue(manager.list(app).contains("c (shared)"));
        assertEquals(lock, manager.install(app, false, false));
    }

    @Test void detectsDeepCycleWithoutOverflowAndLeavesManifestUntouched() throws Exception {
        Path app = temporary.resolve("app");
        Path a = temporary.resolve("a");
        Path b = temporary.resolve("b");
        Path c = temporary.resolve("c");
        PackageTestSupport.writePackage(a, "a", Map.of("b", DependencySpec.path("b", "../b")), null);
        PackageTestSupport.writePackage(b, "b", Map.of("c", DependencySpec.path("c", "../c")), null);
        PackageTestSupport.writePackage(c, "c", Map.of("a", DependencySpec.path("a", "../a")), null);
        Files.createDirectories(app);
        PackageManager manager = new PackageManager(); manager.init(app, "app");
        String before = Files.readString(app.resolve("tlang.toml"));
        PackageException error = assertThrows(PackageException.class,
            () -> manager.add(app, DependencySpec.path("a", "../a")));
        assertTrue(error.getMessage().contains("a -> b -> c -> a"), error.getMessage());
        assertEquals(before, Files.readString(app.resolve("tlang.toml")));
        assertFalse(Files.exists(app.resolve("tlang.lock")));
    }

    @Test void repairsCorruptAndPartialInstallButRejectsChangedLocalSource() throws Exception {
        Path app = temporary.resolve("app"); Path dep = temporary.resolve("dep");
        PackageTestSupport.writePackage(dep, "dep", Map.of(), "let value be 1\n");
        Files.createDirectories(app);
        PackageManager manager = new PackageManager(); manager.init(app, "app");
        manager.add(app, DependencySpec.path("dep", "../dep"));
        Path installed = app.resolve(".tlang/packages/dep/dep.tiny");
        Files.writeString(installed, "corrupt");
        Files.createDirectories(app.resolve(".tlang/packages/.tmp-stale"));
        manager.install(app, false, false);
        assertEquals("let value be 1\n", Files.readString(installed));
        assertFalse(Files.exists(app.resolve(".tlang/packages/.tmp-stale")));

        Files.writeString(dep.resolve("dep.tiny"), "let value be 2\n");
        assertTrue(assertThrows(PackageException.class,
            () -> manager.install(app, false, false)).getMessage().contains("local source changed"));
        manager.install(app, false, true);
        assertEquals("let value be 2\n", Files.readString(installed));
    }

    @Test void removeRetainsSharedTransitivePackageAndDeletesUnusedPackage() throws Exception {
        Path app = temporary.resolve("app"); Path a = temporary.resolve("a");
        Path b = temporary.resolve("b"); Path c = temporary.resolve("c");
        PackageTestSupport.writePackage(c, "c", Map.of(), null);
        PackageTestSupport.writePackage(a, "a", Map.of("c", DependencySpec.path("c", "../c")), null);
        PackageTestSupport.writePackage(b, "b", Map.of("c", DependencySpec.path("c", "../c")), null);
        Files.createDirectories(app); PackageManager manager = new PackageManager(); manager.init(app, "app");
        manager.add(app, DependencySpec.path("a", "../a")); manager.add(app, DependencySpec.path("b", "../b"));
        PackageLock lock = manager.remove(app, "a");
        assertEquals(java.util.Set.of("b", "c"), lock.packages().keySet());
        assertFalse(Files.exists(app.resolve(".tlang/packages/a")));
        assertTrue(Files.exists(app.resolve(".tlang/packages/c")));
    }

    @Test void concurrentInstallersSerializeAndProduceOneValidTree() throws Exception {
        Path app = temporary.resolve("app"); Path dep = temporary.resolve("dep");
        PackageTestSupport.writePackage(dep, "dep", Map.of(), null);
        Files.createDirectories(app); PackageManager manager = new PackageManager(); manager.init(app, "app");
        manager.add(app, DependencySpec.path("dep", "../dep"));
        PackageFiles.deleteTree(app.resolve(".tlang/packages/dep"));
        CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { ready.countDown(); start.await(); return new PackageManager().install(app, false, false); });
            var second = executor.submit(() -> { ready.countDown(); start.await(); return new PackageManager().install(app, false, false); });
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertTrue(Files.isRegularFile(app.resolve(".tlang/packages/dep/.tlang-package-meta")));
        assertFalse(Files.list(app.resolve(".tlang/packages")).anyMatch(p -> p.getFileName().toString().startsWith(".tmp-")));
    }

    @Test void concurrentCliProcessesDoNotCorruptInstallation() throws Exception {
        Path app = temporary.resolve("app"); Path dep = temporary.resolve("dep");
        PackageTestSupport.writePackage(dep, "dep", Map.of(), null);
        Files.createDirectories(app); PackageManager manager = new PackageManager(); manager.init(app, "app");
        manager.add(app, DependencySpec.path("dep", "../dep"));
        PackageFiles.deleteTree(app.resolve(".tlang/packages/dep"));
        CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { ready.countDown(); start.await(); return PackageTestSupport.cli(app, "install"); });
            var second = executor.submit(() -> { ready.countDown(); start.await(); return PackageTestSupport.cli(app, "install"); });
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            assertEquals(0, first.get(15, TimeUnit.SECONDS).exitCode());
            assertEquals(0, second.get(15, TimeUnit.SECONDS).exitCode());
        }
        PackageLock lock = LockfileCodec.parse(Files.readString(app.resolve("tlang.lock")), "lock");
        assertEquals(1, lock.packages().size());
        assertTrue(Files.exists(app.resolve(".tlang/packages/dep/dep.tiny")));
    }

    @Test void resolvesLargeLinearGraphDeterministically() throws Exception {
        int count = 105;
        for (int i = count - 1; i >= 0; i--) {
            String name = "p" + i;
            Path root = temporary.resolve(name);
            Map<String, DependencySpec> deps = i == count - 1 ? Map.of()
                : Map.of("p" + (i + 1), DependencySpec.path("p" + (i + 1), "../p" + (i + 1)));
            PackageTestSupport.writePackage(root, name, deps, null);
        }
        Path app = temporary.resolve("app"); Files.createDirectories(app);
        PackageManager manager = new PackageManager(); manager.init(app, "app");
        PackageLock lock = manager.add(app, DependencySpec.path("p0", "../p0"));
        assertEquals(count, lock.packages().size());
        assertEquals(LockfileCodec.write(lock), LockfileCodec.write(lock));
    }

    @Test void rejectsMissingInvalidAndMismatchedPackages() throws Exception {
        Path app = temporary.resolve("app"); Files.createDirectories(app);
        PackageManager manager = new PackageManager(); manager.init(app, "app");
        assertThrows(PackageException.class, () -> manager.add(app, DependencySpec.path("missing", "../missing")));
        Path noManifest = temporary.resolve("no_manifest"); Files.createDirectories(noManifest);
        assertThrows(PackageException.class, () -> manager.add(app, DependencySpec.path("no_manifest", "../no_manifest")));
        Path wrong = temporary.resolve("wrong"); PackageTestSupport.writePackage(wrong, "actual", Map.of(), null);
        assertThrows(PackageException.class, () -> manager.add(app, DependencySpec.path("wrong", "../wrong")));
        Path malformed = temporary.resolve("malformed"); Files.createDirectories(malformed);
        Files.writeString(malformed.resolve("tlang.toml"), "[package]\nname = \"malformed\"\nversion = nope\n");
        Files.writeString(malformed.resolve("malformed.tiny"), "let value be 1\n");
        assertThrows(PackageException.class, () -> manager.add(app, DependencySpec.path("malformed", "../malformed")));
    }

    @Test void offlineLocalInstallWorksAndMissingLockFails() throws Exception {
        Path app = temporary.resolve("app"); Path dep = temporary.resolve("dep");
        PackageTestSupport.writePackage(dep, "dep", Map.of(), null); Files.createDirectories(app);
        PackageManager manager = new PackageManager(); manager.init(app, "app");
        assertThrows(PackageException.class, () -> manager.install(app, true, false));
        manager.add(app, DependencySpec.path("dep", "../dep"));
        PackageFiles.deleteTree(app.resolve(".tlang/packages/dep"));
        manager.install(app, true, false);
        assertTrue(Files.exists(app.resolve(".tlang/packages/dep/dep.tiny")));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void rejectsSymlinksInsideUntrustedPackage() throws Exception {
        Path app = temporary.resolve("app"); Path dep = temporary.resolve("dep");
        PackageTestSupport.writePackage(dep, "dep", Map.of(), null);
        Files.createSymbolicLink(dep.resolve("escape"), temporary.resolve("outside"));
        Files.createDirectories(app); PackageManager manager = new PackageManager(); manager.init(app, "app");
        assertTrue(assertThrows(PackageException.class,
            () -> manager.add(app, DependencySpec.path("dep", "../dep"))).getMessage().contains("symbolic"));
    }
}
