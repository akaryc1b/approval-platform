package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** MySQL 8.4 projection store with bounded transaction locks and exact CAS semantics. */
public final class JdbcMySqlApprovalProjectionStore implements ApprovalProjectionStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final JdbcDatabaseValueAdapter values;
    private final JdbcMySqlTransactionLockManager locks;

    public JdbcMySqlApprovalProjectionStore(DataSource dataSource, ObjectMapper objectMapper) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        this.values = JdbcDatabaseValueAdapter.resolve(source);
        if (values.vendor() != ApprovalDatabaseVendor.MYSQL) {
            throw new IllegalArgumentException(
                "JdbcMySqlApprovalProjectionStore requires a MySQL 8.4 DataSource"
            );
        }
        this.jdbc = new NamedParameterJdbcTemplate(source);
        this.objectMapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper must not be null"
        );
        this.locks = new JdbcMySqlTransactionLockManager(source);
    }

    @Override
    public void lockDefinition(String tenantId, String definitionKey, int definitionVersion) {
        locks.acquire("definition:" + tenantId + ':' + definitionKey + ':' + definitionVersion);
    }

    @Override
    public Optional<PublishedDefinition> findDefinition(
        String tenantId,
        String definitionKey,
        int definitionVersion
    ) {
        return jdbc.query(
            """
            select * from ap_definition_version
            where tenant_id = :tenantId
              and definition_key = :definitionKey
              and definition_version = :definitionVersion
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("definitionKey", definitionKey)
                .addValue("definitionVersion", definitionVersion),
            definitionMapper()
        ).stream().findFirst();
    }

    @Override
    public void saveDefinition(PublishedDefinition definition) {
        requireTransaction("definition projection save");
        int inserted = jdbc.update(
            """
            insert into ap_definition_version (
                tenant_id, definition_key, definition_version,
                form_key, form_version, compiler_version, content_hash,
                deployment_id, engine_definition_id, engine_version,
                published_by, published_at
            ) values (
                :tenantId, :definitionKey, :definitionVersion,
                :formKey, :formVersion, :compilerVersion, :contentHash,
                :deploymentId, :engineDefinitionId, :engineVersion,
                :publishedBy, :publishedAt
            )
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", definition.tenantId())
                .addValue("definitionKey", definition.definitionKey())
                .addValue("definitionVersion", definition.definitionVersion())
                .addValue("formKey", definition.formKey())
                .addValue("formVersion", definition.formVersion())
                .addValue("compilerVersion", definition.compilerVersion())
                .addValue("contentHash", definition.contentHash())
                .addValue("deploymentId", definition.deploymentId())
                .addValue("engineDefinitionId", definition.engineDefinitionId())
                .addValue("engineVersion", definition.engineVersion())
                .addValue("publishedBy", definition.publishedBy())
                .addValue("publishedAt", values.bindInstant(definition.publishedAt()))
        );
        if (inserted != 1) {
            throw new IllegalStateException("definition projection was not inserted");
        }
    }

    @Override
    public void lockBusinessKey(String tenantId, String businessKey) {
        locks.acquire("business:" + tenantId + ':' + businessKey);
    }

    @Override
    public Optional<InstanceProjection> findByBusinessKey(String tenantId, String businessKey) {
        return queryInstances(
            """
            select * from ap_approval_instance
            where tenant_id = :tenantId and business_key = :businessKey
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("businessKey", businessKey)
        ).stream().findFirst();
    }

    @Override
    public void createInstance(InstanceProjection instance, List<TaskProjection> tasks) {
        requireTransaction("instance projection creation");
        int inserted = jdbc.update(
            """
            insert into ap_approval_instance (
                instance_id, tenant_id, business_key, engine_instance_id,
                definition_key, definition_version, form_key, form_version,
                compiler_version, content_hash,
                release_version, release_package_hash,
                form_package_version, form_package_hash,
                ui_schema_version, ui_schema_hash, engine_definition_id,
                initiator_id,
                amount, supplier, purchase_order_reference,
                attachment_ids_json, assignee_snapshot_json, request_hash,
                status, version, created_at, updated_at
            ) values (
                :instanceId, :tenantId, :businessKey, :engineInstanceId,
                :definitionKey, :definitionVersion, :formKey, :formVersion,
                :compilerVersion, :contentHash,
                :releaseVersion, :releasePackageHash,
                :formPackageVersion, :formPackageHash,
                :uiSchemaVersion, :uiSchemaHash, :engineDefinitionId,
                :initiatorId,
                :amount, :supplier, :purchaseOrderReference,
                cast(:attachmentIdsJson as json), cast(:assigneeSnapshotJson as json),
                :requestHash, :status, :version, :createdAt, :updatedAt
            )
            """,
            instanceParameters(instance)
        );
        if (inserted != 1) {
            throw new IllegalStateException("instance projection was not inserted");
        }
        for (TaskProjection task : tasks) {
            insertTask(task);
        }
    }

    @Override
    public Optional<InstanceProjection> findInstance(String tenantId, UUID instanceId) {
        return queryInstances(
            """
            select * from ap_approval_instance
            where tenant_id = :tenantId and instance_id = :instanceId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("instanceId", values.bindUuid(instanceId))
        ).stream().findFirst();
    }

    @Override
    public List<TaskProjection> findTasks(String tenantId, UUID instanceId) {
        return jdbc.query(
            """
            select * from ap_approval_task
            where tenant_id = :tenantId and instance_id = :instanceId
            order by created_at, task_id
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("instanceId", values.bindUuid(instanceId)),
            taskMapper()
        );
    }

    @Override
    public Optional<TaskProjection> findTask(String tenantId, UUID taskId) {
        return jdbc.query(
            """
            select * from ap_approval_task
            where tenant_id = :tenantId and task_id = :taskId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("taskId", values.bindUuid(taskId)),
            taskMapper()
        ).stream().findFirst();
    }

    @Override
    public TaskProjection claimPendingTask(
        String tenantId,
        UUID taskId,
        String operatorId,
        Instant claimedAt
    ) {
        requireTransaction("task claim");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("taskId", values.bindUuid(taskId))
            .addValue("operatorId", operatorId)
            .addValue("claimedAt", values.bindInstant(claimedAt));
        int updated = jdbc.update(
            """
            update ap_approval_task
            set status = 'COMPLETING',
                version = version + 1,
                updated_at = :claimedAt
            where tenant_id = :tenantId
              and task_id = :taskId
              and assignee_id = :operatorId
              and status = 'PENDING'
            """,
            parameters
        );
        if (updated != 1) {
            throw new ProjectionConflictException(
                "task is not pending, does not exist, or is assigned to another operator"
            );
        }
        return readMutatedTask(
            parameters,
            TaskStatus.COMPLETING,
            operatorId,
            "claimed task could not be read back"
        );
    }

    @Override
    public TaskProjection claimPendingTaskForControl(
        String tenantId,
        UUID taskId,
        Instant claimedAt
    ) {
        requireTransaction("controlled task claim");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("taskId", values.bindUuid(taskId))
            .addValue("claimedAt", values.bindInstant(claimedAt));
        int updated = jdbc.update(
            """
            update ap_approval_task
            set status = 'COMPLETING',
                version = version + 1,
                updated_at = :claimedAt
            where tenant_id = :tenantId
              and task_id = :taskId
              and status = 'PENDING'
            """,
            parameters
        );
        if (updated != 1) {
            throw new ProjectionConflictException(
                "downstream task changed before it could be retrieved"
            );
        }
        return readMutatedTask(
            parameters,
            TaskStatus.COMPLETING,
            null,
            "controlled task claim could not be read back"
        );
    }

    @Override
    public TaskProjection transferPendingTask(
        String tenantId,
        UUID taskId,
        String currentAssigneeId,
        String targetAssigneeId,
        Instant transferredAt
    ) {
        requireTransaction("task transfer");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("taskId", values.bindUuid(taskId))
            .addValue("currentAssigneeId", currentAssigneeId)
            .addValue("targetAssigneeId", targetAssigneeId)
            .addValue("transferredAt", values.bindInstant(transferredAt));
        int updated = jdbc.update(
            """
            update ap_approval_task
            set assignee_id = :targetAssigneeId,
                version = version + 1,
                updated_at = :transferredAt
            where tenant_id = :tenantId
              and task_id = :taskId
              and assignee_id = :currentAssigneeId
              and status = 'PENDING'
            """,
            parameters
        );
        if (updated != 1) {
            throw new ProjectionConflictException(
                "task changed, is no longer pending, or is assigned to another operator"
            );
        }
        return readMutatedTask(
            parameters,
            TaskStatus.PENDING,
            targetAssigneeId,
            "transferred task could not be read back"
        );
    }

    @Override
    public void completeTaskAndSynchronize(
        String tenantId,
        UUID instanceId,
        UUID completedTaskId,
        long claimedTaskVersion,
        List<TaskProjection> activeTasks,
        InstanceStatus instanceStatus,
        Instant completedAt
    ) {
        requireTransaction("task completion synchronization");
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
                .addValue("tenantId", tenantId)
                .addValue("instanceId", values.bindUuid(instanceId))
                .addValue("taskId", values.bindUuid(completedTaskId))
                .addValue("claimedVersion", claimedTaskVersion)
                .addValue("completedAt", values.bindInstant(completedAt))
        );
        if (completed != 1) {
            throw new ProjectionConflictException(
                "claimed task version changed before completion"
            );
        }
        synchronizeActiveTasks(
            tenantId,
            instanceId,
            completedTaskId,
            activeTasks,
            completedAt
        );
        updateRunningInstanceStatus(tenantId, instanceId, instanceStatus, completedAt);
    }

    @Override
    public void cancelClaimedTaskAndSynchronize(
        String tenantId,
        UUID instanceId,
        UUID canceledTaskId,
        long claimedTaskVersion,
        List<TaskProjection> activeTasks,
        Instant changedAt
    ) {
        requireTransaction("controlled task cancellation synchronization");
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
                .addValue("tenantId", tenantId)
                .addValue("instanceId", values.bindUuid(instanceId))
                .addValue("taskId", values.bindUuid(canceledTaskId))
                .addValue("claimedVersion", claimedTaskVersion)
                .addValue("changedAt", values.bindInstant(changedAt))
        );
        if (canceled != 1) {
            throw new ProjectionConflictException(
                "claimed downstream task changed before retrieval"
            );
        }
        synchronizeActiveTasks(
            tenantId,
            instanceId,
            canceledTaskId,
            activeTasks,
            changedAt
        );
        updateRunningInstanceStatus(
            tenantId,
            instanceId,
            InstanceStatus.RUNNING,
            changedAt
        );
    }

    @Override
    public void withdrawRunningInstance(
        String tenantId,
        UUID instanceId,
        String initiatorId,
        Instant withdrawnAt
    ) {
        requireTransaction("instance withdrawal");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("instanceId", values.bindUuid(instanceId))
            .addValue("initiatorId", initiatorId)
            .addValue("withdrawnAt", values.bindInstant(withdrawnAt));
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

    private TaskProjection readMutatedTask(
        MapSqlParameterSource parameters,
        TaskStatus expectedStatus,
        String expectedAssignee,
        String message
    ) {
        Optional<TaskProjection> loaded = jdbc.query(
            """
            select * from ap_approval_task
            where tenant_id = :tenantId and task_id = :taskId
            """,
            parameters,
            taskMapper()
        ).stream().findFirst();
        TaskProjection task = loaded.orElseThrow(() -> new ProjectionConflictException(message));
        if (task.status() != expectedStatus
            || (expectedAssignee != null && !expectedAssignee.equals(task.assigneeId()))) {
            throw new ProjectionConflictException(message);
        }
        return task;
    }

    private void synchronizeActiveTasks(
        String tenantId,
        UUID instanceId,
        UUID ignoredTaskId,
        List<TaskProjection> activeTasks,
        Instant updatedAt
    ) {
        Map<String, TaskProjection> activeByEngineId = activeTasks.stream().collect(
            Collectors.toMap(TaskProjection::engineTaskId, task -> task, (left, right) -> left)
        );
        for (TaskProjection task : findTasks(tenantId, instanceId)) {
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
                        .addValue("taskId", values.bindUuid(task.taskId()))
                        .addValue("updatedAt", values.bindInstant(updatedAt))
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
                .addValue("instanceId", values.bindUuid(instanceId))
                .addValue("status", status.name())
                .addValue("updatedAt", values.bindInstant(updatedAt))
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
        MapSqlParameterSource parameters = taskParameters(pending);
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
            TaskProjection existing = owner.orElseThrow();
            verifyActiveTaskOwner(pending, existing);
            if (activeTaskMatches(existing, pending)) {
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
            TaskProjection concurrentOwner = findTaskByEngineId(
                pending.tenantId(),
                pending.engineTaskId()
            ).orElseThrow(() -> exception);
            verifyActiveTaskOwner(pending, concurrentOwner);
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
        Optional<TaskProjection> taskIdentifierOwner = findTask(
            requested.tenantId(),
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
            taskMapper()
        ).stream().findFirst();
    }

    private List<InstanceProjection> queryInstances(
        String sql,
        MapSqlParameterSource parameters
    ) {
        return jdbc.query(sql, parameters, instanceMapper());
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
            taskParameters(task)
        );
        if (inserted != 1) {
            throw new IllegalStateException("task projection was not inserted");
        }
    }

    private MapSqlParameterSource instanceParameters(InstanceProjection instance) {
        return new MapSqlParameterSource()
            .addValue("instanceId", values.bindUuid(instance.instanceId()))
            .addValue("tenantId", instance.tenantId())
            .addValue("businessKey", instance.businessKey())
            .addValue("engineInstanceId", instance.engineInstanceId())
            .addValue("definitionKey", instance.definitionKey())
            .addValue("definitionVersion", instance.definitionVersion())
            .addValue("formKey", instance.formKey())
            .addValue("formVersion", instance.formVersion())
            .addValue("compilerVersion", instance.compilerVersion())
            .addValue("contentHash", instance.contentHash())
            .addValue("releaseVersion", instance.releaseVersion())
            .addValue("releasePackageHash", instance.releasePackageHash())
            .addValue("formPackageVersion", instance.formPackageVersion())
            .addValue("formPackageHash", instance.formPackageHash())
            .addValue("uiSchemaVersion", instance.uiSchemaVersion())
            .addValue("uiSchemaHash", instance.uiSchemaHash())
            .addValue("engineDefinitionId", instance.engineDefinitionId())
            .addValue("initiatorId", instance.initiatorId())
            .addValue("amount", instance.amount())
            .addValue("supplier", instance.supplier())
            .addValue("purchaseOrderReference", instance.purchaseOrderReference())
            .addValue("attachmentIdsJson", encode(instance.attachmentIds()))
            .addValue("assigneeSnapshotJson", encode(instance.assigneeSnapshot()))
            .addValue("requestHash", instance.requestHash())
            .addValue("status", instance.status().name())
            .addValue("version", instance.version())
            .addValue("createdAt", values.bindInstant(instance.createdAt()))
            .addValue("updatedAt", values.bindInstant(instance.updatedAt()));
    }

    private MapSqlParameterSource taskParameters(TaskProjection task) {
        return new MapSqlParameterSource()
            .addValue("taskId", values.bindUuid(task.taskId()))
            .addValue("instanceId", values.bindUuid(task.instanceId()))
            .addValue("tenantId", task.tenantId())
            .addValue("engineTaskId", task.engineTaskId())
            .addValue("taskDefinitionKey", task.taskDefinitionKey())
            .addValue("taskName", task.name())
            .addValue("assigneeId", task.assigneeId())
            .addValue("status", task.status().name())
            .addValue("version", task.version())
            .addValue("createdAt", values.bindInstant(task.createdAt()))
            .addValue("updatedAt", values.bindInstant(task.updatedAt()))
            .addValue("completedAt", values.bindNullableInstant(task.completedAt()));
    }

    private RowMapper<PublishedDefinition> definitionMapper() {
        return (resultSet, rowNumber) -> new PublishedDefinition(
            resultSet.getString("tenant_id"),
            resultSet.getString("definition_key"),
            resultSet.getInt("definition_version"),
            resultSet.getString("form_key"),
            resultSet.getInt("form_version"),
            resultSet.getString("compiler_version"),
            resultSet.getString("content_hash"),
            resultSet.getString("deployment_id"),
            resultSet.getString("engine_definition_id"),
            resultSet.getInt("engine_version"),
            resultSet.getString("published_by"),
            values.instant(resultSet, "published_at")
        );
    }

    private RowMapper<InstanceProjection> instanceMapper() {
        return (resultSet, rowNumber) -> new InstanceProjection(
            values.uuid(resultSet, "instance_id"),
            resultSet.getString("tenant_id"),
            resultSet.getString("business_key"),
            resultSet.getString("engine_instance_id"),
            resultSet.getString("definition_key"),
            resultSet.getInt("definition_version"),
            resultSet.getString("form_key"),
            resultSet.getInt("form_version"),
            resultSet.getString("compiler_version"),
            resultSet.getString("content_hash"),
            integer(resultSet, "release_version"),
            resultSet.getString("release_package_hash"),
            integer(resultSet, "form_package_version"),
            resultSet.getString("form_package_hash"),
            integer(resultSet, "ui_schema_version"),
            resultSet.getString("ui_schema_hash"),
            resultSet.getString("engine_definition_id"),
            resultSet.getString("initiator_id"),
            resultSet.getBigDecimal("amount"),
            resultSet.getString("supplier"),
            resultSet.getString("purchase_order_reference"),
            decode(resultSet.getString("attachment_ids_json"), STRING_LIST),
            decodeAssignees(resultSet.getString("assignee_snapshot_json")),
            resultSet.getString("request_hash"),
            InstanceStatus.valueOf(resultSet.getString("status")),
            resultSet.getLong("version"),
            values.instant(resultSet, "created_at"),
            values.instant(resultSet, "updated_at")
        );
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

    private AssigneeSnapshot decodeAssignees(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, AssigneeSnapshot.class);
        } catch (JsonProcessingException exception) {
            throw new SQLException("unable to decode assignee snapshot", exception);
        }
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("unable to encode projection JSON", exception);
        }
    }

    private <T> T decode(String json, TypeReference<T> type) throws SQLException {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new SQLException("unable to decode projection JSON", exception);
        }
    }

    private static Integer integer(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static void requireTransaction(String operation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(operation + " requires an active transaction");
        }
    }
}
