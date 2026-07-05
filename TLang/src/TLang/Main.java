package TLang;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

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

        run(source);
    }

    private static void run(String source) {
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
                    System.err.println(err);
                }
                System.exit(65);
            }

            // Stage 3 & 4: Interpretation
            Interpreter interpreter = new Interpreter();
            interpreter.interpret(program);

        } catch (Lexer.LexerError e) {
            System.err.println(e.getMessage());
            System.exit(65);
        } catch (Parser.ParseError e) {
            System.err.println(e.getMessage());
            System.exit(65);
        } catch (RuntimeError e) {
            String line = e.getToken() != null
                    ? "[line " + e.getToken().getLine() + "] "
                    : "";
            System.err.println(line + "Runtime error: " + e.getMessage());
            System.exit(70);
        }
    }
}
