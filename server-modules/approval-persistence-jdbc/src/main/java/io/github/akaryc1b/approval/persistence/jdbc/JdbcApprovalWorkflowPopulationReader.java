package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalWorkflowPopulationReader;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Objects;

/** One PostgreSQL snapshot, fixed-size result, no business transaction or Flowable table access. */
public final class JdbcApprovalWorkflowPopulationReader implements ApprovalWorkflowPopulationReader {
    private static final String SQL = """
        with live_targets as (
            select tenant_id, instance_id, null::uuid as task_id, 'PROCESS' as target_type
            from ap_approval_instance where status = 'RUNNING'
            union all
            select t.tenant_id, t.instance_id, t.task_id, 'TASK' as target_type
            from ap_approval_task t
            join ap_approval_instance p on p.tenant_id = t.tenant_id and p.instance_id = t.instance_id
            where p.status = 'RUNNING' and t.status in ('PENDING', 'COMPLETING')
        ), observed_targets as (
            select t.target_type,
                exists (
                    select 1 from ap_sla_instance s
                    where s.tenant_id = t.tenant_id and s.approval_instance_id = t.instance_id
                      and s.target_type = t.target_type and s.task_id is not distinct from t.task_id
                      and s.collaboration_participant_id is null and s.status in ('ACTIVE', 'PAUSED')
                ) as covered,
                exists (
                    select 1 from ap_sla_instance s
                    where s.tenant_id = t.tenant_id and s.approval_instance_id = t.instance_id
                      and s.target_type = t.target_type and s.task_id is not distinct from t.task_id
                      and s.collaboration_participant_id is null and s.status = 'ACTIVE'
                      and s.overdue_at <= statement_timestamp()
                ) as overdue
            from live_targets t
        )
        select statement_timestamp() as observed_at,
            count(*) filter (where target_type = 'PROCESS') as active_processes,
            count(*) filter (where target_type = 'PROCESS' and covered) as covered_processes,
            count(*) filter (where target_type = 'PROCESS' and overdue) as overdue_processes,
            count(*) filter (where target_type = 'TASK') as active_tasks,
            count(*) filter (where target_type = 'TASK' and covered) as covered_tasks,
            count(*) filter (where target_type = 'TASK' and overdue) as overdue_tasks
        from observed_targets
        """;
    private final DataSource dataSource;

    public JdbcApprovalWorkflowPopulationReader(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public Snapshot read() {
        // The caller supplies an isolated pool, never a transaction-aware business connection.
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.getAutoCommit()) {
                throw new IllegalStateException("Workflow observation requires an independent connection");
            }
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(SQL)) {
                statement.setQueryTimeout(2);
                statement.setMaxRows(1);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("Workflow aggregate row unavailable");
                    return new Snapshot(rows.getObject("observed_at", OffsetDateTime.class).toInstant(),
                        rows.getLong("active_processes"), rows.getLong("covered_processes"),
                        rows.getLong("overdue_processes"), rows.getLong("active_tasks"),
                        rows.getLong("covered_tasks"), rows.getLong("overdue_tasks"));
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Workflow aggregate read failed", failure);
        }
    }
}
