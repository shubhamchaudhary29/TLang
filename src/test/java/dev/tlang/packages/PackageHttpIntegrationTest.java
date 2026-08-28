package dev.tlang.packages;

import dev.tlang.ast.Stmt;
import dev.tlang.interpreter.Interpreter;
import dev.tlang.lexer.Lexer;
import dev.tlang.lexer.Token;
import dev.tlang.lexer.TokenType;
import dev.tlang.modules.ModuleLoader;
import dev.tlang.parser.Parser;
import dev.tlang.runtime.http.ServerOps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PackageHttpIntegrationTest {
    @TempDir Path temporary;

    @Test void installedPackageFunctionRunsInsideConcurrentHttpHandler() throws Exception {
        Path dependency = temporary.resolve("handler_dep");
        PackageTestSupport.writePackage(dependency, "handler_dep", Map.of(), """
            define message taking id
                return "package:" + id
            """);
        Path app = temporary.resolve("app"); Files.createDirectories(app);
        PackageManager manager = new PackageManager(); manager.init(app, "app");
        manager.add(app, DependencySpec.path("handler_dep", "../handler_dep"));
        Path main = app.resolve("main.tiny");
        String source = """
            import handler_dep
            define handle taking req and res
                res.text(handler_dep.message(req.params.id))
            """;
        Files.writeString(main, source);
        Interpreter interpreter = new Interpreter(new ModuleLoader(app));
        List<Stmt> program = new Parser(new Lexer(source, main.toString()).tokenize()).parse();
        interpreter.interpret(program);
        Token token = new Token(TokenType.IDENTIFIER, "handle", null, 1, 1);
        Object handler = interpreter.getGlobalEnvironment().get(token);

        ServerOps server = new ServerOps(0, 4, 32);
        try {
            server.addRoute("GET", "/items/:id", handler, token);
            server.start(interpreter, token);
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest first = HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + server.getBoundPort() + "/items/one")).GET().build();
            HttpRequest second = HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + server.getBoundPort() + "/items/two")).GET().build();
            var a = client.sendAsync(first, HttpResponse.BodyHandlers.ofString());
            var b = client.sendAsync(second, HttpResponse.BodyHandlers.ofString());
            assertEquals("package:one", a.get().body());
            assertEquals("package:two", b.get().body());
        } finally {
            server.stop();
        }
    }
}
