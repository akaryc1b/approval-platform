package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.InstanceFact;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.InstanceStatus;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.function.Supplier;

abstract class JdbcMySqlApprovalMigrationPlanAggregationFactSupport
    extends JdbcMySqlApprovalMigrationPlanAggregationSignalSupport {

    JdbcMySqlApprovalMigrationPlanAggregationFactSupport(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEvents,
        Supplier<UUID> identifiers
    ) {
        super(dataSource, objectMapper, transactionManager, auditEvents, identifiers);
    }

    @Override
    protected InstanceFact fact(FactRow row) {
        InstanceStatus status;
        if (row.attemptCount() == 0) {
            status = InstanceStatus.UNPROVISIONED;
        } else if (row.attemptCount() != 1 || row.attemptId() == null) {
            status = InstanceStatus.INVALID_INCOMPLETE_EVIDENCE;
        } else if (row.conflictCount() > 0) {
            status = InstanceStatus.BINDING_CONFLICT;
        } else if (row.completionCount() > 1) {
            status = InstanceStatus.INVALID_INCOMPLETE_EVIDENCE;
        } else if (row.completionCount() == 1) {
            status = exactCompletion(row)
                ? InstanceStatus.EXACTLY_COMPLETED
                : InstanceStatus.INVALID_INCOMPLETE_EVIDENCE;
        } else {
            status = switch (row.attemptStatus()) {
                case "PENDING" -> InstanceStatus.PENDING;
                case "CLAIMED" -> InstanceStatus.CLAIMED;
                case "ENGINE_REQUESTED" -> InstanceStatus.ENGINE_REQUESTED;
                case "VERIFYING" -> InstanceStatus.VERIFYING;
                case "UNKNOWN" -> InstanceStatus.UNKNOWN;
                case "RECONCILING" -> manual(row.reconciliationStatus())
                    ? InstanceStatus.MANUAL_REVIEW_REQUIRED
                    : InstanceStatus.RECONCILING;
                case "BLOCKED_STALE" -> InstanceStatus.BLOCKED_STALE;
                case "FAILED_RETRYABLE", "FAILED_TERMINAL", "CANCELLED" ->
                    InstanceStatus.TERMINAL_FAILURE;
                case "SUCCEEDED" -> InstanceStatus.INVALID_INCOMPLETE_EVIDENCE;
                default -> InstanceStatus.INVALID_INCOMPLETE_EVIDENCE;
            };
        }

        String evidenceHash = hashValues(
            "M5-D8-INSTANCE-FACT-V1",
            row.sequenceNo(),
            row.approvalInstanceId(),
            row.canary(),
            row.instanceEvidenceHash(),
            row.attemptCount(),
            row.attemptId(),
            row.attemptNumber(),
            row.attemptStatus(),
            row.attemptRevision(),
            row.engineOutcome(),
            row.expectedBindingEvidenceHash(),
            row.engineRequestHash(),
            row.engineRequestEvidenceHash(),
            row.engineOutcomeDisposition(),
            row.outcomeHash(),
            row.verificationClassification(),
            row.verificationTruncated(),
            row.verificationEvidenceHash(),
            row.completionCount(),
            row.completionAttemptId(),
            row.bindingRevision(),
            row.targetBindingEvidenceHash(),
            row.completionHash(),
            row.conflictCount(),
            row.conflictHash(),
            row.reconciliationStatus(),
            row.reconciliationHash(),
            row.observationClassification(),
            row.observationDisposition(),
            row.observationHash(),
            status
        );
        return new InstanceFact(
            row.sequenceNo(),
            row.approvalInstanceId(),
            row.canary(),
            row.attemptId(),
            status,
            row.instanceEvidenceHash(),
            evidenceHash
        );
    }

    protected static boolean exactCompletion(FactRow row) {
        return row.attemptId().equals(row.completionAttemptId())
            && "SUCCEEDED".equals(row.attemptStatus())
            && "EXACT_TARGET_RUNTIME".equals(row.verificationClassification())
            && Boolean.FALSE.equals(row.verificationTruncated())
            && row.bindingRevision() != null
            && row.bindingRevision() > 1
            && row.targetBindingEvidenceHash() != null
            && row.verificationEvidenceHash() != null
            && row.completionHash() != null;
    }
}
