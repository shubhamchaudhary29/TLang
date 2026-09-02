package dev.tlang.runtime.database;

import java.util.List;

/** Operations shared by a database handle and a pinned transaction. */
public interface DatabaseSession {
    List<Object> query(String sql, List<?> parameters);
    int execute(String sql, List<?> parameters);
    int lastInsertId();
    boolean isClosed();
}
