package dev.tlang.modules;

import dev.tlang.errors.RuntimeError;
import dev.tlang.errors.RuntimeErrorKind;
import dev.tlang.interpreter.NativeFunction;
import dev.tlang.interpreter.RuntimeCollections;
import dev.tlang.interpreter.Interpreter;
import dev.tlang.lexer.Token;
import dev.tlang.runtime.database.DatabaseConnection;
import dev.tlang.runtime.database.DatabaseFailure;
import dev.tlang.runtime.database.DatabaseOptions;
import dev.tlang.runtime.database.DatabaseProvider;
import dev.tlang.runtime.database.DatabaseSession;
import dev.tlang.runtime.database.DatabaseTransaction;
import dev.tlang.runtime.database.PostgresProvider;
import dev.tlang.runtime.database.SqliteProvider;
import dev.tlang.types.Type;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The single public db module, backed by focused provider implementations. */
public final class DatabaseModule implements NativeModule {
    private static final Set<String> OPTION_NAMES = Set.of(
        "username", "password", "poolSize", "connectionTimeoutMs", "queryTimeoutSeconds");

    private final Map<String, Object> exports = new LinkedHashMap<>();
    private final List<DatabaseProvider> providers = List.of(
        new PostgresProvider(),
        new SqliteProvider());

