package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** PostgreSQL detect-only consistency scanner over platform-owned approval tables. */
public final class JdbcApprovalConsistencyStore implements ApprovalConsistencyStore {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;

    public JdbcApprovalConsistencyStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager
    ) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.transaction = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
    }

    @Override
    public ConsistencyCheck run(ConsistencyCheckRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        try {
            ConsistencyCheck result = transaction.execute(status -> executeRun(request));
            if (result == null) {
                throw new IllegalStateException("consistency check did not return a result");
            }
            return result;
        } catch (RuntimeException exception) {
            recordFailure(request, exception);
            throw exception;
        }
    }

    @Override
    public ConsistencyCheckPage findChecks(ConsistencyCheckCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", criteria.tenantId())
            .addValue("limit", criteria.limit())
            .addValue("offset", criteria.offset());
        String statusFilter = "";
        if (criteria.status() != null) {
            statusFilter = " and status = :status";
            parameters.addValue("status", criteria.status().name());
        }
        Long total = jdbc.queryForObject(
            "select count(*) from ap_consistency_check where tenant_id = :tenantId" + statusFilter,
            parameters,
            Long.class
        );
        long matched = total == null ? 0 : total;
        List<ConsistencyCheck> items = matched == 0
            ? List.of()
            : jdbc.query(
                """
                select *
                from ap_consistency_check
                where tenant_id = :tenantId
                %s
                order by started_at desc, check_id desc
                limit :limit offset :offset
                """.formatted(statusFilter),
                parameters,
                (resultSet, rowNumber) -> check(resultSet)
            );
        return new ConsistencyCheckPage(items, matched, criteria.limit(), criteria.offset());
    }

    @Override
    public ConsistencyFindingPage findFindings(ConsistencyFindingCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        requireCheck(criteria.tenantId(), criteria.checkId());
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", criteria.tenantId())
            .addValue("checkId", criteria.checkId())
            .addValue("limit", criteria.limit())
            .addValue("offset", criteria.offset());
        List<String> conditions = new ArrayList<>();
        conditions.add("tenant_id = :tenantId");
        conditions.add("check_id = :checkId");
        if (criteria.checkType() != null) {
            conditions.add("check_type = :checkType");
            parameters.addValue("checkType", criteria.checkType().name());
        }
        if (criteria.severity() != null) {
            conditions.add("severity = :severity");
            parameters.addValue("severity", criteria.severity().name());
        }
        String where = String.join(" and ", conditions);
        Long total = jdbc.queryForObject(
            "select count(*) from ap_consistency_finding where " + where,
            parameters,
            Long.class
        );
        long matched = total == null ? 0 : total;
        List<ConsistencyFinding> items = matched == 0
            ? List.of()
            : jdbc.query(
                """
                select *
                from ap_consistency_finding
                where %s
                order by
                    case severity when 'CRITICAL' then 1 when 'ERROR' then 2 else 3 end,
                    check_type,
                    detected_at,
                    finding_id
                limit :limit offset :offset
                """.formatted(where),
                parameters,
                (resultSet, rowNumber) -> finding(resultSet)
            );
        return new ConsistencyFindingPage(items, matched, criteria.limit(), criteria.offset());
    }

    private ConsistencyCheck executeRun(ConsistencyCheckRequest request) {
        insertRunningCheck(request);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", request.tenantId())
            .addValue("detectedAt", offset(request.startedAt()));
        List<Candidate> candidates = new ArrayList<>();
        candidates.addAll(scanInstanceTaskState(parameters));
        candidates.addAll(scanDelegationEvidence(parameters));
        candidates.addAll(scanHandoverEvidence(parameters));
        candidates.addAll(scanCollaborationPolicy(parameters));
        candidates.addAll(scanNotificationDelivery(parameters));
        candidates.addAll(scanCommentRevision(parameters));
        candidates.addAll(scanAttachmentReference(parameters));
        candidates.addAll(scanAuditBusinessEvidence(parameters));
        for (Candidate candidate : candidates) {
            insertFinding(request, candidate);
        }
        int updated = jdbc.update(
            """
            update ap_consistency_check
            set status = 'COMPLETED',
                completed_at = :completedAt,
                finding_count = :findingCount,
                version = version + 1
            where tenant_id = :tenantId
              and check_id = :checkId
              and status = 'RUNNING'
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", request.tenantId())
                .addValue("checkId", request.checkId())
                .addValue("completedAt", offset(request.startedAt()))
                .addValue("findingCount", candidates.size())
        );
        if (updated != 1) {
            throw new IllegalStateException("consistency check could not be completed");
        }
        return requireCheck(request.tenantId(), request.checkId());
    }

    private void insertRunningCheck(ConsistencyCheckRequest request) {
        int inserted = jdbc.update(
            """
            insert into ap_consistency_check (
                check_id, tenant_id, requested_by, request_id, trace_id,
                scope, status, started_at, completed_at, finding_count,
                error_code, error_message, version
            ) values (
                :checkId, :tenantId, :requestedBy, :requestId, :traceId,
                :scope, 'RUNNING', :startedAt, null, 0, null, null, 1
            )
            """,
            new MapSqlParameterSource()
                .addValue("checkId", request.checkId())
                .addValue("tenantId", request.tenantId())
                .addValue("requestedBy", request.requestedBy())
                .addValue("requestId", request.requestId())
                .addValue("traceId", request.traceId())
                .addValue("scope", request.scope().name())
                .addValue("startedAt", offset(request.startedAt()))
        );
        if (inserted != 1) {
            throw new IllegalStateException("consistency check was not inserted");
        }
    }

    private void recordFailure(ConsistencyCheckRequest request, RuntimeException exception) {
        String message = exception.getMessage() == null
            ? exception.getClass().getSimpleName()
            : exception.getMessage();
        String bounded = message.length() <= 2_000 ? message : message.substring(0, 2_000);
        transaction.executeWithoutResult(status -> jdbc.update(
            """
            insert into ap_consistency_check (
                check_id, tenant_id, requested_by, request_id, trace_id,
                scope, status, started_at, completed_at, finding_count,
                error_code, error_message, version
            ) values (
                :checkId, :tenantId, :requestedBy, :requestId, :traceId,
                :scope, 'FAILED', :startedAt, :completedAt, 0,
                'CONSISTENCY_SCAN_FAILED', :errorMessage, 1
            ) on conflict (tenant_id, check_id) do update
            set status = 'FAILED',
                completed_at = excluded.completed_at,
                finding_count = 0,
                error_code = excluded.error_code,
                error_message = excluded.error_message,
                version = ap_consistency_check.version + 1
            """,
            new MapSqlParameterSource()
                .addValue("checkId", request.checkId())
                .addValue("tenantId", request.tenantId())
                .addValue("requestedBy", request.requestedBy())
                .addValue("requestId", request.requestId())
                .addValue("traceId", request.traceId())
                .addValue("scope", request.scope().name())
                .addValue("startedAt", offset(request.startedAt()))
                .addValue("completedAt", offset(request.startedAt()))
                .addValue("errorMessage", bounded)
        ));
    }

    private List<Candidate> scanInstanceTaskState(MapSqlParameterSource parameters) {
        return jdbc.query(
            """
            with task_stats as (
                select
                    tenant_id,
                    instance_id,
                    count(*) filter (where status in ('PENDING', 'COMPLETING')) as active_tasks
                from ap_approval_task
                where tenant_id = :tenantId
                group by tenant_id, instance_id
            )
            select
                instance.instance_id,
                instance.status as instance_status,
                coalesce(stats.active_tasks, 0) as active_tasks,
                case
                    when instance.status = 'RUNNING' then 'RUNNING_WITHOUT_ACTIVE_TASK'
                    else 'TERMINAL_WITH_ACTIVE_TASK'
                end as issue_code
            from ap_approval_instance instance
            left join task_stats stats
              on stats.tenant_id = instance.tenant_id
             and stats.instance_id = instance.instance_id
            where instance.tenant_id = :tenantId
              and (
                  (instance.status = 'RUNNING' and coalesce(stats.active_tasks, 0) = 0)
                  or
                  (instance.status <> 'RUNNING' and coalesce(stats.active_tasks, 0) > 0)
              )
            """,
            parameters,
            (resultSet, rowNumber) -> new Candidate(
                CheckType.INSTANCE_TASK_STATE,
                Severity.ERROR,
                "APPROVAL_INSTANCE",
                resultSet.getObject("instance_id", UUID.class).toString(),
                details(
                    "issueCode", resultSet.getString("issue_code"),
                    "instanceStatus", resultSet.getString("instance_status"),
                    "activeTaskCount", resultSet.getLong("active_tasks")
                ),
                "Inspect the platform instance/task projections and replay synchronization through the existing application service or Engine SPI; do not update Flowable tables directly."
            )
        );
    }

    private List<Candidate> scanDelegationEvidence(MapSqlParameterSource parameters) {
        return jdbc.query(
            """
            select
                assignment.assignment_id,
                assignment.engine_task_id,
                assignment.delegate_assignee_id,
                assignment.delegation_scope,
                task.task_id,
                task.status as task_status,
                task.assignee_id,
                rule.scope as rule_scope,
                rule.definition_key as rule_definition_key,
                assignment.definition_key as assignment_definition_key,
                case
                    when task.task_id is null then 'PLATFORM_TASK_MISSING'
                    when task.status <> 'PENDING' then 'ACTIVE_ASSIGNMENT_FOR_NON_PENDING_TASK'
                    when task.assignee_id <> assignment.delegate_assignee_id then 'DELEGATE_ASSIGNEE_MISMATCH'
                    when assignment.delegation_scope <> rule.scope then 'RULE_SCOPE_MISMATCH'
                    when assignment.delegation_scope = 'DEFINITION'
                         and assignment.definition_key <> rule.definition_key
                        then 'RULE_DEFINITION_MISMATCH'
                    else 'UNKNOWN'
                end as issue_code
            from ap_task_delegation_assignment assignment
            join ap_delegation_rule rule
              on rule.tenant_id = assignment.tenant_id
             and rule.rule_id = assignment.delegation_rule_id
            left join ap_approval_task task
              on task.tenant_id = assignment.tenant_id
             and task.engine_task_id = assignment.engine_task_id
            where assignment.tenant_id = :tenantId
              and assignment.status = 'ACTIVE'
              and (
                  task.task_id is null
                  or task.status <> 'PENDING'
                  or task.assignee_id <> assignment.delegate_assignee_id
                  or assignment.delegation_scope <> rule.scope
                  or (
                      assignment.delegation_scope = 'DEFINITION'
                      and assignment.definition_key <> rule.definition_key
                  )
              )
            """,
            parameters,
            (resultSet, rowNumber) -> new Candidate(
                CheckType.DELEGATION_EVIDENCE,
                Severity.ERROR,
                "TASK_DELEGATION_ASSIGNMENT",
                resultSet.getObject("assignment_id", UUID.class).toString(),
                details(
                    "issueCode", resultSet.getString("issue_code"),
                    "engineTaskId", resultSet.getString("engine_task_id"),
                    "delegateAssigneeId", resultSet.getString("delegate_assignee_id"),
                    "taskStatus", resultSet.getString("task_status"),
                    "projectedAssigneeId", resultSet.getString("assignee_id"),
                    "assignmentScope", resultSet.getString("delegation_scope"),
                    "ruleScope", resultSet.getString("rule_scope"),
                    "assignmentDefinitionKey", resultSet.getString("assignment_definition_key"),
                    "ruleDefinitionKey", resultSet.getString("rule_definition_key")
                ),
                "Inspect delegation evidence and task synchronization. Any replay must use ApprovalDelegationService and the existing engine/projection ports."
            )
        );
    }

    private List<Candidate> scanHandoverEvidence(MapSqlParameterSource parameters) {
        return jdbc.query(
            """
            select
                assignment.assignment_id,
                assignment.engine_task_id,
                assignment.successor_assignee_id,
                assignment.principal_assignee_id,
                task.task_id,
                task.status as task_status,
                task.assignee_id,
                handover.principal_id as policy_principal_id,
                handover.successor_id as policy_successor_id,
                case
                    when task.task_id is null then 'PLATFORM_TASK_MISSING'
                    when task.status <> 'PENDING' then 'ACTIVE_ASSIGNMENT_FOR_NON_PENDING_TASK'
                    when task.assignee_id <> assignment.successor_assignee_id then 'SUCCESSOR_ASSIGNEE_MISMATCH'
                    when assignment.principal_assignee_id <> handover.principal_id then 'POLICY_PRINCIPAL_MISMATCH'
                    when assignment.successor_assignee_id <> handover.successor_id then 'POLICY_SUCCESSOR_MISMATCH'
                    else 'UNKNOWN'
                end as issue_code
            from ap_task_handover_assignment assignment
            join ap_principal_handover handover
              on handover.tenant_id = assignment.tenant_id
             and handover.handover_id = assignment.handover_id
            left join ap_approval_task task
              on task.tenant_id = assignment.tenant_id
             and task.engine_task_id = assignment.engine_task_id
            where assignment.tenant_id = :tenantId
              and assignment.status = 'ACTIVE'
              and (
                  task.task_id is null
                  or task.status <> 'PENDING'
                  or task.assignee_id <> assignment.successor_assignee_id
                  or assignment.principal_assignee_id <> handover.principal_id
                  or assignment.successor_assignee_id <> handover.successor_id
              )
            """,
            parameters,
            (resultSet, rowNumber) -> new Candidate(
                CheckType.HANDOVER_EVIDENCE,
                Severity.ERROR,
                "TASK_HANDOVER_ASSIGNMENT",
                resultSet.getObject("assignment_id", UUID.class).toString(),
                details(
                    "issueCode", resultSet.getString("issue_code"),
                    "engineTaskId", resultSet.getString("engine_task_id"),
                    "principalAssigneeId", resultSet.getString("principal_assignee_id"),
                    "successorAssigneeId", resultSet.getString("successor_assignee_id"),
                    "taskStatus", resultSet.getString("task_status"),
                    "projectedAssigneeId", resultSet.getString("assignee_id"),
                    "policyPrincipalId", resultSet.getString("policy_principal_id"),
                    "policySuccessorId", resultSet.getString("policy_successor_id")
                ),
                "Inspect handover evidence and task synchronization. Any replay must use ApprovalHandoverService and existing engine/projection ports."
            )
        );
    }

    private List<Candidate> scanCollaborationPolicy(MapSqlParameterSource parameters) {
        return jdbc.query(
            """
            with participant_stats as (
                select
                    tenant_id,
                    policy_id,
                    count(*) filter (where status not in ('REMOVED', 'CANCELED')) as active_participants,
                    count(*) filter (where status = 'PENDING') as pending_participants,
                    coalesce(sum(participant_weight) filter (
                        where status not in ('REMOVED', 'CANCELED')
                    ), 0) as active_weight
                from ap_task_collaboration_participant
                where tenant_id = :tenantId
                group by tenant_id, policy_id
            )
            select
                policy.policy_id,
                policy.task_id,
                policy.status as policy_status,
                policy.collaboration_mode,
                policy.approval_threshold,
                policy.approval_weight_threshold,
                task.status as task_status,
                coalesce(stats.active_participants, 0) as active_participants,
                coalesce(stats.pending_participants, 0) as pending_participants,
                coalesce(stats.active_weight, 0) as active_weight,
                case
                    when policy.status = 'ACTIVE' and task.status <> 'PENDING'
                        then 'ACTIVE_POLICY_FOR_NON_PENDING_TASK'
                    when policy.status = 'ACTIVE' and coalesce(stats.active_participants, 0) = 0
                        then 'ACTIVE_POLICY_WITHOUT_PARTICIPANTS'
                    when policy.status = 'ACTIVE'
                         and policy.collaboration_mode = 'VOTE'
                         and policy.approval_threshold > coalesce(stats.active_participants, 0)
                        then 'UNREACHABLE_VOTE_THRESHOLD'
                    when policy.status = 'ACTIVE'
                         and policy.collaboration_mode = 'WEIGHTED'
                         and policy.approval_weight_threshold > coalesce(stats.active_weight, 0)
                        then 'UNREACHABLE_WEIGHT_THRESHOLD'
                    when policy.status <> 'ACTIVE' and coalesce(stats.pending_participants, 0) > 0
                        then 'TERMINAL_POLICY_WITH_PENDING_PARTICIPANTS'
                    else 'UNKNOWN'
                end as issue_code
            from ap_task_collaboration_policy policy
            join ap_approval_task task
              on task.tenant_id = policy.tenant_id
             and task.task_id = policy.task_id
            left join participant_stats stats
              on stats.tenant_id = policy.tenant_id
             and stats.policy_id = policy.policy_id
            where policy.tenant_id = :tenantId
              and (
                  (policy.status = 'ACTIVE' and task.status <> 'PENDING')
                  or (policy.status = 'ACTIVE' and coalesce(stats.active_participants, 0) = 0)
                  or (
                      policy.status = 'ACTIVE'
                      and policy.collaboration_mode = 'VOTE'
                      and policy.approval_threshold > coalesce(stats.active_participants, 0)
                  )
                  or (
                      policy.status = 'ACTIVE'
                      and policy.collaboration_mode = 'WEIGHTED'
                      and policy.approval_weight_threshold > coalesce(stats.active_weight, 0)
                  )
                  or (policy.status <> 'ACTIVE' and coalesce(stats.pending_participants, 0) > 0)
              )
            """,
            parameters,
            (resultSet, rowNumber) -> {
                String issue = resultSet.getString("issue_code");
                Severity severity = issue.startsWith("UNREACHABLE")
                    ? Severity.CRITICAL
                    : issue.equals("TERMINAL_POLICY_WITH_PENDING_PARTICIPANTS")
                        ? Severity.WARNING
                        : Severity.ERROR;
                return new Candidate(
                    CheckType.COLLABORATION_POLICY,
                    severity,
                    "TASK_COLLABORATION_POLICY",
                    resultSet.getObject("policy_id", UUID.class).toString(),
                    details(
                        "issueCode", issue,
                        "taskId", resultSet.getObject("task_id", UUID.class),
                        "policyStatus", resultSet.getString("policy_status"),
                        "taskStatus", resultSet.getString("task_status"),
                        "mode", resultSet.getString("collaboration_mode"),
                        "approvalThreshold", resultSet.getObject("approval_threshold"),
                        "approvalWeightThreshold", resultSet.getObject("approval_weight_threshold"),
                        "activeParticipantCount", resultSet.getLong("active_participants"),
                        "pendingParticipantCount", resultSet.getLong("pending_participants"),
                        "activeWeight", resultSet.getLong("active_weight")
                    ),
                    "Inspect collaboration policy and participant evidence. Do not alter decisions or thresholds directly; use ApprovalTaskCollaborationService for any governed action."
                );
            }
        );
    }

    private List<Candidate> scanNotificationDelivery(MapSqlParameterSource parameters) {
        return jdbc.query(
            """
            with attempt_stats as (
                select
                    tenant_id,
                    intent_id,
                    count(*) as attempt_rows,
                    coalesce(max(attempt_number), 0) as max_attempt_number,
                    count(*) filter (where successful) as successful_attempts,
                    count(*) filter (where not successful) as failed_attempts
                from ap_notification_delivery_attempt
                where tenant_id = :tenantId
                group by tenant_id, intent_id
            )
            select
                intent.intent_id,
                intent.channel,
                intent.status,
                intent.attempt_count,
                intent.max_attempts,
                intent.locked_until,
                coalesce(stats.attempt_rows, 0) as attempt_rows,
                coalesce(stats.max_attempt_number, 0) as max_attempt_number,
                coalesce(stats.successful_attempts, 0) as successful_attempts,
                coalesce(stats.failed_attempts, 0) as failed_attempts,
                case
                    when intent.channel <> 'IN_APP'
                         and intent.status = 'DELIVERED'
                         and coalesce(stats.successful_attempts, 0) = 0
                        then 'DELIVERED_WITHOUT_SUCCESSFUL_ATTEMPT'
                    when intent.status = 'DEAD_LETTER'
                         and (
                             intent.attempt_count < intent.max_attempts
                             or coalesce(stats.failed_attempts, 0) = 0
                         )
                        then 'INVALID_DEAD_LETTER_EVIDENCE'
                    when intent.channel <> 'IN_APP'
                         and intent.attempt_count <> coalesce(stats.max_attempt_number, 0)
                        then 'ATTEMPT_COUNT_MISMATCH'
                    when intent.status = 'PROCESSING'
                         and intent.locked_until < :detectedAt
                        then 'EXPIRED_PROCESSING_LOCK'
                    else 'UNKNOWN'
                end as issue_code
            from ap_notification_intent intent
            left join attempt_stats stats
              on stats.tenant_id = intent.tenant_id
             and stats.intent_id = intent.intent_id
            where intent.tenant_id = :tenantId
              and (
                  (
                      intent.channel <> 'IN_APP'
                      and intent.status = 'DELIVERED'
                      and coalesce(stats.successful_attempts, 0) = 0
                  )
                  or (
                      intent.status = 'DEAD_LETTER'
                      and (
                          intent.attempt_count < intent.max_attempts
                          or coalesce(stats.failed_attempts, 0) = 0
                      )
                  )
                  or (
                      intent.channel <> 'IN_APP'
                      and intent.attempt_count <> coalesce(stats.max_attempt_number, 0)
                  )
                  or (
                      intent.status = 'PROCESSING'
                      and intent.locked_until < :detectedAt
                  )
              )
            """,
            parameters,
            (resultSet, rowNumber) -> {
                String issue = resultSet.getString("issue_code");
                return new Candidate(
                    CheckType.NOTIFICATION_DELIVERY,
                    issue.equals("EXPIRED_PROCESSING_LOCK") ? Severity.WARNING : Severity.ERROR,
                    "NOTIFICATION_INTENT",
                    resultSet.getObject("intent_id", UUID.class).toString(),
                    details(
                        "issueCode", issue,
                        "channel", resultSet.getString("channel"),
                        "status", resultSet.getString("status"),
                        "attemptCount", resultSet.getInt("attempt_count"),
                        "maxAttempts", resultSet.getInt("max_attempts"),
                        "attemptRows", resultSet.getLong("attempt_rows"),
                        "maxAttemptNumber", resultSet.getLong("max_attempt_number"),
                        "successfulAttempts", resultSet.getLong("successful_attempts"),
                        "failedAttempts", resultSet.getLong("failed_attempts"),
                        "lockedUntil", resultSet.getObject("locked_until", OffsetDateTime.class)
                    ),
                    "Inspect notification intent and attempt evidence. Use ApprovalNotificationService replay APIs only after authorization; do not merge notification and business Outbox state machines."
                );
            }
        );
    }

    private List<Candidate> scanCommentRevision(MapSqlParameterSource parameters) {
        return jdbc.query(
            """
            with revision_stats as (
                select
                    tenant_id,
                    comment_id,
                    max(revision_number) as max_revision,
                    (array_agg(revision_type order by revision_number desc))[1] as latest_type
                from ap_approval_comment_revision
                where tenant_id = :tenantId
                group by tenant_id, comment_id
            )
            select
                comment.comment_id,
                comment.status,
                comment.current_revision,
                stats.max_revision,
                stats.latest_type,
                case
                    when stats.max_revision is null then 'REVISION_EVIDENCE_MISSING'
                    when comment.current_revision <> stats.max_revision then 'CURRENT_REVISION_MISMATCH'
                    when comment.status = 'ACTIVE' and stats.latest_type = 'DELETE'
                        then 'ACTIVE_COMMENT_WITH_DELETE_REVISION'
                    when comment.status = 'DELETED' and stats.latest_type <> 'DELETE'
                        then 'DELETED_COMMENT_WITHOUT_DELETE_REVISION'
                    else 'UNKNOWN'
                end as issue_code
            from ap_approval_comment comment
            left join revision_stats stats
              on stats.tenant_id = comment.tenant_id
             and stats.comment_id = comment.comment_id
            where comment.tenant_id = :tenantId
              and (
                  stats.max_revision is null
                  or comment.current_revision <> stats.max_revision
                  or (comment.status = 'ACTIVE' and stats.latest_type = 'DELETE')
                  or (comment.status = 'DELETED' and stats.latest_type <> 'DELETE')
              )
            """,
            parameters,
            (resultSet, rowNumber) -> new Candidate(
                CheckType.COMMENT_REVISION,
                Severity.ERROR,
                "APPROVAL_COMMENT",
                resultSet.getObject("comment_id", UUID.class).toString(),
                details(
                    "issueCode", resultSet.getString("issue_code"),
                    "commentStatus", resultSet.getString("status"),
                    "currentRevision", resultSet.getInt("current_revision"),
                    "maxRevision", resultSet.getObject("max_revision"),
                    "latestRevisionType", resultSet.getString("latest_type")
                ),
                "Inspect comment and revision evidence through ApprovalCommentService. Do not rewrite historical revisions or tombstone evidence."
            )
        );
    }

    private List<Candidate> scanAttachmentReference(MapSqlParameterSource parameters) {
        List<Candidate> findings = new ArrayList<>();
        findings.addAll(jdbc.query(
            """
            select
                comment.comment_id,
                comment.instance_id as comment_instance_id,
                comment.author_id,
                reference.attachment_id,
                attachment.instance_id as attachment_instance_id,
                attachment.uploader_id,
                case
                    when attachment.attachment_id is null then 'ATTACHMENT_MISSING'
                    when attachment.instance_id is null then 'ATTACHMENT_NOT_BOUND'
                    when attachment.instance_id <> comment.instance_id then 'ATTACHMENT_INSTANCE_MISMATCH'
                    when attachment.uploader_id <> comment.author_id then 'ATTACHMENT_AUTHOR_MISMATCH'
                    else 'UNKNOWN'
                end as issue_code
            from ap_approval_comment comment
            cross join lateral (
                select value::uuid as attachment_id
                from jsonb_array_elements_text(comment.attachment_ids_json) value
            ) reference
            left join ap_approval_attachment attachment
              on attachment.tenant_id = comment.tenant_id
             and attachment.attachment_id = reference.attachment_id
            where comment.tenant_id = :tenantId
              and (
                  attachment.attachment_id is null
                  or attachment.instance_id is null
                  or attachment.instance_id <> comment.instance_id
                  or attachment.uploader_id <> comment.author_id
              )
            """,
            parameters,
            (resultSet, rowNumber) -> attachmentCandidate(resultSet, "CURRENT_COMMENT_REFERENCE")
        ));
        findings.addAll(jdbc.query(
            """
            select
                revision.comment_id,
                comment.instance_id as comment_instance_id,
                comment.author_id,
                reference.attachment_id,
                attachment.instance_id as attachment_instance_id,
                attachment.uploader_id,
                case
                    when attachment.attachment_id is null then 'ATTACHMENT_MISSING'
                    when attachment.instance_id is null then 'ATTACHMENT_NOT_BOUND'
                    when attachment.instance_id <> comment.instance_id then 'ATTACHMENT_INSTANCE_MISMATCH'
                    else 'UNKNOWN'
                end as issue_code
            from ap_approval_comment_revision revision
            join ap_approval_comment comment
              on comment.tenant_id = revision.tenant_id
             and comment.comment_id = revision.comment_id
            cross join lateral (
                select value::uuid as attachment_id
                from jsonb_array_elements_text(revision.attachment_ids_json) value
            ) reference
            left join ap_approval_attachment attachment
              on attachment.tenant_id = revision.tenant_id
             and attachment.attachment_id = reference.attachment_id
            where revision.tenant_id = :tenantId
              and (
                  attachment.attachment_id is null
                  or attachment.instance_id is null
                  or attachment.instance_id <> comment.instance_id
              )
            """,
            parameters,
            (resultSet, rowNumber) -> attachmentCandidate(resultSet, "REVISION_REFERENCE")
        ));
        return List.copyOf(findings);
    }

    private Candidate attachmentCandidate(ResultSet resultSet, String referenceType) throws SQLException {
        UUID attachmentId = resultSet.getObject("attachment_id", UUID.class);
        return new Candidate(
            CheckType.ATTACHMENT_REFERENCE,
            Severity.ERROR,
            "APPROVAL_ATTACHMENT",
            attachmentId.toString(),
            details(
                "issueCode", resultSet.getString("issue_code"),
                "referenceType", referenceType,
                "commentId", resultSet.getObject("comment_id", UUID.class),
                "commentInstanceId", resultSet.getObject("comment_instance_id", UUID.class),
                "attachmentInstanceId", resultSet.getObject("attachment_instance_id", UUID.class),
                "commentAuthorId", resultSet.getString("author_id"),
                "attachmentUploaderId", resultSet.getString("uploader_id")
            ),
            "Inspect attachment ownership and binding through ApprovalAttachmentService and ApprovalCommentService. Do not relink evidence directly in the database."
        );
    }

    private List<Candidate> scanAuditBusinessEvidence(MapSqlParameterSource parameters) {
        return jdbc.query(
            """
            select
                revision.comment_id,
                revision.revision_number,
                revision.revision_type,
                case revision.revision_type
                    when 'EDIT' then 'INSTANCE_COMMENT_EDITED'
                    when 'DELETE' then 'INSTANCE_COMMENT_DELETED'
                end as expected_action
            from ap_approval_comment_revision revision
            where revision.tenant_id = :tenantId
              and revision.revision_type in ('EDIT', 'DELETE')
              and not exists (
                  select 1
                  from ap_audit_event event
                  where event.tenant_id = revision.tenant_id
                    and event.action = case revision.revision_type
                        when 'EDIT' then 'INSTANCE_COMMENT_EDITED'
                        when 'DELETE' then 'INSTANCE_COMMENT_DELETED'
                    end
                    and event.attributes_json ->> 'commentId' = revision.comment_id::text
                    and event.attributes_json ->> 'commentRevision' = revision.revision_number::text
              )
            """,
            parameters,
            (resultSet, rowNumber) -> new Candidate(
                CheckType.AUDIT_BUSINESS_EVIDENCE,
                Severity.WARNING,
                "APPROVAL_COMMENT",
                resultSet.getObject("comment_id", UUID.class).toString(),
                details(
                    "issueCode", "COMMENT_REVISION_AUDIT_MISSING",
                    "revisionNumber", resultSet.getInt("revision_number"),
                    "revisionType", resultSet.getString("revision_type"),
                    "expectedAction", resultSet.getString("expected_action")
                ),
                "Inspect the originating comment transaction and audit sink. Do not synthesize or backdate audit evidence without an explicitly governed replay operation."
            )
        );
    }

    private void insertFinding(ConsistencyCheckRequest request, Candidate candidate) {
        int inserted = jdbc.update(
            """
            insert into ap_consistency_finding (
                finding_id, tenant_id, check_id, check_type, severity,
                aggregate_type, aggregate_id, detected_at,
                details_json, suggested_action
            ) values (
                :findingId, :tenantId, :checkId, :checkType, :severity,
                :aggregateType, :aggregateId, :detectedAt,
                cast(:detailsJson as jsonb), :suggestedAction
            )
            """,
            new MapSqlParameterSource()
                .addValue("findingId", UUID.randomUUID())
                .addValue("tenantId", request.tenantId())
                .addValue("checkId", request.checkId())
                .addValue("checkType", candidate.checkType().name())
                .addValue("severity", candidate.severity().name())
                .addValue("aggregateType", candidate.aggregateType())
                .addValue("aggregateId", candidate.aggregateId())
                .addValue("detectedAt", offset(request.startedAt()))
                .addValue("detailsJson", encode(candidate.details()))
                .addValue("suggestedAction", candidate.suggestedAction())
        );
        if (inserted != 1) {
            throw new IllegalStateException("consistency finding was not inserted");
        }
    }

    private ConsistencyCheck requireCheck(String tenantId, UUID checkId) {
        return jdbc.query(
            """
            select *
            from ap_consistency_check
            where tenant_id = :tenantId and check_id = :checkId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("checkId", checkId),
            (resultSet, rowNumber) -> check(resultSet)
        ).stream().findFirst().orElseThrow(() ->
            new ConsistencyCheckNotFoundException("consistency check was not found")
        );
    }

    private ConsistencyCheck check(ResultSet resultSet) throws SQLException {
        return new ConsistencyCheck(
            resultSet.getObject("check_id", UUID.class),
            resultSet.getString("tenant_id"),
            resultSet.getString("requested_by"),
            resultSet.getString("request_id"),
            resultSet.getString("trace_id"),
            CheckScope.valueOf(resultSet.getString("scope")),
            CheckStatus.valueOf(resultSet.getString("status")),
            instant(resultSet, "started_at"),
            nullableInstant(resultSet, "completed_at"),
            resultSet.getInt("finding_count"),
            resultSet.getString("error_code"),
            resultSet.getString("error_message"),
            resultSet.getLong("version")
        );
    }

    private ConsistencyFinding finding(ResultSet resultSet) throws SQLException {
        return new ConsistencyFinding(
            resultSet.getObject("finding_id", UUID.class),
            resultSet.getObject("check_id", UUID.class),
            CheckType.valueOf(resultSet.getString("check_type")),
            Severity.valueOf(resultSet.getString("severity")),
            resultSet.getString("aggregate_type"),
            resultSet.getString("aggregate_id"),
            instant(resultSet, "detected_at"),
            decode(resultSet.getString("details_json")),
            resultSet.getString("suggested_action")
        );
    }

    private String encode(Map<String, String> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("unable to encode consistency details", exception);
        }
    }

    private Map<String, String> decode(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, STRING_MAP);
        } catch (JsonProcessingException exception) {
            throw new SQLException("unable to decode consistency details", exception);
        }
    }

    private static Map<String, String> details(Object... values) {
        LinkedHashMap<String, String> details = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            String key = Objects.toString(values[index]);
            Object value = values[index + 1];
            if (value != null) {
                details.put(key, value.toString());
            }
        }
        return Map.copyOf(details);
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

    private record Candidate(
        CheckType checkType,
        Severity severity,
        String aggregateType,
        String aggregateId,
        Map<String, String> details,
        String suggestedAction
    ) {
    }
}
