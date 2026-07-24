package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Current one-instance migration attempt state. */
public record ApprovalMigrationAttempt(
    UUID attemptId,
    String tenantId,
    UUID intentId,
    UUID approvalInstanceId,
    String engineInstanceId,
    int attemptNumber,
    UUID parentAttemptId,
    String expectedBindingEvidenceHash,
    String sourceEngineDefinitionId,
    String targetEngineDefinitionId,
    AttemptStatus status,
    EngineOutcome engineOutcome,
    long revision,
    String leaseOwner,
    Instant leaseUntil,
    String engineRequestReference,
    FailureClass failureClass,
    String errorSummary,
    Instant createdAt,
    Instant updatedAt,
    String requestId,
    String traceId
) {
    public ApprovalMigrationAttempt {
        attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        tenantId = ApprovalMigrationRules.requireText(tenantId, "tenantId", 128);
        intentId = Objects.requireNonNull(intentId, "intentId must not be null");
        approvalInstanceId = Objects.requireNonNull(
            approvalInstanceId,
            "approvalInstanceId must not be null"
        );
        engineInstanceId = ApprovalMigrationRules.requireText(
            engineInstanceId,
            "engineInstanceId",
            256
        );
        ApprovalMigrationRules.requirePositive(attemptNumber, "attemptNumber");
        if ((attemptNumber == 1) != (parentAttemptId == null)) {
            throw new IllegalArgumentException("only the first attempt can omit parentAttemptId");
        }
        if (attemptId.equals(parentAttemptId)) {
            throw new IllegalArgumentException("attempt cannot be its own parent");
        }
        expectedBindingEvidenceHash = ApprovalMigrationRules.requireHash(
            expectedBindingEvidenceHash,
            "expectedBindingEvidenceHash"
        );
        sourceEngineDefinitionId = ApprovalMigrationRules.requireText(
            sourceEngineDefinitionId,
            "sourceEngineDefinitionId",
            256
        );
        targetEngineDefinitionId = ApprovalMigrationRules.requireText(
            targetEngineDefinitionId,
            "targetEngineDefinitionId",
            256
        );
        if (sourceEngineDefinitionId.equals(targetEngineDefinitionId)) {
            throw new IllegalArgumentException("source and target engine definitions must be distinct");
        }
        status = Objects.requireNonNull(status, "status must not be null");
        engineOutcome = Objects.requireNonNull(engineOutcome, "engineOutcome must not be null");
        ApprovalMigrationRules.requirePositive(revision, "revision");
        leaseOwner = ApprovalMigrationRules.optionalText(leaseOwner, "leaseOwner", 200);
        engineRequestReference = ApprovalMigrationRules.optionalText(
            engineRequestReference,
            "engineRequestReference",
            256
        );
        failureClass = Objects.requireNonNull(failureClass, "failureClass must not be null");
        errorSummary = ApprovalMigrationRules.optionalText(errorSummary, "errorSummary", 1000);
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
        requestId = ApprovalMigrationRules.requireText(requestId, "requestId", 256);
        traceId = ApprovalMigrationRules.optionalText(traceId, "traceId", 256);
        ApprovalMigrationRules.validateAttemptEvidence(
            status, engineOutcome, leaseOwner, leaseUntil,
            engineRequestReference, failureClass, errorSummary, updatedAt
        );
    }

    public ApprovalMigrationAttempt transitioned(ApprovalMigrationAttemptTransition transition) {
        Objects.requireNonNull(transition, "transition must not be null");
        ApprovalMigrationProtocol.requireAttemptTransition(status, transition.status());
        if (transition.happenedAt().isBefore(updatedAt)) {
            throw new IllegalArgumentException("attempt transition time moved backwards");
        }
        ApprovalMigrationRules.validateAttemptTransitionEvidence(this, transition);
        return new ApprovalMigrationAttempt(
            attemptId, tenantId, intentId, approvalInstanceId, engineInstanceId,
            attemptNumber, parentAttemptId, expectedBindingEvidenceHash,
            sourceEngineDefinitionId, targetEngineDefinitionId,
            transition.status(), transition.engineOutcome(), revision + 1,
            transition.leaseOwner(), transition.leaseUntil(), transition.engineRequestReference(),
            transition.failureClass(), transition.errorSummary(), createdAt, transition.happenedAt(),
            requestId, traceId
        );
    }

    public boolean terminal() {
        return ApprovalMigrationProtocol.terminal(status);
    }
}
