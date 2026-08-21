package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Shared bounded value, JSON and row-mapping contract for MySQL projections. */
final class JdbcMySqlProjectionCodec {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final JdbcDatabaseValueAdapter values;

    JdbcMySqlProjectionCodec(DataSource dataSource, ObjectMapper objectMapper) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        this.values = JdbcDatabaseValueAdapter.resolve(source);
        if (values.vendor() != ApprovalDatabaseVendor.MYSQL) {
            throw new IllegalArgumentException(
                "MySQL projection persistence requires a MySQL 8.4 DataSource"
            );
        }
        this.objectMapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper must not be null"
        );
    }

    JdbcDatabaseValueAdapter values() {
        return values;
    }

    MapSqlParameterSource definitionParameters(PublishedDefinition definition) {
        PublishedDefinition exact = Objects.requireNonNull(
            definition,
            "definition must not be null"
        );
        return new MapSqlParameterSource()
            .addValue("tenantId", exact.tenantId())
            .addValue("definitionKey", exact.definitionKey())
            .addValue("definitionVersion", exact.definitionVersion())
            .addValue("formKey", exact.formKey())
            .addValue("formVersion", exact.formVersion())
            .addValue("compilerVersion", exact.compilerVersion())
            .addValue("contentHash", exact.contentHash())
            .addValue("deploymentId", exact.deploymentId())
            .addValue("engineDefinitionId", exact.engineDefinitionId())
            .addValue("engineVersion", exact.engineVersion())
            .addValue("publishedBy", exact.publishedBy())
            .addValue(
                "publishedAt",
                values.bindInstant(canonicalInstant(exact.publishedAt()))
            );
    }

    MapSqlParameterSource instanceParameters(InstanceProjection instance) {
        InstanceProjection exact = requireInstance(instance);
        return new MapSqlParameterSource()
            .addValue("instanceId", values.bindUuid(exact.instanceId()))
            .addValue("tenantId", exact.tenantId())
            .addValue("businessKey", exact.businessKey())
            .addValue("engineInstanceId", exact.engineInstanceId())
            .addValue("definitionKey", exact.definitionKey())
            .addValue("definitionVersion", exact.definitionVersion())
            .addValue("formKey", exact.formKey())
            .addValue("formVersion", exact.formVersion())
            .addValue("compilerVersion", exact.compilerVersion())
            .addValue("contentHash", exact.contentHash())
            .addValue("releaseVersion", exact.releaseVersion())
            .addValue("releasePackageHash", exact.releasePackageHash())
            .addValue("formPackageVersion", exact.formPackageVersion())
            .addValue("formPackageHash", exact.formPackageHash())
            .addValue("uiSchemaVersion", exact.uiSchemaVersion())
            .addValue("uiSchemaHash", exact.uiSchemaHash())
            .addValue("engineDefinitionId", exact.engineDefinitionId())
            .addValue("initiatorId", exact.initiatorId())
            .addValue("amount", exact.amount())
            .addValue("supplier", exact.supplier())
            .addValue("purchaseOrderReference", exact.purchaseOrderReference())
            .addValue("attachmentIdsJson", encode(exact.attachmentIds()))
            .addValue("assigneeSnapshotJson", encode(exact.assigneeSnapshot()))
            .addValue("requestHash", exact.requestHash())
            .addValue("status", exact.status().name())
            .addValue("version", exact.version())
            .addValue("createdAt", values.bindInstant(canonicalInstant(exact.createdAt())))
            .addValue("updatedAt", values.bindInstant(canonicalInstant(exact.updatedAt())));
    }

    MapSqlParameterSource taskParameters(TaskProjection task) {
        TaskProjection exact = Objects.requireNonNull(task, "task must not be null");
        return new MapSqlParameterSource()
            .addValue("taskId", values.bindUuid(exact.taskId()))
            .addValue("instanceId", values.bindUuid(exact.instanceId()))
            .addValue("tenantId", exact.tenantId())
            .addValue("engineTaskId", exact.engineTaskId())
            .addValue("taskDefinitionKey", exact.taskDefinitionKey())
            .addValue("taskName", exact.name())
            .addValue("assigneeId", exact.assigneeId())
            .addValue("status", exact.status().name())
            .addValue("version", exact.version())
            .addValue("createdAt", values.bindInstant(canonicalInstant(exact.createdAt())))
            .addValue("updatedAt", values.bindInstant(canonicalInstant(exact.updatedAt())))
            .addValue(
                "completedAt",
                exact.completedAt() == null
                    ? null
                    : values.bindInstant(canonicalInstant(exact.completedAt()))
            );
    }

    RowMapper<PublishedDefinition> definitionMapper() {
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

    RowMapper<InstanceProjection> instanceMapper() {
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

    RowMapper<TaskProjection> taskMapper() {
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

    InstanceProjection requireInstance(InstanceProjection instance) {
        InstanceProjection exact = Objects.requireNonNull(
            instance,
            "instance must not be null"
        );
        requireText(exact.tenantId(), "instance.tenantId");
        Objects.requireNonNull(exact.instanceId(), "instance.instanceId must not be null");
        requireText(exact.businessKey(), "instance.businessKey");
        requireText(exact.engineInstanceId(), "instance.engineInstanceId");
        return exact;
    }

    List<TaskProjection> validateTasks(
        String tenantId,
        UUID instanceId,
        List<TaskProjection> tasks
    ) {
        String exactTenant = requireText(tenantId, "tenantId");
        UUID exactInstanceId = Objects.requireNonNull(
            instanceId,
            "instanceId must not be null"
        );
        List<TaskProjection> exactTasks = List.copyOf(
            Objects.requireNonNull(tasks, "tasks must not be null")
        );
        Set<UUID> taskIds = new HashSet<>();
        Set<String> engineTaskIds = new HashSet<>();
        for (TaskProjection task : exactTasks) {
            TaskProjection exactTask = Objects.requireNonNull(
                task,
                "task must not be null"
            );
            if (!exactTenant.equals(exactTask.tenantId())) {
                throw new IllegalArgumentException(
                    "task tenantId must match the approval instance tenant"
                );
            }
            if (!exactInstanceId.equals(exactTask.instanceId())) {
                throw new IllegalArgumentException(
                    "task instanceId must match the approval instance"
                );
            }
            if (!taskIds.add(exactTask.taskId())) {
                throw new IllegalArgumentException("duplicate active taskId");
            }
            if (!engineTaskIds.add(requireText(
                exactTask.engineTaskId(),
                "engineTaskId"
            ))) {
                throw new IllegalArgumentException("duplicate active engineTaskId");
            }
        }
        return exactTasks;
    }

    static String requireText(String value, String name) {
        String exact = Objects.requireNonNull(value, name + " must not be null");
        if (exact.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return exact;
    }

    static Instant canonicalInstant(Instant value) {
        return AuditHashCanonicalizer.canonicalInstant(
            Objects.requireNonNull(value, "instant must not be null")
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
}
