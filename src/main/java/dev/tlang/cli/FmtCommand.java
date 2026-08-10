package dev.tlang.cli;

import dev.tlang.errors.LexerError;
import dev.tlang.errors.ParseError;
import dev.tlang.errors.ErrorFormatter;
import dev.tlang.ast.Stmt;
import dev.tlang.lexer.Lexer;
import dev.tlang.lexer.Token;
import dev.tlang.parser.Parser;
import dev.tlang.formatter.Formatter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public final class FmtCommand implements Command {
    @Override
    public void execute(String[] args) {
        boolean check = false;
        String fileName = null;
        for (String arg : args) {
            if (arg.equals("--check")) {
                check = true;
            } else {
                if (fileName != null) {
                    System.err.println("Usage: tlang fmt [--check] <file>");
                    System.exit(64);
                }
                fileName = arg;
            }
        }
        if (fileName == null) {
            System.err.println("Usage: tlang fmt [--check] <file>");
            System.exit(64);
        }

        String source;
        try {
            source = new String(Files.readAllBytes(Paths.get(fileName)));
        } catch (IOException e) {
            System.err.println("Error: Could not read file '" + fileName + "'.");
            System.exit(1);
            return;
        }

        try {
            // Stage 1: Lexing
            Lexer lexer = new Lexer(source);
            List<Token> tokens = lexer.tokenize();

            // Stage 2: Parsing
            Parser parser = new Parser(tokens);
            List<Stmt> program = parser.parse();

            // Stage 3: Formatting
            Formatter formatter = new Formatter();
            String formattedSource = formatter.format(program);

            if (check) {
                if (!source.equals(formattedSource)) {
                    System.out.println("File '" + fileName + "' is not formatted.");
                    System.exit(1);
                } else {
                    System.out.println("File '" + fileName + "' is already formatted.");
                    System.exit(0);
                }
            } else {
                if (!source.equals(formattedSource)) {
                    Files.write(Paths.get(fileName), formattedSource.getBytes());
                }
            }
        } catch (LexerError e) {
            System.err.println(ErrorFormatter.format(source, fileName, e.getLine(), e.getColumn(), "Lexer error", e.getRawMessage()));
            System.exit(65);
        } catch (ParseError e) {
            Token t = e.getToken();
            System.err.println(ErrorFormatter.format(source, fileName, t.getLine(), t.getColumn(), "Parse error", e.getRawMessage()));
            System.exit(65);
        } catch (IOException e) {
            System.err.println("Error: Could not write file '" + fileName + "'.");
            System.exit(1);
        }
    }
}
