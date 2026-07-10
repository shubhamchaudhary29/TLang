package dev.tlang.runtime.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

import dev.tlang.lexer.Token;
import dev.tlang.lexer.TokenType;
import dev.tlang.runtime.json.JsonParser;

/**
 * Helper to wrap an HttpExchange into a TLang request Map.
 */
public final class RequestWrapper {

    public static Map<String, Object> wrap(HttpExchange exchange, Map<String, String> params) throws IOException {
        Map<String, Object> req = new LinkedHashMap<>();

        req.put("method", exchange.getRequestMethod());
        req.put("path", exchange.getRequestURI().getPath());
        req.put("params", params);

        // Read Request Body
        String body = "";
        try (InputStream is = exchange.getRequestBody()) {
            byte[] bytes = is.readAllBytes();
            if (bytes.length > 0) {
                body = new String(bytes, StandardCharsets.UTF_8);
            }
        }
        req.put("body", body);

        // Parse query parameters
        String rawQuery = exchange.getRequestURI().getRawQuery();
        req.put("query", parseQuery(rawQuery));

        // Lower-cased headers
        Map<String, Object> headersMap = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            String key = entry.getKey();
            if (key == null) continue;
            headersMap.put(key.toLowerCase(), String.join(", ", entry.getValue()));
        }
        req.put("headers", headersMap);

        // Parse JSON body if content-type is application/json
        Object parsedJson = null;
        String contentType = (String) headersMap.get("content-type");
        if (contentType != null && contentType.toLowerCase().contains("application/json")) {
            if (!body.trim().isEmpty()) {
                try {
                    Token dummyToken = new Token(TokenType.IDENTIFIER, "json", null, 1);
                    JsonParser parser = new JsonParser(body, dummyToken);
                    parsedJson = parser.parse();
                } catch (Exception e) {
                    // Leave as null on parsing errors
                }
            }
        }
        req.put("json", parsedJson);

        return req;
    }

    private static Map<String, Object> parseQuery(String query) {
        Map<String, Object> queryMap = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return queryMap;
        }

        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            String key;
            String value;
            if (idx > 0) {
                key = pair.substring(0, idx);
                value = pair.substring(idx + 1);
            } else {
                key = pair;
                value = "";
            }

            try {
                key = URLDecoder.decode(key, StandardCharsets.UTF_8.name());
                value = URLDecoder.decode(value, StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                // Keep raw if URLDecoder fails
            }

            if (queryMap.containsKey(key)) {
                String existing = (String) queryMap.get(key);
                queryMap.put(key, existing + ", " + value);
            } else {
                queryMap.put(key, value);
            }
        }
        return queryMap;
    }
}