    public DatabaseModule() {
        exports.put("open", new NativeFunction("open", 1, 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                return open(args, token, null);
            }

            @Override
            public Object call(Interpreter interpreter, List<Object> args, Token token) {
                return open(args, token, interpreter);
            }

            private Object open(List<Object> args, Token token, Interpreter interpreter) {
                String target = requireString(args.get(0), token, "Database target must be a string.");
                Map<String, Object> supplied = args.size() == 1
                    ? Map.of()
                    : requireOptions(args.get(1), token);
                DatabaseOptions options = parseOptions(target, supplied, token);
                DatabaseProvider provider = providers.stream()
                    .filter(candidate -> candidate.accepts(target))
                    .findFirst()
                    .orElseThrow(() -> databaseError(token, "Unsupported database target."));
                try {
                    DatabaseConnection connection = provider.open(options);
                    if (interpreter != null) {
                        interpreter.registerCursorResource(() -> {
                            try {
                                connection.close();
                            } catch (DatabaseFailure failure) {
                                throw databaseError(token, failure);
                            }
                        });
                    }
                    return connectionMap(connection);
                } catch (DatabaseFailure failure) {
                    throw databaseError(token, failure);
                }
            }
        });
    }

    @Override
    public Map<String, Object> getExports() {
        return exports;
    }

    private static Map<String, Object> connectionMap(DatabaseConnection connection) {
        Map<String, Object> result = sessionMap(connection, null);
        result.put("provider", connection.providerName());
        result.put("begin", new NativeFunction("begin", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                return begin(token, null);
            }

            @Override
            public Object call(Interpreter interpreter, List<Object> args, Token token) {
                return begin(token, interpreter);
            }

            private Object begin(Token token, Interpreter interpreter) {
                try {
                    DatabaseTransaction transaction = connection.begin();
                    if (interpreter != null) {
                        interpreter.registerCursorResource(transaction::abort);
                    }
                    return transactionMap(transaction);
                } catch (DatabaseFailure failure) {
                    throw databaseError(token, failure);
                }
            }
        }.setExpectsReceiver(true));
        result.put("close", new NativeFunction("close", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                try {
                    connection.close();
                    return null;
                } catch (DatabaseFailure failure) {
                    throw databaseError(token, failure);
                }
            }
        }.setExpectsReceiver(true));
        return result;
    }

    private static Map<String, Object> transactionMap(DatabaseTransaction transaction) {
        Map<String, Object> result = sessionMap(transaction, transaction);
        result.put("commit", transactionEnd("commit", transaction, true));
        result.put("rollback", transactionEnd("rollback", transaction, false));
        return result;
    }

    private static NativeFunction transactionEnd(
            String name, DatabaseTransaction transaction, boolean commit) {
        return new NativeFunction(name, 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                try {
                    if (commit) transaction.commit(); else transaction.rollback();
                    return null;
                } catch (DatabaseFailure failure) {
                    throw databaseError(token, failure);
                }
            }
        }.setExpectsReceiver(true);
    }

    private static Map<String, Object> sessionMap(
            DatabaseSession session, DatabaseTransaction transaction) {
        Map<String, Object> result = RuntimeCollections.newMap();
        result.put("query", new NativeFunction("query", 3) {
            @Override
            public Object call(List<Object> args, Token token) {
                try {
                    String sql = requireString(args.get(1), token, "SQL query must be a string.");
                    return session.query(sql, requireParameters(args.get(2), token));
                } catch (RuntimeError failure) {
                    abort(transaction);
                    throw failure;
                } catch (DatabaseFailure failure) {
                    abort(transaction);
                    throw databaseError(token, failure);
                }
            }
        }.setExpectsReceiver(true));

        NativeFunction execute = new NativeFunction("execute", 3) {
            @Override
            public Object call(List<Object> args, Token token) {
                try {
                    String sql = requireString(args.get(1), token, "SQL query must be a string.");
                    return session.execute(sql, requireParameters(args.get(2), token));
                } catch (RuntimeError failure) {
                    abort(transaction);
                    throw failure;
                } catch (DatabaseFailure failure) {
                    abort(transaction);
                    throw databaseError(token, failure);
                }
            }
        }.setExpectsReceiver(true);
        result.put("execute", execute);
        result.put("insert", execute);
        result.put("update", execute);
        result.put("delete", execute);
        result.put("lastInsertId", new NativeFunction("lastInsertId", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                try {
                    return session.lastInsertId();
                } catch (DatabaseFailure failure) {
                    abort(transaction);
                    throw databaseError(token, failure);
                }
            }
        }.setExpectsReceiver(true));
        return result;
    }

    private static void abort(DatabaseTransaction transaction) {
        if (transaction != null) transaction.abort();
    }

    private static Map<String, Object> requireOptions(Object value, Token token) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw databaseError(token, "Database options must be a map.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> options = RuntimeCollections.snapshot((Map<String, Object>) raw);
        for (String key : options.keySet()) {
            if (!OPTION_NAMES.contains(key)) {
                throw databaseError(token, "Unknown database option '" + key + "'.");
            }
        }
        return options;
    }

    private static DatabaseOptions parseOptions(
            String target, Map<String, Object> options, Token token) {
        return new DatabaseOptions(
            target,
            optionalString(options, "username", token),
            optionalString(options, "password", token),
            optionalInteger(options, "poolSize", DatabaseOptions.DEFAULT_POOL_SIZE, 1, 64, token),
            optionalInteger(options, "connectionTimeoutMs",
                DatabaseOptions.DEFAULT_CONNECTION_TIMEOUT_MS, 250, 120_000, token),
            optionalInteger(options, "queryTimeoutSeconds",
                DatabaseOptions.DEFAULT_QUERY_TIMEOUT_SECONDS, 1, 3_600, token));
    }

    private static String optionalString(Map<String, Object> options, String key, Token token) {
        if (!options.containsKey(key)) return null;
        Object value = options.get(key);
        if (!(value instanceof String string)) {
            throw databaseError(token, "Database option '" + key + "' must be a string.");
        }
        if (string.chars().anyMatch(Character::isISOControl)) {
            throw databaseError(token, "Database option '" + key + "' contains invalid characters.");
        }
        return string;
    }

    private static int optionalInteger(
            Map<String, Object> options,
            String key,
            int defaultValue,
            int minimum,
            int maximum,
            Token token) {
        if (!options.containsKey(key)) return defaultValue;
        Object value = options.get(key);
        if (!(value instanceof Integer integer) || integer < minimum || integer > maximum) {
            throw databaseError(token,
                "Database option '" + key + "' must be an integer from "
                    + minimum + " to " + maximum + ".");
        }
        return integer;
    }

    private static String requireString(Object value, Token token, String message) {
        if (Type.of(value) != Type.STRING) throw databaseError(token, message);
        return (String) value;
    }

    private static List<?> requireParameters(Object value, Token token) {
        if (!(value instanceof List<?> list)) {
            throw databaseError(token, "Parameters must be a list.");
        }
        return RuntimeCollections.snapshot(list);
    }

    private static RuntimeError databaseError(Token token, String message) {
        return new RuntimeError(RuntimeErrorKind.DATABASE_ERROR, token, message);
    }

    private static RuntimeError databaseError(Token token, DatabaseFailure failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        return new RuntimeError(RuntimeErrorKind.DATABASE_ERROR, token, failure.getMessage(), cause);
    }
}
