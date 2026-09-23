package io.github.akaryc1b.approval.integration.jdbc;

import io.github.akaryc1b.approval.integration.outbox.OutboxBacklogReader;
import io.github.akaryc1b.approval.integration.outbox.SlaTimeoutNotificationConnector;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Objects;

/** PostgreSQL-only, one read-only aggregate statement; never claims or decodes an event. */
public final class JdbcOutboxBacklogReader implements OutboxBacklogReader {

    private static final String SNAPSHOT_SQL = """
        with tracked as (
            select *, connector_key = ? as timeout_notification
            from ap_outbox
            where status in ('PENDING', 'IN_FLIGHT', 'DEAD')
        )
        select statement_timestamp() as observed_at,
               count(*) filter (where status = 'PENDING') as pending,
               count(*) filter (where status = 'PENDING'
                   and available_at <= statement_timestamp()) as due,
               count(*) filter (where status = 'IN_FLIGHT') as in_flight,
               count(*) filter (where status = 'IN_FLIGHT'
                   and locked_until <= statement_timestamp()) as expired_leases,
               count(*) filter (where status = 'DEAD') as dead,
               greatest(0.0, coalesce(extract(epoch from statement_timestamp() -
                   min(created_at) filter (where status in ('PENDING', 'IN_FLIGHT'))), 0.0))
                   as oldest_seconds,
               count(*) filter (where timeout_notification and status = 'PENDING')
                   as notification_pending,
               count(*) filter (where timeout_notification and status = 'PENDING'
                   and available_at <= statement_timestamp()) as notification_due,
               count(*) filter (where timeout_notification and status = 'IN_FLIGHT')
                   as notification_in_flight,
               count(*) filter (where timeout_notification and status = 'IN_FLIGHT'
                   and locked_until <= statement_timestamp()) as notification_expired_leases,
               count(*) filter (where timeout_notification and status = 'DEAD')
                   as notification_dead,
               greatest(0.0, coalesce(extract(epoch from statement_timestamp() -
                   min(created_at) filter (where timeout_notification
                       and status in ('PENDING', 'IN_FLIGHT'))), 0.0))
                   as notification_oldest_seconds
        from tracked
        """;

    private final DataSource dataSource;

    public JdbcOutboxBacklogReader(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public Snapshot read() {
        // The runtime supplies its own pool. Do not join an approval transaction via DataSourceUtils.
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.getAutoCommit()) {
                throw new IllegalStateException("Outbox observation requires an independent connection");
            }
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(SNAPSHOT_SQL)) {
                statement.setString(1, SlaTimeoutNotificationConnector.ROUTE);
                statement.setQueryTimeout(2);
                statement.setMaxRows(1);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new IllegalStateException("Outbox aggregate row unavailable");
                    }
                    return new Snapshot(rows.getObject("observed_at", OffsetDateTime.class).toInstant(),
                        rows.getLong("pending"), rows.getLong("due"), rows.getLong("in_flight"),
                        rows.getLong("expired_leases"), rows.getLong("dead"), rows.getDouble("oldest_seconds"),
                        rows.getLong("notification_pending"), rows.getLong("notification_due"),
                        rows.getLong("notification_in_flight"), rows.getLong("notification_expired_leases"),
                        rows.getLong("notification_dead"), rows.getDouble("notification_oldest_seconds"));
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Outbox aggregate read failed", failure);
        }
    }
}
