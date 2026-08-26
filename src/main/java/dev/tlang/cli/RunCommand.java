package dev.tlang.cli;

import dev.tlang.errors.LexerError;
import dev.tlang.errors.ParseError;
import dev.tlang.errors.RuntimeError;
import dev.tlang.errors.SemanticError;
import dev.tlang.errors.ErrorFormatter;
import dev.tlang.errors.ModuleLoadError;
import dev.tlang.ast.Stmt;
import dev.tlang.lexer.Lexer;
import dev.tlang.lexer.Token;
import dev.tlang.parser.Parser;
import dev.tlang.interpreter.Interpreter;
import dev.tlang.resolver.Resolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public final class RunCommand implements Command {
    @Override
    public void execute(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: tlang run <file>");
            System.exit(64);
        }

        String fileName = args[0];
        String source;
        try {
            source = new String(Files.readAllBytes(Paths.get(fileName)));
        } catch (IOException e) {
            System.err.println("Error: Could not read file '" + fileName + "'.");
            System.exit(1);
            return;
        }

        run(source, fileName);
    }

    private void run(String source, String fileName) {
        try {
            // Stage 1: Lexing
            Lexer lexer = new Lexer(source, fileName);
            List<Token> tokens = lexer.tokenize();

            // Stage 2: Parsing
            Parser parser = new Parser(tokens);
            List<Stmt> program = parser.parse();

            // Semantic Analysis
            Resolver resolver = new Resolver();
            List<SemanticError> errors = resolver.resolve(program);
            if (!errors.isEmpty()) {
                for (SemanticError err : errors) {
                    System.err.println(ErrorFormatter.format(source, fileName, err.getLine(), err.getColumn(), "Semantic error", err.getMessage()));
                }
                System.exit(65);
            }

            // Stage 3 & 4: Interpretation
            java.nio.file.Path scriptDir = Paths.get(fileName).toAbsolutePath().getParent();
            dev.tlang.modules.ModuleLoader moduleLoader = new dev.tlang.modules.ModuleLoader(scriptDir);
            Interpreter interpreter = new Interpreter(moduleLoader);
            interpreter.interpret(program);

        } catch (LexerError e) {
            System.err.println(ErrorFormatter.format(source, fileName, e.getLine(), e.getColumn(), "Lexer error", e.getRawMessage()));
            System.exit(65);
        } catch (ParseError e) {
            Token t = e.getToken();
            System.err.println(ErrorFormatter.format(source, fileName, t.getLine(), t.getColumn(), "Parse error", e.getRawMessage()));
            System.exit(65);
        } catch (ModuleLoadError e) {
            System.err.println(ErrorFormatter.format(
                e.getSource(),
                e.getSourceName(),
                e.getLine(),
                e.getColumn(),
                e.getDiagnosticKind(),
                e.getRawMessage()
            ));
            System.exit(e.getExitCode());
        } catch (RuntimeError e) {
            System.err.println(ErrorFormatter.format(e));
            System.exit(70);
        } catch (StackOverflowError e) {
            System.err.println(ErrorFormatter.format(new RuntimeError(
                null, "Maximum recursion depth exceeded (limit: 1000).")));
            System.exit(70);
        }
    }
}
