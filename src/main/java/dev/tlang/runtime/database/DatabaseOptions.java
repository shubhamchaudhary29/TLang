package dev.tlang.runtime.database;

/** Validated db.open configuration. Secrets are deliberately omitted from string rendering. */
public final class DatabaseOptions {
    public static final int DEFAULT_POOL_SIZE = 10;
    public static final int DEFAULT_CONNECTION_TIMEOUT_MS = 5_000;
    public static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;

    private final String target;
    private final String username;
    private final String password;
    private final int poolSize;
    private final int connectionTimeoutMs;
    private final int queryTimeoutSeconds;

    public DatabaseOptions(
            String target,
            String username,
            String password,
            int poolSize,
            int connectionTimeoutMs,
            int queryTimeoutSeconds) {
        this.target = target;
        this.username = username;
        this.password = password;
        this.poolSize = poolSize;
        this.connectionTimeoutMs = connectionTimeoutMs;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public String target() { return target; }
    public String username() { return username; }
    public String password() { return password; }
    public int poolSize() { return poolSize; }
    public int connectionTimeoutMs() { return connectionTimeoutMs; }
    public int queryTimeoutSeconds() { return queryTimeoutSeconds; }

    @Override
    public String toString() {
        return "DatabaseOptions{target=<redacted>, username=<redacted>, password=<redacted>, "
            + "poolSize=" + poolSize + ", connectionTimeoutMs=" + connectionTimeoutMs
            + ", queryTimeoutSeconds=" + queryTimeoutSeconds + "}";
    }
}
