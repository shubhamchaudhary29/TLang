package dev.tlang.runtime.database;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class RepositoryDefinitionTest {
    private static Map<String, Object> descriptor() {
        return new HashMap<>(Map.of("primaryKey", "id", "fields", List.of("id", "name", "created"),
            "readOnly", List.of("created")));
    }

    @Test void descriptorSnapshotsAndImmutableAccessors() {
        var fields = new ArrayList<>(List.of("name", "id", "created"));
        var readOnly = new ArrayList<>(List.of("created"));
        var supplied = new HashMap<String, Object>(Map.of("fields", fields, "primaryKey", "id", "readOnly", readOnly));
        var definition = RepositoryDefinition.parse("users", supplied);
        fields.clear(); readOnly.clear(); supplied.clear();
        assertEquals("users", definition.table());
        assertEquals("id", definition.primaryKey());
        assertEquals(List.of("name", "id", "created"), definition.fields());
        assertEquals(java.util.Set.of("created"), definition.readOnly());
        assertThrows(UnsupportedOperationException.class, () -> definition.fields().clear());
        assertThrows(UnsupportedOperationException.class, () -> definition.readOnly().clear());
        assertThrows(DatabaseFailure.class, () -> definition.mutation(Map.of("created", "changed"), false));
        assertEquals(java.util.Set.of(), RepositoryDefinition.parse("users",
            Map.of("fields", List.of("id"), "primaryKey", "id")).readOnly());
    }

    @Test void malformedDescriptorsFailWithoutOpeningDatabase() {
        for (Object invalid : Arrays.asList(null, 1, "fields", List.of())) {
            assertThrows(DatabaseFailure.class, () -> RepositoryDefinition.parse("users", invalid));
        }
        for (String required : List.of("primaryKey", "fields")) {
            var missing = descriptor(); missing.remove(required);
            assertThrows(DatabaseFailure.class, () -> RepositoryDefinition.parse("users", missing));
        }
        for (String key : List.of("fields", "readOnly")) {
            for (Object invalid : Arrays.asList(null, 1, "id", Map.of())) {
                var supplied = descriptor(); supplied.put(key, invalid);
                assertThrows(DatabaseFailure.class, () -> RepositoryDefinition.parse("users", supplied));
            }
        }
        for (Object primary : Arrays.asList(null, 1, List.of("id"), "missing", "ID", "id--")) {
            var supplied = descriptor(); supplied.put("primaryKey", primary);
            assertThrows(DatabaseFailure.class, () -> RepositoryDefinition.parse("users", supplied));
        }
        for (List<?> fields : List.of(List.of(), List.of("id", "id"), List.of("id", "ID"),
                List.of("id", 1), Arrays.asList("id", null), Collections.nCopies(101, "id"))) {
            var supplied = descriptor(); supplied.put("fields", fields);
            assertThrows(DatabaseFailure.class, () -> RepositoryDefinition.parse("users", supplied));
        }
        for (List<?> readOnly : List.of(List.of("missing"), List.of("created", "created"), List.of(1),
                Arrays.asList((Object) null), List.of("CREATED"), Collections.nCopies(101, "id"))) {
            var supplied = descriptor(); supplied.put("readOnly", readOnly);
            assertThrows(DatabaseFailure.class, () -> RepositoryDefinition.parse("users", supplied));
        }
        for (Object key : Arrays.asList("unsafe", "extra", null, 1)) {
            Map<Object, Object> supplied = new HashMap<>(descriptor()); supplied.put(key, true);
            assertThrows(DatabaseFailure.class, () -> RepositoryDefinition.parse("users", supplied));
        }
    }

    @Test void hostileMetadataUsesM3RulesAndDoesNotEchoInput() {
        for (Object hostile : Arrays.asList(null, 1, "users; DROP TABLE users", "name--", "name WHERE 1=1",
                "\"id); DELETE FROM users", "public.users", "*", "", "x".repeat(64), "a\n", "नमस्ते")) {
            assertThrows(DatabaseFailure.class, () -> RepositoryDefinition.parse(hostile, descriptor()));
            for (String key : List.of("fields", "readOnly", "primaryKey")) {
                var supplied = descriptor();
                supplied.put(key, key.equals("primaryKey") ? hostile : Arrays.asList("id", hostile));
                var error = assertThrows(DatabaseFailure.class, () -> RepositoryDefinition.parse("users", supplied));
                if (hostile instanceof String text && text.length() > 4) assertFalse(error.getMessage().contains(text));
            }
        }
        var names = new ArrayList<String>();
        for (int i = 0; i < 100; i++) names.add("field_" + i);
        var maximal = RepositoryDefinition.parse("users", Map.of("primaryKey", "field_0", "fields", names));
        assertEquals(100, maximal.fields().size());
    }

    @Test void mutationsSnapshotAndRetainM3DeterministicParameterOrdering() {
        var definition = RepositoryDefinition.parse("users", descriptor());
        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("name", "' OR 1=1 --"); reversed.put("id", 42);
        Map<?, ?> snapshot = definition.mutation(reversed, false);
        var compiled = DatabaseQuery.table("users").compileInsert(snapshot);
        reversed.clear();
        assertEquals("INSERT INTO \"users\" (\"id\", \"name\") VALUES (?, ?)", compiled.sql());
        assertEquals(List.of(42, "' OR 1=1 --"), compiled.parameters());
        assertEquals(compiled, DatabaseQuery.table("users").compileInsert(
            definition.mutation(Map.of("id", 42, "name", "' OR 1=1 --"), false)));
        assertEquals(2, snapshot.size());
    }

    @Test void mutationContractsRejectAliasesUnknownReadOnlyAndPrimaryKeyWrites() {
        var definition = RepositoryDefinition.parse("users", descriptor());
        for (Object invalid : Arrays.asList(null, 1, List.of(), Map.of(), Map.of("unknown", 1),
                Map.of("created", 1), Map.of("CREATED", 1), Map.of("name--", 1), Map.of(1, 1))) {
            assertThrows(DatabaseFailure.class, () -> definition.mutation(invalid, false));
            assertThrows(DatabaseFailure.class, () -> definition.mutation(invalid, true));
        }
        assertThrows(DatabaseFailure.class, () -> definition.mutation(Map.of("id", 2), true));
        assertEquals(Map.of("id", 2), definition.mutation(Map.of("id", 2), false));
        var protectedId = RepositoryDefinition.parse("users", Map.of("primaryKey", "id",
            "fields", List.of("id", "name"), "readOnly", List.of("id")));
        assertThrows(DatabaseFailure.class, () -> protectedId.mutation(Map.of("id", 2), false));
    }
}
