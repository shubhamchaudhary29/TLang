package dev.tlang.errors;

import dev.tlang.lexer.SourceUnit;
import dev.tlang.lexer.Token;
import dev.tlang.lexer.TokenType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ErrorFormatterTest {
    @Test
    void formatsCategoryPrimarySnippetAndTlangFramesOnly() {
        String source = "define divide taking a and b\n    return a / b\n";
        SourceUnit unit = new SourceUnit("services/math.tiny", source);
        Token slash = new Token(TokenType.SLASH, "/", null, 2, 14, unit);
        Token call = new Token(TokenType.RIGHT_PAREN, ")", null, 5, 20,
            new SourceUnit("main.tiny", "\n\n\n\nlet result be divide(1, 0)\n"));
        IllegalArgumentException cause = new IllegalArgumentException("host detail");
        RuntimeError error = new RuntimeError(
            RuntimeErrorKind.RUNTIME_ERROR, slash, "Division by zero.", cause)
            .withFrame(RuntimeStackFrame.userFunction("divide", SourceLocation.from(call)));

        String formatted = ErrorFormatter.format(error);

        assertSame(cause, error.getCause());
        assertEquals("""
            RuntimeError: Division by zero.

              --> services/math.tiny:2:14
                |
              2 |     return a / b
                |              ^

            Stack trace:
              at divide (main.tiny:5:20)""", formatted);
        assertFalse(formatted.contains("IllegalArgumentException"));
        assertFalse(formatted.contains("dev.tlang"));
        assertFalse(formatted.contains("host detail"));
    }

    @Test
    void formatsUnavailableSourceAndUnknownFrameWithoutNullArtifacts() {
        Token token = new Token(TokenType.IDENTIFIER, "value", null, 3, 7);
        RuntimeError error = new RuntimeError(RuntimeErrorKind.NAME_ERROR, token, "Undefined variable 'value'.")
            .withFrame(RuntimeStackFrame.httpHandler("POST", "/items"));

        String formatted = ErrorFormatter.format(error);

        assertTrue(formatted.contains("NameError: Undefined variable 'value'."));
        assertTrue(formatted.contains("--> script:3:7"));
        assertTrue(formatted.contains("at POST /items"));
        assertFalse(formatted.contains("null"));
        assertFalse(formatted.contains(":0"));

        assertEquals("RuntimeError: Location unavailable.", ErrorFormatter.format(
            new RuntimeError(null, "Location unavailable.")));
    }
}
