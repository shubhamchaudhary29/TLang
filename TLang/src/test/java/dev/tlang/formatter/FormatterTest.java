package dev.tlang.formatter;

import dev.tlang.ast.Stmt;
import dev.tlang.errors.LexerError;
import dev.tlang.errors.ParseError;
import dev.tlang.lexer.Lexer;
import dev.tlang.lexer.Token;
import dev.tlang.parser.Parser;
import dev.tlang.resolver.Resolver;
import dev.tlang.interpreter.Interpreter;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FormatterTest {

    private String formatSource(String source) {
        Lexer lexer = new Lexer(source);
        List<Token> tokens = lexer.tokenize();
        Parser parser = new Parser(tokens);
        List<Stmt> program = parser.parse();
        Formatter formatter = new Formatter();
        return formatter.format(program);
    }

    private void assertIdempotent(String source) {
        String formatted1 = formatSource(source);
        String formatted2 = formatSource(formatted1);
        assertEquals(formatted1, formatted2, "Formatter must be idempotent");
    }

    private String runInterpreter(String source, String fileName) {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        try {
            Lexer lexer = new Lexer(source);
            List<Token> tokens = lexer.tokenize();
            Parser parser = new Parser(tokens);
            List<Stmt> program = parser.parse();

            Resolver resolver = new Resolver();
            resolver.resolve(program);

            File file = new File(fileName);
            File parentDir = file.getParentFile();
            java.nio.file.Path scriptDir = parentDir != null ? parentDir.toPath().toAbsolutePath() : Paths.get(".").toAbsolutePath();
            dev.tlang.modules.ModuleLoader moduleLoader = new dev.tlang.modules.ModuleLoader(scriptDir);

            Interpreter interpreter = new Interpreter(moduleLoader);
            interpreter.interpret(program);
        } catch (Exception e) {
            e.printStackTrace(new PrintStream(outContent));
        } finally {
            System.setOut(originalOut);
        }

        return outContent.toString();
    }

    @Test
    public void testSimpleStatements() {
        String source = "let x be 1\nlet y be 2\nshow x + y\n";
        assertIdempotent(source);
        
        String expected = "let x be 1\nlet y be 2\nshow x + y\n";
        assertEquals(expected, formatSource(source));
    }

    @Test
    public void testUnaryAndBinarySpacing() {
        String source = "let x be -5\nlet y be not true\nlet z be x*y + 2\n";
        assertIdempotent(source);

        String expected = "let x be -5\nlet y be not true\nlet z be x * y + 2\n";
        assertEquals(expected, formatSource(source));
    }

    @Test
    public void testFunctionSpacingAndBlankLines() {
        String source = "import std\nlet x be 1\ndefine foo\n    show x\ndefine bar taking a and b be 2\n    return a + b\n";
        assertIdempotent(source);

        String expected = 
            "import std\n\n" +
            "let x be 1\n\n" +
            "define foo\n" +
            "    show x\n\n" +
            "define bar taking a and b be 2\n" +
            "    return a + b\n";
        assertEquals(expected, formatSource(source));
    }

    @Test
    public void testBlockIndentation() {
        String source = "if true\n    let x be 1\n    if false\n        let y be 2\n    otherwise\n        let z be 3\n";
        assertIdempotent(source);

        String expected = 
            "if true\n" +
            "    let x be 1\n" +
            "    if false\n" +
            "        let y be 2\n" +
            "    otherwise\n" +
            "        let z be 3\n";
        assertEquals(expected, formatSource(source));
    }

    @Test
    public void testListAndMapLiterals() {
        // Compact list (fits under 80 chars)
        String listSrc = "let l be [1, 2, 3, 4]\n";
        assertEquals(listSrc, formatSource(listSrc));
        assertIdempotent(listSrc);

        // Compact map (fits under 80 chars)
        String mapSrc = "let m be {a: 1, b: 2, c: 3}\n";
        assertEquals(mapSrc, formatSource(mapSrc));
        assertIdempotent(mapSrc);

        // Multi-line list (nested multi-line elements or fits > 80 chars)
        String longListSrc = "let l be [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30]\n";
        String expectedLongList = 
            "let l be [\n" +
            "    1,\n" +
            "    2,\n" +
            "    3,\n" +
            "    4,\n" +
            "    5,\n" +
            "    6,\n" +
            "    7,\n" +
            "    8,\n" +
            "    9,\n" +
            "    10,\n" +
            "    11,\n" +
            "    12,\n" +
            "    13,\n" +
            "    14,\n" +
            "    15,\n" +
            "    16,\n" +
            "    17,\n" +
            "    18,\n" +
            "    19,\n" +
            "    20,\n" +
            "    21,\n" +
            "    22,\n" +
            "    23,\n" +
            "    24,\n" +
            "    25,\n" +
            "    26,\n" +
            "    27,\n" +
            "    28,\n" +
            "    29,\n" +
            "    30\n" +
            "]\n";
        assertEquals(expectedLongList, formatSource(longListSrc));
        assertIdempotent(longListSrc);
    }

    @Test
    public void testLambdas() {
        String lambdaSrc = "let f be function taking a and b\n    show a\n    show b\n";
        assertIdempotent(lambdaSrc);

        String expected = 
            "let f be function taking a and b\n" +
            "    show a\n" +
            "    show b\n";
        assertEquals(expected, formatSource(lambdaSrc));
    }

    @Test
    public void testSemanticPreservationOnFixtures() throws IOException {
        String[] representativeFiles = {
            "src/test/resources/integration/test_comprehensive.tiny",
            "src/test/resources/integration/test_objects.tiny",
            "src/test/resources/runtime/test_multiline_literals.tiny"
        };

        for (String filePath : representativeFiles) {
            File file = new File(filePath);
            assertTrue(file.exists(), "Test fixture must exist: " + filePath);

            String originalSource = new String(Files.readAllBytes(Paths.get(filePath)));

            // 1. Run original
            String originalOutput = runInterpreter(originalSource, filePath);

            // 2. Format
            String formattedSource = formatSource(originalSource);

            // 3. Verify idempotency
            assertIdempotent(originalSource);

            // 4. Run formatted
            String formattedOutput = runInterpreter(formattedSource, filePath);

            // 5. Compare stdout execution output
            assertEquals(originalOutput, formattedOutput, "Formatting must preserve program behavior for " + filePath);
        }
    }
}
