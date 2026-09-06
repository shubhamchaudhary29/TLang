package dev.tlang.runtime.database;

import dev.tlang.interpreter.RuntimeCollections;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Immutable query intent. Compilation never borrows a database resource. */
public final class DatabaseQuery {
    // Conservative shared bounds, below SQLite expression/variable limits.
    public static final int MAX_PARAMETERS = 900;
    public static final int MAX_TERMS = 100;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    public record Compiled(String sql, List<Object> parameters) {
        public Compiled {
            parameters = Collections.unmodifiableList(new ArrayList<>(parameters));
            if (parameters.size() > MAX_PARAMETERS) throw fail("Query exceeds 900 bound parameters.");
        }
    }

    private record Predicate(String sql, List<Object> parameters) {
        private Predicate {
            parameters = Collections.unmodifiableList(new ArrayList<>(parameters));
        }
    }

    private final String table;
    private final List<String> columns;
    private final List<Predicate> predicates;
    private final List<String> ordering;
    private final Integer limit;
    private final Integer offset;

    private DatabaseQuery(String table, List<String> columns, List<Predicate> predicates,
                          List<String> ordering, Integer limit, Integer offset) {
        this.table = table;
        this.columns = List.copyOf(columns);
        this.predicates = List.copyOf(predicates);
        this.ordering = List.copyOf(ordering);
        this.limit = limit;
        this.offset = offset;
    }

    public static DatabaseQuery table(Object name) {
        return new DatabaseQuery(identifier(name), List.of(), List.of(), List.of(), null, null);
    }

    public DatabaseQuery select(Object value) {
        List<?> supplied = list(value);
        if (supplied.isEmpty() || supplied.size() > MAX_TERMS) throw fail("Select requires 1 to 100 columns.");
        List<String> selected = supplied.stream().map(this::column).toList();
        if (selected.stream().distinct().count() != selected.size()) throw fail("Duplicate select column.");
        return new DatabaseQuery(table, selected, predicates, ordering, limit, offset);
    }

    public DatabaseQuery where(Object column, Object operator, Object value) {
        String name = column(column);
        String op = operator instanceof String s ? s.toLowerCase(Locale.ROOT) : "";
        if (!List.of("=", "!=", "<", "<=", ">", ">=", "like").contains(op)) {
            throw fail("Unsupported comparison operator.");
        }
        SqlParameters.validateValue(value);
        if (value == null) {
            if (!op.equals("=") && !op.equals("!=")) throw fail("Null comparisons require = or !=.");
            return add(new Predicate(name + (op.equals("=") ? " IS NULL" : " IS NOT NULL"), List.of()));
        }
        return add(new Predicate(name + " " + op.toUpperCase(Locale.ROOT) + " ?", List.of(value)));
    }

    public DatabaseQuery whereIn(Object column, Object values) {
        String name = column(column);
        List<?> supplied = list(values);
        if (supplied.size() > MAX_PARAMETERS) throw fail("whereIn exceeds 900 values.");
        List<Object> bound = new ArrayList<>();
        boolean hasNull = false;
        for (Object value : supplied) {
            SqlParameters.validateValue(value);
            if (value == null) hasNull = true; else bound.add(value);
        }
        String sql = bound.isEmpty() ? (hasNull ? name + " IS NULL" : "1 = 0")
            : name + " IN (" + placeholders(bound.size()) + ")";
        if (hasNull && !bound.isEmpty()) sql = "(" + sql + " OR " + name + " IS NULL)";
        return add(new Predicate(sql, bound));
    }

    private DatabaseQuery add(Predicate predicate) {
        if (predicates.size() >= MAX_TERMS) throw fail("Query exceeds 100 predicates.");
        int count = predicate.parameters.size();
        for (Predicate existing : predicates) count += existing.parameters.size();
        if (count > MAX_PARAMETERS) throw fail("Query exceeds 900 bound parameters.");
        List<Predicate> next = new ArrayList<>(predicates);
        next.add(predicate);
        return new DatabaseQuery(table, columns, next, ordering, limit, offset);
    }

    public DatabaseQuery orderBy(Object column, Object direction) {
        String name = column(column);
        if (!(direction instanceof String s) || !(s.equalsIgnoreCase("asc") || s.equalsIgnoreCase("desc"))) {
            throw fail("Order direction must be asc or desc.");
        }
        if (ordering.size() >= MAX_TERMS) throw fail("Query exceeds 100 orderings.");
        List<String> next = new ArrayList<>(ordering);
        next.add(name + " " + ((String) direction).toUpperCase(Locale.ROOT));
        return new DatabaseQuery(table, columns, predicates, next, limit, offset);
    }

    public DatabaseQuery limit(Object value) {
        return new DatabaseQuery(table, columns, predicates, ordering, nonnegative(value), offset);
    }

    public DatabaseQuery offset(Object value) {
        return new DatabaseQuery(table, columns, predicates, ordering, limit, nonnegative(value));
    }

    public Compiled compileAll() { return compileRead(false, false); }
    public Compiled compileFirst() { return compileRead(true, false); }
    public Compiled compileCount() { return compileRead(false, true); }

