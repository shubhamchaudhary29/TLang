package dev.tlang.runtime.http;

import dev.tlang.errors.ErrorFormatter;
import dev.tlang.errors.RuntimeError;
import dev.tlang.errors.RuntimeErrorKind;
import dev.tlang.lexer.SourceUnit;
import dev.tlang.lexer.Token;
import dev.tlang.lexer.TokenType;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HttpOpsTest {
    private static final Token TOKEN = new Token(
        TokenType.RIGHT_PAREN, ")", null, 2, 24,
        new SourceUnit("http-client.tiny", "import http\nhttp.get(\"bad\")\n"));

    @Test
    void malformedUriBecomesHttpError() {
        RuntimeError error = assertThrows(RuntimeError.class,
            () -> HttpOps.get("://bad", null, TOKEN));

        assertHttpError(error, URISyntaxException.class);
        assertTrue(error.getMessage().contains("Malformed URL"));
    }

    @Test
    void unsupportedSchemeCannotEscapeAsIllegalArgumentException() {
        RuntimeError error = assertThrows(RuntimeError.class,
            () -> HttpOps.get("ftp://example.com", null, TOKEN));

        assertHttpError(error, IllegalArgumentException.class);
        assertTrue(error.getMessage().contains("Unsupported or invalid HTTP URL"));
    }

    @Test
    void invalidHeaderNameBecomesHttpErrorAndRetainsCause() {
        RuntimeError error = assertThrows(RuntimeError.class,
            () -> HttpOps.get("http://example.com", Map.of("Bad Header", "value"), TOKEN));

        assertHttpError(error, IllegalArgumentException.class);
        assertTrue(error.getMessage().contains("Invalid HTTP headers"));
    }

    @Test
    void invalidHeaderValueBecomesHttpError() {
        RuntimeError error = assertThrows(RuntimeError.class,
            () -> HttpOps.get("http://example.com", Map.of("X-Test", "bad\r\nvalue"), TOKEN));

        assertHttpError(error, IllegalArgumentException.class);
        assertTrue(error.getMessage().contains("Invalid HTTP headers"));
    }

    private static void assertHttpError(RuntimeError error, Class<? extends Throwable> causeType) {
        assertEquals(RuntimeErrorKind.HTTP_ERROR, error.getKind());
        assertSame(TOKEN, error.getToken());
        assertEquals("http-client.tiny", error.getLocation().sourceName());
        assertInstanceOf(causeType, error.getCause());

        String formatted = ErrorFormatter.format(error);
        assertTrue(formatted.contains("HttpError"));
        assertFalse(formatted.contains("java.lang.IllegalArgumentException"));
        assertFalse(formatted.contains("java.net.http"));
        assertFalse(formatted.contains("dev.tlang"));
        assertFalse(formatted.contains("at java."));
        assertFalse(formatted.contains("\tat "));
        assertFalse(formatted.contains("java.base"));
    }
}
