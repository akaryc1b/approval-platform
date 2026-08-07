package io.github.akaryc1b.approval.integration.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
abstract class MySqlInboxOutboxIntegrationSupport {

    static final Instant NOW = Instant.parse("2026-08-07T10:20:30.123456Z");

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("approval_mysql_integration")
        .withUsername("approval")
        .withPassword("approval")
        .withCommand(
            "--default-time-zone=+00:00",
            "--character-set-server=utf8mb4",
            "--collation-server=utf8mb4_0900_as_cs",
            "--transaction-isolation=READ-COMMITTED",
            "--innodb-strict-mode=ON",
            "--sql-mode=STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,"
                + "NO_ENGINE_SUBSTITUTION"
        );

    static DataSource dataSource;
    static JdbcTemplate jdbc;
    private ExecutorService executor;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
            MySqlInboxOutboxFixtures.configuredJdbcUrl(MYSQL),
            MYSQL.getUsername(),
            MYSQL.getPassword()
        );
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/mysqlmigration")
            .failOnMissingLocations(true)
            .validateMigrationNaming(true)
            .load()
            .migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void clean() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        jdbc.update("delete from ap_outbox");
        jdbc.update("delete from ap_inbox");
    }

    static JdbcInboxRepository inbox() {
        return new JdbcInboxRepository(dataSource);
    }

    static JdbcOutboxRepository outbox() {
        return new JdbcOutboxRepository(dataSource, new ObjectMapper());
    }

    <T> List<T> concurrently(CheckedSupplier<T> first, CheckedSupplier<T> second)
        throws Exception {
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<T> firstFuture = executor.submit(() -> run(ready, start, first));
        Future<T> secondFuture = executor.submit(() -> run(ready, start, second));
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        return List.of(
            firstFuture.get(30, TimeUnit.SECONDS),
            secondFuture.get(30, TimeUnit.SECONDS)
        );
    }

    private static <T> T run(
        CountDownLatch ready,
        CountDownLatch start,
        CheckedSupplier<T> supplier
    ) throws Exception {
        ready.countDown();
        if (!start.await(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException("start latch timed out");
        }
        return supplier.get();
    }

    @FunctionalInterface
    interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
