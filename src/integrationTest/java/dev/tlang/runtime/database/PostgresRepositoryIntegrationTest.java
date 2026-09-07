package dev.tlang.runtime.database;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
final class PostgresRepositoryIntegrationTest extends RepositoryContract {
    @Container private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine")
        .withDatabaseName("repositories").withUsername("tlang").withPassword("repository-test-password");

    @org.junit.jupiter.api.Test void descriptorsDoNotBorrowAndPoolRecoversAfterExhaustion() {
        var connection = new PostgresProvider().open(new DatabaseOptions(
            "postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName(),
            POSTGRES.getUsername(), POSTGRES.getPassword(), 2, 250, 5));
        try {
            connection.execute("CREATE TABLE repo_pool (id INTEGER PRIMARY KEY)", java.util.List.of());
            var first = connection.begin();
            var second = connection.begin();
            try {
                var repository = DatabaseRepository.define(connection, "repo_pool",
                    java.util.Map.of("primaryKey", "id", "fields", java.util.List.of("id")));
                org.junit.jupiter.api.Assertions.assertNotNull(repository.query());
                org.junit.jupiter.api.Assertions.assertThrows(DatabaseFailure.class, repository::count);
                first.rollback();
                for (int i = 0; i < 20; i++) {
                    org.junit.jupiter.api.Assertions.assertFalse(repository.exists(i));
                    org.junit.jupiter.api.Assertions.assertEquals(0, repository.count());
                }
            } finally { first.abort(); second.abort(); }
        } finally { connection.close(); }
    }

    @Override protected DatabaseConnection open() {
        return new PostgresProvider().open(new DatabaseOptions(
            "postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName(),
            POSTGRES.getUsername(), POSTGRES.getPassword(), 4, 5000, 5));
    }
}
