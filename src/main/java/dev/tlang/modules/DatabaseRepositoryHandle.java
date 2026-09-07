package dev.tlang.modules;

import dev.tlang.interpreter.NativeFunction;
import dev.tlang.interpreter.RuntimeCollections;
import dev.tlang.runtime.database.DatabaseRepository;
import dev.tlang.runtime.database.DatabaseSession;

import java.util.Map;

import static dev.tlang.modules.DatabaseQueryHandle.method;

/** Native repository adapter sharing M3's receiver, lifecycle, and safe error boundary. */
final class DatabaseRepositoryHandle {
    private DatabaseRepositoryHandle() {}

    static NativeFunction repository(DatabaseSession session) {
        return method("repository", 2, session, args -> handle(session,
            DatabaseRepository.define(session, args.get(1), args.get(2))));
    }

    private static Map<String, Object> handle(DatabaseSession session, DatabaseRepository repository) {
        Map<String, Object> result = RuntimeCollections.newMap();
        result.put("find", method("find", 1, session, args -> repository.find(args.get(1))));
        result.put("exists", method("exists", 1, session, args -> repository.exists(args.get(1))));
        result.put("create", method("create", 1, session, args -> repository.create(args.get(1))));
        result.put("update", method("update", 2, session, args -> repository.update(args.get(1), args.get(2))));
        result.put("delete", method("delete", 1, session, args -> repository.delete(args.get(1))));
        result.put("count", method("count", 0, session, args -> repository.count()));
        result.put("query", method("query", 0, session,
            args -> DatabaseQueryHandle.handle(session, repository.query())));
        return result;
    }
}
