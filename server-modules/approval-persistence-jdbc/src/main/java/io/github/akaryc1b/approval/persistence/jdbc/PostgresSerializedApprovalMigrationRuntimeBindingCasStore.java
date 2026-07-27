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
 * replay read. Closing the lock connection releases the lock even when the delegate fails. The
 * delegate retains ownership of its short platform transaction and never performs a Flowable call.
 */
public final class PostgresSerializedApprovalMigrationRuntimeBindingCasStore
    implements ApprovalMigrationRuntimeBindingCasStore {

    private static final String LOCK_SQL =
        "select pg_advisory_lock(hashtextextended(?, 0))";

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
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(LOCK_SQL)) {
            statement.setString(1, lockKey);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new BindingCasException("runtime binding CAS advisory lock was not acquired");
                }
            }
            return delegate.complete(request);
        } catch (BindingCasException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw new BindingCasException(
                "runtime binding CAS advisory lock failed",
                exception
            );
        }
    }
}
