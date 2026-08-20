package dev.tlang.runtime.http;

import dev.tlang.lexer.Token;
import dev.tlang.lexer.TokenType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RouteTest {
    private static final Token TOKEN = new Token(TokenType.STRING, "route", null, 1, 1);

    @Test
    void matchesParameterizedRoutesAndMethods() {
        Route route = new Route("GET", "/users/:userId/posts/:postId", new Object(), TOKEN);
        assertTrue(route.matches("get", Route.getSegments("/users/42/posts/7")));
        assertFalse(route.matches("POST", Route.getSegments("/users/42/posts/7")));
        assertFalse(route.matches("GET", Route.getSegments("/users/42/profile/7")));
        assertEquals(2, route.specificityScore());
    }
}
