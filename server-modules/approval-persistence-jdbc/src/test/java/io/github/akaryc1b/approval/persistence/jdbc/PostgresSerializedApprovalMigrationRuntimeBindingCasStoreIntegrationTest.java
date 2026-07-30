package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationRuntimeBindingCasStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationRuntimeBindingCasStore.BindingCasException;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationRuntimeBindingCasStore.CompletionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class PostgresSerializedApprovalMigrationRuntimeBindingCasStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_migration_cas_serialization")
        .withUsername("approval")
        .withPassword("approval");

    @Test
    void sameTenantAndAttemptAreSerializedBeforeDelegateReplayRead() throws Exception {
        DataSource dataSource = dataSource();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);

        ApprovalMigrationRuntimeBindingCasStore delegate = request -> {
            int call = calls.incrementAndGet();
            int current = active.incrementAndGet();
            maximumActive.accumulateAndGet(current, Math::max);
            try {
                if (call == 1) {
                    firstEntered.countDown();
                    await(releaseFirst);
                }
                return null;
            } finally {
                active.decrementAndGet();
            }
        };
        var firstStore = new PostgresSerializedApprovalMigrationRuntimeBindingCasStore(
            dataSource,
            delegate
        );
        var secondStore = new PostgresSerializedApprovalMigrationRuntimeBindingCasStore(
            dataSource,
            delegate
        );

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> firstStore.complete(request()));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            Future<?> second = executor.submit(() -> {
                secondStarted.countDown();
                return secondStore.complete(request());
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));

            Thread.sleep(250);
            assertEquals(1, calls.get());
            assertEquals(1, maximumActive.get());

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }

        assertEquals(2, calls.get());
        assertEquals(1, maximumActive.get());
    }

    @Test
    void closingFailedLockSessionReleasesAttemptForNextCaller() {
        DataSource dataSource = dataSource();
        AtomicInteger calls = new AtomicInteger();
        ApprovalMigrationRuntimeBindingCasStore delegate = request -> {
            if (calls.incrementAndGet() == 1) {
                throw new BindingCasException("expected delegate failure");
            }
            return null;
        };
        var store = new PostgresSerializedApprovalMigrationRuntimeBindingCasStore(
            dataSource,
            delegate
        );

        assertThrows(BindingCasException.class, () -> store.complete(request()));
        assertDoesNotThrow(() -> store.complete(request()));
        assertEquals(2, calls.get());
    }

    private static CompletionRequest request() {
        return new CompletionRequest(
            "tenant-cas-serialization",
            UUID.fromString("55000000-0000-0000-0000-000000000001"),
            UUID.fromString("55000000-0000-0000-0000-000000000002"),
            "worker-cas-serialization",
            4,
            1,
            1,
            Instant.parse("2026-07-27T00:00:00Z"),
            "request-cas-serialization",
            "trace-cas-serialization"
        );
    }

    private static DataSource dataSource() {
        return new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out awaiting concurrent CAS test gate");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrent CAS test was interrupted", exception);
        }
    }
}
