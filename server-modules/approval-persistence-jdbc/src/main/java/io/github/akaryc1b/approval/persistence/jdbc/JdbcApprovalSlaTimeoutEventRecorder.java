package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalSlaActionStateRecorder;
import io.github.akaryc1b.approval.application.port.ApprovalSlaActionStateRecorder.ActionStateException;
import io.github.akaryc1b.approval.application.port.ApprovalSlaActionStateRecorder.RecordResult;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ActionType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionIntent;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.IntentStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaTimeoutEventRecorder;
import io.github.akaryc1b.approval.integration.outbox.OutboxRepository;
import io.github.akaryc1b.approval.integration.outbox.SlaTimeoutNotificationConnector;
import io.github.akaryc1b.approval.integration.outbox.SlaTimeoutNotificationConnector.Timeout;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** Same-transaction overdue evidence and Outbox append; no transport and no Flowable table access. */
public final class JdbcApprovalSlaTimeoutEventRecorder implements ApprovalSlaTimeoutEventRecorder {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ApprovalSlaExecutionStore executions;
    private final ApprovalSlaActionStateRecorder state;
    private final OutboxRepository outbox;
    private final String connectorKey;
    private final Clock clock;

    public JdbcApprovalSlaTimeoutEventRecorder(DataSource dataSource, PlatformTransactionManager manager,
        ApprovalSlaExecutionStore executions, ApprovalSlaActionStateRecorder state,
        OutboxRepository outbox, String connectorKey, Clock clock) {
        this.jdbc = new NamedParameterJdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        this.transactions.setTimeout(5);
        this.executions = Objects.requireNonNull(executions, "executions");
        this.state = Objects.requireNonNull(state, "state");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.connectorKey = SlaTimeoutNotificationConnector.routeKey(connectorKey);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public RecordResult record(ExecutionIntent claimed) {
        Objects.requireNonNull(claimed, "claimed");
        if (claimed.actionType() != ActionType.OVERDUE || claimed.status() != IntentStatus.CLAIMED) {
            throw rejected("SLA_TIMEOUT_CLAIM_REQUIRED");
        }
        try {
            return Objects.requireNonNull(transactions.execute(status -> recordLocked(claimed)));
        } catch (ActionStateException rejected) {
            throw rejected;
        } catch (RuntimeException unavailable) {
            throw new ActionStateException("SLA_TIMEOUT_PERSISTENCE_UNAVAILABLE", true,
                "timeout event persistence failed; no delivery was attempted");
        }
    }

    private RecordResult recordLocked(ExecutionIntent claimed) {
        var parameters = new MapSqlParameterSource().addValue("tenant", claimed.tenantId())
            .addValue("sla", claimed.slaInstanceId()).addValue("intent", claimed.intentId());
        // SLA first: serialize with deadline/responsibility/terminal changes and concurrent recorders.
        var rows = jdbc.query("""
            select status, last_action_sequence, approval_instance_id, task_id,
                   collaboration_participant_id, policy_id, policy_version, target_type,
                   responsible_user_id, due_at, overdue_at
            from ap_sla_instance where tenant_id=:tenant and sla_instance_id=:sla for update
            """, parameters, (rs, index) -> new Current(rs.getString("status"), rs.getLong("last_action_sequence"),
                new Timeout(claimed.slaInstanceId(), rs.getObject("approval_instance_id", UUID.class),
                    rs.getObject("task_id", UUID.class), rs.getObject("collaboration_participant_id", UUID.class),
                    rs.getObject("policy_id", UUID.class), rs.getInt("policy_version"), claimed.actionSequence(),
                    rs.getString("target_type"), rs.getString("responsible_user_id"),
                    rs.getObject("due_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("overdue_at", OffsetDateTime.class).toInstant(), connectorKey)));
        if (rows.size() != 1) throw rejected("SLA_TIMEOUT_SOURCE_NOT_FOUND");
        var locked = jdbc.query("""
            select intent_id from ap_sla_execution_intent
            where tenant_id=:tenant and sla_instance_id=:sla and intent_id=:intent for update
            """, parameters, (rs, index) -> rs.getObject("intent_id", UUID.class));
        if (locked.size() != 1) throw rejected("SLA_TIMEOUT_CLAIM_REQUIRED");
        ExecutionIntent actual = executions.findIntent(claimed.tenantId(), claimed.intentId())
            .orElseThrow(() -> rejected("SLA_TIMEOUT_CLAIM_REQUIRED"));
        // Read the trusted clock after acquiring locks, not before potentially waiting for them.
        Instant detectedAt = clock.instant();
        if (!actual.equals(claimed) || !actual.leaseUntil().isAfter(detectedAt)
            || actual.nextAttemptAt().isAfter(detectedAt) || actual.updatedAt().isAfter(detectedAt)) {
            throw rejected("SLA_TIMEOUT_CLAIM_STALE");
        }
        Current current = rows.getFirst();
        // A prior deployment may already have recorded this sequence without notification.
        // Do not retrospectively manufacture an event, or resend after an acknowledgement loss.
        if (current.sequence() >= claimed.actionSequence()) return RecordResult.ALREADY_RECORDED;
        Timeout timeout = current.timeout();
        if (!"ACTIVE".equals(current.status()) || detectedAt.isBefore(timeout.overdueAt())
            || !timeout.overdueAt().equals(actual.scheduledAt())
            || !timeout.approvalInstanceId().equals(actual.approvalInstanceId())
            || !Objects.equals(timeout.taskId(), actual.taskId())
            || !Objects.equals(timeout.collaborationParticipantId(), actual.collaborationParticipantId())
            || !timeout.policyId().equals(actual.policyId()) || timeout.policyVersion() != actual.policyVersion()
            || !timeout.recipientId().equals(actual.responsibleUserId())) {
            throw rejected("SLA_TIMEOUT_SOURCE_CHANGED");
        }
        RecordResult result = state.recordOverdue(claimed.tenantId(), claimed.slaInstanceId(),
            claimed.actionSequence(), claimed.requestId(), claimed.traceId(), detectedAt);
        if (result != RecordResult.RECORDED) throw rejected("SLA_TIMEOUT_STATE_NOT_RECORDED");
        if (outbox.append(SlaTimeoutNotificationConnector.message(claimed.tenantId(), claimed.requestId(),
            claimed.traceId(), detectedAt, timeout)) != OutboxRepository.AppendResult.INSERTED) {
            throw rejected("SLA_TIMEOUT_EVENT_CONFLICT");
        }
        return result;
    }

    private static ActionStateException rejected(String code) {
        return new ActionStateException(code, false, "SLA timeout evidence is not current and authoritative");
    }

    private record Current(String status, long sequence, Timeout timeout) {
    }
}
