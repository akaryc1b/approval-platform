package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalHandoverStore;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityReference;
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

/** PostgreSQL employee-handover policy and task-responsibility store. */
public final class JdbcApprovalHandoverStore implements ApprovalHandoverStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcApprovalHandoverStore(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
    }

    @Override
    public void lockPrincipal(String tenantId, String principalId) {
        advisoryLock(
            "handover-principal:"
                + requireText(tenantId, "tenantId")
                + ':'
                + requireText(principalId, "principalId")
        );
    }

    @Override
    public PrincipalHandover create(PrincipalHandover handover) {
        Objects.requireNonNull(handover, "handover must not be null");
        lockPrincipal(handover.tenantId(), handover.principalId());
        if (findActiveByPrincipal(handover.tenantId(), handover.principalId()).isPresent()) {
            throw new HandoverConflictException(
                "principal already has an active employee handover"
            );
        }
        int inserted = jdbc.update(
            """
            insert into ap_principal_handover (
                handover_id, tenant_id, connector_key,
                principal_id, principal_source, principal_object_type,
                principal_external_value,
                successor_id, successor_source, successor_object_type,
                successor_external_value,
                reason, status, created_by, created_at,
                revoked_by, revoked_at, revoke_reason, version
            ) values (
                :handoverId, :tenantId, :connectorKey,
                :principalId, :principalSource, :principalObjectType,
                :principalExternalValue,
                :successorId, :successorSource, :successorObjectType,
                :successorExternalValue,
                :reason, :status, :createdBy, :createdAt,
                null, null, null, :version
            )
            """,
            handoverParameters(handover)
        );
        if (inserted != 1) {
            throw new IllegalStateException("employee handover was not inserted");
        }
        return handover;
    }

    @Override
    public Optional<PrincipalHandover> findById(String tenantId, UUID handoverId) {
        List<PrincipalHandover> rows = jdbc.query(
            """
            select *
            from ap_principal_handover
            where tenant_id = :tenantId
              and handover_id = :handoverId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue(
                    "handoverId",
                    Objects.requireNonNull(handoverId, "handoverId must not be null")
                ),
            handoverMapper()
        );
        return rows.stream().findFirst();
    }

    @Override
    public Optional<PrincipalHandover> findActiveByPrincipal(
        String tenantId,
        String principalId
    ) {
        List<PrincipalHandover> rows = jdbc.query(
            """
            select *
            from ap_principal_handover
            where tenant_id = :tenantId
              and principal_id = :principalId
              and status = 'ACTIVE'
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue("principalId", requireText(principalId, "principalId")),
            handoverMapper()
        );
        return rows.stream().findFirst();
    }

    @Override
    public List<PrincipalHandover> findByPrincipal(
        String tenantId,
        String principalId,
        boolean includeRevoked
    ) {
        return List.copyOf(jdbc.query(
            """
            select *
            from ap_principal_handover
            where tenant_id = :tenantId
              and principal_id = :principalId
              and (:includeRevoked = true or status = 'ACTIVE')
            order by created_at desc, handover_id desc
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue("principalId", requireText(principalId, "principalId"))
                .addValue("includeRevoked", includeRevoked),
            handoverMapper()
        ));
    }

    @Override
    public PrincipalHandover revoke(
        String tenantId,
        UUID handoverId,
        String revokedBy,
        String revokeReason,
        Instant revokedAt
    ) {
        String normalizedTenant = requireText(tenantId, "tenantId");
        UUID normalizedId = Objects.requireNonNull(handoverId, "handoverId must not be null");
        String normalizedActor = requireText(revokedBy, "revokedBy");
        String normalizedReason = requireText(revokeReason, "revokeReason");
        Instant normalizedAt = Objects.requireNonNull(revokedAt, "revokedAt must not be null");
        PrincipalHandover current = findById(normalizedTenant, normalizedId)
            .orElseThrow(() -> new HandoverNotFoundException(
                "employee handover was not found"
            ));
        lockPrincipal(normalizedTenant, current.principalId());
        current = findById(normalizedTenant, normalizedId)
            .orElseThrow(() -> new HandoverNotFoundException(
                "employee handover was not found"
            ));
        if (current.status() != HandoverStatus.ACTIVE) {
            throw new HandoverConflictException(
                "only an active employee handover can be revoked"
            );
        }
        int updated = jdbc.update(
            """
            update ap_principal_handover
            set status = 'REVOKED',
                revoked_by = :revokedBy,
                revoked_at = :revokedAt,
                revoke_reason = :revokeReason,
                version = version + 1
            where tenant_id = :tenantId
              and handover_id = :handoverId
              and status = 'ACTIVE'
              and version = :version
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", normalizedTenant)
                .addValue("handoverId", normalizedId)
                .addValue("revokedBy", normalizedActor)
                .addValue("revokedAt", offset(normalizedAt))
                .addValue("revokeReason", normalizedReason)
                .addValue("version", current.version())
        );
        if (updated != 1) {
            throw new HandoverConflictException("employee handover changed concurrently");
        }
        return findById(normalizedTenant, normalizedId)
            .orElseThrow(() -> new IllegalStateException(
                "revoked employee handover disappeared"
            ));
    }

    @Override
    public List<PendingTask> findPendingTasksByPrincipal(
        String tenantId,
        String principalId
    ) {
        return List.copyOf(jdbc.query(
            """
            select
                task.task_id,
                task.instance_id,
                task.engine_task_id,
                instance.engine_instance_id,
                instance.definition_key,
                task.task_definition_key,
                task.task_name,
                task.assignee_id,
                task.version
            from ap_approval_task task
            join ap_approval_instance instance
              on instance.tenant_id = task.tenant_id
             and instance.instance_id = task.instance_id
            where task.tenant_id = :tenantId
              and task.assignee_id = :principalId
              and task.status = 'PENDING'
              and instance.status = 'RUNNING'
            order by task.created_at, task.task_id
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue("principalId", requireText(principalId, "principalId")),
            (resultSet, rowNumber) -> new PendingTask(
                resultSet.getObject("task_id", UUID.class),
                resultSet.getObject("instance_id", UUID.class),
                resultSet.getString("engine_task_id"),
                resultSet.getString("engine_instance_id"),
                resultSet.getString("definition_key"),
                resultSet.getString("task_definition_key"),
                resultSet.getString("task_name"),
                resultSet.getString("assignee_id"),
                resultSet.getLong("version")
            )
        ));
    }

    @Override
    public void lockEngineTask(String tenantId, String engineTaskId) {
        advisoryLock(
            "handover-task:"
                + requireText(tenantId, "tenantId")
                + ':'
                + requireText(engineTaskId, "engineTaskId")
        );
    }

    @Override
    public HandoverTaskAssignment createAssignment(HandoverTaskAssignment assignment) {
        Objects.requireNonNull(assignment, "assignment must not be null");
        int inserted = jdbc.update(
            """
            insert into ap_task_handover_assignment (
                assignment_id, tenant_id, engine_task_id, engine_instance_id,
                definition_key, task_definition_key,
                principal_assignee_id, successor_assignee_id,
                handover_id, status, assigned_at,
                completed_by, completed_at,
                superseded_assignee_id, superseded_at,
                canceled_at, version
            ) values (
                :assignmentId, :tenantId, :engineTaskId, :engineInstanceId,
                :definitionKey, :taskDefinitionKey,
                :principalAssigneeId, :successorAssigneeId,
                :handoverId, :status, :assignedAt,
                null, null, null, null, null, :version
            )
            on conflict (tenant_id, engine_task_id) do nothing
            """,
            assignmentParameters(assignment)
        );
        if (inserted == 1) {
            return assignment;
        }
        HandoverTaskAssignment existing = findAssignmentByEngineTask(
            assignment.tenantId(),
            assignment.engineTaskId()
        ).orElseThrow(() -> new IllegalStateException(
            "handover task assignment conflict could not be reloaded"
        ));
        if (!sameAssignmentIdentity(existing, assignment)) {
            throw new HandoverConflictException(
                "engine task already has different handover responsibility evidence"
            );
        }
        return existing;
    }

    @Override
    public Optional<HandoverTaskAssignment> findAssignmentByEngineTask(
        String tenantId,
        String engineTaskId
    ) {
        List<HandoverTaskAssignment> rows = jdbc.query(
            """
            select *
            from ap_task_handover_assignment
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
    public void markCompleted(
        String tenantId,
        String engineTaskId,
        String completedBy,
        Instant completedAt
    ) {
        updateActive(
            """
            update ap_task_handover_assignment
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
            completedAt
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
            update ap_task_handover_assignment
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
            supersededAt
        );
    }

    @Override
    public void markCanceled(String tenantId, String engineTaskId, Instant canceledAt) {
        updateActive(
            """
            update ap_task_handover_assignment
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
            canceledAt
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
            update ap_task_handover_assignment
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
        Instant changedAt
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

    private void advisoryLock(String lockKey) {
        jdbc.query(
            "select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
            new MapSqlParameterSource("lockKey", lockKey),
            resultSet -> null
        );
    }

    private static MapSqlParameterSource handoverParameters(PrincipalHandover handover) {
        return new MapSqlParameterSource()
            .addValue("handoverId", handover.handoverId())
            .addValue("tenantId", handover.tenantId())
            .addValue("connectorKey", handover.connectorKey())
            .addValue("principalId", handover.principalId())
            .addValue("principalSource", handover.principalIdentity().source())
            .addValue("principalObjectType", handover.principalIdentity().objectType())
            .addValue("principalExternalValue", handover.principalIdentity().value())
            .addValue("successorId", handover.successorId())
            .addValue("successorSource", handover.successorIdentity().source())
            .addValue("successorObjectType", handover.successorIdentity().objectType())
            .addValue("successorExternalValue", handover.successorIdentity().value())
            .addValue("reason", handover.reason())
            .addValue("status", handover.status().name())
            .addValue("createdBy", handover.createdBy())
            .addValue("createdAt", offset(handover.createdAt()))
            .addValue("version", handover.version());
    }

    private static MapSqlParameterSource assignmentParameters(
        HandoverTaskAssignment assignment
    ) {
        return new MapSqlParameterSource()
            .addValue("assignmentId", assignment.assignmentId())
            .addValue("tenantId", assignment.tenantId())
            .addValue("engineTaskId", assignment.engineTaskId())
            .addValue("engineInstanceId", assignment.engineInstanceId())
            .addValue("definitionKey", assignment.definitionKey())
            .addValue("taskDefinitionKey", assignment.taskDefinitionKey())
            .addValue("principalAssigneeId", assignment.principalAssigneeId())
            .addValue("successorAssigneeId", assignment.successorAssigneeId())
            .addValue("handoverId", assignment.handoverId())
            .addValue("status", assignment.status().name())
            .addValue("assignedAt", offset(assignment.assignedAt()))
            .addValue("version", assignment.version());
    }

    private static RowMapper<PrincipalHandover> handoverMapper() {
        return (resultSet, rowNumber) -> new PrincipalHandover(
            resultSet.getObject("handover_id", UUID.class),
            resultSet.getString("tenant_id"),
            resultSet.getString("connector_key"),
            resultSet.getString("principal_id"),
            identity(resultSet, "principal"),
            resultSet.getString("successor_id"),
            identity(resultSet, "successor"),
            resultSet.getString("reason"),
            HandoverStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("created_by"),
            instant(resultSet, "created_at"),
            resultSet.getString("revoked_by"),
            nullableInstant(resultSet, "revoked_at"),
            resultSet.getString("revoke_reason"),
            resultSet.getLong("version")
        );
    }

    private static RowMapper<HandoverTaskAssignment> assignmentMapper() {
        return (resultSet, rowNumber) -> new HandoverTaskAssignment(
            resultSet.getObject("assignment_id", UUID.class),
            resultSet.getString("tenant_id"),
            resultSet.getString("engine_task_id"),
            resultSet.getString("engine_instance_id"),
            resultSet.getString("definition_key"),
            resultSet.getString("task_definition_key"),
            resultSet.getString("principal_assignee_id"),
            resultSet.getString("successor_assignee_id"),
            resultSet.getObject("handover_id", UUID.class),
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

    private static IdentityReference identity(ResultSet resultSet, String prefix)
        throws SQLException {
        return new IdentityReference(
            resultSet.getString(prefix + "_source"),
            resultSet.getString(prefix + "_object_type"),
            resultSet.getString(prefix + "_external_value")
        );
    }

    private static boolean sameAssignmentIdentity(
        HandoverTaskAssignment left,
        HandoverTaskAssignment right
    ) {
        return left.engineInstanceId().equals(right.engineInstanceId())
            && left.definitionKey().equals(right.definitionKey())
            && left.taskDefinitionKey().equals(right.taskDefinitionKey())
            && left.principalAssigneeId().equals(right.principalAssigneeId())
            && left.successorAssigneeId().equals(right.successorAssigneeId())
            && left.handoverId().equals(right.handoverId());
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
