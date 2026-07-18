package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.UserIdentitySnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL task-center read model backed only by platform projection tables.
 */
public final class JdbcApprovalTaskQuery implements ApprovalTaskQuery {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcApprovalTaskQuery(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public PendingTaskPage findPendingTasks(PendingTaskCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        MapSqlParameterSource parameters = parameters(criteria);
        Long total = jdbc.queryForObject(
            """
            select count(*)
            from ap_approval_task task
            join ap_approval_instance instance
              on instance.tenant_id = task.tenant_id
             and instance.instance_id = task.instance_id
            where task.tenant_id = :tenantId
              and task.assignee_id = :assigneeId
              and task.status = 'PENDING'
              and instance.status = 'RUNNING'
              and (
                  :hasKeyword = false
                  or position(:keyword in lower(task.task_name)) > 0
                  or position(:keyword in lower(instance.business_key)) > 0
                  or position(:keyword in lower(instance.supplier)) > 0
                  or position(:keyword in lower(instance.purchase_order_reference)) > 0
              )
            """,
            parameters,
            Long.class
        );
        long matched = total == null ? 0 : total;
        if (matched == 0) {
            return new PendingTaskPage(List.of(), 0, criteria.limit(), criteria.offset());
        }

        List<PendingTaskItem> items = jdbc.query(
            """
            select
                task.task_id,
                task.instance_id,
                instance.definition_key,
                task.task_definition_key,
                task.task_name,
                instance.business_key,
                instance.initiator_id,
                instance.amount,
                instance.supplier,
                instance.purchase_order_reference,
                task.created_at as task_created_at,
                task.updated_at as task_updated_at
            from ap_approval_task task
            join ap_approval_instance instance
              on instance.tenant_id = task.tenant_id
             and instance.instance_id = task.instance_id
            where task.tenant_id = :tenantId
              and task.assignee_id = :assigneeId
              and task.status = 'PENDING'
              and instance.status = 'RUNNING'
              and (
                  :hasKeyword = false
                  or position(:keyword in lower(task.task_name)) > 0
                  or position(:keyword in lower(instance.business_key)) > 0
                  or position(:keyword in lower(instance.supplier)) > 0
                  or position(:keyword in lower(instance.purchase_order_reference)) > 0
              )
            order by task.created_at, task.task_id
            limit :limit offset :offset
            """,
            parameters,
            pendingTaskMapper()
        );
        return new PendingTaskPage(items, matched, criteria.limit(), criteria.offset());
    }

    @Override
    public Optional<PendingTaskDetails> findPendingTask(PendingTaskIdentity identity) {
        Objects.requireNonNull(identity, "identity must not be null");
        List<PendingTaskDetails> rows = jdbc.query(
            """
            select
                task.task_id,
                task.instance_id,
                task.assignee_id,
                instance.definition_key,
                instance.definition_version,
                instance.form_key,
                instance.form_version,
                instance.compiler_version,
                instance.content_hash,
                task.task_definition_key,
                task.task_name,
                instance.business_key,
                instance.initiator_id,
                instance.amount,
                instance.supplier,
                instance.purchase_order_reference,
                instance.attachment_ids_json,
                instance.assignee_snapshot_json,
                instance.created_at as instance_created_at,
                instance.updated_at as instance_updated_at,
                task.created_at as task_created_at,
                task.updated_at as task_updated_at
            from ap_approval_task task
            join ap_approval_instance instance
              on instance.tenant_id = task.tenant_id
             and instance.instance_id = task.instance_id
            where task.tenant_id = :tenantId
              and task.assignee_id = :assigneeId
              and task.task_id = :taskId
              and task.status = 'PENDING'
              and instance.status = 'RUNNING'
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", identity.tenantId())
                .addValue("assigneeId", identity.assigneeId())
                .addValue("taskId", identity.taskId()),
            pendingTaskDetailsMapper()
        );
        return rows.stream().findFirst();
    }

    private static MapSqlParameterSource parameters(PendingTaskCriteria criteria) {
        boolean hasKeyword = criteria.keyword() != null;
        return new MapSqlParameterSource()
            .addValue("tenantId", criteria.tenantId())
            .addValue("assigneeId", criteria.assigneeId())
            .addValue("hasKeyword", hasKeyword)
            .addValue(
                "keyword",
                hasKeyword ? criteria.keyword().toLowerCase(Locale.ROOT) : ""
            )
            .addValue("limit", criteria.limit())
            .addValue("offset", criteria.offset());
    }

    private static RowMapper<PendingTaskItem> pendingTaskMapper() {
        return (resultSet, rowNumber) -> new PendingTaskItem(
            resultSet.getObject("task_id", UUID.class),
            resultSet.getObject("instance_id", UUID.class),
            resultSet.getString("definition_key"),
            resultSet.getString("task_definition_key"),
            resultSet.getString("task_name"),
            resultSet.getString("business_key"),
            resultSet.getString("initiator_id"),
            resultSet.getBigDecimal("amount"),
            resultSet.getString("supplier"),
            resultSet.getString("purchase_order_reference"),
            instant(resultSet, "task_created_at"),
            instant(resultSet, "task_updated_at")
        );
    }

    private RowMapper<PendingTaskDetails> pendingTaskDetailsMapper() {
        return (resultSet, rowNumber) -> new PendingTaskDetails(
            resultSet.getObject("task_id", UUID.class),
            resultSet.getObject("instance_id", UUID.class),
            resultSet.getString("definition_key"),
            resultSet.getInt("definition_version"),
            resultSet.getString("form_key"),
            resultSet.getInt("form_version"),
            resultSet.getString("compiler_version"),
            resultSet.getString("content_hash"),
            resultSet.getString("task_definition_key"),
            resultSet.getString("task_name"),
            resultSet.getString("business_key"),
            resultSet.getString("initiator_id"),
            resultSet.getBigDecimal("amount"),
            resultSet.getString("supplier"),
            resultSet.getString("purchase_order_reference"),
            decodeStringList(resultSet.getString("attachment_ids_json")),
            decodeTransferCandidates(
                resultSet.getString("assignee_snapshot_json"),
                resultSet.getString("assignee_id")
            ),
            instant(resultSet, "instance_created_at"),
            instant(resultSet, "instance_updated_at"),
            instant(resultSet, "task_created_at"),
            instant(resultSet, "task_updated_at")
        );
    }

    private List<String> decodeStringList(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new SQLException("unable to decode task detail attachments", exception);
        }
    }

    private List<TransferCandidate> decodeTransferCandidates(
        String json,
        String currentAssigneeId
    ) throws SQLException {
        try {
            AssigneeSnapshot snapshot = objectMapper.readValue(json, AssigneeSnapshot.class);
            Map<String, String> candidates = new LinkedHashMap<>();
            addCandidate(candidates, snapshot, snapshot.managerAssignee(), currentAssigneeId);
            addCandidate(candidates, snapshot, snapshot.financeReviewer(), currentAssigneeId);
            for (String userId : snapshot.financeApprovers()) {
                addCandidate(candidates, snapshot, userId, currentAssigneeId);
            }
            for (String userId : snapshot.identities().keySet()) {
                addCandidate(candidates, snapshot, userId, currentAssigneeId);
            }
            return candidates.entrySet().stream()
                .map(entry -> new TransferCandidate(entry.getKey(), entry.getValue()))
                .toList();
        } catch (JsonProcessingException exception) {
            throw new SQLException("unable to decode transfer candidates", exception);
        }
    }

    private static void addCandidate(
        Map<String, String> candidates,
        AssigneeSnapshot snapshot,
        String userId,
        String currentAssigneeId
    ) {
        if (userId == null || userId.isBlank() || userId.equals(currentAssigneeId)) {
            return;
        }
        candidates.putIfAbsent(userId, displayName(snapshot, userId));
    }

    private static String displayName(AssigneeSnapshot snapshot, String userId) {
        UserIdentitySnapshot direct = snapshot.identities().get(userId);
        if (direct != null && direct.displayName() != null && !direct.displayName().isBlank()) {
            return direct.displayName();
        }
        return snapshot.identities().values().stream()
            .filter(identity -> userId.equals(identity.externalId()))
            .map(UserIdentitySnapshot::displayName)
            .filter(Objects::nonNull)
            .filter(value -> !value.isBlank())
            .findFirst()
            .orElse(userId);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
