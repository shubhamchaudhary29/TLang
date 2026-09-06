package dev.tlang.runtime.database;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class DatabaseQueryTest {
    private final DatabaseQuery base = DatabaseQuery.table("users");

    private static void compiled(DatabaseQuery.Compiled actual, String sql, Object... values) {
        assertEquals(sql, actual.sql());
        assertEquals(Arrays.asList(values), actual.parameters());
        assertEquals(values.length, SqlParameters.countPlaceholders(actual.sql()));
    }

    @Test void exactReadsAndCountSemantics() {
        compiled(base.compileAll(), "SELECT * FROM \"users\"");
        DatabaseQuery query = base.select(List.of("id", "name")).where("age", ">=", 18)
            .where("name", "LIKE", "Sh%").orderBy("id", "DeSc").limit(20).offset(40);
        compiled(query.compileAll(), "SELECT \"users\".\"id\", \"users\".\"name\" FROM \"users\" WHERE \"users\".\"age\" >= ? AND \"users\".\"name\" LIKE ? ORDER BY \"users\".\"id\" DESC LIMIT ? OFFSET ?", 18, "Sh%", 20, 40);
        compiled(query.compileFirst(), "SELECT \"users\".\"id\", \"users\".\"name\" FROM \"users\" WHERE \"users\".\"age\" >= ? AND \"users\".\"name\" LIKE ? ORDER BY \"users\".\"id\" DESC LIMIT ? OFFSET ?", 18, "Sh%", 1, 40);
        compiled(query.compileCount(), "SELECT COUNT(*) AS \"count_value\" FROM \"users\" WHERE \"users\".\"age\" >= ? AND \"users\".\"name\" LIKE ?", 18, "Sh%");
        compiled(base.offset(0).compileAll(), "SELECT * FROM \"users\" LIMIT ? OFFSET ?", Integer.MAX_VALUE, 0);
        compiled(base.limit(0).compileFirst(), "SELECT * FROM \"users\" LIMIT ?", 0);
        compiled(base.compileFirst(), "SELECT * FROM \"users\" LIMIT ?", 1);
        for (String op : List.of("=", "!=", "<", "<=", ">", ">=")) {
            compiled(base.where("id", op, 42).compileAll(), "SELECT * FROM \"users\" WHERE \"users\".\"id\" " + op + " ?", 42);
        }
    }

    @Test void nullAndInAreExplicitAndComposeWithAnd() {
        compiled(base.where("name", "=", null).where("id", "!=", null).compileAll(),
            "SELECT * FROM \"users\" WHERE \"users\".\"name\" IS NULL AND \"users\".\"id\" IS NOT NULL");
        compiled(base.whereIn("id", List.of()).compileAll(), "SELECT * FROM \"users\" WHERE 1 = 0");
        compiled(base.whereIn("id", Arrays.asList((Object) null)).compileAll(), "SELECT * FROM \"users\" WHERE \"users\".\"id\" IS NULL");
        compiled(base.whereIn("id", Arrays.asList(1, null, 2)).where("name", "!=", "x").compileAll(),
            "SELECT * FROM \"users\" WHERE (\"users\".\"id\" IN (?, ?) OR \"users\".\"id\" IS NULL) AND \"users\".\"name\" != ?", 1, 2, "x");
        compiled(base.whereIn("id", List.of(1, 2, 3)).compileAll(), "SELECT * FROM \"users\" WHERE \"users\".\"id\" IN (?, ?, ?)", 1, 2, 3);
        assertThrows(DatabaseFailure.class, () -> base.where("id", "<", null));
    }

    @Test void deterministicWritesAndImmutableSnapshots() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("z", null); fields.put("a", "hostile' --");
        compiled(base.compileInsert(fields), "INSERT INTO \"users\" (\"a\", \"z\") VALUES (?, ?)", "hostile' --", null);
        compiled(base.where("id", "=", 9).compileUpdate(fields), "UPDATE \"users\" SET \"a\" = ?, \"z\" = ? WHERE \"users\".\"id\" = ?", "hostile' --", null, 9);
        compiled(base.where("id", "=", 9).compileDelete(), "DELETE FROM \"users\" WHERE \"users\".\"id\" = ?", 9);
        List<Object> supplied = new ArrayList<>(List.of(1, 2));
        DatabaseQuery derived = base.whereIn("id", supplied);
        supplied.clear();
        compiled(derived.compileAll(), "SELECT * FROM \"users\" WHERE \"users\".\"id\" IN (?, ?)", 1, 2);
        compiled(base.compileAll(), "SELECT * FROM \"users\"");
        assertThrows(UnsupportedOperationException.class, () -> derived.compileAll().parameters().clear());
        var insert = base.compileInsert(fields);
        fields.clear();
        assertEquals(Arrays.asList("hostile' --", null), insert.parameters());
        List<String> selection = new ArrayList<>(List.of("id"));
        DatabaseQuery selected = base.select(selection);
        selection.clear();
        compiled(selected.compileAll(), "SELECT \"users\".\"id\" FROM \"users\"");
    }

    @Test void injectionAndIdentifierSafety() {
        for (String hostile : List.of("users; DROP TABLE users", "users--", "users WHERE 1=1", "\"name); DELETE", "public.users", "a.b.c", "*", "", " name", "a\n", "1name", "नमस्ते", "x".repeat(64))) {
            assertThrows(DatabaseFailure.class, () -> DatabaseQuery.table(hostile));
            assertThrows(DatabaseFailure.class, () -> base.select(List.of(hostile)));
            assertThrows(DatabaseFailure.class, () -> base.where(hostile, "=", 1));
            assertThrows(DatabaseFailure.class, () -> base.whereIn(hostile, List.of(1)));
            assertThrows(DatabaseFailure.class, () -> base.orderBy(hostile, "asc"));
            assertThrows(DatabaseFailure.class, () -> base.compileInsert(Map.of(hostile, 1)));
            assertThrows(DatabaseFailure.class, () -> base.where("id", "=", 1).compileUpdate(Map.of(hostile, 1)));
        }
        for (String hostile : List.of("' OR 1=1 --", "a\n'b", "", "🚀", "x".repeat(100_000))) {
            compiled(base.where("email", "=", hostile).compileAll(), "SELECT * FROM \"users\" WHERE \"users\".\"email\" = ?", hostile);
        }
        for (Object invalid : Arrays.asList(null, 1, "= ? OR 1=1 --", "==", "in")) {
            assertThrows(DatabaseFailure.class, () -> base.where("id", invalid, 1));
            assertThrows(DatabaseFailure.class, () -> base.orderBy("id", invalid));
        }
        compiled(DatabaseQuery.table("select").select(List.of("From")).compileAll(), "SELECT \"select\".\"From\" FROM \"select\"");
    }

    @Test void invalidArgumentsAndMutationGuards() {
        assertThrows(DatabaseFailure.class, base::compileDelete);
        assertThrows(DatabaseFailure.class, () -> base.compileUpdate(Map.of("id", 1)));
        assertThrows(DatabaseFailure.class, () -> base.compileInsert(Map.of()));
        assertThrows(DatabaseFailure.class, () -> base.where("id", "=", 1).compileUpdate(Map.of()));
        assertThrows(DatabaseFailure.class, () -> base.where("id", "=", 1).compileInsert(Map.of("id", 1)));
        for (DatabaseQuery read : List.of(base.select(List.of("id")), base.orderBy("id", "asc"), base.limit(0), base.offset(0))) {
            assertThrows(DatabaseFailure.class, () -> read.where("id", "=", 1).compileDelete());
            assertThrows(DatabaseFailure.class, () -> read.where("id", "=", 1).compileUpdate(Map.of("id", 1)));
            assertThrows(DatabaseFailure.class, () -> read.compileInsert(Map.of("id", 1)));
        }
        for (Object invalid : Arrays.asList(null, -1, 1L, 2147483648L, true, "1", 1.5)) {
            assertThrows(DatabaseFailure.class, () -> base.limit(invalid));
            assertThrows(DatabaseFailure.class, () -> base.offset(invalid));
        }
        for (Object invalid : Arrays.asList(null, 1, "id", Map.of())) {
            assertThrows(DatabaseFailure.class, () -> base.select(invalid));
            assertThrows(DatabaseFailure.class, () -> base.whereIn("id", invalid));
        }
        assertThrows(DatabaseFailure.class, () -> base.select(List.of()));
        assertThrows(DatabaseFailure.class, () -> base.select(List.of("id", "id")));
        assertThrows(DatabaseFailure.class, () -> base.compileInsert(Map.of(1, "bad")));
        for (Object invalid : List.of(List.of(1), Map.of("x", 1), 1L)) {
            assertThrows(DatabaseFailure.class, () -> base.where("id", "=", invalid));
            assertThrows(DatabaseFailure.class, () -> base.whereIn("id", List.of(invalid)));
            assertThrows(DatabaseFailure.class, () -> base.compileInsert(Map.of("id", invalid)));
        }
    }

    @Test void queryComplexityIsBoundedIncludingWriteAndPaginationParameters() {
        assertThrows(DatabaseFailure.class, () -> base.whereIn("id", Collections.nCopies(901, 1)));
        var full = base.whereIn("id", Collections.nCopies(900, 1));
        assertEquals(900, full.compileAll().parameters().size());
        assertThrows(DatabaseFailure.class, () -> full.limit(1).compileAll());
        assertThrows(DatabaseFailure.class, () -> full.compileUpdate(Map.of("id", 2)));
        assertThrows(DatabaseFailure.class, () -> full.where("id", "=", 1));
        DatabaseQuery many = base;
        for (int i = 0; i < 100; i++) many = many.where("id", "!=", null);
        DatabaseQuery bounded = many;
        assertThrows(DatabaseFailure.class, () -> bounded.where("id", "=", null));
        assertThrows(DatabaseFailure.class, () -> base.select(Collections.nCopies(101, "id")));
    }
}