    private Compiled compileRead(boolean first, boolean count) {
        String projection = count ? "COUNT(*) AS \"count_value\"" : columns.isEmpty() ? "*" : String.join(", ", columns);
        StringBuilder sql = new StringBuilder("SELECT " + projection + " FROM " + table);
        List<Object> parameters = new ArrayList<>();
        appendWhere(sql, parameters);
        if (!count) {
            if (!ordering.isEmpty()) sql.append(" ORDER BY ").append(String.join(", ", ordering));
            Integer effective = first ? Integer.valueOf(limit == null ? 1 : Math.min(limit, 1)) : limit;
            if (effective != null || offset != null) {
                sql.append(" LIMIT ?");
                parameters.add(effective == null ? Integer.MAX_VALUE : effective);
            }
            if (offset != null) {
                sql.append(" OFFSET ?");
                parameters.add(offset);
            }
        }
        return new Compiled(sql.toString(), parameters);
    }

    public Compiled compileInsert(Object values) {
        requireWrite(false);
        if (!predicates.isEmpty()) throw fail("Insert does not accept predicates.");
        Map<String, Object> fields = fields(values);
        return new Compiled("INSERT INTO " + table + " (" + String.join(", ", fields.keySet())
            + ") VALUES (" + placeholders(fields.size()) + ")", new ArrayList<>(fields.values()));
    }

    public Compiled compileUpdate(Object values) {
        requireWrite(true);
        Map<String, Object> fields = fields(values);
        StringBuilder sql = new StringBuilder("UPDATE " + table + " SET "
            + String.join(", ", fields.keySet().stream().map(name -> name + " = ?").toList()));
        List<Object> parameters = new ArrayList<>(fields.values());
        appendWhere(sql, parameters);
        return new Compiled(sql.toString(), parameters);
    }

    public Compiled compileDelete() {
        requireWrite(true);
        StringBuilder sql = new StringBuilder("DELETE FROM " + table);
        List<Object> parameters = new ArrayList<>();
        appendWhere(sql, parameters);
        return new Compiled(sql.toString(), parameters);
    }

    private void requireWrite(boolean needsWhere) {
        if (needsWhere && predicates.isEmpty()) throw fail("Update/delete requires a WHERE clause.");
        if (!columns.isEmpty() || !ordering.isEmpty() || limit != null || offset != null) {
            throw fail("Writes do not accept select, orderBy, limit, or offset.");
        }
    }

    private void appendWhere(StringBuilder sql, List<Object> parameters) {
        if (predicates.isEmpty()) return;
        sql.append(" WHERE ").append(String.join(" AND ", predicates.stream().map(Predicate::sql).toList()));
        for (Predicate predicate : predicates) parameters.addAll(predicate.parameters);
    }

    public List<Object> all(DatabaseSession session) { return perform(session, () -> query(session, compileAll())); }
    public Object first(DatabaseSession session) {
        return perform(session, () -> {
            List<Object> rows = query(session, compileFirst());
            return rows.isEmpty() ? null : rows.getFirst();
        });
    }
    public int count(DatabaseSession session) {
        return perform(session, () -> (Integer) ((Map<?, ?>) query(session, compileCount()).getFirst()).get("count_value"));
    }
    public int insert(DatabaseSession session, Object values) { return perform(session, () -> execute(session, compileInsert(values))); }
    public int update(DatabaseSession session, Object values) { return perform(session, () -> execute(session, compileUpdate(values))); }
    public int delete(DatabaseSession session) { return perform(session, () -> execute(session, compileDelete())); }

    private static List<Object> query(DatabaseSession session, Compiled compiled) {
        return session.query(compiled.sql, compiled.parameters);
    }
    private static int execute(DatabaseSession session, Compiled compiled) {
        return session.execute(compiled.sql, compiled.parameters);
    }
    private static <T> T perform(DatabaseSession session, Supplier<T> operation) {
        try { return operation.get(); }
        catch (DatabaseFailure failure) {
            if (session instanceof DatabaseTransaction transaction) transaction.abort();
            throw failure;
        }
    }

    private static Map<String, Object> fields(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw fail("Insert/update requires a map.");
        Map<?, ?> snapshot = RuntimeCollections.snapshot(map);
        if (snapshot.isEmpty()) throw fail("Insert/update map must not be empty.");
        if (snapshot.size() > MAX_TERMS) throw fail("Insert/update exceeds 100 columns.");
        Map<String, Object> sorted = new TreeMap<>();
        snapshot.forEach((key, field) -> {
            String name = identifier(key);
            SqlParameters.validateValue(field);
            sorted.put(name, field);
        });
        return sorted;
    }

    private String column(Object value) { return table + "." + identifier(value); }

    private static String identifier(Object value) {
        if (!(value instanceof String name) || !IDENTIFIER.matcher(name).matches()) {
            throw fail("Invalid identifier: use 1 to 63 ASCII letters, digits, or underscores, starting with a letter or underscore.");
        }
        return "\"" + name + "\"";
    }
    private static List<?> list(Object value) {
        if (!(value instanceof List<?> list)) throw fail("Expected a list.");
        return RuntimeCollections.snapshot(list);
    }
    private static int nonnegative(Object value) {
        if (!(value instanceof Integer number) || number < 0) throw fail("Limit/offset must be an integer from 0 to 2147483647.");
        return number;
    }
    private static String placeholders(int size) { return String.join(", ", Collections.nCopies(size, "?")); }
    private static DatabaseFailure fail(String message) { return new DatabaseFailure(message); }
}
