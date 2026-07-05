package TLang;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import TLang.errors.ErrorFormatter;
import TLang.ast.Stmt;
import TLang.lexer.Lexer;
import TLang.lexer.Token;
import TLang.parser.Parser;
import TLang.runtime.Interpreter;
import TLang.runtime.RuntimeError;
import TLang.semantic.Resolver;
import TLang.semantic.SemanticError;

/**
 * Entry point for the TinyLang interpreter.
 *
 * Usage:
 *   java TLang.Main <script.tiny>
 */
public final class Main {

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: TLang <script>");
            System.exit(1);
        }

        String source;
        try {
            source = new String(Files.readAllBytes(Paths.get(args[0])));
        } catch (IOException e) {
            System.err.println("Error: Could not read file '" + args[0] + "'.");
            System.exit(1);
            return;
        }

        run(source, args[0]);
    }

    private static void run(String source, String fileName) {
        try {
            // Stage 1: Lexing
            Lexer lexer = new Lexer(source);
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
            TLang.runtime.ModuleLoader moduleLoader = new TLang.runtime.ModuleLoader(scriptDir);
            Interpreter interpreter = new Interpreter(moduleLoader);
            interpreter.interpret(program);

        } catch (Lexer.LexerError e) {
            System.err.println(ErrorFormatter.format(source, fileName, e.getLine(), e.getColumn(), "Lexer error", e.getRawMessage()));
            System.exit(65);
        } catch (Parser.ParseError e) {
            Token t = e.getToken();
            System.err.println(ErrorFormatter.format(source, fileName, t.getLine(), t.getColumn(), "Parse error", e.getRawMessage()));
            System.exit(65);
        } catch (RuntimeError e) {
            Token t = e.getToken();
            if (t != null) {
                System.err.println(ErrorFormatter.format(source, fileName, t.getLine(), t.getColumn(), "Runtime error", e.getMessage()));
            } else {
                System.err.println(ErrorFormatter.format(source, fileName, 0, 0, "Runtime error", e.getMessage()));
            }
            System.exit(70);
        } catch (StackOverflowError e) {
            System.err.println(ErrorFormatter.format(source, fileName, 0, 0, "Runtime error", "Maximum recursion depth exceeded (limit: 1000)."));
            System.exit(70);
        }
    }
}
