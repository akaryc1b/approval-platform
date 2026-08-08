package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.ProjectionConflictException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Bounded MySQL named locks released only after the owning local transaction completes. */
final class JdbcMySqlTransactionLockManager {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final String LOCK_NAMESPACE = "approval-platform:projection:";

    private final DataSource dataSource;
    private final int timeoutSeconds;
    private final Object transactionResourceKey = new Object();

    JdbcMySqlTransactionLockManager(DataSource dataSource) {
        this(dataSource, DEFAULT_TIMEOUT_SECONDS);
    }

    JdbcMySqlTransactionLockManager(DataSource dataSource, int timeoutSeconds) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        if (timeoutSeconds < 1 || timeoutSeconds > 120) {
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and 120");
        }
        this.timeoutSeconds = timeoutSeconds;
    }

    void acquire(String scope) {
        String exactScope = requireText(scope, "scope");
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                "MySQL projection serialization requires an active synchronized transaction"
            );
        }

        LockRegistry existing = registry();
        if (existing != null) {
            existing.acquire(lockName(exactScope), timeoutSeconds);
            return;
        }

        Connection connection = DataSourceUtils.getConnection(dataSource);
        LockRegistry created = new LockRegistry(connection);
        try {
            created.acquire(lockName(exactScope), timeoutSeconds);
            TransactionSynchronizationManager.bindResource(transactionResourceKey, created);
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        RuntimeException releaseFailure = null;
                        try {
                            created.releaseAll();
                        } catch (RuntimeException exception) {
                            releaseFailure = exception;
                        } finally {
                            TransactionSynchronizationManager.unbindResourceIfPossible(
                                transactionResourceKey
                            );
                        }
                        if (releaseFailure != null) {
                            throw releaseFailure;
                        }
                    }
                }
            );
        } catch (RuntimeException exception) {
            try {
                created.releaseAll();
            } catch (RuntimeException releaseFailure) {
                exception.addSuppressed(releaseFailure);
            }
            throw exception;
        }
    }

    private LockRegistry registry() {
        Object resource = TransactionSynchronizationManager.getResource(transactionResourceKey);
        if (resource == null) {
            return null;
        }
        if (resource instanceof LockRegistry registry) {
            return registry;
        }
        throw new IllegalStateException("unexpected MySQL projection lock transaction resource");
    }

    private static String lockName(String scope) {
        return sha256(LOCK_NAMESPACE + scope);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
                )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requireText(String value, String name) {
        String exact = Objects.requireNonNull(value, name + " must not be null").strip();
        if (exact.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return exact;
    }

    private static final class LockRegistry {

        private final Connection connection;
        private final Set<String> heldLocks = new LinkedHashSet<>();

        private LockRegistry(Connection connection) {
            this.connection = Objects.requireNonNull(
                connection,
                "connection must not be null"
            );
        }

        private void acquire(String lockName, int timeoutSeconds) {
            if (heldLocks.contains(lockName)) {
                return;
            }
            int result = executeLockFunction(
                "select get_lock(?, ?)",
                lockName,
                timeoutSeconds
            );
            if (result != 1) {
                throw new ProjectionConflictException(
                    "MySQL projection serialization lock could not be acquired"
                );
            }
            heldLocks.add(lockName);
        }

        private void releaseAll() {
            List<String> reverseOrder = new ArrayList<>(heldLocks);
            Collections.reverse(reverseOrder);
            RuntimeException failure = null;
            for (String lockName : reverseOrder) {
                try {
                    int result = executeLockFunction(
                        "select release_lock(?)",
                        lockName,
                        null
                    );
                    if (result != 1) {
                        throw new IllegalStateException(
                            "MySQL projection serialization lock was not released"
                        );
                    }
                } catch (RuntimeException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
            heldLocks.clear();
            if (failure != null) {
                throw failure;
            }
        }

        private int executeLockFunction(
            String sql,
            String lockName,
            Integer timeoutSeconds
        ) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, lockName);
                if (timeoutSeconds != null) {
                    statement.setInt(2, timeoutSeconds);
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalStateException(
                            "MySQL lock function did not return a result"
                        );
                    }
                    int result = resultSet.getInt(1);
                    if (resultSet.wasNull()) {
                        throw new IllegalStateException(
                            "MySQL lock function returned null"
                        );
                    }
                    return result;
                }
            } catch (SQLException exception) {
                throw new IllegalStateException(
                    "MySQL projection serialization lock operation failed",
                    exception
                );
            }
        }
    }
}
