package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationRuntimeBindingCasStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Cross-node, per-attempt serialization boundary for D5 completion replay.
 *
 * <p>The dedicated session-scoped advisory lock is acquired before the delegate performs any
 * replay read and is explicitly released before the connection is returned to a pool. The delegate
 * retains ownership of its short platform transaction and never performs a Flowable call.
 */
public final class PostgresSerializedApprovalMigrationRuntimeBindingCasStore
    implements ApprovalMigrationRuntimeBindingCasStore {

    private static final String LOCK_SQL =
        "select pg_advisory_lock(hashtextextended(?, 0))";
    private static final String UNLOCK_SQL =
        "select pg_advisory_unlock(hashtextextended(?, 0))";

    private final DataSource dataSource;
    private final ApprovalMigrationRuntimeBindingCasStore delegate;

    public PostgresSerializedApprovalMigrationRuntimeBindingCasStore(
        DataSource dataSource,
        ApprovalMigrationRuntimeBindingCasStore delegate
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public BindingCasResult complete(CompletionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String lockKey = "approval-migration-binding-cas:"
            + request.tenantId()
            + ':'
            + request.attemptId();
        try (Connection connection = dataSource.getConnection()) {
            acquire(connection, lockKey);
            RuntimeException delegateFailure = null;
            try {
                return delegate.complete(request);
            } catch (RuntimeException exception) {
                delegateFailure = exception;
                throw exception;
            } finally {
                try {
                    release(connection, lockKey);
                } catch (SQLException exception) {
                    if (delegateFailure != null) {
                        delegateFailure.addSuppressed(exception);
                    } else {
                        throw exception;
                    }
                }
            }
        } catch (BindingCasException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw new BindingCasException(
                "runtime binding CAS advisory lock failed; exact replay is required",
                exception
            );
        }
    }

    private static void acquire(Connection connection, String lockKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_SQL)) {
            statement.setString(1, lockKey);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("runtime binding CAS advisory lock returned no row");
                }
            }
        }
    }

    private static void release(Connection connection, String lockKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UNLOCK_SQL)) {
            statement.setString(1, lockKey);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new SQLException("runtime binding CAS advisory lock was not released");
                }
            }
        }
    }
}
