package dev.tlang.runtime.http;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

import dev.tlang.interpreter.Interpreter;
import dev.tlang.errors.RuntimeError;
import dev.tlang.interpreter.NativeFunction;
import dev.tlang.lexer.Token;

/**
 * Manages the com.sun.net.httpserver.HttpServer instance, routes, middlewares, and request handling pipeline.
 */
public final class ServerOps {
    private final int port;
    private HttpServer server;
    private final List<Route> routes = new ArrayList<>();
    private final List<Object> middlewares = new ArrayList<>();
    private boolean started = false;

    public ServerOps(int port) {
        this.port = port;
    }

    public void addRoute(String method, String path, Object handler, Token token) {
        Route newRoute = new Route(method, path, handler, token);
        // Check for duplicate route patterns
        for (Route r : routes) {
            if (matchesPatternShape(newRoute, r)) {
                throw new RuntimeError(token, "Duplicate route registration: " + method + " " + path);
            }
        }
        routes.add(newRoute);
    }

    public void addMiddleware(Object middleware) {
        middlewares.add(middleware);
    }

    private boolean matchesPatternShape(Route a, Route b) {
        if (!a.method.equalsIgnoreCase(b.method)) return false;
        if (a.segments.size() != b.segments.size()) return false;
        for (int i = 0; i < a.segments.size(); i++) {
            String sa = a.segments.get(i);
            String sb = b.segments.get(i);
            boolean isParamA = sa.startsWith(":");
            boolean isParamB = sb.startsWith(":");
            if (isParamA != isParamB) return false;
            if (!isParamA && !sa.equals(sb)) return false;
        }
        return true;
    }

