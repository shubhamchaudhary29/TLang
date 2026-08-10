package dev.tlang.runtime.http;

import java.util.*;
import dev.tlang.lexer.Token;
import dev.tlang.errors.RuntimeError;

/**
 * Represents a registered HTTP route in the server, with parsed path segments and parameter validation.
 */
public final class Route {
    public final String method;
    public final String originalPath;
    public final String normalizedPath;
    public final List<String> segments;
    public final Object handler;

    public Route(String method, String originalPath, Object handler, Token token) {
        this.method = method;
        this.originalPath = originalPath;
        this.handler = handler;
        this.normalizedPath = normalizePath(originalPath);
        this.segments = getSegments(normalizedPath);

        // Check for duplicate parameter names
        Set<String> seenParams = new HashSet<>();
        for (String seg : segments) {
            if (seg.startsWith(":")) {
                String paramName = seg.substring(1);
                if (paramName.isEmpty()) {
                    throw new RuntimeError(token, "Parameter name cannot be empty in route pattern: " + originalPath);
                }
                if (!seenParams.add(paramName)) {
                    throw new RuntimeError(token, "Duplicate parameter name '" + paramName + "' in route pattern: " + originalPath);
                }
            }
        }
    }

    public static String normalizePath(String path) {
        if (path == null) return "/";
        path = path.trim();
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return path;
    }

    public static List<String> getSegments(String normalizedPath) {
        if (normalizedPath.equals("/")) {
            return Collections.emptyList();
        }
        String[] parts = normalizedPath.substring(1).split("/");
        return Arrays.asList(parts);
    }
}
