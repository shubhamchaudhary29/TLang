package dev.tlang.runtime.http;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import dev.tlang.interpreter.Interpreter;
import dev.tlang.errors.ErrorFormatter;
import dev.tlang.errors.RuntimeError;
import dev.tlang.errors.RuntimeStackFrame;
import dev.tlang.interpreter.NativeFunction;
import dev.tlang.interpreter.RuntimeCollections;
import dev.tlang.lexer.Token;

/**
 * Manages the com.sun.net.httpserver.HttpServer instance, routes, middlewares, and request handling pipeline.
 */
public final class ServerOps {
    private static final int DEFAULT_QUEUE_CAPACITY = 256;
    private static final AtomicInteger SERVER_SEQUENCE = new AtomicInteger();

    private final int port;
    private final int workerCount;
    private final int queueCapacity;
    private final Object lifecycleLock = new Object();
    private final String workerThreadPrefix;
    private final Consumer<RuntimeError> diagnosticSink;
    private HttpServer server;
    private final List<Route> routes = new ArrayList<>();
    private final List<Object> middlewares = new ArrayList<>();
    private volatile List<Route> activeRoutes = List.of();
    private volatile List<Object> activeMiddlewares = List.of();
    private ThreadPoolExecutor executor;
    private volatile boolean started = false;
    private boolean stoppedPermanently = false;

    public ServerOps(int port) {
        this(port, defaultWorkerCount(), DEFAULT_QUEUE_CAPACITY, ServerOps::reportDiagnostic);
    }

    public ServerOps(int port, int workerCount, int queueCapacity) {
        this(port, workerCount, queueCapacity, ServerOps::reportDiagnostic);
    }

