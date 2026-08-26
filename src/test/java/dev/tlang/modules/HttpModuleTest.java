package dev.tlang.modules;

import dev.tlang.errors.RuntimeError;
import dev.tlang.errors.RuntimeErrorKind;
import dev.tlang.interpreter.NativeFunction;
import dev.tlang.lexer.SourceUnit;
import dev.tlang.lexer.Token;
import dev.tlang.lexer.TokenType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpModuleTest {
    private static final Token TOKEN = new Token(
        TokenType.RIGHT_PAREN, ")", null, 3, 12,
        new SourceUnit("http-types.tiny", "import http\n\nhttp.get(1)\n"));

    private final Map<String, Object> http = new HttpModule().getExports();

    @Test
    void clientArgumentsWithWrongTlangTypesProduceTypeError() {
        assertTypeError(function("get"), List.of(42));
        assertTypeError(function("post"), List.of(42, "body"));
        assertTypeError(function("post"), List.of("http://example.com", 42));
        assertTypeError(function("put"), List.of(42, "body"));
        assertTypeError(function("put"), List.of("http://example.com", 42));
        assertTypeError(function("delete"), List.of(false));
        assertTypeError(function("get"), List.of("http://example.com", List.of()));
        assertTypeError(function("get"), List.of("http://example.com", Map.of("X-Test", 42)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void serverArgumentsWithWrongTlangTypesProduceTypeError() {
        assertTypeError(function("server"), List.of("8080"));

        Map<String, Object> server = (Map<String, Object>) function("server").call(List.of(8080), TOKEN);
        for (String method : List.of("get", "post", "put", "delete")) {
            NativeFunction route = (NativeFunction) server.get(method);
            assertTypeError(route, List.of(server, 42, noOpFunction()));
            assertTypeError(route, List.of(server, "/", 42));
        }
        assertTypeError((NativeFunction) server.get("use"), List.of(server, 42));
    }

    private NativeFunction function(String name) {
        return (NativeFunction) http.get(name);
    }

    private static NativeFunction noOpFunction() {
        return new NativeFunction("handler", 0) {
            @Override
            public Object call(List<Object> args, Token token) {
                return null;
            }
        };
    }

    private static void assertTypeError(NativeFunction function, List<Object> args) {
        RuntimeError error = assertThrows(RuntimeError.class, () -> function.call(args, TOKEN));
        assertEquals(RuntimeErrorKind.TYPE_ERROR, error.getKind());
        assertEquals(TOKEN, error.getToken());
    }
}
