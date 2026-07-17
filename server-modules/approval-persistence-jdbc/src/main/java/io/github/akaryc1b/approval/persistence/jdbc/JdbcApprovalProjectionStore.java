package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JdbcApprovalProjectionStore implements ApprovalProjectionStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcApprovalProjectionStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public void lockDefinition(String tenantId, String definitionKey, int definitionVersion) {
        advisoryLock("definition:" + tenantId + ':' + definitionKey + ':' + definitionVersion);
    }

    @Override
    public Optional<PublishedDefinition> findDefinition(
        String tenantId,
        String definitionKey,
        int definitionVersion
    ) {
        List<PublishedDefinition> rows = jdbc.query(
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
        );
        return rows.stream().findFirst();
    }

    @Override
    public void saveDefinition(PublishedDefinition definition) {
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
                .addValue("publishedAt", offset(definition.publishedAt()))
        );
        if (inserted != 1) {
            throw new IllegalStateException("definition projection was not inserted");
        }
    }

    @Override
    public void lockBusinessKey(String tenantId, String businessKey) {
        advisoryLock("business:" + tenantId + ':' + businessKey);
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
        int inserted = jdbc.update(
            """
            insert into ap_approval_instance (
                instance_id, tenant_id, business_key, engine_instance_id,
                definition_key, definition_version, form_key, form_version,
                compiler_version, content_hash, initiator_id,
                amount, supplier, purchase_order_reference,
                attachment_ids_json, assignee_snapshot_json, request_hash,
                status, version, created_at, updated_at
            ) values (
                :instanceId, :tenantId, :businessKey, :engineInstanceId,
                :definitionKey, :definitionVersion, :formKey, :formVersion,
                :compilerVersion, :contentHash, :initiatorId,
                :amount, :supplier, :purchaseOrderReference,
                cast(:attachmentIdsJson as jsonb), cast(:assigneeSnapshotJson as jsonb),
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
                .addValue("instanceId", instanceId)
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
                .addValue("instanceId", instanceId),
            taskMapper()
        );
    }

    @Override
    public TaskProjection claimPendingTask(
        String tenantId,
        UUID taskId,
        String operatorId,
        Instant claimedAt
    ) {
        List<TaskProjection> claimed = jdbc.query(
            """
            update ap_approval_task
            set status = 'COMPLETING',
                version = version + 1,
                updated_at = :claimedAt
            where tenant_id = :tenantId
              and task_id = :taskId
              and assignee_id = :operatorId
              and status = 'PENDING'
            returning *
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("taskId", taskId)
                .addValue("operatorId", operatorId)
                .addValue("claimedAt", offset(claimedAt)),
            taskMapper()
        );
        if (claimed.size() == 1) {
            return claimed.getFirst();
        }
        throw new ProjectionConflictException(
            "task is not pending, does not exist, or is assigned to another operator"
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
                .addValue("instanceId", instanceId)
                .addValue("taskId", completedTaskId)
                .addValue("claimedVersion", claimedTaskVersion)
                .addValue("completedAt", offset(completedAt))
        );
        if (completed != 1) {
            throw new ProjectionConflictException("claimed task version changed before completion");
        }

        Map<String, TaskProjection> activeByEngineId = activeTasks.stream().collect(
            java.util.stream.Collectors.toMap(
                TaskProjection::engineTaskId,
                task -> task,
                (left, right) -> left
            )
        );
        List<TaskProjection> previous = findTasks(tenantId, instanceId);
        for (TaskProjection task : previous) {
            if (task.taskId().equals(completedTaskId)) {
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
                        .addValue("taskId", task.taskId())
                        .addValue("updatedAt", offset(completedAt))
                );
            }
        }
        for (TaskProjection task : activeTasks) {
            upsertActiveTask(task, completedAt);
        }

        int instanceUpdated = jdbc.update(
            """
            update ap_approval_instance
            set status = :status,
                version = version + 1,
                updated_at = :updatedAt
            where tenant_id = :tenantId and instance_id = :instanceId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("instanceId", instanceId)
                .addValue("status", instanceStatus.name())
                .addValue("updatedAt", offset(completedAt))
        );
        if (instanceUpdated != 1) {
            throw new ProjectionConflictException("instance projection is missing");
        }
    }

    private void advisoryLock(String lockKey) {
        jdbc.query(
            "select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
            new MapSqlParameterSource("lockKey", lockKey),
            resultSet -> null
        );
    }

    private List<InstanceProjection> queryInstances(String sql, MapSqlParameterSource parameters) {
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

    private void upsertActiveTask(TaskProjection task, Instant updatedAt) {
        jdbc.update(
            """
            insert into ap_approval_task (
                task_id, instance_id, tenant_id, engine_task_id,
                task_definition_key, task_name, assignee_id,
                status, version, created_at, updated_at, completed_at
            ) values (
                :taskId, :instanceId, :tenantId, :engineTaskId,
                :taskDefinitionKey, :taskName, :assigneeId,
                'PENDING', :version, :createdAt, :updatedAt, null
            )
            on conflict (tenant_id, engine_task_id) do update
            set task_definition_key = excluded.task_definition_key,
                task_name = excluded.task_name,
                assignee_id = excluded.assignee_id,
                status = 'PENDING',
                updated_at = excluded.updated_at,
                completed_at = null
            """,
            taskParameters(new TaskProjection(
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
            ))
        );
    }

    private MapSqlParameterSource instanceParameters(InstanceProjection instance) {
        return new MapSqlParameterSource()
            .addValue("instanceId", instance.instanceId())
            .addValue("tenantId", instance.tenantId())
            .addValue("businessKey", instance.businessKey())
            .addValue("engineInstanceId", instance.engineInstanceId())
            .addValue("definitionKey", instance.definitionKey())
            .addValue("definitionVersion", instance.definitionVersion())
            .addValue("formKey", instance.formKey())
            .addValue("formVersion", instance.formVersion())
            .addValue("compilerVersion", instance.compilerVersion())
            .addValue("contentHash", instance.contentHash())
            .addValue("initiatorId", instance.initiatorId())
            .addValue("amount", instance.amount())
            .addValue("supplier", instance.supplier())
            .addValue("purchaseOrderReference", instance.purchaseOrderReference())
            .addValue("attachmentIdsJson", encode(instance.attachmentIds()))
            .addValue("assigneeSnapshotJson", encode(instance.assigneeSnapshot()))
            .addValue("requestHash", instance.requestHash())
            .addValue("status", instance.status().name())
            .addValue("version", instance.version())
            .addValue("createdAt", offset(instance.createdAt()))
            .addValue("updatedAt", offset(instance.updatedAt()));
    }

    private static MapSqlParameterSource taskParameters(TaskProjection task) {
        return new MapSqlParameterSource()
            .addValue("taskId", task.taskId())
            .addValue("instanceId", task.instanceId())
            .addValue("tenantId", task.tenantId())
            .addValue("engineTaskId", task.engineTaskId())
            .addValue("taskDefinitionKey", task.taskDefinitionKey())
            .addValue("taskName", task.name())
            .addValue("assigneeId", task.assigneeId())
            .addValue("status", task.status().name())
            .addValue("version", task.version())
            .addValue("createdAt", offset(task.createdAt()))
            .addValue("updatedAt", offset(task.updatedAt()))
            .addValue("completedAt", task.completedAt() == null
                ? null
                : offset(task.completedAt()));
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
            instant(resultSet, "published_at")
        );
    }

    private RowMapper<InstanceProjection> instanceMapper() {
        return (resultSet, rowNumber) -> new InstanceProjection(
            resultSet.getObject("instance_id", UUID.class),
            resultSet.getString("tenant_id"),
            resultSet.getString("business_key"),
            resultSet.getString("engine_instance_id"),
            resultSet.getString("definition_key"),
            resultSet.getInt("definition_version"),
            resultSet.getString("form_key"),
            resultSet.getInt("form_version"),
            resultSet.getString("compiler_version"),
            resultSet.getString("content_hash"),
            resultSet.getString("initiator_id"),
            resultSet.getBigDecimal("amount"),
            resultSet.getString("supplier"),
            resultSet.getString("purchase_order_reference"),
            decode(resultSet.getString("attachment_ids_json"), STRING_LIST),
            decodeAssignees(resultSet.getString("assignee_snapshot_json")),
            resultSet.getString("request_hash"),
            InstanceStatus.valueOf(resultSet.getString("status")),
            resultSet.getLong("version"),
            instant(resultSet, "created_at"),
            instant(resultSet, "updated_at")
        );
    }

    private static RowMapper<TaskProjection> taskMapper() {
        return (resultSet, rowNumber) -> new TaskProjection(
            resultSet.getObject("task_id", UUID.class),
            resultSet.getObject("instance_id", UUID.class),
            resultSet.getString("tenant_id"),
            resultSet.getString("engine_task_id"),
            resultSet.getString("task_definition_key"),
            resultSet.getString("task_name"),
            resultSet.getString("assignee_id"),
            TaskStatus.valueOf(resultSet.getString("status")),
            resultSet.getLong("version"),
            instant(resultSet, "created_at"),
            instant(resultSet, "updated_at"),
            nullableInstant(resultSet, "completed_at")
        );
    }

    private AssigneeSnapshot decodeAssignees(String json) throws SQLException {
        try {
            Map<String, Object> values = objectMapper.readValue(
                json,
                new TypeReference<Map<String, Object>>() {
                }
            );
            List<String> approvers = objectMapper.convertValue(
                values.get("financeApprovers"),
                STRING_LIST
            );
            Map<String, String> attributes = values.get("attributes") == null
                ? Map.of()
                : objectMapper.convertValue(values.get("attributes"), STRING_MAP);
            return new AssigneeSnapshot(
                Objects.toString(values.get("managerAssignee")),
                Objects.toString(values.get("financeReviewer")),
                approvers,
                attributes
            );
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

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
