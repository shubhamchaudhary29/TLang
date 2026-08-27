package dev.tlang.packages;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GitPackageIntegrationTest {
    @TempDir Path temporary;

    @Test void pinsBranchAndLockedInstallDoesNotAdvanceUntilUpdate() throws Exception {
        Path repository = temporary.resolve("gitdep");
        PackageTestSupport.initGitPackage(repository, "gitdep", Map.of(), "let value be 1\n");
        String branch = PackageTestSupport.git(repository, "branch", "--show-current");
        String firstCommit = PackageTestSupport.git(repository, "rev-parse", "HEAD");
        Path app = initializedApp("app");
        PackageManager manager = new PackageManager();
        PackageLock initial = manager.add(app, DependencySpec.git("gitdep", PackageTestSupport.fileUri(repository), branch));
        assertEquals(firstCommit, initial.packages().get("gitdep").commit());

        Files.writeString(repository.resolve("gitdep.tiny"), "let value be 2\n");
        PackageTestSupport.git(repository, "add", "gitdep.tiny");
        PackageTestSupport.git(repository, "commit", "--quiet", "-m", "advance");
        String secondCommit = PackageTestSupport.git(repository, "rev-parse", "HEAD");
        assertNotEquals(firstCommit, secondCommit);

        PackageFiles.deleteTree(app.resolve(".tlang/packages/gitdep"));
        PackageLock locked = manager.install(app, false, false);
        assertEquals(firstCommit, locked.packages().get("gitdep").commit());
        assertEquals("let value be 1\n", Files.readString(app.resolve(".tlang/packages/gitdep/gitdep.tiny")));

        PackageLock updated = manager.install(app, false, true);
        assertEquals(secondCommit, updated.packages().get("gitdep").commit());
        assertEquals("let value be 2\n", Files.readString(app.resolve(".tlang/packages/gitdep/gitdep.tiny")));
    }

    @Test void supportsTagAndCommitRevisions() throws Exception {
        Path repository = temporary.resolve("lib");
        PackageTestSupport.initGitPackage(repository, "lib", Map.of(), null);
        String commit = PackageTestSupport.git(repository, "rev-parse", "HEAD");
        PackageTestSupport.git(repository, "tag", "v1");

        PackageLock tagLock = new PackageManager().add(initializedApp("tag-app"),
            DependencySpec.git("lib", PackageTestSupport.fileUri(repository), "v1"));
        PackageLock commitLock = new PackageManager().add(initializedApp("commit-app"),
            DependencySpec.git("lib", PackageTestSupport.fileUri(repository), commit));
        assertEquals(commit, tagLock.packages().get("lib").commit());
        assertEquals(commit, commitLock.packages().get("lib").commit());
    }

    @Test void offlineUsesExactCacheAndNeverFallsBackWhenCacheMissing() throws Exception {
        Path repository = temporary.resolve("dep");
        PackageTestSupport.initGitPackage(repository, "dep", Map.of(), null);
        Path app = initializedApp("app"); PackageManager manager = new PackageManager();
        manager.add(app, DependencySpec.git("dep", PackageTestSupport.fileUri(repository),
            PackageTestSupport.git(repository, "rev-parse", "HEAD")));
        Files.move(repository, temporary.resolve("repository-unavailable"));
        PackageFiles.deleteTree(app.resolve(".tlang/packages/dep"));
        manager.install(app, true, false);
        assertTrue(Files.exists(app.resolve(".tlang/packages/dep/dep.tiny")));

        // A complete verified install is itself sufficient for offline use.
        PackageFiles.deleteTree(app.resolve(".tlang/cache/git"));
        manager.install(app, true, false);

        PackageFiles.deleteTree(app.resolve(".tlang/packages/dep"));
        PackageException error = assertThrows(PackageException.class, () -> manager.install(app, true, false));
        assertTrue(error.getMessage().contains("offline install is missing cached"), error.getMessage());
    }

    @Test void resolvesTransitiveGitAndMixedLocalGraph() throws Exception {
        Path child = temporary.resolve("child");
        PackageTestSupport.initGitPackage(child, "child", Map.of(), null);
        String childCommit = PackageTestSupport.git(child, "rev-parse", "HEAD");
        Path parent = temporary.resolve("parent");
        PackageTestSupport.initGitPackage(parent, "parent", Map.of(
            "child", DependencySpec.git("child", PackageTestSupport.fileUri(child), childCommit)),
            "import child\nlet value be child.package_name\n");
        String parentCommit = PackageTestSupport.git(parent, "rev-parse", "HEAD");
        Path local = temporary.resolve("local"); PackageTestSupport.writePackage(local, "local", Map.of(), null);
        Path app = initializedApp("app"); PackageManager manager = new PackageManager();
        manager.add(app, DependencySpec.path("local", "../local"));
        PackageLock lock = manager.add(app, DependencySpec.git("parent", PackageTestSupport.fileUri(parent), parentCommit));
        assertEquals(java.util.Set.of("child", "local", "parent"), lock.packages().keySet());
        assertEquals(java.util.List.of("child"), lock.packages().get("parent").dependencies());
    }

    @Test void reportsRevisionAndRepositoryFailuresWithoutChangingManifest() throws Exception {
        Path repository = temporary.resolve("dep");
        PackageTestSupport.initGitPackage(repository, "dep", Map.of(), null);
        Path app = initializedApp("app"); String before = Files.readString(app.resolve("tlang.toml"));
        PackageManager manager = new PackageManager();
        PackageException missingRevision = assertThrows(PackageException.class, () -> manager.add(app,
            DependencySpec.git("dep", PackageTestSupport.fileUri(repository), "does-not-exist")));
        assertTrue(missingRevision.getMessage().contains("was not found"), missingRevision.getMessage());
        assertEquals(before, Files.readString(app.resolve("tlang.toml")));
        assertThrows(PackageException.class, () -> manager.add(app,
            DependencySpec.git("dep", temporary.resolve("absent.git").toUri().toString(), "main")));
        assertEquals(before, Files.readString(app.resolve("tlang.toml")));
    }

    @Test void rejectsGitRepositoryWithoutManifest() throws Exception {
        Path repository = temporary.resolve("empty-repo"); Files.createDirectories(repository);
        PackageTestSupport.git(repository, "init", "--quiet");
        PackageTestSupport.git(repository, "config", "user.email", "tests@tlang.dev");
        PackageTestSupport.git(repository, "config", "user.name", "TLang Tests");
        Files.writeString(repository.resolve("README"), "nothing");
        PackageTestSupport.git(repository, "add", "."); PackageTestSupport.git(repository, "commit", "--quiet", "-m", "initial");
        String commit = PackageTestSupport.git(repository, "rev-parse", "HEAD");
        PackageException error = assertThrows(PackageException.class, () -> new PackageManager().add(initializedApp("app"),
            DependencySpec.git("empty_repo", PackageTestSupport.fileUri(repository), commit)));
        assertTrue(error.getMessage().contains("tlang.toml"));
    }

    @Test void cliAddsGitDependencyAndPrintsSafeFailure() throws Exception {
        Path repository = temporary.resolve("cli_dep");
        PackageTestSupport.initGitPackage(repository, "cli_dep", Map.of(), null);
        String commit = PackageTestSupport.git(repository, "rev-parse", "HEAD");
        Path app = initializedApp("cli-app");
        PackageTestSupport.ProcessResult added = PackageTestSupport.cli(app, "add", "cli_dep",
            "--git", repository.toUri().toString(), "--rev", commit);
        assertEquals(0, added.exitCode(), added.output());
        assertEquals(commit, LockfileCodec.parse(Files.readString(app.resolve("tlang.lock")), "lock")
            .packages().get("cli_dep").commit());

        Path other = initializedApp("failure-app");
        PackageTestSupport.ProcessResult failure = PackageTestSupport.cli(other, "add", "cli_dep",
            "--git", repository.toUri().toString(), "--rev", "missing-ref");
        assertEquals(1, failure.exitCode());
        assertTrue(failure.output().startsWith("Package error:"), failure.output());
        assertFalse(failure.output().contains("java.lang"));
    }

    private Path initializedApp(String name) throws Exception {
        Path app = temporary.resolve(name); Files.createDirectories(app); new PackageManager().init(app, name); return app;
    }
}
