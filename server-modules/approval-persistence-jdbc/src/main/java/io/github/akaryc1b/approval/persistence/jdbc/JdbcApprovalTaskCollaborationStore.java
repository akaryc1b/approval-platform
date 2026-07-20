package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityReference;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** PostgreSQL collaboration policy, voting threshold and participant evidence store. */
public final class JdbcApprovalTaskCollaborationStore implements ApprovalTaskCollaborationStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcApprovalTaskCollaborationStore(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
    }

    @Override
    public void lockTask(String tenantId, UUID taskId) {
        String lockKey = requireText(tenantId, "tenantId")
            + '\u001f'
            + Objects.requireNonNull(taskId, "taskId must not be null");
        jdbc.query(
            "select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
            new MapSqlParameterSource("lockKey", "task-collaboration:" + lockKey),
            resultSet -> null
        );
    }

    @Override
    public TaskCollaboration create(TaskCollaboration collaboration) {
        Objects.requireNonNull(collaboration, "collaboration must not be null");
        lockTask(collaboration.tenantId(), collaboration.taskId());
        if (findActiveByTask(collaboration.tenantId(), collaboration.taskId()).isPresent()) {
            throw conflict(
                "APPROVAL_TASK_COLLABORATION_STATE_CONFLICT",
                "task already has an active collaboration policy"
            );
        }
        int inserted = jdbc.update(
            """
            insert into ap_task_collaboration_policy (
                policy_id, tenant_id, task_id, instance_id,
                engine_task_id, engine_instance_id,
                definition_key, task_definition_key, task_name,
                owner_assignee_id, collaboration_mode,
                approval_threshold, approval_weight_threshold, status,
                reason, created_by, created_at,
                terminal_by, terminal_at, terminal_reason, version
            ) values (
                :policyId, :tenantId, :taskId, :instanceId,
                :engineTaskId, :engineInstanceId,
                :definitionKey, :taskDefinitionKey, :taskName,
                :ownerAssigneeId, :mode,
                :approvalThreshold, :approvalWeightThreshold, :status,
                :reason, :createdBy, :createdAt,
                null, null, null, :version
            )
            """,
            policyParameters(collaboration)
        );
        if (inserted != 1) {
            throw new IllegalStateException("task collaboration policy was not inserted");
        }
        insertParticipants(collaboration, collaboration.participants());
        return requireByPolicy(collaboration.tenantId(), collaboration.policyId());
    }

    @Override
    public Optional<TaskCollaboration> findByTask(String tenantId, UUID taskId) {
        List<UUID> rows = jdbc.query(
            """
            select policy_id
            from ap_task_collaboration_policy
            where tenant_id = :tenantId
              and task_id = :taskId
            order by case when status = 'ACTIVE' then 0 else 1 end,
                     created_at desc,
                     policy_id desc
            limit 1
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue("taskId", Objects.requireNonNull(taskId, "taskId must not be null")),
            (resultSet, rowNumber) -> resultSet.getObject("policy_id", UUID.class)
        );
        return rows.stream().findFirst().flatMap(policyId -> findByPolicyId(tenantId, policyId));
    }

    @Override
    public Optional<TaskCollaboration> findByParticipant(
        String tenantId,
        UUID participantId
    ) {
        List<UUID> rows = jdbc.query(
            """
            select policy_id
            from ap_task_collaboration_participant
            where tenant_id = :tenantId
              and participant_id = :participantId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue(
                    "participantId",
                    Objects.requireNonNull(participantId, "participantId must not be null")
                ),
            (resultSet, rowNumber) -> resultSet.getObject("policy_id", UUID.class)
        );
        return rows.stream().findFirst().flatMap(policyId -> findByPolicyId(tenantId, policyId));
    }

    @Override
    public List<PendingCollaborationTask> findPendingByParticipant(
        String tenantId,
        String participantUserId,
        int limit
    ) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return List.copyOf(jdbc.query(
            """
            select
                participant.participant_id,
                policy.policy_id,
                policy.task_id,
                policy.instance_id,
                policy.definition_key,
                policy.task_definition_key,
                policy.task_name,
                policy.owner_assignee_id,
                policy.collaboration_mode,
                policy.approval_threshold,
                policy.approval_weight_threshold,
                participant.participant_weight,
                progress.eligible_participant_count,
                progress.approved_count,
                progress.rejected_count,
                progress.pending_count,
                progress.total_weight,
                progress.approved_weight,
                progress.rejected_weight,
                progress.pending_weight,
                progress.maximum_reachable_approval_count,
                progress.maximum_reachable_approval_weight,
                policy.reason,
                participant.added_at
            from ap_task_collaboration_participant participant
            join ap_task_collaboration_policy policy
              on policy.tenant_id = participant.tenant_id
             and policy.policy_id = participant.policy_id
            join lateral (
                select
                    count(*) filter (where status <> 'REMOVED')::integer
                        as eligible_participant_count,
                    count(*) filter (where status = 'APPROVED')::integer as approved_count,
                    count(*) filter (where status = 'REJECTED')::integer as rejected_count,
                    count(*) filter (where status = 'PENDING')::integer as pending_count,
                    coalesce(sum(participant_weight) filter (where status <> 'REMOVED'), 0)::integer
                        as total_weight,
                    coalesce(sum(participant_weight) filter (where status = 'APPROVED'), 0)::integer
                        as approved_weight,
                    coalesce(sum(participant_weight) filter (where status = 'REJECTED'), 0)::integer
                        as rejected_weight,
                    coalesce(sum(participant_weight) filter (where status = 'PENDING'), 0)::integer
                        as pending_weight,
                    (
                        count(*) filter (where status = 'APPROVED')
                        + count(*) filter (where status = 'PENDING')
                    )::integer as maximum_reachable_approval_count,
                    coalesce(
                        sum(participant_weight) filter (
                            where status in ('APPROVED', 'PENDING')
                        ),
                        0
                    )::integer as maximum_reachable_approval_weight
                from ap_task_collaboration_participant aggregate_participant
                where aggregate_participant.tenant_id = policy.tenant_id
                  and aggregate_participant.policy_id = policy.policy_id
            ) progress on true
            join ap_approval_task task
              on task.tenant_id = policy.tenant_id
             and task.task_id = policy.task_id
            join ap_approval_instance instance
              on instance.tenant_id = task.tenant_id
             and instance.instance_id = task.instance_id
            where participant.tenant_id = :tenantId
              and participant.participant_user_id = :participantUserId
              and participant.status = 'PENDING'
              and policy.status = 'ACTIVE'
              and task.status = 'PENDING'
              and instance.status = 'RUNNING'
            order by participant.added_at, participant.participant_id
            limit :limit
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue(
                    "participantUserId",
                    requireText(participantUserId, "participantUserId")
                )
                .addValue("limit", limit),
            (resultSet, rowNumber) -> new PendingCollaborationTask(
                resultSet.getObject("participant_id", UUID.class),
                resultSet.getObject("policy_id", UUID.class),
                resultSet.getObject("task_id", UUID.class),
                resultSet.getObject("instance_id", UUID.class),
                resultSet.getString("definition_key"),
                resultSet.getString("task_definition_key"),
                resultSet.getString("task_name"),
                resultSet.getString("owner_assignee_id"),
                CollaborationMode.valueOf(resultSet.getString("collaboration_mode")),
                nullableInteger(resultSet, "approval_threshold"),
                nullableInteger(resultSet, "approval_weight_threshold"),
                resultSet.getInt("participant_weight"),
                new CollaborationProgress(
                    resultSet.getInt("eligible_participant_count"),
                    resultSet.getInt("approved_count"),
                    resultSet.getInt("rejected_count"),
                    resultSet.getInt("pending_count"),
                    resultSet.getInt("total_weight"),
                    resultSet.getInt("approved_weight"),
                    resultSet.getInt("rejected_weight"),
                    resultSet.getInt("pending_weight"),
                    resultSet.getInt("maximum_reachable_approval_count"),
                    resultSet.getInt("maximum_reachable_approval_weight")
                ),
                resultSet.getString("reason"),
                instant(resultSet, "added_at")
            )
        ));
    }

    @Override
    public TaskCollaboration addParticipants(
        String tenantId,
        UUID policyId,
        List<CollaborationParticipant> participants
    ) {
        if (participants == null || participants.isEmpty()) {
            throw new IllegalArgumentException("participants must not be empty");
        }
        TaskCollaboration current = requireByPolicy(tenantId, policyId);
        lockTask(current.tenantId(), current.taskId());
        current = requireByPolicy(tenantId, policyId);
        requireActive(current);
        requireNoDecisions(current);
        Set<String> existingUsers = new HashSet<>();
        for (CollaborationParticipant participant : current.participants()) {
            existingUsers.add(participant.participantUserId());
        }
        for (CollaborationParticipant participant : participants) {
            if (!participant.policyId().equals(current.policyId())
                || !participant.tenantId().equals(current.tenantId())) {
                throw new IllegalArgumentException(
                    "participant must belong to the collaboration policy"
                );
            }
            if (!existingUsers.add(participant.participantUserId())) {
                throw conflict(
                    "APPROVAL_TASK_COLLABORATION_DUPLICATE_PARTICIPANT",
                    "collaboration participant already exists"
                );
            }
        }
        int eligible = (int) current.participants().stream()
            .filter(item -> item.status() != ParticipantStatus.REMOVED)
            .count();
        if (eligible + participants.size() > 20) {
            throw new CollaborationValidationException(
                "APPROVAL_TASK_COLLABORATION_INVALID_PARTICIPANTS",
                "participants must not exceed 20 users"
            );
        }
        insertParticipants(current, participants);
        incrementPolicyVersion(current);
        return requireByPolicy(current.tenantId(), current.policyId());
    }

    @Override
    public TaskCollaboration removeParticipant(
        String tenantId,
        UUID participantId,
        String removedBy,
        String reason,
        Instant removedAt
    ) {
        TaskCollaboration current = requireByParticipant(tenantId, participantId);
        lockTask(current.tenantId(), current.taskId());
        current = requireByParticipant(tenantId, participantId);
        requireActive(current);
        requireNoDecisions(current);
        CollaborationParticipant participant = requireParticipant(current, participantId);
        if (participant.status() != ParticipantStatus.PENDING) {
            throw conflict(
                "APPROVAL_TASK_COLLABORATION_STATE_CONFLICT",
                "only a pending collaboration participant can be removed"
            );
        }
        long pending = current.participants().stream()
            .filter(item -> item.status() == ParticipantStatus.PENDING)
            .count();
        if (pending <= 1) {
            throw conflict(
                "APPROVAL_TASK_COLLABORATION_THRESHOLD_UNREACHABLE",
                "the final pending collaboration participant cannot be removed"
            );
        }
        validateThresholdAfterRemoval(current, participant);
        int updated = jdbc.update(
            """
            update ap_task_collaboration_participant
            set status = 'REMOVED',
                removed_by = :removedBy,
                removed_at = :removedAt,
                removal_reason = :reason,
                version = version + 1
            where tenant_id = :tenantId
              and participant_id = :participantId
              and status = 'PENDING'
              and version = :version
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", current.tenantId())
                .addValue("participantId", participant.participantId())
                .addValue("removedBy", requireText(removedBy, "removedBy"))
                .addValue("removedAt", offset(Objects.requireNonNull(
                    removedAt,
                    "removedAt must not be null"
                )))
                .addValue("reason", requireText(reason, "reason"))
                .addValue("version", participant.version())
        );
        if (updated != 1) {
            throw concurrentModification();
        }
        incrementPolicyVersion(current);
        return requireByPolicy(current.tenantId(), current.policyId());
    }

    @Override
    public TaskCollaboration decideParticipant(
        String tenantId,
        UUID participantId,
        String participantUserId,
        ParticipantDecision decision,
        String comment,
        Instant decidedAt
    ) {
        TaskCollaboration current = requireByParticipant(tenantId, participantId);
        lockTask(current.tenantId(), current.taskId());
        current = requireByParticipant(tenantId, participantId);
        requireActive(current);
        CollaborationParticipant participant = requireParticipant(current, participantId);
        String normalizedUser = requireText(participantUserId, "participantUserId");
        if (!participant.participantUserId().equals(normalizedUser)) {
            throw new CollaborationAuthorizationException(
                "collaboration participant is assigned to another operator"
            );
        }
        if (participant.status() != ParticipantStatus.PENDING) {
            throw conflict(
                "APPROVAL_TASK_COLLABORATION_STATE_CONFLICT",
                "only a pending collaboration participant can decide"
            );
        }
        ParticipantDecision normalizedDecision = Objects.requireNonNull(
            decision,
            "decision must not be null"
        );
        String normalizedComment = requireText(comment, "comment");
        Instant normalizedAt = Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        int updated = jdbc.update(
            """
            update ap_task_collaboration_participant
            set status = :status,
                decision_comment = :comment,
                decided_at = :decidedAt,
                version = version + 1
            where tenant_id = :tenantId
              and participant_id = :participantId
              and status = 'PENDING'
              and version = :version
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", current.tenantId())
                .addValue("participantId", participant.participantId())
                .addValue("status", normalizedDecision.name())
                .addValue("comment", normalizedComment)
                .addValue("decidedAt", offset(normalizedAt))
                .addValue("version", participant.version())
        );
        if (updated != 1) {
            throw concurrentModification();
        }

        TaskCollaboration afterDecision = requireByPolicy(current.tenantId(), current.policyId());
        CollaborationStatus terminal = terminalStatus(afterDecision);
        if (terminal == CollaborationStatus.ACTIVE) {
            incrementPolicyVersion(afterDecision);
            return requireByPolicy(afterDecision.tenantId(), afterDecision.policyId());
        }
        String terminalReason = terminal == CollaborationStatus.SATISFIED
            ? "collaboration approval threshold reached"
            : "collaboration approval threshold became impossible";
        int policyUpdated = jdbc.update(
            """
            update ap_task_collaboration_policy
            set status = :status,
                terminal_by = :terminalBy,
                terminal_at = :terminalAt,
                terminal_reason = :terminalReason,
                version = version + 1
            where tenant_id = :tenantId
              and policy_id = :policyId
              and status = 'ACTIVE'
              and version = :version
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", afterDecision.tenantId())
                .addValue("policyId", afterDecision.policyId())
                .addValue("status", terminal.name())
                .addValue("terminalBy", normalizedUser)
                .addValue("terminalAt", offset(normalizedAt))
                .addValue("terminalReason", terminalReason)
                .addValue("version", afterDecision.version())
        );
        if (policyUpdated != 1) {
            throw concurrentModification();
        }
        cancelPending(afterDecision, normalizedAt);
        return requireByPolicy(afterDecision.tenantId(), afterDecision.policyId());
    }

    @Override
    public Optional<TaskCollaboration> cancelActiveByTask(
        String tenantId,
        UUID taskId,
        String canceledBy,
        String reason,
        Instant canceledAt
    ) {
        String normalizedTenant = requireText(tenantId, "tenantId");
        UUID normalizedTask = Objects.requireNonNull(taskId, "taskId must not be null");
        lockTask(normalizedTenant, normalizedTask);
        Optional<TaskCollaboration> active = findActiveByTask(normalizedTenant, normalizedTask);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        TaskCollaboration current = active.get();
        Instant normalizedAt = Objects.requireNonNull(canceledAt, "canceledAt must not be null");
        int updated = jdbc.update(
            """
            update ap_task_collaboration_policy
            set status = 'CANCELED',
                terminal_by = :terminalBy,
                terminal_at = :terminalAt,
                terminal_reason = :terminalReason,
                version = version + 1
            where tenant_id = :tenantId
              and policy_id = :policyId
              and status = 'ACTIVE'
              and version = :version
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", current.tenantId())
                .addValue("policyId", current.policyId())
                .addValue("terminalBy", requireText(canceledBy, "canceledBy"))
                .addValue("terminalAt", offset(normalizedAt))
                .addValue("terminalReason", requireText(reason, "reason"))
                .addValue("version", current.version())
        );
        if (updated != 1) {
            throw concurrentModification();
        }
        cancelPending(current, normalizedAt);
        return findByPolicyId(current.tenantId(), current.policyId());
    }

    private void insertParticipants(
        TaskCollaboration collaboration,
        List<CollaborationParticipant> participants
    ) {
        for (CollaborationParticipant participant : participants) {
            if (!participant.policyId().equals(collaboration.policyId())
                || !participant.tenantId().equals(collaboration.tenantId())) {
                throw new IllegalArgumentException(
                    "participant must belong to the collaboration policy"
                );
            }
            int participantInserted = jdbc.update(
                """
                insert into ap_task_collaboration_participant (
                    participant_id, tenant_id, policy_id, participant_user_id,
                    identity_source, identity_object_type, identity_external_value,
                    participant_weight, status, added_by, added_at,
                    decision_comment, decided_at,
                    removed_by, removed_at, removal_reason,
                    canceled_at, version
                ) values (
                    :participantId, :tenantId, :policyId, :participantUserId,
                    :identitySource, :identityObjectType, :identityExternalValue,
                    :participantWeight, :status, :addedBy, :addedAt,
                    null, null, null, null, null, null, :version
                )
                """,
                participantParameters(participant)
            );
            if (participantInserted != 1) {
                throw new IllegalStateException(
                    "task collaboration participant was not inserted"
                );
            }
        }
    }

    private void cancelPending(TaskCollaboration collaboration, Instant canceledAt) {
        jdbc.update(
            """
            update ap_task_collaboration_participant
            set status = 'CANCELED',
                canceled_at = :canceledAt,
                version = version + 1
            where tenant_id = :tenantId
              and policy_id = :policyId
              and status = 'PENDING'
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", collaboration.tenantId())
                .addValue("policyId", collaboration.policyId())
                .addValue("canceledAt", offset(canceledAt))
        );
    }

    private Optional<TaskCollaboration> findActiveByTask(String tenantId, UUID taskId) {
        List<UUID> rows = jdbc.query(
            """
            select policy_id
            from ap_task_collaboration_policy
            where tenant_id = :tenantId
              and task_id = :taskId
              and status = 'ACTIVE'
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue("taskId", Objects.requireNonNull(taskId, "taskId must not be null")),
            (resultSet, rowNumber) -> resultSet.getObject("policy_id", UUID.class)
        );
        return rows.stream().findFirst().flatMap(policyId -> findByPolicyId(tenantId, policyId));
    }

    private Optional<TaskCollaboration> findByPolicyId(String tenantId, UUID policyId) {
        List<PolicyRow> policies = jdbc.query(
            """
            select *
            from ap_task_collaboration_policy
            where tenant_id = :tenantId
              and policy_id = :policyId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue("policyId", Objects.requireNonNull(policyId, "policyId must not be null")),
            policyMapper()
        );
        if (policies.isEmpty()) {
            return Optional.empty();
        }
        PolicyRow policy = policies.getFirst();
        List<CollaborationParticipant> participants = jdbc.query(
            """
            select *
            from ap_task_collaboration_participant
            where tenant_id = :tenantId
              and policy_id = :policyId
            order by added_at, participant_id
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", policy.tenantId())
                .addValue("policyId", policy.policyId()),
            participantMapper()
        );
        return Optional.of(new TaskCollaboration(
            policy.policyId(),
            policy.tenantId(),
            policy.taskId(),
            policy.instanceId(),
            policy.engineTaskId(),
            policy.engineInstanceId(),
            policy.definitionKey(),
            policy.taskDefinitionKey(),
            policy.taskName(),
            policy.ownerAssigneeId(),
            policy.mode(),
            policy.approvalThreshold(),
            policy.approvalWeightThreshold(),
            policy.status(),
            policy.reason(),
            policy.createdBy(),
            policy.createdAt(),
            policy.terminalBy(),
            policy.terminalAt(),
            policy.terminalReason(),
            policy.version(),
            participants
        ));
    }

    private TaskCollaboration requireByParticipant(String tenantId, UUID participantId) {
        return findByParticipant(tenantId, participantId)
            .orElseThrow(() -> new CollaborationNotFoundException(
                "task collaboration participant was not found"
            ));
    }

    private TaskCollaboration requireByPolicy(String tenantId, UUID policyId) {
        return findByPolicyId(tenantId, policyId)
            .orElseThrow(() -> new CollaborationNotFoundException(
                "task collaboration policy was not found"
            ));
    }

    private static CollaborationParticipant requireParticipant(
        TaskCollaboration collaboration,
        UUID participantId
    ) {
        return collaboration.participants().stream()
            .filter(item -> item.participantId().equals(participantId))
            .findFirst()
            .orElseThrow(() -> new CollaborationNotFoundException(
                "task collaboration participant was not found"
            ));
    }

    private static void requireActive(TaskCollaboration collaboration) {
        if (collaboration.status() != CollaborationStatus.ACTIVE) {
            throw conflict(
                "APPROVAL_TASK_COLLABORATION_STATE_CONFLICT",
                "only an active task collaboration policy can be changed"
            );
        }
    }

    private static void requireNoDecisions(TaskCollaboration collaboration) {
        if (collaboration.participants().stream().anyMatch(item ->
            item.status() == ParticipantStatus.APPROVED
                || item.status() == ParticipantStatus.REJECTED
        )) {
            throw conflict(
                "APPROVAL_TASK_COLLABORATION_DECISIONS_STARTED",
                "participants cannot be changed after collaboration decisions begin"
            );
        }
    }

    private static void validateThresholdAfterRemoval(
        TaskCollaboration collaboration,
        CollaborationParticipant removed
    ) {
        int remainingCount = (int) collaboration.participants().stream()
            .filter(item -> item.status() != ParticipantStatus.REMOVED)
            .filter(item -> !item.participantId().equals(removed.participantId()))
            .count();
        int remainingWeight = collaboration.participants().stream()
            .filter(item -> item.status() != ParticipantStatus.REMOVED)
            .filter(item -> !item.participantId().equals(removed.participantId()))
            .mapToInt(CollaborationParticipant::weight)
            .sum();
        if (collaboration.mode() == CollaborationMode.VOTE
            && collaboration.approvalThreshold() > remainingCount) {
            throw conflict(
                "APPROVAL_TASK_COLLABORATION_THRESHOLD_UNREACHABLE",
                "removing the participant would make the vote threshold unreachable"
            );
        }
        if (collaboration.mode() == CollaborationMode.WEIGHTED
            && collaboration.approvalWeightThreshold() > remainingWeight) {
            throw conflict(
                "APPROVAL_TASK_COLLABORATION_THRESHOLD_UNREACHABLE",
                "removing the participant would make the weight threshold unreachable"
            );
        }
    }

    private void incrementPolicyVersion(TaskCollaboration collaboration) {
        int updated = jdbc.update(
            """
            update ap_task_collaboration_policy
            set version = version + 1
            where tenant_id = :tenantId
              and policy_id = :policyId
              and status = 'ACTIVE'
              and version = :version
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", collaboration.tenantId())
                .addValue("policyId", collaboration.policyId())
                .addValue("version", collaboration.version())
        );
        if (updated != 1) {
            throw concurrentModification();
        }
    }

    private static CollaborationStatus terminalStatus(TaskCollaboration collaboration) {
        var progress = collaboration.progress();
        return switch (collaboration.mode()) {
            case ALL -> {
                if (progress.rejectedCount() > 0) {
                    yield CollaborationStatus.REJECTED;
                }
                yield progress.pendingCount() == 0
                    ? CollaborationStatus.SATISFIED
                    : CollaborationStatus.ACTIVE;
            }
            case ANY -> {
                if (progress.approvedCount() > 0) {
                    yield CollaborationStatus.SATISFIED;
                }
                yield progress.pendingCount() == 0
                    ? CollaborationStatus.REJECTED
                    : CollaborationStatus.ACTIVE;
            }
            case VOTE -> {
                if (progress.approvedCount() >= collaboration.approvalThreshold()) {
                    yield CollaborationStatus.SATISFIED;
                }
                yield progress.maximumReachableApprovalCount()
                    < collaboration.approvalThreshold()
                    ? CollaborationStatus.REJECTED
                    : CollaborationStatus.ACTIVE;
            }
            case WEIGHTED -> {
                if (progress.approvedWeight() >= collaboration.approvalWeightThreshold()) {
                    yield CollaborationStatus.SATISFIED;
                }
                yield progress.maximumReachableApprovalWeight()
                    < collaboration.approvalWeightThreshold()
                    ? CollaborationStatus.REJECTED
                    : CollaborationStatus.ACTIVE;
            }
        };
    }

    private static MapSqlParameterSource policyParameters(TaskCollaboration collaboration) {
        return new MapSqlParameterSource()
            .addValue("policyId", collaboration.policyId())
            .addValue("tenantId", collaboration.tenantId())
            .addValue("taskId", collaboration.taskId())
            .addValue("instanceId", collaboration.instanceId())
            .addValue("engineTaskId", collaboration.engineTaskId())
            .addValue("engineInstanceId", collaboration.engineInstanceId())
            .addValue("definitionKey", collaboration.definitionKey())
            .addValue("taskDefinitionKey", collaboration.taskDefinitionKey())
            .addValue("taskName", collaboration.taskName())
            .addValue("ownerAssigneeId", collaboration.ownerAssigneeId())
            .addValue("mode", collaboration.mode().name())
            .addValue("approvalThreshold", collaboration.approvalThreshold())
            .addValue("approvalWeightThreshold", collaboration.approvalWeightThreshold())
            .addValue("status", collaboration.status().name())
            .addValue("reason", collaboration.reason())
            .addValue("createdBy", collaboration.createdBy())
            .addValue("createdAt", offset(collaboration.createdAt()))
            .addValue("version", collaboration.version());
    }

    private static MapSqlParameterSource participantParameters(
        CollaborationParticipant participant
    ) {
        return new MapSqlParameterSource()
            .addValue("participantId", participant.participantId())
            .addValue("tenantId", participant.tenantId())
            .addValue("policyId", participant.policyId())
            .addValue("participantUserId", participant.participantUserId())
            .addValue("identitySource", participant.identity().source())
            .addValue("identityObjectType", participant.identity().objectType())
            .addValue("identityExternalValue", participant.identity().value())
            .addValue("participantWeight", participant.weight())
            .addValue("status", participant.status().name())
            .addValue("addedBy", participant.addedBy())
            .addValue("addedAt", offset(participant.addedAt()))
            .addValue("version", participant.version());
    }

    private static RowMapper<PolicyRow> policyMapper() {
        return (resultSet, rowNumber) -> new PolicyRow(
            resultSet.getObject("policy_id", UUID.class),
            resultSet.getString("tenant_id"),
            resultSet.getObject("task_id", UUID.class),
            resultSet.getObject("instance_id", UUID.class),
            resultSet.getString("engine_task_id"),
            resultSet.getString("engine_instance_id"),
            resultSet.getString("definition_key"),
            resultSet.getString("task_definition_key"),
            resultSet.getString("task_name"),
            resultSet.getString("owner_assignee_id"),
            CollaborationMode.valueOf(resultSet.getString("collaboration_mode")),
            nullableInteger(resultSet, "approval_threshold"),
            nullableInteger(resultSet, "approval_weight_threshold"),
            CollaborationStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("reason"),
            resultSet.getString("created_by"),
            instant(resultSet, "created_at"),
            resultSet.getString("terminal_by"),
            nullableInstant(resultSet, "terminal_at"),
            resultSet.getString("terminal_reason"),
            resultSet.getLong("version")
        );
    }

    private static RowMapper<CollaborationParticipant> participantMapper() {
        return (resultSet, rowNumber) -> new CollaborationParticipant(
            resultSet.getObject("participant_id", UUID.class),
            resultSet.getObject("policy_id", UUID.class),
            resultSet.getString("tenant_id"),
            resultSet.getString("participant_user_id"),
            new IdentityReference(
                resultSet.getString("identity_source"),
                resultSet.getString("identity_object_type"),
                resultSet.getString("identity_external_value")
            ),
            resultSet.getInt("participant_weight"),
            ParticipantStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("added_by"),
            instant(resultSet, "added_at"),
            resultSet.getString("decision_comment"),
            nullableInstant(resultSet, "decided_at"),
            resultSet.getString("removed_by"),
            nullableInstant(resultSet, "removed_at"),
            resultSet.getString("removal_reason"),
            nullableInstant(resultSet, "canceled_at"),
            resultSet.getLong("version")
        );
    }

    private static Integer nullableInteger(ResultSet resultSet, String column)
        throws SQLException {
        return resultSet.getObject(column, Integer.class);
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

    private static CollaborationConflictException concurrentModification() {
        return conflict(
            "APPROVAL_TASK_COLLABORATION_CONCURRENT_MODIFICATION",
            "task collaboration policy changed concurrently"
        );
    }

    private static CollaborationConflictException conflict(String code, String message) {
        return new CollaborationConflictException(code, message);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private record PolicyRow(
        UUID policyId,
        String tenantId,
        UUID taskId,
        UUID instanceId,
        String engineTaskId,
        String engineInstanceId,
        String definitionKey,
        String taskDefinitionKey,
        String taskName,
        String ownerAssigneeId,
        CollaborationMode mode,
        Integer approvalThreshold,
        Integer approvalWeightThreshold,
        CollaborationStatus status,
        String reason,
        String createdBy,
        Instant createdAt,
        String terminalBy,
        Instant terminalAt,
        String terminalReason,
        long version
    ) {
    }
}