    public void start(Interpreter interpreter, Token token) {
        if (started) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    try {
                        handleRequest(exchange, interpreter);
                    } catch (Exception e) {
                        sendErrorResponse(exchange, 500, "Internal Server Error: " + e.getMessage());
                    }
                }
            });
            server.setExecutor(null); // default executor
            server.start();
            started = true;
        } catch (IOException e) {
            throw new RuntimeError(token, "Failed to start HTTP server on port " + port + ": " + e.getMessage());
        }
    }

    public void stop() {
        if (started && server != null) {
            server.stop(0); // stop immediately
            started = false;
        }
    }

    private void handleRequest(HttpExchange exchange, Interpreter interpreter) throws IOException {
        // Wrap request with empty path params map initially, and wrap response
        Map<String, Object> reqMap = RequestWrapper.wrap(exchange, new LinkedHashMap<>());
        ResponseWrapper resWrapper = new ResponseWrapper(exchange);

        // Invoke middleware chain (Thread-safe synchronization on the tree-walking interpreter)
        synchronized (interpreter) {
            try {
                runChain(0, reqMap, resWrapper, interpreter, exchange);

                // Fallback check: if the entire chain finished (including dispatch/short-circuit) and no response sent
                if (!resWrapper.isSent()) {
                    Token dummyToken = new Token(dev.tlang.lexer.TokenType.IDENTIFIER, "handler", null, 1);
                    throw new RuntimeError(dummyToken, "No response was sent by the handler or middleware.");
                }
                resWrapper.flush();
            } catch (RuntimeError e) {
                // Respond 500, do not crash server
                sendErrorResponse(exchange, 500, "Runtime Error: " + e.getMessage());
            } catch (Exception e) {
                // Convert escaped Java exception to 500
                sendErrorResponse(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }
    }

    private void runChain(int index, Map<String, Object> reqMap, ResponseWrapper resWrapper, Interpreter interpreter, HttpExchange exchange) throws Exception {
        if (index < middlewares.size()) {
            Object middlewareFn = middlewares.get(index);
            boolean[] nextCalled = new boolean[]{false};
            Token dummyToken = new Token(dev.tlang.lexer.TokenType.IDENTIFIER, "middleware", null, 1);

            NativeFunction nextFn = new NativeFunction("next", 0) {
                @Override
                public Object call(List<Object> args, Token token) {
                    if (nextCalled[0]) {
                        throw new RuntimeError(token, "next() called more than once.");
                    }
                    nextCalled[0] = true;
                    try {
                        runChain(index + 1, reqMap, resWrapper, interpreter, exchange);
                    } catch (RuntimeError e) {
                        throw e;
                    } catch (Exception e) {
                        throw new RuntimeError(token, e.getMessage());
                    }
                    return null;
                }
            };

            interpreter.executeCallDirect(middlewareFn, List.of(reqMap, resWrapper.asMap(), nextFn), dummyToken);
        } else {
            // Terminal Route Dispatch
            executeRouteDispatch(reqMap, resWrapper, interpreter, exchange);
        }
    }

    private void executeRouteDispatch(Map<String, Object> reqMap, ResponseWrapper resWrapper, Interpreter interpreter, HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String requestPath = Route.normalizePath(exchange.getRequestURI().getPath());
        List<String> reqSegments = Route.getSegments(requestPath);

        // Find all matching routes for the requested method
        Route matchedRoute = null;
        int bestScore = -1;

        for (Route r : routes) {
            if (r.method.equalsIgnoreCase(method) && matchesPath(r.segments, reqSegments)) {
                int score = calculateSpecificityScore(r.segments);
                if (score > bestScore) {
                    bestScore = score;
                    matchedRoute = r;
                }
            }
        }

        // If no route matches the method, check 404 vs 405
        if (matchedRoute == null) {
            Set<String> allowedMethods = new LinkedHashSet<>();
            for (Route r : routes) {
                if (matchesPath(r.segments, reqSegments)) {
                    allowedMethods.add(r.method.toUpperCase());
                }
            }

            if (!allowedMethods.isEmpty()) {
                // 405 Method Not Allowed
                String allowHeader = String.join(", ", allowedMethods);
                exchange.getResponseHeaders().set("Allow", allowHeader);
                sendErrorResponse(exchange, 405, "Method Not Allowed");
            } else {
                // 404 Not Found
                sendErrorResponse(exchange, 404, "Not Found");
            }
            return;
        }

        // Extract path parameters (URL-decoded) into req.params
        @SuppressWarnings("unchecked")
        Map<String, String> params = (Map<String, String>) reqMap.get("params");
        params.clear();
        for (int i = 0; i < matchedRoute.segments.size(); i++) {
            String seg = matchedRoute.segments.get(i);
            if (seg.startsWith(":")) {
                String paramName = seg.substring(1);
                String rawVal = reqSegments.get(i);
                String decodedVal = rawVal;
                try {
                    decodedVal = java.net.URLDecoder.decode(rawVal, java.nio.charset.StandardCharsets.UTF_8.name());
                } catch (Exception e) {
                    // Keep raw value on failure
                }
                params.put(paramName, decodedVal);
            }
        }

        Token dummyToken = new Token(dev.tlang.lexer.TokenType.IDENTIFIER, "handler", null, 1);
        interpreter.executeCallDirect(matchedRoute.handler, List.of(reqMap, resWrapper.asMap()), dummyToken);
    }

    private boolean matchesPath(List<String> patternSegs, List<String> reqSegs) {
        if (patternSegs.size() != reqSegs.size()) return false;
        for (int i = 0; i < patternSegs.size(); i++) {
            String patSeg = patternSegs.get(i);
            if (patSeg.startsWith(":")) {
                continue; // Matches any segment value
            }
            if (!patSeg.equals(reqSegs.get(i))) {
                return false;
            }
        }
        return true;
    }

    private int calculateSpecificityScore(List<String> patternSegs) {
        int score = 0;
        for (String seg : patternSegs) {
            if (!seg.startsWith(":")) {
                score++;
            }
        }
        return score;
    }

    private void sendErrorResponse(HttpExchange exchange, int status, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
