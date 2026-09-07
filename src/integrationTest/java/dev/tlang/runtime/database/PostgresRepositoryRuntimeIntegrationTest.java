package dev.tlang.runtime.database;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
final class PostgresRepositoryRuntimeIntegrationTest extends RepositoryRuntimeContract {
    @Container private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine")
        .withDatabaseName("repository_runtime").withUsername("tlang").withPassword("repository-runtime-password");

    @Override protected String connectionExpression() {
        return "db.open(\"postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)
            + "/" + POSTGRES.getDatabaseName() + "\", {username: \"" + POSTGRES.getUsername()
            + "\", password: \"" + POSTGRES.getPassword() + "\", poolSize: 4})";
    }
}
