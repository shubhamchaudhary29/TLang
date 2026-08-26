package dev.tlang.errors;

import dev.tlang.ast.Stmt;
import dev.tlang.interpreter.Environment;
import dev.tlang.interpreter.Interpreter;
import dev.tlang.interpreter.NativeFunction;
import dev.tlang.lexer.Lexer;
import dev.tlang.lexer.SourceUnit;
import dev.tlang.lexer.Token;
import dev.tlang.lexer.TokenType;
import dev.tlang.modules.DatabaseModule;
import dev.tlang.modules.ModuleLoader;
import dev.tlang.modules.ValidateModule;
import dev.tlang.parser.Parser;
import dev.tlang.runtime.http.HttpOps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeDiagnosticTest {
    @Test
    void nestedCallsProduceImmutableInnermostFirstFrames(@TempDir Path directory) {
        String source = """
            define c
                return 1 / 0
            define b
                return c()
            define a
                return b()
            a()
            """;

        RuntimeError error = interpretFailure(source, directory.resolve("nested.tiny"), directory);

        assertEquals(RuntimeErrorKind.RUNTIME_ERROR, error.getKind());
        assertEquals(2, error.getLocation().line());
        assertTrue(error.getLocation().sourceName().endsWith("nested.tiny"));
        assertEquals(List.of("c", "b", "a"),
            error.getFrames().stream().map(RuntimeStackFrame::name).toList());
        assertEquals(List.of(4, 6, 7),
            error.getFrames().stream().map(frame -> frame.location().line()).toList());
        assertThrows(UnsupportedOperationException.class,
            () -> error.getFrames().add(RuntimeStackFrame.httpHandler("GET", "/")));
    }

    @Test
    void recursionAndMutualRecursionRetainLegitimateRepeatedFrames(@TempDir Path directory) {
        String recursive = """
            define recurse taking n
                if n == 0
                    return 1 / 0
                return recurse(n - 1)
            recurse(3)
            """;
        RuntimeError recursion = interpretFailure(
            recursive, directory.resolve("recursive.tiny"), directory);
        assertEquals(List.of("recurse", "recurse", "recurse", "recurse"),
            recursion.getFrames().stream().map(RuntimeStackFrame::name).toList());

        String mutual = """
            define left taking n
                if n == 0
                    return 1 / 0
                return right(n - 1)
            define right taking n
                return left(n - 1)
            left(2)
            """;
        RuntimeError mutualError = interpretFailure(
            mutual, directory.resolve("mutual.tiny"), directory);
        assertEquals(List.of("left", "right", "left"),
            mutualError.getFrames().stream().map(RuntimeStackFrame::name).toList());
    }

    @Test
    void closuresAnonymousFunctionsAndDefaultsKeepSourceIdentity(@TempDir Path directory) {
        String source = """
            define factory
                return function
                    return 1 / 0
            let closure be factory()
            closure()
            """;
        Path sourcePath = directory.resolve("closures.tiny");
        RuntimeError closure = interpretFailure(source, sourcePath, directory);
        assertEquals("<anonymous>", closure.getFrames().get(0).name());
        assertEquals(StackFrameType.ANONYMOUS_FUNCTION, closure.getFrames().get(0).type());
        assertEquals(sourcePath.toString(), closure.getLocation().sourceName());

        String defaultSource = """
            define failByDefault taking value be 1 / 0
                return value
            failByDefault()
            """;
        RuntimeError defaultError = interpretFailure(
            defaultSource, directory.resolve("defaults.tiny"), directory);
        assertEquals(List.of("failByDefault"),
            defaultError.getFrames().stream().map(RuntimeStackFrame::name).toList());
        assertEquals(1, defaultError.getLocation().line());
    }

    @Test
    void interpolatedExpressionsRetainTheirOwningSourceUnit(@TempDir Path directory) {
        Path sourcePath = directory.resolve("interpolation.tiny");
        String source = "let divisor be 0\nshow \"value ${1 / divisor}\"\n";

        RuntimeError error = interpretFailure(source, sourcePath, directory);

        assertEquals(sourcePath.toString(), error.getLocation().sourceName());
        assertEquals(source, error.getLocation().source());
        assertEquals(2, error.getLocation().line());
        assertTrue(error.getLocation().column() > 1);
    }

    @Test
    void nestedModulesPreserveOriginAndCrossFileFrames(@TempDir Path directory) throws Exception {
        Path moduleB = directory.resolve("moduleB.tiny");
        Path moduleA = directory.resolve("moduleA.tiny");
        Path main = directory.resolve("main.tiny");
        Files.writeString(moduleB, """
            define crash
                return 1 / 0
            """);
        Files.writeString(moduleA, """
            import moduleB
            define relay
                return moduleB.crash()
            """);
        String mainSource = """
            import moduleA
            define outer
                return moduleA.relay()
            outer()
            """;

        RuntimeError error = interpretFailure(mainSource, main, directory);

        assertEquals(moduleB.toAbsolutePath().normalize().toString(), error.getLocation().sourceName());
        assertEquals(List.of("crash", "relay", "outer"),
            error.getFrames().stream().map(RuntimeStackFrame::name).toList());
        assertEquals(moduleA.toAbsolutePath().normalize().toString(),
            error.getFrames().get(0).location().sourceName());
        assertEquals(main.toString(), error.getFrames().get(1).location().sourceName());
    }

    @Test
    void checkedInFixtureProvesThreeFileDiagnostic() throws Exception {
        Path directory = Path.of("src/test/resources/runtime/errors").toAbsolutePath().normalize();
        Path main = directory.resolve("stack_main.tiny");

        RuntimeError error = interpretFailure(Files.readString(main), main, directory);

        assertTrue(error.getLocation().sourceName().endsWith("stack_math.tiny"));
        assertEquals(List.of("divide", "buildReport", "handleRequest"),
            error.getFrames().stream().map(RuntimeStackFrame::name).toList());
        assertTrue(error.getFrames().get(0).location().sourceName().endsWith("stack_service.tiny"));
        assertTrue(error.getFrames().get(1).location().sourceName().endsWith("stack_main.tiny"));
    }

    @Test
    void moduleInitializationFailureRetainsEveryModuleBoundary(@TempDir Path directory) throws Exception {
        Path moduleB = directory.resolve("moduleB.tiny");
        Path moduleA = directory.resolve("moduleA.tiny");
        Path main = directory.resolve("main.tiny");
        Files.writeString(moduleB, "let broken be 1 / 0\n");
        Files.writeString(moduleA, "import moduleB\n");

        RuntimeError error = interpretFailure("import moduleA\n", main, directory);

        assertEquals(moduleB.toAbsolutePath().normalize().toString(), error.getLocation().sourceName());
        assertEquals(List.of("<module moduleB>", "<module moduleA>"),
            error.getFrames().stream().map(RuntimeStackFrame::name).toList());
        assertEquals(StackFrameType.MODULE, error.getFrames().get(0).type());
    }

    @Test
    void importedCompileErrorsKeepTheFailingModuleSource(@TempDir Path directory) throws Exception {
        record Case(String module, String source, String diagnostic) {}
        List<Case> cases = List.of(
            new Case("badLexer", "let value be @\n", "Lexer error"),
            new Case("badParser", "let value be\n", "Parse error"),
            new Case("badSemantic", "let value be missing\n", "Semantic error")
        );

        for (Case testCase : cases) {
            Path modulePath = directory.resolve(testCase.module() + ".tiny");
            Files.writeString(modulePath, testCase.source());
            Token importToken = token("import", "main.tiny", "import " + testCase.module() + "\n");

            ModuleLoadError error = assertThrows(ModuleLoadError.class,
                () -> new ModuleLoader(directory).load(testCase.module(), importToken));

            assertEquals(65, error.getExitCode());
            assertEquals(testCase.diagnostic(), error.getDiagnosticKind());
            assertEquals(modulePath.toAbsolutePath().normalize().toString(), error.getSourceName());
            assertEquals(testCase.source(), error.getSource());
        }
    }

    @Test
    void circularImportIsAnImportErrorAtTheActualNestedImport(@TempDir Path directory) throws Exception {
        Path moduleA = directory.resolve("moduleA.tiny");
        Path moduleB = directory.resolve("moduleB.tiny");
        Files.writeString(moduleA, "import moduleB\n");
        Files.writeString(moduleB, "import moduleA\n");

        RuntimeError error = interpretFailure(
            "import moduleA\n", directory.resolve("main.tiny"), directory);

        assertEquals(RuntimeErrorKind.IMPORT_ERROR, error.getKind());
        assertEquals(moduleB.toAbsolutePath().normalize().toString(), error.getLocation().sourceName());
        assertTrue(error.getMessage().contains("Circular import"));
        assertEquals(List.of("<module moduleB>", "<module moduleA>"),
            error.getFrames().stream().map(RuntimeStackFrame::name).toList());
    }

    @Test
    void requiredCategoriesAndNativeCausesAreStructured(@TempDir Path directory) {
        SourceUnit source = new SourceUnit("categories.tiny", "missing\n");
        Token token = new Token(TokenType.IDENTIFIER, "missing", null, 1, 1, source);

        RuntimeError name = assertThrows(RuntimeError.class, () -> new Environment().get(token));
        assertEquals(RuntimeErrorKind.NAME_ERROR, name.getKind());

        RuntimeError type = interpretFailure("let value be 1\nvalue()\n",
            directory.resolve("type.tiny"), directory);
        assertEquals(RuntimeErrorKind.TYPE_ERROR, type.getKind());

        RuntimeError arity = interpretFailure("define one taking value\n    return value\none()\n",
            directory.resolve("arity.tiny"), directory);
        assertEquals(RuntimeErrorKind.ARITY_ERROR, arity.getKind());

        RuntimeError index = interpretFailure("let values be [1]\nshow values[3]\n",
            directory.resolve("index.tiny"), directory);
        assertEquals(RuntimeErrorKind.INDEX_ERROR, index.getKind());

        RuntimeError missingModule = assertThrows(RuntimeError.class,
            () -> new ModuleLoader(directory).load("missing_module", token));
        assertEquals(RuntimeErrorKind.IMPORT_ERROR, missingModule.getKind());

        NativeFunction open = (NativeFunction) new DatabaseModule().getExports().get("open");
        RuntimeError database = assertThrows(RuntimeError.class,
            () -> open.call(List.of(42), token));
        assertEquals(RuntimeErrorKind.DATABASE_ERROR, database.getKind());

        RuntimeError http = assertThrows(RuntimeError.class,
            () -> HttpOps.get("://bad", null, token));
        assertEquals(RuntimeErrorKind.HTTP_ERROR, http.getKind());
        assertNotNull(http.getCause());

        NativeFunction validate = (NativeFunction) new ValidateModule().getExports().get("check");
        RuntimeError validation = assertThrows(RuntimeError.class,
            () -> validate.call(List.of(42, 42), token));
        assertEquals(RuntimeErrorKind.VALIDATION_ERROR, validation.getKind());
    }

    @Test
    @SuppressWarnings("unchecked")
    void databaseProviderFailureRetainsCauseWithoutFormattingIt(@TempDir Path directory) {
        Token token = token("db", "database.tiny", "connection.execute(\"bad sql\", [])\n");
        NativeFunction open = (NativeFunction) new DatabaseModule().getExports().get("open");
        Map<String, Object> connection = (Map<String, Object>) open.call(
            List.of(directory.resolve("errors.sqlite").toString()), token);
        NativeFunction execute = (NativeFunction) connection.get("execute");
        NativeFunction close = (NativeFunction) connection.get("close");
        try {
            RuntimeError error = assertThrows(RuntimeError.class,
                () -> execute.call(List.of(connection, "not valid sql", List.of()), token));
            assertEquals(RuntimeErrorKind.DATABASE_ERROR, error.getKind());
            assertNotNull(error.getCause());
            String formatted = ErrorFormatter.format(error);
            assertFalse(formatted.contains(error.getCause().getClass().getName()));
            assertFalse(formatted.contains("org.sqlite"));
        } finally {
            close.call(List.of(connection), token);
        }
    }

    private static RuntimeError interpretFailure(String source, Path sourcePath, Path scriptDirectory) {
        List<Stmt> program = new Parser(new Lexer(source, sourcePath.toString()).tokenize()).parse();
        Interpreter interpreter = new Interpreter(new ModuleLoader(scriptDirectory));
        return assertThrows(RuntimeError.class, () -> interpreter.interpret(program));
    }

    private static Token token(String lexeme, String sourceName, String source) {
        return new Token(TokenType.IDENTIFIER, lexeme, null, 1, 1,
            new SourceUnit(sourceName, source));
    }
}
