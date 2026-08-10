package dev.tlang.modules;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.tlang.lexer.Token;
import dev.tlang.types.Type;
import dev.tlang.errors.RuntimeError;
import dev.tlang.interpreter.NativeFunction;

public final class DatabaseModule implements NativeModule {
    private final Map<String, Object> exports = new LinkedHashMap<>();

    public DatabaseModule() {
        exports.put("open", new NativeFunction("open", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object pathObj = args.get(0);
                if (Type.of(pathObj) != Type.STRING) {
                    throw new RuntimeError(token, "Database path must be a string.");
                }
                String path = (String) pathObj;
                String url = "jdbc:sqlite:" + path;

                try {
                    Class.forName("org.sqlite.JDBC");
                    Connection conn = DriverManager.getConnection(url);
                    final Connection[] connRef = new Connection[]{conn};

                    Map<String, Object> connMap = new LinkedHashMap<>();

                    connMap.put("query", new NativeFunction("query", 3) {
                        @Override
                        public Object call(List<Object> subArgs, Token subToken) {
                            Connection connVal = connRef[0];
                            if (connVal == null) {
                                throw new RuntimeError(subToken, "Connection is closed.");
                            }
                            try {
                                if (connVal.isClosed()) {
                                    throw new RuntimeError(subToken, "Connection is closed.");
                                }
                            } catch (SQLException e) {
                                throw new RuntimeError(subToken, "Database error: " + e.getMessage());
                            }

                            Object sqlObj = subArgs.get(1);
                            Object paramsObj = subArgs.get(2);
                            if (Type.of(sqlObj) != Type.STRING) {
                                throw new RuntimeError(subToken, "SQL query must be a string.");
                            }
                            if (!(paramsObj instanceof List)) {
                                throw new RuntimeError(subToken, "Parameters must be a list.");
                            }
                            String sql = (String) sqlObj;
                            List<?> params = (List<?>) paramsObj;

                            // Validate parameter counts
                            int expectedParams = 0;
                            boolean inSingleQuotes = false;
                            boolean inDoubleQuotes = false;
                            for (int i = 0; i < sql.length(); i++) {
                                char c = sql.charAt(i);
                                if (c == '\'' && !inDoubleQuotes) {
                                    inSingleQuotes = !inSingleQuotes;
                                } else if (c == '"' && !inSingleQuotes) {
                                    inDoubleQuotes = !inDoubleQuotes;
                                } else if (c == '?' && !inSingleQuotes && !inDoubleQuotes) {
                                    expectedParams++;
                                }
                            }

                            if (expectedParams != params.size()) {
                                throw new RuntimeError(subToken, "Expected " + expectedParams + " parameters, but got " + params.size() + ".");
                            }

                            try (PreparedStatement stmt = connVal.prepareStatement(sql)) {
                                for (int i = 0; i < params.size(); i++) {
                                    Object param = params.get(i);
                                    int paramIdx = i + 1;
                                    if (param == null) {
                                        stmt.setNull(paramIdx, Types.NULL);
                                    } else if (param instanceof Integer) {
                                        stmt.setInt(paramIdx, (Integer) param);
                                    } else if (param instanceof String) {
                                        stmt.setString(paramIdx, (String) param);
                                    } else if (param instanceof Boolean) {
                                        stmt.setInt(paramIdx, (Boolean) param ? 1 : 0);
                                    } else {
                                        throw new RuntimeError(subToken, "Unsupported parameter type: " + Type.of(param));
                                    }
                                }

                                try (ResultSet rs = stmt.executeQuery()) {
                                    ResultSetMetaData md = rs.getMetaData();
                                    int columns = md.getColumnCount();
                                    List<Object> rows = new ArrayList<>();

                                    while (rs.next()) {
                                        Map<String, Object> row = new LinkedHashMap<>();
                                        for (int i = 1; i <= columns; i++) {
                                            Object val = rs.getObject(i);
                                            Object tlangVal;
                                            if (val == null) {
                                                tlangVal = null;
                                            } else if (val instanceof Integer) {
                                                tlangVal = val;
                                            } else if (val instanceof Long) {
                                                long longVal = (Long) val;
                                                if (longVal < Integer.MIN_VALUE || longVal > Integer.MAX_VALUE) {
                                                    throw new RuntimeError(subToken, "Integer overflow: SQLite value " + longVal + " does not fit in a 32-bit integer.");
                                                }
                                                tlangVal = (int) longVal;
                                            } else if (val instanceof Double || val instanceof Float) {
                                                double dVal = ((Number) val).doubleValue();
                                                if (dVal % 1 != 0) {
                                                    throw new RuntimeError(subToken, "Floating point values with nonzero fractional parts (like " + dVal + ") are not supported in TLang.");
                                                }
                                                if (dVal < Integer.MIN_VALUE || dVal > Integer.MAX_VALUE) {
                                                    throw new RuntimeError(subToken, "Integer overflow: SQLite REAL value " + dVal + " does not fit in a 32-bit integer.");
                                                }
                                                tlangVal = (int) dVal;
                                            } else if (val instanceof String) {
                                                tlangVal = val;
                                            } else if (val instanceof byte[]) {
                                                throw new RuntimeError(subToken, "BLOB type is not supported in this version of TLang database wrapper (future work).");
                                            } else {
                                                if (val instanceof Boolean) {
                                                    tlangVal = (Boolean) val ? 1 : 0;
                                                } else {
                                                    throw new RuntimeError(subToken, "Unsupported database value type: " + val.getClass().getName());
                                                }
                                            }
                                            row.put(md.getColumnLabel(i), tlangVal);
                                        }
                                        rows.add(row);
                                    }
                                    return rows;
                                }
                            } catch (SQLException e) {
                                throw new RuntimeError(subToken, "Database error: " + e.getMessage());
                            }
                        }
                    }.setExpectsReceiver(true));

                    NativeFunction executeFn = new NativeFunction("execute", 3) {
                        @Override
                        public Object call(List<Object> subArgs, Token subToken) {
                            Connection connVal = connRef[0];
                            if (connVal == null) {
                                throw new RuntimeError(subToken, "Connection is closed.");
                            }
                            try {
                                if (connVal.isClosed()) {
                                    throw new RuntimeError(subToken, "Connection is closed.");
                                }
                            } catch (SQLException e) {
                                throw new RuntimeError(subToken, "Database error: " + e.getMessage());
                            }

                            Object sqlObj = subArgs.get(1);
                            Object paramsObj = subArgs.get(2);
                            if (Type.of(sqlObj) != Type.STRING) {
                                throw new RuntimeError(subToken, "SQL query must be a string.");
                            }
                            if (!(paramsObj instanceof List)) {
                                throw new RuntimeError(subToken, "Parameters must be a list.");
                            }
                            String sql = (String) sqlObj;
                            List<?> params = (List<?>) paramsObj;

                            // Validate parameter counts
                            int expectedParams = 0;
                            boolean inSingleQuotes = false;
                            boolean inDoubleQuotes = false;
                            for (int i = 0; i < sql.length(); i++) {
                                char c = sql.charAt(i);
                                if (c == '\'' && !inDoubleQuotes) {
                                    inSingleQuotes = !inSingleQuotes;
                                } else if (c == '"' && !inSingleQuotes) {
                                    inDoubleQuotes = !inDoubleQuotes;
                                } else if (c == '?' && !inSingleQuotes && !inDoubleQuotes) {
                                    expectedParams++;
                                }
                            }

                            if (expectedParams != params.size()) {
                                throw new RuntimeError(subToken, "Expected " + expectedParams + " parameters, but got " + params.size() + ".");
                            }

                            try (PreparedStatement stmt = connVal.prepareStatement(sql)) {
                                for (int i = 0; i < params.size(); i++) {
                                    Object param = params.get(i);
                                    int paramIdx = i + 1;
                                    if (param == null) {
                                        stmt.setNull(paramIdx, Types.NULL);
                                    } else if (param instanceof Integer) {
                                        stmt.setInt(paramIdx, (Integer) param);
                                    } else if (param instanceof String) {
                                        stmt.setString(paramIdx, (String) param);
                                    } else if (param instanceof Boolean) {
                                        stmt.setInt(paramIdx, (Boolean) param ? 1 : 0);
                                    } else {
                                        throw new RuntimeError(subToken, "Unsupported parameter type: " + Type.of(param));
                                    }
                                }

                                int affectedRows = stmt.executeUpdate();
                                return affectedRows;
                            } catch (SQLException e) {
                                throw new RuntimeError(subToken, "Database error: " + e.getMessage());
                            }
                        }
                    }.setExpectsReceiver(true);

                    connMap.put("execute", executeFn);
                    connMap.put("insert", executeFn);
                    connMap.put("update", executeFn);
                    connMap.put("delete", executeFn);

                    connMap.put("lastInsertId", new NativeFunction("lastInsertId", 1) {
                        @Override
                        public Object call(List<Object> subArgs, Token subToken) {
                            Connection connVal = connRef[0];
                            if (connVal == null) {
                                throw new RuntimeError(subToken, "Database connection is closed.");
                            }
                            try (PreparedStatement stmt = connVal.prepareStatement("SELECT last_insert_rowid()");
                                 ResultSet rs = stmt.executeQuery()) {
                                if (rs.next()) {
                                    return rs.getInt(1);
                                }
                                throw new RuntimeError(subToken, "Failed to retrieve last insert ID.");
                            } catch (SQLException e) {
                                throw new RuntimeError(subToken, "Database error: " + e.getMessage());
                            }
                        }
                    }.setExpectsReceiver(true));

                    connMap.put("close", new NativeFunction("close", 1) {
                        @Override
                        public Object call(List<Object> subArgs, Token subToken) {
                            Connection connVal = connRef[0];
                            if (connVal != null) {
                                try {
                                    connVal.close();
                                } catch (SQLException e) {
                                    throw new RuntimeError(subToken, "Database error during close: " + e.getMessage());
                                } finally {
                                    connRef[0] = null;
                                }
                            }
                            return null;
                        }
                    }.setExpectsReceiver(true));

                    return connMap;
                } catch (ClassNotFoundException e) {
                    throw new RuntimeError(token, "SQLite JDBC driver not found on classpath: " + e.getMessage());
                } catch (SQLException e) {
                    throw new RuntimeError(token, "Database connection error: " + e.getMessage());
                }
            }
        });
    }

    @Override
    public Map<String, Object> getExports() {
        return exports;
    }
}
