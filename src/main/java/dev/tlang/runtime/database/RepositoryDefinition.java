package dev.tlang.runtime.database;

import dev.tlang.interpreter.RuntimeCollections;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Immutable application field contract, validated by the existing M3 identifier rules. */
public final class RepositoryDefinition {
    private static final Set<String> OPTIONS = Set.of("primaryKey", "fields", "readOnly");
    private final String table;
    private final String primaryKey;
    private final List<String> fields;
    private final Set<String> fieldSet;
    private final Set<String> readOnly;

    private RepositoryDefinition(String table, String primaryKey, List<String> fields, Set<String> readOnly) {
        this.table = table;
        this.primaryKey = primaryKey;
        this.fields = List.copyOf(fields);
        this.fieldSet = Set.copyOf(fields);
        this.readOnly = Set.copyOf(readOnly);
    }

    public static RepositoryDefinition parse(Object table, Object value) {
        DatabaseQuery base = DatabaseQuery.table(table);
        if (!(value instanceof Map<?, ?> map)) throw fail("Repository definition must be a map.");
        Map<?, ?> supplied = RuntimeCollections.snapshot(map);
        for (Object key : supplied.keySet()) {
            if (!(key instanceof String name) || !OPTIONS.contains(name)) {
                throw fail("Unknown repository definition option.");
            }
        }
        List<?> rawFields = list(supplied.get("fields"), "Repository fields must be a list.");
        // M3 validates identifiers, nonempty projection, duplicates, and its shared column bound.
        base.select(rawFields);
        List<String> fields = rawFields.stream().map(String.class::cast).toList();
        Set<String> folded = new HashSet<>();
        for (String field : fields) {
            if (!folded.add(field.toLowerCase(Locale.ROOT))) {
                throw fail("Repository fields must not contain duplicates, including case-only aliases.");
            }
        }
        if (!(supplied.get("primaryKey") instanceof String primaryKey) || !fields.contains(primaryKey)) {
            throw fail("Repository primary key must be declared in fields.");
        }
        List<?> rawReadOnly = supplied.containsKey("readOnly")
            ? list(supplied.get("readOnly"), "Repository readOnly must be a list.") : List.of();
        Set<String> readOnly = new HashSet<>();
        for (Object field : rawReadOnly) {
            if (!(field instanceof String name) || !fields.contains(name)) {
                throw fail("Repository read-only field must be declared in fields.");
            }
            if (!readOnly.add(name)) throw fail("Duplicate repository read-only field.");
        }
        return new RepositoryDefinition((String) table, primaryKey, fields, readOnly);
    }

    public String table() { return table; }
    public String primaryKey() { return primaryKey; }
    public List<String> fields() { return fields; }
    public Set<String> readOnly() { return readOnly; }

    /** Snapshot before validation so callers cannot change a map between checking and execution. */
    Map<?, ?> mutation(Object value, boolean update) {
        if (!(value instanceof Map<?, ?> map)) throw fail("Repository mutation requires a map.");
        Map<?, ?> snapshot;
        synchronized (map) {
            if (map.isEmpty()) throw fail("Repository mutation map must not be empty.");
            if (map.size() > fields.size()) throw fail("Repository mutation exceeds declared field count.");
            snapshot = RuntimeCollections.snapshot(map);
        }
        for (Object key : snapshot.keySet()) {
            if (!(key instanceof String name) || !fieldSet.contains(name)) throw fail("Unknown repository field.");
            if (update && name.equals(primaryKey)) throw fail("Repository update cannot change the primary key.");
            if (readOnly.contains(name)) throw fail("Repository field is read-only.");
        }
        return snapshot;
    }

    private static List<?> list(Object value, String message) {
        if (!(value instanceof List<?> list)) throw fail(message);
        synchronized (list) {
            if (list.size() > DatabaseQuery.MAX_TERMS) throw fail("Repository field list exceeds 100 entries.");
            return RuntimeCollections.snapshot(list);
        }
    }

    private static DatabaseFailure fail(String message) { return new DatabaseFailure(message); }
}
