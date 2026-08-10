package dev.tlang.lexer;

import dev.tlang.errors.LexerError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class LexerPositionTest {
    private static Token firstString(String source) {
        return new Lexer(source).tokenize().stream()
                .filter(token -> token.getType() == TokenType.STRING)
                .findFirst().orElseThrow();
    }

    @Test
    void stringPositionsUseTheOpeningQuoteAndLaterTokensUseTheFinalLine() {
        List<Token> tokens = new Lexer("show \"one\ntwo\"\nshow 7\n").tokenize();
        Token string = tokens.stream().filter(token -> token.getType() == TokenType.STRING).findFirst().orElseThrow();
        Token number = tokens.stream().filter(token -> token.getType() == TokenType.NUMBER).findFirst().orElseThrow();
        assertEquals(1, string.getLine());
        assertEquals(6, string.getColumn());
        assertEquals(3, number.getLine());
        assertEquals(6, number.getColumn());
        assertTrue(tokens.stream().allMatch(token -> token.getLine() >= 1 && token.getColumn() >= 1));
    }

    @Test
    void multilineStringVariantsAndCrLfHavePositivePositions() {
        for (String source : List.of("show \"\"\n", "show \"\n\"\n", "show \"a\n\nb\"\n", "show \"a\r\nb\"\r\nshow 1\r\n", "\tshow \"π\nβ\"\n")) {
            List<Token> tokens = new Lexer(source).tokenize();
            assertTrue(tokens.stream().allMatch(token -> token.getLine() >= 1 && token.getColumn() >= 1), source);
            assertTrue(firstString(source).getColumn() >= 1);
        }
    }

    @Test
    void unterminatedMultilineStringReportsItsOpeningPosition() {
        LexerError error = assertThrows(LexerError.class, () -> new Lexer("  show \"a\nb").tokenize());
        assertEquals(1, error.getLine());
        assertEquals(8, error.getColumn());
        assertEquals("Unterminated string.", error.getRawMessage());
    }
}
