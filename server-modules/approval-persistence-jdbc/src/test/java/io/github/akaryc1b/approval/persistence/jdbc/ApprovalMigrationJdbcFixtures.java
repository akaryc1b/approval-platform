package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntentEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliation;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationVerification;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.IntentStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.ReconciliationStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.VerificationOutcome;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class ApprovalMigrationJdbcFixtures {

    static final String TENANT = "tenant-migration-protocol";
    static final String OTHER_TENANT = "tenant-migration-protocol-other";
    static final String DEFINITION_KEY = "purchasePayment";
    static final Instant NOW = Instant.parse("2026-07-24T03:00:00Z");
    static final UUID INTENT_ID = UUID.fromString("74000000-0000-0000-0000-000000000001");
    static final UUID PLAN_ID = UUID.fromString("74000000-0000-0000-0000-000000000002");
    static final UUID ATTEMPT_ID = UUID.fromString("74000000-0000-0000-0000-000000000003");
    static final UUID INSTANCE_ID = UUID.fromString("74000000-0000-0000-0000-000000000004");

    private ApprovalMigrationJdbcFixtures() {
    }

    static ApprovalMigrationIntent intent() {
        return intent(INTENT_ID, PLAN_ID, "intent-key-one", hash('d'));
    }

    static ApprovalMigrationIntent intent(UUID intentId, UUID planId, String key, String evidenceHash) {
        return new ApprovalMigrationIntent(
            intentId, TENANT, planId, hash('a'), DEFINITION_KEY,
            1, hash('b'), 2, hash('c'), 1,
            IntentStatus.PENDING, 1, key, evidenceHash,
            "migration-requester", "Create durable M5-B migration protocol evidence",
            NOW.plusSeconds(900), NOW, NOW,
            "request-" + key, "trace-migration-protocol", "audit-" + key
        );
    }

    static ApprovalMigrationIntentEvent initialIntentEvent(ApprovalMigrationIntent value, String suffix) {
        return intentEvent(value, null, suffix);
    }

    static ApprovalMigrationIntentEvent intentEvent(
        ApprovalMigrationIntent value,
        IntentStatus from,
        String suffix
    ) {
        return new ApprovalMigrationIntentEvent(
            UUID.nameUUIDFromBytes(suffix.getBytes()), value.tenantId(), value.intentId(),
            value.revision(), from, value.status(), "Migration intent " + suffix,
            value.requestedBy(), value.updatedAt(), value.requestId(),
            value.traceId(), value.auditChainReference()
        );
    }

    static ApprovalMigrationAttempt attempt() {
        return attempt(ATTEMPT_ID, INTENT_ID, 1, null);
    }

    static ApprovalMigrationAttempt attempt(
        UUID attemptId,
        UUID intentId,
        int number,
        UUID parentId
    ) {
        return new ApprovalMigrationAttempt(
            attemptId, TENANT, intentId, INSTANCE_ID, "engine-instance-one",
            number, parentId, hash('e'), "source-definition", "target-definition",
            AttemptStatus.PENDING, EngineOutcome.NOT_REQUESTED, 1,
            null, null, null, FailureClass.NONE, null,
            NOW.plusSeconds(number), NOW.plusSeconds(number),
            "request-attempt-" + number, "trace-migration-protocol"
        );
    }

    static ApprovalMigrationAttemptEvent initialAttemptEvent(
        ApprovalMigrationAttempt value,
        String suffix
    ) {
        return attemptEvent(value, null, suffix);
    }

    static ApprovalMigrationAttemptEvent attemptEvent(
        ApprovalMigrationAttempt value,
        AttemptStatus from,
        String suffix
    ) {
        return new ApprovalMigrationAttemptEvent(
            UUID.nameUUIDFromBytes(suffix.getBytes()), value.tenantId(), value.attemptId(),
            value.revision(), from, value.status(), value.engineOutcome(), value.failureClass(),
            value.errorSummary(), value.updatedAt(), value.requestId(), value.traceId()
        );
    }

    static ApprovalMigrationVerification verification(
        ApprovalMigrationIntent intent,
        ApprovalMigrationAttempt attempt,
        int sequence
    ) {
        return new ApprovalMigrationVerification(
            new UUID(80, sequence), TENANT, intent.intentId(), attempt.attemptId(), sequence,
            attempt.expectedBindingEvidenceHash(), hash('f'),
            attempt.sourceEngineDefinitionId(), attempt.targetEngineDefinitionId(),
            attempt.targetEngineDefinitionId(), List.of("review"), List.of("review"),
            true, true, VerificationOutcome.TARGET_CONFIRMED,
            hash((char) ('0' + sequence)), NOW.plusSeconds(20 + sequence),
            "request-verification-" + sequence, "trace-migration-protocol"
        );
    }

    static ApprovalMigrationReconciliation reconciliation(
        ApprovalMigrationIntent intent,
        ApprovalMigrationAttempt attempt,
        int sequence,
        ReconciliationStatus status
    ) {
        boolean terminal = status != ReconciliationStatus.OPEN
            && status != ReconciliationStatus.MANUAL_REVIEW_REQUIRED;
        return new ApprovalMigrationReconciliation(
            new UUID(90, sequence), TENANT, intent.intentId(), attempt.attemptId(), sequence,
            status, FailureClass.RECONCILIATION_REQUIRED,
            "Compare engine and platform evidence", hash((char) ('a' + sequence)),
            terminal ? hash((char) ('f' - sequence)) : null,
            terminal ? "migration-reviewer" : null,
            NOW.plusSeconds(40 + sequence),
            terminal ? NOW.plusSeconds(50 + sequence) : null,
            "request-reconciliation-" + sequence, "trace-migration-protocol",
            "audit-reconciliation-" + sequence
        );
    }

    static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
