package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalDelegationStore.DelegationScope;
import io.github.akaryc1b.approval.application.port.ApprovalTaskDelegationAssignmentStore;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL task-delegation evidence projection.
 */
public final class JdbcApprovalTaskDelegationAssignmentStore
    implements ApprovalTaskDelegationAssignmentStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcApprovalTaskDelegationAssignmentStore(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
    }

    @Override
    public void lockEngineTask(String tenantId, String engineTaskId) {
        String lockKey = requireText(tenantId, "tenantId")
            + '\u001f'
            + requireText(engineTaskId, "engineTaskId");
        jdbc.query(
            "select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
            new MapSqlParameterSource("lockKey", lockKey),
            resultSet -> null
        );
    }

    @Override
    public DelegatedTaskAssignment create(DelegatedTaskAssignment assignment) {
        Objects.requireNonNull(assignment, "assignment must not be null");
        int inserted = jdbc.update(
            """
            insert into ap_task_delegation_assignment (
                assignment_id, tenant_id, engine_task_id, engine_instance_id,
                definition_key, task_definition_key,
                principal_assignee_id, delegate_assignee_id,
                delegation_rule_id, delegation_scope,
                status, assigned_at,
                completed_by, completed_at,
                superseded_assignee_id, superseded_at,
                canceled_at, version
            ) values (
                :assignmentId, :tenantId, :engineTaskId, :engineInstanceId,
                :definitionKey, :taskDefinitionKey,
                :principalAssigneeId, :delegateAssigneeId,
                :delegationRuleId, :delegationScope,
                :status, :assignedAt,
                null, null, null, null, null, :version
            )
            on conflict (tenant_id, engine_task_id) do nothing
            """,
            parameters(assignment)
        );
        if (inserted == 1) {
            return assignment;
        }
        DelegatedTaskAssignment existing = findByEngineTask(
            assignment.tenantId(),
            assignment.engineTaskId()
        ).orElseThrow(() -> new IllegalStateException(
            "delegated task assignment conflict could not be reloaded"
        ));
        if (!sameIdentity(existing, assignment)) {
            throw new IllegalStateException(
                "engine task already has different delegation responsibility evidence"
            );
        }
        return existing;
    }

    @Override
    public Optional<DelegatedTaskAssignment> findByEngineTask(
        String tenantId,
        String engineTaskId
    ) {
        List<DelegatedTaskAssignment> rows = jdbc.query(
            """
            select *
            from ap_task_delegation_assignment
            where tenant_id = :tenantId
              and engine_task_id = :engineTaskId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue("engineTaskId", requireText(engineTaskId, "engineTaskId")),
            assignmentMapper()
        );
        return rows.stream().findFirst();
    }

    @Override
    public Optional<String> findDefinitionKeyByEngineInstance(
        String tenantId,
        String engineInstanceId
    ) {
        List<String> rows = jdbc.query(
            """
            select definition_key
            from ap_approval_instance
            where tenant_id = :tenantId
              and engine_instance_id = :engineInstanceId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue(
                    "engineInstanceId",
                    requireText(engineInstanceId, "engineInstanceId")
                ),
            (resultSet, rowNumber) -> resultSet.getString("definition_key")
        );
        return rows.stream().findFirst();
    }

    @Override
    public void markCompleted(
        String tenantId,
        String engineTaskId,
        String completedBy,
        Instant completedAt
    ) {
        updateActive(
            """
            update ap_task_delegation_assignment
            set status = 'COMPLETED',
                completed_by = :actorId,
                completed_at = :changedAt,
                version = version + 1
            where tenant_id = :tenantId
              and engine_task_id = :engineTaskId
              and status = 'ACTIVE'
            """,
            tenantId,
            engineTaskId,
            requireText(completedBy, "completedBy"),
            completedAt,
            null
        );
    }

    @Override
    public void markSuperseded(
        String tenantId,
        String engineTaskId,
        String targetAssigneeId,
        Instant supersededAt
    ) {
        updateActive(
            """
            update ap_task_delegation_assignment
            set status = 'SUPERSEDED',
                superseded_assignee_id = :actorId,
                superseded_at = :changedAt,
                version = version + 1
            where tenant_id = :tenantId
              and engine_task_id = :engineTaskId
              and status = 'ACTIVE'
            """,
            tenantId,
            engineTaskId,
            requireText(targetAssigneeId, "targetAssigneeId"),
            supersededAt,
            null
        );
    }

    @Override
    public void markCanceled(
        String tenantId,
        String engineTaskId,
        Instant canceledAt
    ) {
        updateActive(
            """
            update ap_task_delegation_assignment
            set status = 'CANCELED',
                canceled_at = :changedAt,
                version = version + 1
            where tenant_id = :tenantId
              and engine_task_id = :engineTaskId
              and status = 'ACTIVE'
            """,
            tenantId,
            engineTaskId,
            null,
            canceledAt,
            null
        );
    }

    @Override
    public void cancelActiveByEngineInstance(
        String tenantId,
        String engineInstanceId,
        Instant canceledAt
    ) {
        jdbc.update(
            """
            update ap_task_delegation_assignment
            set status = 'CANCELED',
                canceled_at = :canceledAt,
                version = version + 1
            where tenant_id = :tenantId
              and engine_instance_id = :engineInstanceId
              and status = 'ACTIVE'
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue(
                    "engineInstanceId",
                    requireText(engineInstanceId, "engineInstanceId")
                )
                .addValue(
                    "canceledAt",
                    offset(Objects.requireNonNull(canceledAt, "canceledAt must not be null"))
                )
        );
    }

    private void updateActive(
        String sql,
        String tenantId,
        String engineTaskId,
        String actorId,
        Instant changedAt,
        String ignored
    ) {
        jdbc.update(
            sql,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue("engineTaskId", requireText(engineTaskId, "engineTaskId"))
                .addValue("actorId", actorId)
                .addValue(
                    "changedAt",
                    offset(Objects.requireNonNull(changedAt, "changedAt must not be null"))
                )
        );
    }

    private static boolean sameIdentity(
        DelegatedTaskAssignment left,
        DelegatedTaskAssignment right
    ) {
        return left.engineInstanceId().equals(right.engineInstanceId())
            && left.definitionKey().equals(right.definitionKey())
            && left.taskDefinitionKey().equals(right.taskDefinitionKey())
            && left.principalAssigneeId().equals(right.principalAssigneeId())
            && left.delegateAssigneeId().equals(right.delegateAssigneeId())
            && left.delegationRuleId().equals(right.delegationRuleId());
    }

    private static MapSqlParameterSource parameters(DelegatedTaskAssignment assignment) {
        return new MapSqlParameterSource()
            .addValue("assignmentId", assignment.assignmentId())
            .addValue("tenantId", assignment.tenantId())
            .addValue("engineTaskId", assignment.engineTaskId())
            .addValue("engineInstanceId", assignment.engineInstanceId())
            .addValue("definitionKey", assignment.definitionKey())
            .addValue("taskDefinitionKey", assignment.taskDefinitionKey())
            .addValue("principalAssigneeId", assignment.principalAssigneeId())
            .addValue("delegateAssigneeId", assignment.delegateAssigneeId())
            .addValue("delegationRuleId", assignment.delegationRuleId())
            .addValue("delegationScope", assignment.delegationScope().name())
            .addValue("status", assignment.status().name())
            .addValue("assignedAt", offset(assignment.assignedAt()))
            .addValue("version", assignment.version());
    }

    private static RowMapper<DelegatedTaskAssignment> assignmentMapper() {
        return (resultSet, rowNumber) -> new DelegatedTaskAssignment(
            resultSet.getObject("assignment_id", UUID.class),
            resultSet.getString("tenant_id"),
            resultSet.getString("engine_task_id"),
            resultSet.getString("engine_instance_id"),
            resultSet.getString("definition_key"),
            resultSet.getString("task_definition_key"),
            resultSet.getString("principal_assignee_id"),
            resultSet.getString("delegate_assignee_id"),
            resultSet.getObject("delegation_rule_id", UUID.class),
            DelegationScope.valueOf(resultSet.getString("delegation_scope")),
            AssignmentStatus.valueOf(resultSet.getString("status")),
            instant(resultSet, "assigned_at"),
            resultSet.getString("completed_by"),
            nullableInstant(resultSet, "completed_at"),
            resultSet.getString("superseded_assignee_id"),
            nullableInstant(resultSet, "superseded_at"),
            nullableInstant(resultSet, "canceled_at"),
            resultSet.getLong("version")
        );
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column)
        throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