    ServerOps(int port, int workerCount, int queueCapacity, Consumer<RuntimeError> diagnosticSink) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("HTTP worker count must be positive.");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("HTTP queue capacity must be positive.");
        }
        this.port = port;
        this.workerCount = workerCount;
        this.queueCapacity = queueCapacity;
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.workerThreadPrefix = "tlang-http-" + SERVER_SEQUENCE.incrementAndGet() + "-worker-";
    }

    public void addRoute(String method, String path, Object handler, Token token) {
        synchronized (lifecycleLock) {
            ensureRegistrationOpen(token);
            Route newRoute = new Route(method, path, handler, token);
            // Check for duplicate route patterns
            for (Route r : routes) {
                if (matchesPatternShape(newRoute, r)) {
                    throw new RuntimeError(token, "Duplicate route registration: " + method + " " + path);
                }
            }
            routes.add(newRoute);
        }
    }

    public void addMiddleware(Object middleware, Token token) {
        synchronized (lifecycleLock) {
            ensureRegistrationOpen(token);
            middlewares.add(middleware);
        }
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
        synchronized (lifecycleLock) {
            if (started) {
                return;
            }
            if (stoppedPermanently) {
                throw new RuntimeError(token, "HTTP server instances cannot be restarted after stop().");
            }
            HttpServer newServer = null;
            ThreadPoolExecutor newExecutor = null;
            try {
                newServer = HttpServer.create(new InetSocketAddress(port), 0);
                newExecutor = createExecutor();
                activeRoutes = List.copyOf(routes);
                activeMiddlewares = List.copyOf(middlewares);
                newServer.createContext("/", exchange -> handleRequest(exchange, interpreter));
                newServer.setExecutor(newExecutor);
                server = newServer;
                executor = newExecutor;
                started = true;
                newServer.start();
            } catch (IOException | RuntimeException e) {
                started = false;
                server = null;
                executor = null;
                if (newServer != null) {
                    newServer.stop(0);
                }
                if (newExecutor != null) {
                    newExecutor.shutdownNow();
                }
                throw new RuntimeError(token, "Failed to start HTTP server on port " + port + ": " + e.getMessage());
            }
        }
    }

    public void stop() {
        HttpServer serverToStop;
        ThreadPoolExecutor executorToStop;
        synchronized (lifecycleLock) {
            if (!started || server == null) {
                return;
            }
            serverToStop = server;
            executorToStop = executor;
            started = false;
            stoppedPermanently = true;
            server = null;
            executor = null;
        }

        // Allow active exchanges a short grace period before closing sockets.
        serverToStop.stop(1);
        executorToStop.shutdown();
        if (!Thread.currentThread().getName().startsWith(workerThreadPrefix)) {
            try {
                if (!executorToStop.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorToStop.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorToStop.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public int getBoundPort() {
        HttpServer current = server;
        return current == null ? -1 : current.getAddress().getPort();
    }

    public boolean isRunning() {
        return started;
    }

    private void handleRequest(HttpExchange exchange, Interpreter programInterpreter) throws IOException {
        // Wrap request with empty path params map initially, and wrap response
        Map<String, Object> reqMap = RequestWrapper.wrap(exchange, RuntimeCollections.newMap());
        ResponseWrapper resWrapper = new ResponseWrapper(exchange);
        Interpreter requestInterpreter = programInterpreter.forkForRequest();

        try {
            runChain(0, reqMap, resWrapper, requestInterpreter, exchange);

            // Fallback check: if the entire chain finished (including dispatch/short-circuit) and no response sent
            if (!resWrapper.isSent()) {
                Token dummyToken = new Token(dev.tlang.lexer.TokenType.IDENTIFIER, "handler", null, 1);
                throw new RuntimeError(dummyToken, "No response was sent by the handler or middleware.");
            }
        } catch (RuntimeError e) {
            RuntimeError diagnostic = e.withFrame(RuntimeStackFrame.httpHandler(
                exchange.getRequestMethod(), Route.normalizePath(exchange.getRequestURI().getPath())));
            diagnosticSink.accept(diagnostic);
            resWrapper.replaceWithError(500, "Internal Server Error");
        } catch (Exception e) {
            RuntimeError diagnostic = new RuntimeError(
                dev.tlang.errors.RuntimeErrorKind.HTTP_ERROR,
                null,
                "Unexpected HTTP server failure.",
                e
            ).withFrame(RuntimeStackFrame.httpHandler(
                exchange.getRequestMethod(), Route.normalizePath(exchange.getRequestURI().getPath())));
            diagnosticSink.accept(diagnostic);
            // Host implementation details are retained as the cause, never sent remotely.
            resWrapper.replaceWithError(500, "Internal Server Error");
        }
        resWrapper.flush();
    }

    private void runChain(int index, Map<String, Object> reqMap, ResponseWrapper resWrapper, Interpreter interpreter, HttpExchange exchange) throws IOException {
        if (index < activeMiddlewares.size()) {
            Object middlewareFn = activeMiddlewares.get(index);
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
                    } catch (IOException e) {
                        throw new RuntimeError(
                            dev.tlang.errors.RuntimeErrorKind.HTTP_ERROR,
                            token,
                            "HTTP middleware chain failed.",
                            e);
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

        for (Route r : activeRoutes) {
            if (r.matches(method, reqSegments)) {
                int score = r.specificityScore();
                if (score > bestScore) {
                    bestScore = score;
                    matchedRoute = r;
                }
            }
        }

        // If no route matches the method, check 404 vs 405
        if (matchedRoute == null) {
            Set<String> allowedMethods = new LinkedHashSet<>();
            for (Route r : activeRoutes) {
                if (r.matches(r.method, reqSegments)) {
                    allowedMethods.add(r.method.toUpperCase());
                }
            }

            if (!allowedMethods.isEmpty()) {
                // 405 Method Not Allowed
                String allowHeader = String.join(", ", allowedMethods);
                exchange.getResponseHeaders().set("Allow", allowHeader);
                resWrapper.replaceWithError(405, "Method Not Allowed");
            } else {
                // 404 Not Found
                resWrapper.replaceWithError(404, "Not Found");
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
                } catch (IllegalArgumentException | java.io.UnsupportedEncodingException e) {
                    // Keep raw value on failure
                }
                params.put(paramName, decodedVal);
            }
        }

        interpreter.executeCallDirect(
            matchedRoute.handler,
            List.of(reqMap, resWrapper.asMap()),
            matchedRoute.registrationToken);
    }

    private ThreadPoolExecutor createExecutor() {
        AtomicInteger threadSequence = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, workerThreadPrefix + threadSequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
        return new ThreadPoolExecutor(
            workerCount,
            workerCount,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            threadFactory,
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    private void ensureRegistrationOpen(Token token) {
        if (started || stoppedPermanently) {
            throw new RuntimeError(token, "Routes and middleware cannot be registered after the HTTP server starts.");
        }
    }

    private static int defaultWorkerCount() {
        int configured = Integer.getInteger("tlang.http.workers", 0);
        if (configured > 0) {
            return configured;
        }
        return Math.min(32, Math.max(4, Runtime.getRuntime().availableProcessors()));
    }

    private static void reportDiagnostic(RuntimeError error) {
        System.err.println(ErrorFormatter.format(error));
    }
}
