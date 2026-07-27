package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/** Cross-node tenant/plan serialization before any D8 replay or revision read. */
public final class PostgresSerializedApprovalMigrationPlanAggregationStore
    implements ApprovalMigrationPlanAggregationStore {

    private static final String LOCK_SQL =
        "select pg_advisory_lock(hashtextextended(?, 0))";
    private static final String UNLOCK_SQL =
        "select pg_advisory_unlock(hashtextextended(?, 0))";

    private final DataSource dataSource;
    private final ApprovalMigrationPlanAggregationStore delegate;

    public PostgresSerializedApprovalMigrationPlanAggregationStore(
        DataSource dataSource,
        ApprovalMigrationPlanAggregationStore delegate
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public AggregationResult aggregate(AggregationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String lockKey = "approval-migration-plan-aggregation:"
            + request.tenantId() + ':' + request.planId();
        try (Connection connection = dataSource.getConnection()) {
            acquire(connection, lockKey);
            RuntimeException delegateFailure = null;
            try {
                return delegate.aggregate(request);
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
        } catch (AggregationConflictException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw new AggregationConflictException(
                "plan aggregation advisory lock failed; exact replay is required",
                exception
            );
        }
    }

    private static void acquire(Connection connection, String lockKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_SQL)) {
            statement.setString(1, lockKey);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("plan aggregation advisory lock returned no row");
                }
            }
        }
    }

    private static void release(Connection connection, String lockKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UNLOCK_SQL)) {
            statement.setString(1, lockKey);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new SQLException("plan aggregation advisory lock was not released");
                }
            }
        }
    }
}
