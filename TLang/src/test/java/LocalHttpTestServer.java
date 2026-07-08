import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Minimal local HTTP test server for TLang HTTP client tests.
 *
 * Binds to localhost:8973 and serves:
 *   /echo   — 200, body includes request method + received body + echoed custom header
 *   /status/404 — always 404 with a short body
 *
 * Run: javac LocalHttpTestServer.java && java LocalHttpTestServer
 * Kill with Ctrl-C or SIGTERM.
 */
public class LocalHttpTestServer {

    private static final int PORT = 8973;

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", PORT), 0);

        // /echo — echoes method, body, and a custom header
        server.createContext("/echo", exchange -> {
            String method = exchange.getRequestMethod();
            String body = readBody(exchange);
            String customHeader = exchange.getRequestHeaders().getFirst("X-Custom");

            StringBuilder response = new StringBuilder();
            response.append("method=").append(method);
            if (!body.isEmpty()) {
                response.append("\nbody=").append(body);
            }
            if (customHeader != null) {
                response.append("\ncustom=").append(customHeader);
            }

            sendResponse(exchange, 200, response.toString());
        });

        // /status/404 — always 404
        server.createContext("/status/404", exchange -> {
            sendResponse(exchange, 404, "not found");
        });

        server.setExecutor(null);
        server.start();
        System.out.println("READY on port " + PORT);
        System.out.flush();
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
