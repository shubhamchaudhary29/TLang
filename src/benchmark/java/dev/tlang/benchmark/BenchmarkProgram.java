package dev.tlang.benchmark;

import dev.tlang.ast.Stmt;
import dev.tlang.errors.SemanticError;
import dev.tlang.interpreter.Interpreter;
import dev.tlang.lexer.Lexer;
import dev.tlang.lexer.Token;
import dev.tlang.modules.ModuleLoader;
import dev.tlang.parser.Parser;
import dev.tlang.resolver.Resolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/** A parsed, semantically valid benchmark fixture that can be executed repeatedly. */
public final class BenchmarkProgram {
    private final String name;
    private final String source;
    private final List<Stmt> statements;
    private final Path scriptDirectory;

    private BenchmarkProgram(String name, String source, List<Stmt> statements, Path scriptDirectory) {
        this.name = name;
        this.source = source;
        this.statements = List.copyOf(statements);
        this.scriptDirectory = scriptDirectory;
    }

    public static BenchmarkProgram load(String name) {
        String resource = "benchmarks/" + name + ".tiny";
        try (InputStream stream = BenchmarkProgram.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalArgumentException("Benchmark fixture not found: " + resource);
            }
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return compile(name, source, Path.of(".").toAbsolutePath().normalize());
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not read benchmark fixture: " + resource, exception);
        }
    }

    public static BenchmarkProgram compile(String name, String source, Path scriptDirectory) {
        try {
            List<Token> tokens = new Lexer(source).tokenize();
            List<Stmt> statements = new Parser(tokens).parse();
            List<SemanticError> errors = new Resolver().resolve(statements);
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException("Invalid benchmark fixture '" + name
                    + "': " + errors.get(0).getMessage());
            }
            return new BenchmarkProgram(name, source, statements, scriptDirectory);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid benchmark fixture '" + name + "'", exception);
        }
    }

    public Object execute() {
        Interpreter interpreter = new Interpreter(new ModuleLoader(scriptDirectory));
        interpreter.interpret(statements);
        if (!interpreter.getGlobalEnvironment().getValues().containsKey("result")) {
            throw new IllegalStateException("Benchmark fixture '" + name
                + "' must define a top-level 'result' binding");
        }
        return interpreter.getGlobalEnvironment().getValues().get("result");
    }

    public Object executeFullPipeline() {
        return compile(name, source, scriptDirectory).execute();
    }

    public void validateResult(Object expected) {
        Object actual = execute();
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Benchmark fixture '" + name + "' expected "
                + expected + " but produced " + actual);
        }
    }

    public String source() {
        return source;
    }

    public List<Stmt> statements() {
        return statements;
    }
}
