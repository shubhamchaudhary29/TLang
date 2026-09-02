package dev.tlang.runtime.database;

/** A safe database failure whose message may cross the TLang runtime boundary. */
public final class DatabaseFailure extends RuntimeException {
    public DatabaseFailure(String message) {
        this(message, null);
    }

    public DatabaseFailure(String message, Throwable cause) {
        super(message, cause, false, false);
    }
}
