package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.ProjectionConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** MySQL completion, cancellation, active-task sync and withdrawal persistence. */
final class JdbcMySqlTaskLifecycleProjectionStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcMySqlProjectionCodec codec;
    private final JdbcMySqlApprovalTaskCasStore taskCas;

    JdbcMySqlTaskLifecycleProjectionStore(
        DataSource dataSource,
        JdbcMySqlProjectionCodec codec,
        JdbcMySqlApprovalTaskCasStore taskCas
    ) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        this.taskCas = Objects.requireNonNull(taskCas, "taskCas must not be null");
    }

    void completeTaskAndSynchronize(
        String tenantId,
        UUID instanceId,
        UUID completedTaskId,
        long claimedTaskVersion,
        List<TaskProjection> activeTasks,
        InstanceStatus instanceStatus,
        Instant completedAt
    ) {
        requireTransaction("task completion synchronization");
        String exactTenant = JdbcMySqlProjectionCodec.requireText(tenantId, "tenantId");
        UUID exactInstanceId = Objects.requireNonNull(
            instanceId,
            "instanceId must not be null"
        );
        UUID exactCompletedTaskId = Objects.requireNonNull(
            completedTaskId,
            "completedTaskId must not be null"
        );
        if (claimedTaskVersion < 1) {
            throw new IllegalArgumentException("claimedTaskVersion must be positive");
        }
        List<TaskProjection> exactActiveTasks = codec.validateTasks(
            exactTenant,
            exactInstanceId,
            activeTasks
        );
        InstanceStatus exactInstanceStatus = Objects.requireNonNull(
            instanceStatus,
            "instanceStatus must not be null"
        );
        Instant exactCompletedAt = JdbcMySqlProjectionCodec.canonicalInstant(completedAt);
        int completed = jdbc.update(
            """
            update ap_approval_task
            set status = 'COMPLETED',
                completed_at = :completedAt,
                updated_at = :completedAt,
                version = version + 1
            where tenant_id = :tenantId
              and instance_id = :instanceId
              and task_id = :taskId
              and status = 'COMPLETING'
              and version = :claimedVersion
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", exactTenant)
                .addValue("instanceId", codec.values().bindUuid(exactInstanceId))
                .addValue("taskId", codec.values().bindUuid(exactCompletedTaskId))
                .addValue("claimedVersion", claimedTaskVersion)
                .addValue("completedAt", codec.values().bindInstant(exactCompletedAt))
        );
        if (completed != 1) {
            throw new ProjectionConflictException(
                "claimed task version changed before completion"
            );
        }
        synchronizeActiveTasks(
            exactTenant,
            exactInstanceId,
            exactCompletedTaskId,
            exactActiveTasks,
            exactCompletedAt
        );
        updateRunningInstanceStatus(
            exactTenant,
            exactInstanceId,
            exactInstanceStatus,
            exactCompletedAt
        );
    }

    void cancelClaimedTaskAndSynchronize(
        String tenantId,
        UUID instanceId,
        UUID canceledTaskId,
        long claimedTaskVersion,
        List<TaskProjection> activeTasks,
        Instant changedAt
    ) {
        requireTransaction("controlled task cancellation synchronization");
        String exactTenant = JdbcMySqlProjectionCodec.requireText(tenantId, "tenantId");
        UUID exactInstanceId = Objects.requireNonNull(
            instanceId,
            "instanceId must not be null"
        );
        UUID exactCanceledTaskId = Objects.requireNonNull(
            canceledTaskId,
            "canceledTaskId must not be null"
        );
        if (claimedTaskVersion < 1) {
            throw new IllegalArgumentException("claimedTaskVersion must be positive");
        }
        List<TaskProjection> exactActiveTasks = codec.validateTasks(
            exactTenant,
            exactInstanceId,
            activeTasks
        );
        Instant exactChangedAt = JdbcMySqlProjectionCodec.canonicalInstant(changedAt);
        int canceled = jdbc.update(
            """
            update ap_approval_task
            set status = 'CANCELED',
                updated_at = :changedAt,
                version = version + 1
            where tenant_id = :tenantId
              and instance_id = :instanceId
              and task_id = :taskId
              and status = 'COMPLETING'
              and version = :claimedVersion
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", exactTenant)
                .addValue("instanceId", codec.values().bindUuid(exactInstanceId))
                .addValue("taskId", codec.values().bindUuid(exactCanceledTaskId))
                .addValue("claimedVersion", claimedTaskVersion)
                .addValue("changedAt", codec.values().bindInstant(exactChangedAt))
        );
        if (canceled != 1) {
            throw new ProjectionConflictException(
                "claimed downstream task changed before retrieval"
            );
        }
        synchronizeActiveTasks(
            exactTenant,
            exactInstanceId,
            exactCanceledTaskId,
            exactActiveTasks,
            exactChangedAt
        );
        updateRunningInstanceStatus(
            exactTenant,
            exactInstanceId,
            InstanceStatus.RUNNING,
            exactChangedAt
        );
    }

    void withdrawRunningInstance(
        String tenantId,
        UUID instanceId,
        String initiatorId,
        Instant withdrawnAt
    ) {
        requireTransaction("instance withdrawal");
        String exactTenant = JdbcMySqlProjectionCodec.requireText(tenantId, "tenantId");
        UUID exactInstanceId = Objects.requireNonNull(
            instanceId,
            "instanceId must not be null"
        );
        String exactInitiator = JdbcMySqlProjectionCodec.requireText(
            initiatorId,
            "initiatorId"
        );
        Instant exactWithdrawnAt = JdbcMySqlProjectionCodec.canonicalInstant(withdrawnAt);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", exactTenant)
            .addValue("instanceId", codec.values().bindUuid(exactInstanceId))
            .addValue("initiatorId", exactInitiator)
            .addValue("withdrawnAt", codec.values().bindInstant(exactWithdrawnAt));
        int updated = jdbc.update(
            """
            update ap_approval_instance
            set status = 'WITHDRAWN',
                version = version + 1,
                updated_at = :withdrawnAt
            where tenant_id = :tenantId
              and instance_id = :instanceId
              and initiator_id = :initiatorId
              and status = 'RUNNING'
            """,
            parameters
        );
        if (updated != 1) {
            throw new ProjectionConflictException(
                "instance is missing, no longer running, or was not started by the operator"
            );
        }
        jdbc.update(
            """
            update ap_approval_task
            set status = 'CANCELED',
                version = version + 1,
                updated_at = :withdrawnAt
            where tenant_id = :tenantId
              and instance_id = :instanceId
              and status in ('PENDING', 'COMPLETING')
            """,
            parameters
        );
    }

    private void synchronizeActiveTasks(
        String tenantId,
        UUID instanceId,
        UUID ignoredTaskId,
        List<TaskProjection> activeTasks,
        Instant updatedAt
    ) {
        Map<String, TaskProjection> activeByEngineId = activeTasks.stream().collect(
            Collectors.toMap(TaskProjection::engineTaskId, task -> task)
        );
        for (TaskProjection task : taskCas.findTasks(tenantId, instanceId)) {
            if (task.taskId().equals(ignoredTaskId)) {
                continue;
            }
            if ((task.status() == TaskStatus.PENDING || task.status() == TaskStatus.COMPLETING)
                && !activeByEngineId.containsKey(task.engineTaskId())) {
                jdbc.update(
                    """
                    update ap_approval_task
                    set status = 'CANCELED',
                        updated_at = :updatedAt,
                        version = version + 1
                    where tenant_id = :tenantId
                      and task_id = :taskId
                      and status in ('PENDING', 'COMPLETING')
                    """,
                    new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("taskId", codec.values().bindUuid(task.taskId()))
                        .addValue("updatedAt", codec.values().bindInstant(updatedAt))
                );
            }
        }
        for (TaskProjection task : activeTasks) {
            upsertActiveTask(task, updatedAt);
        }
    }

    private void updateRunningInstanceStatus(
        String tenantId,
        UUID instanceId,
        InstanceStatus status,
        Instant updatedAt
    ) {
        int updated = jdbc.update(
            """
            update ap_approval_instance
            set status = :status,
                version = version + 1,
                updated_at = :updatedAt
            where tenant_id = :tenantId
              and instance_id = :instanceId
              and status = 'RUNNING'
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("instanceId", codec.values().bindUuid(instanceId))
                .addValue("status", status.name())
                .addValue("updatedAt", codec.values().bindInstant(updatedAt))
        );
        if (updated != 1) {
            throw new ProjectionConflictException(
                "running instance projection changed or is missing"
            );
        }
    }

    private void upsertActiveTask(TaskProjection task, Instant updatedAt) {
        TaskProjection pending = new TaskProjection(
            task.taskId(),
            task.instanceId(),
            task.tenantId(),
            task.engineTaskId(),
            task.taskDefinitionKey(),
            task.name(),
            task.assigneeId(),
            TaskStatus.PENDING,
            task.version(),
            task.createdAt(),
            updatedAt,
            null
        );
        MapSqlParameterSource parameters = codec.taskParameters(pending);
        int updated = updateExistingActiveTask(parameters);
        if (updated == 1) {
            return;
        }
        if (updated != 0) {
            throw new IllegalStateException("unexpected active task update row count");
        }

        Optional<TaskProjection> owner = findTaskByEngineId(
            pending.tenantId(),
            pending.engineTaskId()
        );
        if (owner.isPresent()) {
            verifyActiveTaskOwner(pending, owner.orElseThrow());
            if (activeTaskMatches(owner.orElseThrow(), pending)) {
                return;
            }
            int retried = updateExistingActiveTask(parameters);
            if (retried == 1 || activeTaskMatches(
                findTaskByEngineId(pending.tenantId(), pending.engineTaskId())
                    .orElseThrow(() -> new ProjectionConflictException(
                        "active task disappeared during synchronization"
                    )),
                pending
            )) {
                return;
            }
            throw new ProjectionConflictException(
                "active task changed during synchronization"
            );
        }

        try {
            insertTask(pending);
        } catch (DuplicateKeyException exception) {
            classifyDuplicateAndSynchronize(pending, parameters);
        }
    }

    private void classifyDuplicateAndSynchronize(
        TaskProjection pending,
        MapSqlParameterSource parameters
    ) {
        Optional<TaskProjection> concurrentOwner = findTaskByEngineId(
            pending.tenantId(),
            pending.engineTaskId()
        );
        if (concurrentOwner.isEmpty()) {
            if (findTaskIdentifierOwner(pending.taskId()).isPresent()) {
                throw new ProjectionConflictException(
                    "active task identifier is already owned by another engine task"
                );
            }
            throw new ProjectionConflictException(
                "active task uniqueness conflict without a readable owner"
            );
        }
        verifyActiveTaskOwner(pending, concurrentOwner.orElseThrow());
        int retried = updateExistingActiveTask(parameters);
        if (retried == 1 || activeTaskMatches(
            findTaskByEngineId(pending.tenantId(), pending.engineTaskId())
                .orElseThrow(() -> new ProjectionConflictException(
                    "concurrent active task disappeared during synchronization"
                )),
            pending
        )) {
            return;
        }
        throw new ProjectionConflictException(
            "concurrent active task changed during synchronization"
        );
    }

    private int updateExistingActiveTask(MapSqlParameterSource parameters) {
        return jdbc.update(
            """
            update ap_approval_task
            set task_definition_key = :taskDefinitionKey,
                task_name = :taskName,
                assignee_id = :assigneeId,
                status = 'PENDING',
                updated_at = :updatedAt,
                completed_at = null
            where tenant_id = :tenantId
              and instance_id = :instanceId
              and engine_task_id = :engineTaskId
            """,
            parameters
        );
    }

    private void verifyActiveTaskOwner(
        TaskProjection requested,
        TaskProjection existing
    ) {
        if (!existing.instanceId().equals(requested.instanceId())) {
            throw new ProjectionConflictException(
                "engine task is already owned by another approval instance"
            );
        }
        Optional<TaskProjection> taskIdentifierOwner = findTaskIdentifierOwner(
            requested.taskId()
        );
        if (taskIdentifierOwner.isPresent()
            && !taskIdentifierOwner.orElseThrow().taskId().equals(existing.taskId())) {
            throw new ProjectionConflictException(
                "active task identifier is already owned by another engine task"
            );
        }
    }

    private static boolean activeTaskMatches(
        TaskProjection existing,
        TaskProjection requested
    ) {
        return existing.instanceId().equals(requested.instanceId())
            && existing.engineTaskId().equals(requested.engineTaskId())
            && existing.taskDefinitionKey().equals(requested.taskDefinitionKey())
            && existing.name().equals(requested.name())
            && existing.assigneeId().equals(requested.assigneeId())
            && existing.status() == TaskStatus.PENDING
            && existing.completedAt() == null;
    }

    private Optional<TaskProjection> findTaskIdentifierOwner(UUID taskId) {
        return jdbc.query(
            """
            select * from ap_approval_task
            where task_id = :taskId
            """,
            new MapSqlParameterSource("taskId", codec.values().bindUuid(taskId)),
            codec.taskMapper()
        ).stream().findFirst();
    }

    private Optional<TaskProjection> findTaskByEngineId(
        String tenantId,
        String engineTaskId
    ) {
        return jdbc.query(
            """
            select * from ap_approval_task
            where tenant_id = :tenantId and engine_task_id = :engineTaskId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("engineTaskId", engineTaskId),
            codec.taskMapper()
        ).stream().findFirst();
    }

    private void insertTask(TaskProjection task) {
        int inserted = jdbc.update(
            """
            insert into ap_approval_task (
                task_id, instance_id, tenant_id, engine_task_id,
                task_definition_key, task_name, assignee_id,
                status, version, created_at, updated_at, completed_at
            ) values (
                :taskId, :instanceId, :tenantId, :engineTaskId,
                :taskDefinitionKey, :taskName, :assigneeId,
                :status, :version, :createdAt, :updatedAt, :completedAt
            )
            """,
            codec.taskParameters(task)
        );
        if (inserted != 1) {
            throw new IllegalStateException("task projection was not inserted");
        }
    }

    private static void requireTransaction(String operation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(operation + " requires an active transaction");
        }
    }
}
