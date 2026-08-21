package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Bounded MySQL primitive for task lookup, claim and transfer compare-and-set behavior.
 *
 * <p>This class is intentionally not a complete {@link ApprovalProjectionStore}. It remains
 * unbound from the executable application until the complete MySQL projection-store contract is
 * proven.</p>
 */
public final class JdbcMySqlApprovalTaskCasStore {

    private static final String TASK_COLUMNS = """
        task_id, instance_id, tenant_id, engine_task_id,
        task_definition_key, task_name, assignee_id,
        status, version, created_at, updated_at, completed_at
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcDatabaseValueAdapter values;
    private final TransactionTemplate transactionTemplate;

    public JdbcMySqlApprovalTaskCasStore(DataSource dataSource) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        this.values = JdbcDatabaseValueAdapter.resolve(source);
        if (values.vendor() != ApprovalDatabaseVendor.MYSQL) {
            throw new IllegalArgumentException(
                "JdbcMySqlApprovalTaskCasStore requires MySQL 8.4"
            );
        }
        this.jdbc = new NamedParameterJdbcTemplate(source);
        this.transactionTemplate = new TransactionTemplate(
            new JdbcTransactionManager(source)
        );
    }

    public Optional<TaskProjection> findTask(String tenantId, UUID taskId) {
        String exactTenant = requireText(tenantId, "tenantId");
        UUID exactTaskId = Objects.requireNonNull(taskId, "taskId must not be null");
        return readTask(exactTenant, exactTaskId, false);
    }

    public List<TaskProjection> findTasks(String tenantId, UUID instanceId) {
        String exactTenant = requireText(tenantId, "tenantId");
        UUID exactInstanceId = Objects.requireNonNull(
            instanceId,
            "instanceId must not be null"
        );
        List<TaskProjection> tasks = jdbc.query(
            "select " + TASK_COLUMNS + """
                from ap_approval_task
                where tenant_id = :tenantId
                  and instance_id = :instanceId
                order by created_at, task_id
                """,
            new MapSqlParameterSource()
                .addValue("tenantId", exactTenant)
                .addValue("instanceId", values.bindUuid(exactInstanceId)),
            taskMapper()
        );
        return List.copyOf(tasks);
    }

    public TaskProjection claimPendingTask(
        String tenantId,
        UUID taskId,
        String operatorId,
        Instant claimedAt
    ) {
        String exactTenant = requireText(tenantId, "tenantId");
        UUID exactTaskId = Objects.requireNonNull(taskId, "taskId must not be null");
        String exactOperator = requireText(operatorId, "operatorId");
        Instant exactClaimedAt = requireMicrosecondInstant(claimedAt, "claimedAt");
        MapSqlParameterSource parameters = taskParameters(
            exactTenant,
            exactTaskId,
            exactClaimedAt
        ).addValue("operatorId", exactOperator);
        return mutate(
            exactTenant,
            exactTaskId,
            exactClaimedAt,
            parameters,
            """
            update ap_approval_task
            set status = 'COMPLETING',
                version = version + 1,
                updated_at = :operationTime
            where tenant_id = :tenantId
              and task_id = :taskId
              and assignee_id = :operatorId
              and status = 'PENDING'
              and version = :expectedVersion
            """,
            task -> task.status() == TaskStatus.PENDING
                && task.assigneeId().equals(exactOperator),
            TaskStatus.COMPLETING,
            exactOperator,
            "task is not pending, does not exist, or is assigned to another operator"
        );
    }

    public TaskProjection claimPendingTaskForControl(
        String tenantId,
        UUID taskId,
        Instant claimedAt
    ) {
        String exactTenant = requireText(tenantId, "tenantId");
        UUID exactTaskId = Objects.requireNonNull(taskId, "taskId must not be null");
        Instant exactClaimedAt = requireMicrosecondInstant(claimedAt, "claimedAt");
        MapSqlParameterSource parameters = taskParameters(
            exactTenant,
            exactTaskId,
            exactClaimedAt
        );
        return mutate(
            exactTenant,
            exactTaskId,
            exactClaimedAt,
            parameters,
            """
            update ap_approval_task
            set status = 'COMPLETING',
                version = version + 1,
                updated_at = :operationTime
            where tenant_id = :tenantId
              and task_id = :taskId
              and status = 'PENDING'
              and version = :expectedVersion
            """,
            task -> task.status() == TaskStatus.PENDING,
            TaskStatus.COMPLETING,
            null,
            "downstream task changed before it could be retrieved"
        );
    }

    public TaskProjection transferPendingTask(
        String tenantId,
        UUID taskId,
        String currentAssigneeId,
        String targetAssigneeId,
        Instant transferredAt
    ) {
        String exactTenant = requireText(tenantId, "tenantId");
        UUID exactTaskId = Objects.requireNonNull(taskId, "taskId must not be null");
        String exactCurrentAssignee = requireText(
            currentAssigneeId,
            "currentAssigneeId"
        );
        String exactTargetAssignee = requireText(targetAssigneeId, "targetAssigneeId");
        Instant exactTransferredAt = requireMicrosecondInstant(
            transferredAt,
            "transferredAt"
        );
        MapSqlParameterSource parameters = taskParameters(
            exactTenant,
            exactTaskId,
            exactTransferredAt
        )
            .addValue("currentAssigneeId", exactCurrentAssignee)
            .addValue("targetAssigneeId", exactTargetAssignee);
        return mutate(
            exactTenant,
            exactTaskId,
            exactTransferredAt,
            parameters,
            """
            update ap_approval_task
            set assignee_id = :targetAssigneeId,
                version = version + 1,
                updated_at = :operationTime
            where tenant_id = :tenantId
              and task_id = :taskId
              and assignee_id = :currentAssigneeId
              and status = 'PENDING'
              and version = :expectedVersion
            """,
            task -> task.status() == TaskStatus.PENDING
                && task.assigneeId().equals(exactCurrentAssignee),
            TaskStatus.PENDING,
            exactTargetAssignee,
            "task changed, is no longer pending, or is assigned to another operator"
        );
    }

    private TaskProjection mutate(
        String tenantId,
        UUID taskId,
        Instant operationTime,
        MapSqlParameterSource parameters,
        String updateSql,
        Predicate<TaskProjection> precondition,
        TaskStatus expectedStatus,
        String expectedAssignee,
        String conflictMessage
    ) {
        TaskProjection result = transactionTemplate.execute(status -> {
            TaskProjection before = readTask(tenantId, taskId, false)
                .filter(precondition)
                .orElseThrow(() -> new ApprovalProjectionStore.ProjectionConflictException(
                    conflictMessage
                ));
            parameters.addValue("expectedVersion", before.version());
            int updated = jdbc.update(updateSql, parameters);
            if (updated != 1) {
                throw new ApprovalProjectionStore.ProjectionConflictException(conflictMessage);
            }
            TaskProjection after = readTask(tenantId, taskId, true)
                .orElseThrow(() -> new IllegalStateException(
                    "MySQL task CAS readback was missing"
                ));
            verifyReadback(
                before,
                after,
                expectedStatus,
                expectedAssignee,
                operationTime
            );
            return after;
        });
        return Objects.requireNonNull(result, "MySQL task CAS result must not be null");
    }

    private Optional<TaskProjection> readTask(
        String tenantId,
        UUID taskId,
        boolean forUpdate
    ) {
        String lockClause = forUpdate ? " for update" : "";
        List<TaskProjection> tasks = jdbc.query(
            "select " + TASK_COLUMNS + """
                from ap_approval_task
                where tenant_id = :tenantId
                  and task_id = :taskId
                """ + lockClause,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("taskId", values.bindUuid(taskId)),
            taskMapper()
        );
        if (tasks.size() > 1) {
            throw new IllegalStateException("task key resolved multiple rows");
        }
        return tasks.stream().findFirst();
    }

    private void verifyReadback(
        TaskProjection before,
        TaskProjection after,
        TaskStatus expectedStatus,
        String expectedAssignee,
        Instant operationTime
    ) {
        String requiredAssignee = expectedAssignee == null
            ? before.assigneeId()
            : expectedAssignee;
        if (!after.taskId().equals(before.taskId())
            || !after.instanceId().equals(before.instanceId())
            || !after.tenantId().equals(before.tenantId())
            || after.status() != expectedStatus
            || !after.assigneeId().equals(requiredAssignee)
            || after.version() != before.version() + 1
            || !after.updatedAt().equals(operationTime)) {
            throw new IllegalStateException("MySQL task CAS readback did not match mutation");
        }
    }

    private MapSqlParameterSource taskParameters(
        String tenantId,
        UUID taskId,
        Instant operationTime
    ) {
        return new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("taskId", values.bindUuid(taskId))
            .addValue("operationTime", values.bindInstant(operationTime));
    }

    private RowMapper<TaskProjection> taskMapper() {
        return (resultSet, rowNumber) -> new TaskProjection(
            values.uuid(resultSet, "task_id"),
            values.uuid(resultSet, "instance_id"),
            resultSet.getString("tenant_id"),
            resultSet.getString("engine_task_id"),
            resultSet.getString("task_definition_key"),
            resultSet.getString("task_name"),
            resultSet.getString("assignee_id"),
            TaskStatus.valueOf(resultSet.getString("status")),
            resultSet.getLong("version"),
            values.instant(resultSet, "created_at"),
            values.instant(resultSet, "updated_at"),
            values.nullableInstant(resultSet, "completed_at")
        );
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Instant requireMicrosecondInstant(Instant value, String name) {
        Instant exact = Objects.requireNonNull(value, name + " must not be null");
        if (exact.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                name + " must have at most microsecond precision"
            );
        }
        return exact;
    }
}
