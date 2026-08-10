package dev.tlang.lexer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class NumberLiteralTest {
    @Test
    void acceptsTheLargestSupportedIntegerLiteral() {
        Token token = new Lexer("show 2147483647\n").tokenize().stream()
                .filter(value -> value.getType() == TokenType.NUMBER).findFirst().orElseThrow();
        assertEquals(Integer.MAX_VALUE, token.getLiteral());
    }

    @Test
    void rejectsIntegerLiteralOverflow() {
        assertThrows(NumberFormatException.class, () -> new Lexer("show 2147483648\n").tokenize());
    }

    @Test
    void treatsMinusAsAnOperatorAndDecimalPointAsSyntax() {
        var tokens = new Lexer("show -12\n").tokenize();
        assertEquals(TokenType.MINUS, tokens.get(1).getType());
        assertEquals(12, tokens.get(2).getLiteral());
        assertEquals(TokenType.DOT, new Lexer("show 1.5\n").tokenize().get(2).getType());
    }
}
