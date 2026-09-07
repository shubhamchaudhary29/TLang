package dev.tlang.runtime.database;

import java.util.List;
import java.util.function.Supplier;

/** Thin session-bound repository. SQL construction, binding, and execution belong to M3/M1. */
public final class DatabaseRepository {
    private final DatabaseSession session;
    private final RepositoryDefinition definition;

    private DatabaseRepository(DatabaseSession session, RepositoryDefinition definition) {
        this.session = session;
        this.definition = definition;
    }

    public static DatabaseRepository define(DatabaseSession session, Object table, Object descriptor) {
        return inSession(session, () -> new DatabaseRepository(session, RepositoryDefinition.parse(table, descriptor)));
    }

    public Object find(Object id) {
        return inSession(session, () -> byId(id).select(definition.fields()).first(session));
    }

    public boolean exists(Object id) {
        return inSession(session, () -> byId(id).select(List.of(definition.primaryKey())).first(session) != null);
    }

    public int create(Object fields) {
        return inSession(session, () -> table().insert(session, definition.mutation(fields, false)));
    }

    public int update(Object id, Object fields) {
        return inSession(session, () -> byId(id).update(session, definition.mutation(fields, true)));
    }

    public int delete(Object id) {
        return inSession(session, () -> byId(id).delete(session));
    }

    public int count() {
        return inSession(session, () -> table().count(session));
    }

    /** Explicit escape to an ordinary M3 builder; only its initial projection is constrained. */
    public DatabaseQuery query() {
        return inSession(session, () -> table().select(definition.fields()));
    }

    private DatabaseQuery table() { return DatabaseQuery.table(definition.table()); }

    private DatabaseQuery byId(Object id) {
        if (id == null) throw new DatabaseFailure("Repository ID must not be nil.");
        return table().where(definition.primaryKey(), "=", id);
    }

    private static <T> T inSession(DatabaseSession session, Supplier<T> operation) {
        try {
            if (session.isClosed()) throw new DatabaseFailure("Database session is closed.");
            return operation.get();
        } catch (DatabaseFailure failure) {
            if (session instanceof DatabaseTransaction transaction) transaction.abort();
            throw failure;
        }
    }
}
