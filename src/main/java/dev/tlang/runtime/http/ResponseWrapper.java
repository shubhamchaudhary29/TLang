package dev.tlang.runtime.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import dev.tlang.interpreter.NativeFunction;
import dev.tlang.errors.RuntimeError;
import dev.tlang.types.Type;
import dev.tlang.lexer.Token;

/**
 * Wraps an HttpExchange response into a TLang Map with chainable helper functions.
 * Buffers the response until the handler completes to ensure transactional error handling.
 */
public final class ResponseWrapper {
    private final HttpExchange exchange;
    private int statusCode = 200;
    private final Map<String, String> responseHeaders = new LinkedHashMap<>();
    private boolean sent = false;
    private String bufferedBody = "";
    private boolean flushed = false;
    private final Map<String, Object> resMap = new LinkedHashMap<>();

    public ResponseWrapper(HttpExchange exchange) {
        this.exchange = exchange;
        setupResMap();
    }

    public Map<String, Object> asMap() {
        return resMap;
    }

    public boolean isSent() {
        return sent;
    }

    private void setupResMap() {
        // status(code) -> returns resMap
        resMap.put("status", new NativeFunction("status", 2) { // (self, code)
            @Override
            public Object call(List<Object> args, Token token) {
                checkSent(token);
                Object codeObj = args.get(1);
                if (Type.of(codeObj) != Type.NUMBER) {
                    throw new RuntimeError(token, "Status code must be an integer.");
                }
                statusCode = (Integer) codeObj;
                return resMap;
            }
        }.setExpectsReceiver(true));

        // header(name, value) -> returns resMap
        resMap.put("header", new NativeFunction("header", 3) { // (self, name, value)
            @Override
            public Object call(List<Object> args, Token token) {
                checkSent(token);
                Object nameObj = args.get(1);
                Object valObj = args.get(2);
                if (Type.of(nameObj) != Type.STRING || Type.of(valObj) != Type.STRING) {
                    throw new RuntimeError(token, "Header name and value must be strings.");
                }
                responseHeaders.put((String) nameObj, (String) valObj);
                return resMap;
            }
        }.setExpectsReceiver(true));

        // text(body) -> ends exchange
        resMap.put("text", new NativeFunction("text", 2) { // (self, body)
            @Override
            public Object call(List<Object> args, Token token) {
                checkSent(token);
                Object bodyObj = args.get(1);
                if (Type.of(bodyObj) != Type.STRING) {
                    throw new RuntimeError(token, "Response body must be a string.");
                }
                responseHeaders.put("Content-Type", "text/plain; charset=utf-8");
                bufferResponse((String) bodyObj);
                return null;
            }
        }.setExpectsReceiver(true));

        // json(value) -> ends exchange
        resMap.put("json", new NativeFunction("json", 2) { // (self, value)
            @Override
            public Object call(List<Object> args, Token token) {
                checkSent(token);
                Object valObj = args.get(1);
                String jsonStr = dev.tlang.modules.JsonModule.jsonStringifyExternal(valObj, token);
                responseHeaders.put("Content-Type", "application/json");
                bufferResponse(jsonStr);
                return null;
            }
        }.setExpectsReceiver(true));

        // send(body) -> ends exchange
        resMap.put("send", new NativeFunction("send", 2) { // (self, body)
            @Override
            public Object call(List<Object> args, Token token) {
                checkSent(token);
                Object bodyObj = args.get(1);
                if (Type.of(bodyObj) != Type.STRING) {
                    throw new RuntimeError(token, "Response body must be a string.");
                }
                bufferResponse((String) bodyObj);
                return null;
            }
        }.setExpectsReceiver(true));
    }

    private void checkSent(Token token) {
        if (sent) {
            throw new RuntimeError(token, "Response already sent. Exactly one response should ever be sent.");
        }
    }

    private void bufferResponse(String body) {
        sent = true;
        bufferedBody = body;
    }

    public void flush() throws IOException {
        if (flushed) return;
        flushed = true;

        // Apply all custom headers
        for (Map.Entry<String, String> entry : responseHeaders.entrySet()) {
            exchange.getResponseHeaders().set(entry.getKey(), entry.getValue());
        }

        byte[] bytes = bufferedBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
