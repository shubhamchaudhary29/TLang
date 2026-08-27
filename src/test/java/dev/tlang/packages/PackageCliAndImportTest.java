package dev.tlang.packages;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PackageCliAndImportTest {
    @TempDir Path temporary;

    @Test void cliInitAddListInstallRemoveAndHelpHaveStableUx() throws Exception {
        Path dependency = temporary.resolve("utils");
        PackageTestSupport.writePackage(dependency, "utils", Map.of(), "let value be 42\n");
        Path app = temporary.resolve("app"); Files.createDirectories(app);

        PackageTestSupport.ProcessResult init = PackageTestSupport.cli(app, "init", "--name", "app");
        assertEquals(0, init.exitCode(), init.output());
        assertTrue(init.output().contains("Created tlang.toml"));
        PackageTestSupport.ProcessResult overwrite = PackageTestSupport.cli(app, "init");
        assertEquals(1, overwrite.exitCode());
        assertTrue(overwrite.output().startsWith("Package error:"));
        assertFalse(overwrite.output().contains("Exception"));

        PackageTestSupport.ProcessResult add = PackageTestSupport.cli(app, "add", "utils", "--path", "../utils");
        assertEquals(0, add.exitCode(), add.output());
        assertTrue(Files.isRegularFile(app.resolve("tlang.lock")));
        assertEquals(0, PackageTestSupport.cli(app, "install", "--offline").exitCode());
        PackageTestSupport.ProcessResult list = PackageTestSupport.cli(app, "list");
        assertEquals(0, list.exitCode()); assertTrue(list.output().contains("utils"));
        PackageTestSupport.ProcessResult remove = PackageTestSupport.cli(app, "remove", "utils");
        assertEquals(0, remove.exitCode(), remove.output());
        assertFalse(Files.exists(app.resolve(".tlang/packages/utils")));

        PackageTestSupport.ProcessResult invalid = PackageTestSupport.cli(app, "add", "bad-name", "--path", "x");
        assertEquals(1, invalid.exitCode());
        assertFalse(invalid.output().contains("java.lang"));
        PackageTestSupport.ProcessResult usage = PackageTestSupport.cli(app, "install", "--unknown");
        assertEquals(64, usage.exitCode());
        assertEquals(0, PackageTestSupport.cli(app, "help").exitCode());
        assertTrue(PackageTestSupport.cli(app, "help").output().contains("tlang install [--offline] [--update]"));
    }

    @Test void cliReportsMissingProjectAndInvalidArgumentsWithDocumentedExitCodes() throws Exception {
        Path empty = temporary.resolve("empty"); Files.createDirectories(empty);
        PackageTestSupport.ProcessResult missing = PackageTestSupport.cli(empty, "install");
        assertEquals(1, missing.exitCode());
        assertTrue(missing.output().startsWith("Package error: no tlang.toml"), missing.output());
        assertFalse(missing.output().contains("Exception"));
        PackageTestSupport.ProcessResult invalid = PackageTestSupport.cli(empty, "add", "dep", "--git", "x");
        assertEquals(64, invalid.exitCode());
        assertTrue(invalid.output().contains("Usage: tlang add"));
    }

    @Test void importsDirectPackageFromRootClosureAndSpawnedTask() throws Exception {
        Path dependency = temporary.resolve("utils");
        PackageTestSupport.writePackage(dependency, "utils", Map.of(), """
            define double taking value
                return value * 2
            """);
        Path app = initializedApp();
        new PackageManager().add(app, DependencySpec.path("utils", "../utils"));
        Files.writeString(app.resolve("main.tiny"), """
            import utils
            let call be function taking value
                return utils.double(value)
            let work be spawn call(21)
            show await work
            """);
        PackageTestSupport.ProcessResult run = PackageTestSupport.cli(app, "run", "main.tiny");
        assertEquals(0, run.exitCode(), run.output());
        assertEquals("42\n", run.output());
    }

    @Test void importsPackageFromLocalModuleAndTransitivePackageFromDependency() throws Exception {
        Path child = temporary.resolve("child");
        PackageTestSupport.writePackage(child, "child", Map.of(), "let value be \"transitive\"\n");
        Path parent = temporary.resolve("parent");
        PackageTestSupport.writePackage(parent, "parent", Map.of("child", DependencySpec.path("child", "../child")), """
            import child
            import helper
            let value be child.value + helper.suffix
            """);
        Files.writeString(parent.resolve("helper.tiny"), "let suffix be \"-nested\"\n");
        Path app = initializedApp(); new PackageManager().add(app, DependencySpec.path("parent", "../parent"));
        Files.writeString(app.resolve("feature.tiny"), "import parent\nlet result be parent.value\n");
        Files.writeString(app.resolve("main.tiny"), "import feature\nshow feature.result\n");
        PackageTestSupport.ProcessResult run = PackageTestSupport.cli(app, "main.tiny");
        assertEquals(0, run.exitCode(), run.output());
        assertEquals("transitive-nested\n", run.output());
    }

    @Test void localModulePrecedesInstalledPackageAndNativeModulesRemainReserved() throws Exception {
        Path dependency = temporary.resolve("utils");
        PackageTestSupport.writePackage(dependency, "utils", Map.of(), "let value be \"package\"\n");
        Path app = initializedApp(); new PackageManager().add(app, DependencySpec.path("utils", "../utils"));
        Files.writeString(app.resolve("utils.tiny"), "let value be \"local\"\n");
        Files.writeString(app.resolve("main.tiny"), "import utils\nshow utils.value\n");
        PackageTestSupport.ProcessResult run = PackageTestSupport.cli(app, "run", "main.tiny");
        assertEquals(0, run.exitCode(), run.output()); assertEquals("local\n", run.output());
    }

    @Test void runtimeErrorReportsInstalledDependencySourceWithoutJavaFrames() throws Exception {
        Path dependency = temporary.resolve("broken");
        PackageTestSupport.writePackage(dependency, "broken", Map.of(), "let value be 1 / 0\n");
        Path app = initializedApp(); new PackageManager().add(app, DependencySpec.path("broken", "../broken"));
        Files.writeString(app.resolve("main.tiny"), "import broken\n");
        PackageTestSupport.ProcessResult run = PackageTestSupport.cli(app, "run", "main.tiny");
        assertEquals(70, run.exitCode(), run.output());
        assertTrue(run.output().replace('\\', '/').contains(".tlang/packages/broken/broken.tiny:1"), run.output());
        assertFalse(run.output().contains("java.lang"));
    }

    @Test void dependencyCannotImportUnrelatedRootOrTransitivePackage() throws Exception {
        Path hidden = temporary.resolve("hidden"); PackageTestSupport.writePackage(hidden, "hidden", Map.of(), null);
        Path parent = temporary.resolve("parent");
        PackageTestSupport.writePackage(parent, "parent", Map.of(), "import hidden\n");
        Path app = initializedApp(); PackageManager manager = new PackageManager();
        manager.add(app, DependencySpec.path("hidden", "../hidden"));
        manager.add(app, DependencySpec.path("parent", "../parent"));
        Files.writeString(app.resolve("hidden.tiny"), "let root_secret be true\n");
        Files.writeString(app.resolve("main.tiny"), "import parent\n");
        PackageTestSupport.ProcessResult run = PackageTestSupport.cli(app, "run", "main.tiny");
        assertEquals(70, run.exitCode(), run.output());
        assertTrue(run.output().contains("Module 'hidden' not found"), run.output());
    }

    private Path initializedApp() throws Exception {
        Path app = temporary.resolve("app"); Files.createDirectories(app); new PackageManager().init(app, "app"); return app;
    }
}
