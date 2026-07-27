package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Supplier;

/** Cross-node serialization before any D7 orchestration replay read. */
public final class PostgresSerializedApprovalMigrationOrchestrationStore
    implements ApprovalMigrationOrchestrationStore {

    private static final String LOCK_SQL =
        "select pg_advisory_lock(hashtextextended(?, 0))";
    private static final String UNLOCK_SQL =
        "select pg_advisory_unlock(hashtextextended(?, 0))";

    private final DataSource dataSource;
    private final ApprovalMigrationOrchestrationStore delegate;

    public PostgresSerializedApprovalMigrationOrchestrationStore(
        DataSource dataSource,
        ApprovalMigrationOrchestrationStore delegate
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public PreparedOrchestration prepare(PrepareRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return serialized(
            "approval-migration-orchestration-intent:"
                + request.tenantId() + ':' + request.intentId(),
            () -> delegate.prepare(request)
        );
    }

    @Override
    public DispatchAuthorization authorizeDispatch(DispatchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return serialized(
            "approval-migration-orchestration-run:"
                + request.run().tenantId() + ':' + request.run().runId(),
            () -> delegate.authorizeDispatch(request)
        );
    }

    @Override
    public FinalizedOrchestration finalizeRun(FinalizeRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return serialized(
            "approval-migration-orchestration-run:"
                + request.prepared().run().tenantId() + ':'
                + request.prepared().run().runId(),
            () -> delegate.finalizeRun(request)
        );
    }

    private <T> T serialized(String lockKey, Supplier<T> operation) {
        try (Connection connection = dataSource.getConnection()) {
            acquire(connection, lockKey);
            RuntimeException operationFailure = null;
            try {
                return operation.get();
            } catch (RuntimeException exception) {
                operationFailure = exception;
                throw exception;
            } finally {
                try {
                    release(connection, lockKey);
                } catch (SQLException exception) {
                    if (operationFailure != null) {
                        operationFailure.addSuppressed(exception);
                    } else {
                        throw exception;
                    }
                }
            }
        } catch (OrchestrationConflictException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw new OrchestrationConflictException(
                "orchestration advisory lock failed; exact replay is required",
                exception
            );
        }
    }

    private static void acquire(Connection connection, String lockKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_SQL)) {
            statement.setString(1, lockKey);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("orchestration advisory lock returned no row");
                }
            }
        }
    }

    private static void release(Connection connection, String lockKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UNLOCK_SQL)) {
            statement.setString(1, lockKey);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new SQLException("orchestration advisory lock was not released");
                }
            }
        }
    }
}
