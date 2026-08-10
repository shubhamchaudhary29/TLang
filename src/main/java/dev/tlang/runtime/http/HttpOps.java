package dev.tlang.runtime.http;

import dev.tlang.errors.RuntimeError;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.tlang.lexer.Token;

/**
 * Shared HTTP client operations for the TLang 'http' native module.
 *
 * Uses Java's built-in java.net.http.HttpClient (JDK 11+).
 * All methods return a TLang-style Map with keys: status, ok, body, headers.
 * Connection-level failures become RuntimeError; non-2xx status codes are
 * returned normally (they are not errors).
 */
public final class HttpOps {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // ── Public API ──────────────────────────────────────────────

    public static Map<String, Object> get(String url, Map<String, String> headers, Token token) {
        HttpRequest request = buildRequest(url, "GET", null, headers, token);
        return execute(request, url, token);
    }

    public static Map<String, Object> post(String url, String body, Map<String, String> headers, Token token) {
        HttpRequest request = buildRequest(url, "POST", body, headers, token);
        return execute(request, url, token);
    }

    public static Map<String, Object> put(String url, String body, Map<String, String> headers, Token token) {
        HttpRequest request = buildRequest(url, "PUT", body, headers, token);
        return execute(request, url, token);
    }

    public static Map<String, Object> delete(String url, Map<String, String> headers, Token token) {
        HttpRequest request = buildRequest(url, "DELETE", null, headers, token);
        return execute(request, url, token);
    }

    // ── Internals ───────────────────────────────────────────────

    private static HttpRequest buildRequest(String url, String method, String body,
                                            Map<String, String> headers, Token token) {
        URI uri;
        try {
            uri = new URI(url);
            // Validate that the URI has a scheme (otherwise HttpClient throws unhelpful errors)
            if (uri.getScheme() == null) {
                throw new URISyntaxException(url, "Missing scheme (e.g. http:// or https://)");
            }
        } catch (URISyntaxException e) {
            throw new RuntimeError(token, "Malformed URL '" + url + "': " + e.getMessage());
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(REQUEST_TIMEOUT);

        // Apply custom headers
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }

        // Set method and body
        if (body != null) {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        return builder.build();
    }

    private static Map<String, Object> execute(HttpRequest request, String url, Token token) {
        HttpResponse<String> response;
        try {
            response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new RuntimeError(token, "HTTP request to '" + url + "' timed out.");
        } catch (IOException e) {
            throw new RuntimeError(token, "HTTP request to '" + url + "' failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeError(token, "HTTP request to '" + url + "' was interrupted.");
        } catch (IllegalArgumentException e) {
            throw new RuntimeError(token, "Invalid HTTP request to '" + url + "': " + e.getMessage());
        }

        return buildResponseMap(response);
    }

    private static Map<String, Object> buildResponseMap(HttpResponse<String> response) {
        Map<String, Object> result = new LinkedHashMap<>();

        int statusCode = response.statusCode();
        result.put("status", statusCode);
        result.put("ok", statusCode >= 200 && statusCode <= 299);
        result.put("body", response.body() != null ? response.body() : "");

        // Build headers map: lower-case keys, join multiple values with ", "
        Map<String, Object> headersMap = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : response.headers().map().entrySet()) {
            String key = entry.getKey();
            if (key == null) continue; // skip the status-line pseudo-header
            headersMap.put(key.toLowerCase(), String.join(", ", entry.getValue()));
        }
        result.put("headers", headersMap);

        return result;
    }
}
