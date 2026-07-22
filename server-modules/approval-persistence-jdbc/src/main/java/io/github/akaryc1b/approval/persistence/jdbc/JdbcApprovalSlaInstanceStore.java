package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.ResponsibilityChange;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstance;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstanceCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstancePage;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTerminalReason;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaMappings.instanceParameters;
import static io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaMappings.offset;
import static io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaMappings.params;
import static io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaMappings.responsibilityChangeMapper;
import static io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaMappings.responsibilityChangeParameters;

final class JdbcApprovalSlaInstanceStore extends JdbcApprovalSlaStoreSupport {

    JdbcApprovalSlaInstanceStore(DataSource dataSource, PlatformTransactionManager transactionManager) {
        super(dataSource, transactionManager);
    }

    public int createInstances(List<SlaInstance> instances) {
        if (instances == null || instances.isEmpty()) {
            return 0;
        }
        int inserted = 0;
        for (SlaInstance instance : instances) {
            inserted += jdbc.update(
                """
                insert into ap_sla_instance (
                    sla_instance_id,tenant_id,approval_instance_id,task_id,collaboration_participant_id,
                    definition_key,task_definition_key,target_type,policy_id,policy_version,
                    calendar_id,calendar_version,time_zone,responsible_user_id,original_responsible_user_id,
                    started_at,due_at,next_reminder_at,overdue_at,paused_at,pause_reason,
                    accumulated_paused_millis,terminal_at,terminal_reason,status,last_action_sequence,
                    request_id,trace_id,version,created_at,updated_at
                )
                select
                    :slaInstanceId,:tenantId,:approvalInstanceId,:taskId,:collaborationParticipantId,
                    :definitionKey,:taskDefinitionKey,:targetType,:policyId,:policyVersion,
                    :calendarId,:calendarVersion,:timeZone,:responsibleUserId,:originalResponsibleUserId,
                    :startedAt,:dueAt,:nextReminderAt,:overdueAt,:pausedAt,:pauseReason,
                    :accumulatedPausedMillis,:terminalAt,:terminalReason,:status,:lastActionSequence,
                    :requestId,:traceId,:version,:createdAt,:updatedAt
                where exists (
                    select 1 from ap_approval_instance
                    where tenant_id=:tenantId and instance_id=:approvalInstanceId and status='RUNNING'
                ) and (
                    :taskId is null or exists (
                        select 1 from ap_approval_task
                        where tenant_id=:tenantId and task_id=:taskId and status='PENDING'
                    )
                ) and (
                    :collaborationParticipantId is null or exists (
                        select 1 from ap_task_collaboration_participant
                        where tenant_id=:tenantId and participant_id=:collaborationParticipantId
                          and status='PENDING'
                    )
                ) on conflict do nothing
                """,
                instanceParameters(instance)
            );
        }
        return inserted;
    }
    public Optional<SlaInstance> findInstance(String tenantId, UUID slaInstanceId) {
        return queryInstances(
            "select * from ap_sla_instance where tenant_id=:tenantId and sla_instance_id=:slaInstanceId",
            params("tenantId", tenantId, "slaInstanceId", slaInstanceId)
        ).stream().findFirst();
    }
    public Optional<SlaInstance> findActiveProcessInstance(String tenantId, UUID approvalInstanceId) {
        return queryInstances(
            """
            select * from ap_sla_instance where tenant_id=:tenantId
              and approval_instance_id=:approvalInstanceId and target_type='PROCESS'
              and status in ('ACTIVE','PAUSED') order by created_at desc limit 1
            """,
            params("tenantId", tenantId, "approvalInstanceId", approvalInstanceId)
        ).stream().findFirst();
    }
    public Optional<SlaInstance> findActiveTaskInstance(String tenantId, UUID taskId) {
        return queryInstances(
            """
            select * from ap_sla_instance where tenant_id=:tenantId and task_id=:taskId
              and target_type='TASK' and status in ('ACTIVE','PAUSED')
              order by created_at desc limit 1
            """,
            params("tenantId", tenantId, "taskId", taskId)
        ).stream().findFirst();
    }
    public Optional<SlaInstance> findActiveCollaborationInstance(
        String tenantId,
        UUID collaborationParticipantId
    ) {
        return queryInstances(
            """
            select * from ap_sla_instance where tenant_id=:tenantId
              and collaboration_participant_id=:collaborationParticipantId
              and status in ('ACTIVE','PAUSED') order by created_at desc limit 1
            """,
            params(
                "tenantId", tenantId,
                "collaborationParticipantId", collaborationParticipantId
            )
        ).stream().findFirst();
    }
    public List<SlaInstance> findActiveByApprovalInstance(String tenantId, UUID approvalInstanceId) {
        return queryInstances(
            """
            select * from ap_sla_instance where tenant_id=:tenantId
              and approval_instance_id=:approvalInstanceId and status in ('ACTIVE','PAUSED')
            order by created_at,sla_instance_id
            """,
            params("tenantId", tenantId, "approvalInstanceId", approvalInstanceId)
        );
    }
    public SlaInstance pause(
        String tenantId,
        UUID slaInstanceId,
        long expectedVersion,
        Instant pausedAt,
        String reason
    ) {
        int changed = jdbc.update(
            """
            update ap_sla_instance set status='PAUSED',paused_at=:pausedAt,pause_reason=:reason,
                updated_at=:pausedAt,version=version+1
            where tenant_id=:tenantId and sla_instance_id=:slaInstanceId
              and status='ACTIVE' and version=:expectedVersion
            """,
            params(
                "tenantId", tenantId,
                "slaInstanceId", slaInstanceId,
                "expectedVersion", expectedVersion,
                "pausedAt", offset(pausedAt),
                "reason", reason
            )
        );
        if (changed != 1) {
            throw conflict("APPROVAL_SLA_INSTANCE_STATE_CONFLICT", "SLA pause changed concurrently");
        }
        return findInstance(tenantId, slaInstanceId).orElseThrow();
    }
    public SlaInstance resume(
        String tenantId,
        UUID slaInstanceId,
        long expectedVersion,
        Instant dueAt,
        Instant nextReminderAt,
        Instant overdueAt,
        Duration accumulatedPausedDuration,
        Instant resumedAt
    ) {
        int changed = jdbc.update(
            """
            update ap_sla_instance set status='ACTIVE',paused_at=null,pause_reason=null,
                due_at=:dueAt,next_reminder_at=:nextReminderAt,overdue_at=:overdueAt,
                accumulated_paused_millis=:accumulatedPausedMillis,
                updated_at=:resumedAt,version=version+1
            where tenant_id=:tenantId and sla_instance_id=:slaInstanceId
              and status='PAUSED' and version=:expectedVersion
            """,
            params(
                "tenantId", tenantId,
                "slaInstanceId", slaInstanceId,
                "expectedVersion", expectedVersion,
                "dueAt", offset(dueAt),
                "nextReminderAt", offset(nextReminderAt),
                "overdueAt", offset(overdueAt),
                "accumulatedPausedMillis", accumulatedPausedDuration.toMillis(),
                "resumedAt", offset(resumedAt)
            )
        );
        if (changed != 1) {
            throw conflict("APPROVAL_SLA_INSTANCE_STATE_CONFLICT", "SLA resume changed concurrently");
        }
        return findInstance(tenantId, slaInstanceId).orElseThrow();
    }
    public int terminalTask(String tenantId, UUID taskId, SlaTerminalReason reason, Instant terminalAt) {
        return terminal(
            "task_id=:targetId",
            params("tenantId", tenantId, "targetId", taskId),
            reason,
            terminalAt
        );
    }
    public int terminalCollaborationParticipant(
        String tenantId,
        UUID collaborationParticipantId,
        SlaTerminalReason reason,
        Instant terminalAt
    ) {
        return terminal(
            "collaboration_participant_id=:targetId",
            params("tenantId", tenantId, "targetId", collaborationParticipantId),
            reason,
            terminalAt
        );
    }
    public int terminalCollaborationParticipantsByTask(
        String tenantId,
        UUID taskId,
        SlaTerminalReason reason,
        Instant terminalAt
    ) {
        return terminal(
            "task_id=:targetId and target_type='COLLABORATION_PARTICIPANT'",
            params("tenantId", tenantId, "targetId", taskId),
            reason,
            terminalAt
        );
    }
    public int terminalApprovalInstance(
        String tenantId,
        UUID approvalInstanceId,
        SlaTerminalReason reason,
        Instant terminalAt
    ) {
        return terminal(
            "approval_instance_id=:targetId",
            params("tenantId", tenantId, "targetId", approvalInstanceId),
            reason,
            terminalAt
        );
    }
    public SlaInstance changeResponsibility(ResponsibilityChange change, long expectedVersion) {
        return required(transactions.execute(status -> {
            int updated = jdbc.update(
                """
                update ap_sla_instance set responsible_user_id=:newResponsibleUserId,
                    request_id=:requestId,trace_id=:traceId,updated_at=:changedAt,version=version+1
                where tenant_id=:tenantId and sla_instance_id=:slaInstanceId
                  and status in ('ACTIVE','PAUSED') and version=:expectedVersion
                  and responsible_user_id=:previousResponsibleUserId
                """,
                params(
                    "tenantId", change.tenantId(),
                    "slaInstanceId", change.slaInstanceId(),
                    "expectedVersion", expectedVersion,
                    "previousResponsibleUserId", change.previousResponsibleUserId(),
                    "newResponsibleUserId", change.newResponsibleUserId(),
                    "requestId", change.requestId(),
                    "traceId", change.traceId(),
                    "changedAt", offset(change.changedAt())
                )
            );
            if (updated != 1) {
                throw conflict("APPROVAL_SLA_INSTANCE_STATE_CONFLICT", "responsibility changed concurrently");
            }
            int inserted = jdbc.update(
                """
                insert into ap_sla_responsibility_change (
                    responsibility_change_id,sla_instance_id,tenant_id,
                    previous_responsible_user_id,new_responsible_user_id,source,reason,
                    changed_by,changed_at,request_id,trace_id
                ) values (
                    :responsibilityChangeId,:slaInstanceId,:tenantId,
                    :previousResponsibleUserId,:newResponsibleUserId,:source,:reason,
                    :changedBy,:changedAt,:requestId,:traceId
                )
                """,
                responsibilityChangeParameters(change)
            );
            if (inserted != 1) {
                throw new IllegalStateException("responsibility history was not appended");
            }
            return findInstance(change.tenantId(), change.slaInstanceId()).orElseThrow();
        }));
    }
    public SlaInstancePage findInstances(SlaInstanceCriteria criteria) {
        StringBuilder where = new StringBuilder(" where tenant_id=:tenantId");
        MapSqlParameterSource parameters = params("tenantId", criteria.tenantId());
        if (criteria.status() != null) {
            where.append(" and status=:status");
            parameters.addValue("status", criteria.status().name());
        }
        if (criteria.responsibleUserId() != null) {
            where.append(" and responsible_user_id=:responsibleUserId");
            parameters.addValue("responsibleUserId", criteria.responsibleUserId());
        }
        if (criteria.dueBefore() != null) {
            where.append(" and due_at<=:dueBefore");
            parameters.addValue("dueBefore", offset(criteria.dueBefore()));
        }
        if (criteria.dueAfter() != null) {
            where.append(" and due_at>=:dueAfter");
            parameters.addValue("dueAfter", offset(criteria.dueAfter()));
        }
        if (criteria.requestId() != null) {
            where.append(" and request_id=:requestId");
            parameters.addValue("requestId", criteria.requestId());
        }
        long total = count("select count(*) from ap_sla_instance" + where, parameters);
        parameters.addValue("limit", criteria.limit()).addValue("offset", criteria.offset());
        List<SlaInstance> items = queryInstances(
            "select * from ap_sla_instance" + where
                + " order by due_at,sla_instance_id limit :limit offset :offset",
            parameters
        );
        return new SlaInstancePage(items, total, criteria.limit(), criteria.offset());
    }
    public SlaInstancePage findUpcoming(
        String tenantId,
        Instant observedAt,
        Instant dueBefore,
        int limit,
        int offsetValue
    ) {
        return boundedPage(
            "status='ACTIVE' and due_at>:observedAt and due_at<=:dueBefore",
            params(
                "tenantId", tenantId,
                "observedAt", offset(observedAt),
                "dueBefore", offset(dueBefore)
            ),
            limit,
            offsetValue
        );
    }
    public SlaInstancePage findOverdue(
        String tenantId,
        Instant observedAt,
        int limit,
        int offsetValue
    ) {
        return boundedPage(
            "status='ACTIVE' and overdue_at<=:observedAt",
            params("tenantId", tenantId, "observedAt", offset(observedAt)),
            limit,
            offsetValue
        );
    }
    public SlaInstancePage findByRequestId(
        String tenantId,
        String requestId,
        int limit,
        int offsetValue
    ) {
        return boundedPage(
            "request_id=:requestId",
            params("tenantId", tenantId, "requestId", requestId),
            limit,
            offsetValue
        );
    }
    public List<ResponsibilityChange> findResponsibilityChanges(
        String tenantId,
        UUID slaInstanceId,
        int limit
    ) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        return jdbc.query(
            """
            select * from ap_sla_responsibility_change
            where tenant_id=:tenantId and sla_instance_id=:slaInstanceId
            order by changed_at desc,responsibility_change_id desc limit :limit
            """,
            params("tenantId", tenantId, "slaInstanceId", slaInstanceId, "limit", limit),
            responsibilityChangeMapper()
        );
    }
    public Optional<SlaInstance> findVisibleTaskSla(String tenantId, UUID taskId, String userId) {
        return queryInstances(
            """
            select s.* from ap_sla_instance s
            join ap_approval_task t on t.tenant_id=s.tenant_id and t.task_id=s.task_id
            where s.tenant_id=:tenantId and s.task_id=:taskId
              and s.status in ('ACTIVE','PAUSED')
              and (s.responsible_user_id=:userId or s.original_responsible_user_id=:userId
                   or t.assignee_id=:userId)
            order by s.created_at desc limit 1
            """,
            params("tenantId", tenantId, "taskId", taskId, "userId", userId)
        ).stream().findFirst();
    }

}
