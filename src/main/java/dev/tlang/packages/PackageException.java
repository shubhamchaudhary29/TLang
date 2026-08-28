package dev.tlang.packages;

/** A safe, user-facing package-management failure. */
public final class PackageException extends RuntimeException {
    public PackageException(String message) {
        super(message);
    }

    public PackageException(String message, Throwable cause) {
        super(message, cause);
    }
}
