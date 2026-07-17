package io.github.akaryc1b.approval.integration;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
class PostgreSqlContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_test")
        .withUsername("approval")
        .withPassword("approval");

    @Test
    void databaseAcceptsQueries() throws Exception {
        try (var connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        ); var statement = connection.createStatement(); var result = statement.executeQuery("select 1")) {
            result.next();
            assertEquals(1, result.getInt(1));
        }
    }
}
