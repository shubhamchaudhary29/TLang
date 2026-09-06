package dev.tlang.runtime.database;

import dev.tlang.errors.RuntimeError;
import dev.tlang.errors.RuntimeErrorKind;
import dev.tlang.interpreter.Interpreter;
import dev.tlang.interpreter.NativeFunction;
import dev.tlang.lexer.Lexer;
import dev.tlang.lexer.Token;
import dev.tlang.lexer.TokenType;
import dev.tlang.modules.DatabaseModule;
import dev.tlang.modules.ModuleLoader;
import dev.tlang.parser.Parser;
import dev.tlang.resolver.Resolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class QueryRuntimeTest {
    private static final Token TOKEN = new Token(TokenType.IDENTIFIER, "query_test", null, 1, 1);

    @Test void validLanguageCrudAndSpawnReuse(@TempDir Path directory) {
        Interpreter interpreter = compile("""
            import db
            let connection be db.open(":memory:")
            connection.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)", [])
            let users be connection.table("users")
            define write taking id
                return users.insert({id: id, name: "user-" + id})
            let tasks be []
            repeat 40 times as index
                tasks.add(spawn write(index))
            repeat 40 times as index
                await tasks.get(index)
            let active be users.where("id", ">=", 20)
            let admins be users.whereIn("id", [1, 2, 3])
            let firstRead be spawn active.orderBy("id", "asc").select(["id"]).limit(2).offset(1).first()
            let secondRead be spawn admins.count()
            let first be await firstRead
            let second be await secondRead
            let affected be users.where("id", "=", 0).update({name: "updated"})
            let removed be users.where("id", "=", 39).delete()
            let missing be users.where("id", "=", 100).first()
            let count be users.count()
            let tx be connection.begin()
            tx.table("users").insert({id: 50, name: nil})
            let txCount be tx.table("users").count()
            tx.rollback()
            connection.close()
            """, directory);
        assertEquals(Map.of("id", 21), global(interpreter, "first"));
        assertEquals(3, global(interpreter, "second"));
        assertEquals(1, global(interpreter, "affected"));
        assertEquals(1, global(interpreter, "removed"));
        assertNull(global(interpreter, "missing"));
        assertEquals(39, global(interpreter, "count"));
        assertEquals(40, global(interpreter, "txCount"));
    }

    @Test void validationAbortsTransactionsAndClosedHandlesFailSafely() {
        Object connection = ((NativeFunction) new DatabaseModule().getExports().get("open")).call(List.of(":memory:"), TOKEN);
        try {
            call(connection, "execute", "CREATE TABLE users (id INTEGER PRIMARY KEY)", List.of());
            for (String operation : List.of("where", "whereIn", "select", "orderBy", "limit", "offset", "insert", "update", "delete")) {
                Object tx = call(connection, "begin");
                Object query = call(tx, "table", "users");
                call(query, "insert", Map.of("id", 1));
                Object[] arguments = switch (operation) {
                    case "where" -> new Object[]{"id", "= OR 1=1", 1};
                    case "whereIn" -> new Object[]{"id", "invalid"};
                    case "orderBy" -> new Object[]{"id", "desc; DROP"};
                    case "select" -> new Object[]{List.of("bad--")};
                    case "delete" -> new Object[]{};
                    case "insert" -> new Object[]{Map.of()};
                    case "update" -> new Object[]{Map.of("id", 2)};
                    default -> new Object[]{-1};
                };
                RuntimeError failure = assertThrows(RuntimeError.class, () -> call(query, operation, arguments));
                assertEquals(RuntimeErrorKind.DATABASE_ERROR, failure.getKind());
                assertThrows(RuntimeError.class, () -> call(tx, "commit"));
                assertEquals(0, call(call(connection, "table", "users"), "count"));
            }
            Object tx = call(connection, "begin");
            Object ended = call(tx, "table", "users");
            call(tx, "commit");
            assertThrows(RuntimeError.class, () -> call(ended, "where", "id", "=", 1));
            Object query = call(connection, "table", "users");
            call(connection, "close");
            assertThrows(RuntimeError.class, () -> call(query, "all"));
            assertThrows(RuntimeError.class, () -> call(query, "limit", 1));
            assertThrows(RuntimeError.class, () -> call(connection, "table", "users"));
        } finally { call(connection, "close"); }
    }

    @Test void taskOwnedConnectionDoesNotEscapeThroughQueryHandle(@TempDir Path directory) {
        Interpreter interpreter = compile("""
            import db
            define createQuery
                let local be db.open(":memory:")
                local.execute("CREATE TABLE users (id INTEGER)", [])
                return local.table("users")
            let task be spawn createQuery()
            let escaped be await task
            """, directory);
        RuntimeError failure = assertThrows(RuntimeError.class, () -> call(global(interpreter, "escaped"), "all"));
        assertEquals(RuntimeErrorKind.DATABASE_ERROR, failure.getKind());
    }

    @Test void requestOwnedQueryClosesAndHttpErrorsStayGeneric(@TempDir Path directory) throws Exception {
        Interpreter interpreter = compile("""
            import db
            let handles be []
            define request taking req and res
                let local be db.open(":memory:")
                local.execute("CREATE TABLE users (id INTEGER)", [])
                let users be local.table("users")
                handles.add(users)
                users.insert({id: 1})
                res.text("ok")
            define failed taking req and res
                let local be db.open(":memory:")
                local.table("secret_internal_table").all()
            """, directory);
        var server = new dev.tlang.runtime.http.ServerOps(0, 4, 16);
        server.addRoute("GET", "/ok", global(interpreter, "request"), TOKEN);
        server.addRoute("GET", "/fail", global(interpreter, "failed"), TOKEN);
        server.start(interpreter, TOKEN);
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            for (String route : List.of("ok", "fail")) {
                var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                    "http://127.0.0.1:" + server.getBoundPort() + "/" + route))
                    .timeout(java.time.Duration.ofSeconds(5)).GET().build();
                var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                assertEquals(route.equals("ok") ? 200 : 500, response.statusCode());
                assertEquals(route.equals("ok") ? "ok" : "Internal Server Error", response.body());
            }
        } finally { server.stop(); }
        Object escaped = ((List<?>) global(interpreter, "handles")).getFirst();
        assertThrows(RuntimeError.class, () -> call(escaped, "all"));
    }

    static Interpreter compile(String source, Path directory) {
        var statements = new Parser(new Lexer(source, directory.resolve("query.tiny").toString()).tokenize()).parse();
        assertTrue(new Resolver().resolve(statements).isEmpty());
        var interpreter = new Interpreter(new ModuleLoader(directory));
        interpreter.interpret(statements);
        return interpreter;
    }
    static Object global(Interpreter interpreter, String name) {
        return interpreter.getGlobalEnvironment().get(new Token(TokenType.IDENTIFIER, name, null, 1, 1));
    }
    static Object call(Object receiver, String method, Object... arguments) {
        List<Object> args = new ArrayList<>();
        args.add(receiver); args.addAll(Arrays.asList(arguments));
        return ((NativeFunction) ((Map<?, ?>) receiver).get(method)).call(args, TOKEN);
    }
}
