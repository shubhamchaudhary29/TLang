package dev.tlang.modules;

import dev.tlang.errors.RuntimeError;
import dev.tlang.errors.RuntimeErrorKind;
import dev.tlang.interpreter.NativeFunction;
import dev.tlang.interpreter.RuntimeCollections;
import dev.tlang.lexer.Token;
import dev.tlang.runtime.database.DatabaseFailure;
import dev.tlang.runtime.database.DatabaseQuery;
import dev.tlang.runtime.database.DatabaseSession;
import dev.tlang.runtime.database.DatabaseTransaction;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** TLang method adapters; all handles capture immutable intent and the existing session. */
final class DatabaseQueryHandle {
    private DatabaseQueryHandle() {}

    static NativeFunction table(DatabaseSession session) {
        return method("table", 1, session, args -> handle(session, DatabaseQuery.table(args.get(1))));
    }

    static Map<String, Object> handle(DatabaseSession session, DatabaseQuery query) {
        Map<String, Object> result = RuntimeCollections.newMap();
        result.put("select", method("select", 1, session, a -> handle(session, query.select(a.get(1)))));
        result.put("where", method("where", 3, session, a -> handle(session, query.where(a.get(1), a.get(2), a.get(3)))));
        result.put("whereIn", method("whereIn", 2, session, a -> handle(session, query.whereIn(a.get(1), a.get(2)))));
        result.put("orderBy", method("orderBy", 2, session, a -> handle(session, query.orderBy(a.get(1), a.get(2)))));
        result.put("limit", method("limit", 1, session, a -> handle(session, query.limit(a.get(1)))));
        result.put("offset", method("offset", 1, session, a -> handle(session, query.offset(a.get(1)))));
        result.put("all", method("all", 0, session, a -> query.all(session)));
        result.put("first", method("first", 0, session, a -> query.first(session)));
        result.put("count", method("count", 0, session, a -> query.count(session)));
        result.put("insert", method("insert", 1, session, a -> query.insert(session, a.get(1))));
        result.put("update", method("update", 1, session, a -> query.update(session, a.get(1))));
        result.put("delete", method("delete", 0, session, a -> query.delete(session)));
        return result;
    }

    static NativeFunction method(String name, int arity, DatabaseSession session,
                                         Function<List<Object>, Object> action) {
        return new NativeFunction(name, arity + 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                try {
                    if (session.isClosed()) throw new DatabaseFailure("Database session is closed.");
                    return action.apply(args);
                } catch (DatabaseFailure failure) {
                    if (session instanceof DatabaseTransaction transaction) transaction.abort();
                    throw new RuntimeError(RuntimeErrorKind.DATABASE_ERROR, token, failure.getMessage());
                }
            }
        }.setExpectsReceiver(true);
    }
}
