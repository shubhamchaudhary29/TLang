package dev.tlang.runtime.database;

/** A transaction that owns one JDBC connection until completion. */
public interface DatabaseTransaction extends DatabaseSession {
    void commit();
    void rollback();

    /** Roll back after a TLang-side validation or execution failure. */
    void abort();
}
