package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanProtocol.ExpectedInstanceStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanProtocol.PlanStatus;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Tenant-scoped immutable migration plan content with a closed approval lifecycle. */
public record ApprovalMigrationPlan(
    UUID planId,
    String tenantId,
    UUID assessmentId,
    String assessmentReportHash,
    String definitionKey,
    int sourceReleaseVersion,
    String sourcePackageHash,
    int targetReleaseVersion,
    String targetPackageHash,
    UUID targetDeploymentRecordId,
    String targetEngineDeploymentId,
    String targetEngineDefinitionId,
    int targetEngineVersion,
    List<SelectedInstance> selectedInstances,
    PlanStatus status,
    long revision,
    String idempotencyKey,
    String planHash,
    String requestedBy,
    String operationReason,
    Instant assessedAt,
    Instant createdAt,
    Instant expiresAt,
    Instant updatedAt,
    UUID authorizationId,
    String authorizationEvidenceHash,
    String authorizedBy,
    Instant authorizedAt,
    Instant authorizationExpiresAt,
    String requestId,
    String traceId,
    String auditChainReference
) {
    private static final Comparator<SelectedInstance> INSTANCE_ORDER = Comparator.comparing(
        item -> item.approvalInstanceId().toString()
    );

    public ApprovalMigrationPlan {
        planId = Objects.requireNonNull(planId, "planId must not be null");
        tenantId = ApprovalMigrationPlanRules.requireText(tenantId, "tenantId", 128);
        assessmentId = Objects.requireNonNull(assessmentId, "assessmentId must not be null");
        assessmentReportHash = ApprovalMigrationPlanRules.requireHash(
            assessmentReportHash,
            "assessmentReportHash"
        );
        definitionKey = ApprovalMigrationPlanRules.requireText(
            definitionKey,
            "definitionKey",
            64
        );
        ApprovalMigrationPlanRules.requirePositive(
            sourceReleaseVersion,
            "sourceReleaseVersion"
        );
        sourcePackageHash = ApprovalMigrationPlanRules.requireHash(
            sourcePackageHash,
            "sourcePackageHash"
        );
        ApprovalMigrationPlanRules.requirePositive(
            targetReleaseVersion,
            "targetReleaseVersion"
        );
        targetPackageHash = ApprovalMigrationPlanRules.requireHash(
            targetPackageHash,
            "targetPackageHash"
        );
        targetDeploymentRecordId = Objects.requireNonNull(
            targetDeploymentRecordId,
            "targetDeploymentRecordId must not be null"
        );
        targetEngineDeploymentId = ApprovalMigrationPlanRules.requireText(
            targetEngineDeploymentId,
            "targetEngineDeploymentId",
            256
        );
        targetEngineDefinitionId = ApprovalMigrationPlanRules.requireText(
            targetEngineDefinitionId,
            "targetEngineDefinitionId",
            256
        );
        ApprovalMigrationPlanRules.requirePositive(targetEngineVersion, "targetEngineVersion");
        if (sourceReleaseVersion == targetReleaseVersion
            || sourcePackageHash.equals(targetPackageHash)) {
            throw new IllegalArgumentException("source and target release must be distinct");
        }
        selectedInstances = selectedInstances == null ? List.of() : List.copyOf(selectedInstances);
        validateInstances(selectedInstances);
        status = Objects.requireNonNull(status, "status must not be null");
        ApprovalMigrationPlanRules.requirePositive(revision, "revision");
        idempotencyKey = ApprovalMigrationPlanRules.requireText(
            idempotencyKey,
            "idempotencyKey",
            200
        );
        planHash = ApprovalMigrationPlanRules.requireHash(planHash, "planHash");
        requestedBy = ApprovalMigrationPlanRules.requireText(requestedBy, "requestedBy", 256);
        operationReason = ApprovalMigrationPlanRules.requireText(
            operationReason,
            "operationReason",
            1000
        );
        assessedAt = Objects.requireNonNull(assessedAt, "assessedAt must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (createdAt.isBefore(assessedAt)
            || !expiresAt.isAfter(createdAt)
            || updatedAt.isBefore(createdAt)
            || updatedAt.isAfter(expiresAt)) {
            throw new IllegalArgumentException("migration plan timestamps are inconsistent");
        }
        requestId = ApprovalMigrationPlanRules.requireText(requestId, "requestId", 256);
        traceId = ApprovalMigrationPlanRules.optionalText(traceId, "traceId", 256);
        auditChainReference = ApprovalMigrationPlanRules.requireText(
            auditChainReference,
            "auditChainReference",
            256
        );
        validateAuthorizationEvidence(
            status,
            requestedBy,
            expiresAt,
            authorizationId,
            authorizationEvidenceHash,
            authorizedBy,
            authorizedAt,
            authorizationExpiresAt
        );
    }

    public ApprovalMigrationPlan authorized(ApprovalMigrationPlanAuthorization authorization) {
        Objects.requireNonNull(authorization, "authorization must not be null");
        if (status != PlanStatus.PROPOSED) {
            throw new IllegalArgumentException("only a proposed migration plan may be authorized");
        }
        requireAuthorizationIdentity(authorization);
        if (requestedBy.equals(authorization.authorizedBy())) {
            throw new IllegalArgumentException("migration plan requester cannot authorize the plan");
        }
        if (authorization.decidedAt().isBefore(createdAt)
            || !authorization.decidedAt().isBefore(expiresAt)
            || authorization.expiresAt().isAfter(expiresAt)) {
            throw new IllegalArgumentException("migration plan authorization is outside plan validity");
        }
        return new ApprovalMigrationPlan(
            planId,
            tenantId,
            assessmentId,
            assessmentReportHash,
            definitionKey,
            sourceReleaseVersion,
            sourcePackageHash,
            targetReleaseVersion,
            targetPackageHash,
            targetDeploymentRecordId,
            targetEngineDeploymentId,
            targetEngineDefinitionId,
            targetEngineVersion,
            selectedInstances,
            PlanStatus.AUTHORIZED,
            revision + 1,
            idempotencyKey,
            planHash,
            requestedBy,
            operationReason,
            assessedAt,
            createdAt,
            expiresAt,
            authorization.decidedAt(),
            authorization.authorizationId(),
            authorization.authorizationEvidenceHash(),
            authorization.authorizedBy(),
            authorization.decidedAt(),
            authorization.expiresAt(),
            requestId,
            traceId,
            auditChainReference
        );
    }

    public boolean authorizedAt(Instant instant) {
        Instant checkedAt = Objects.requireNonNull(instant, "instant must not be null");
        return status == PlanStatus.AUTHORIZED
            && checkedAt.isBefore(expiresAt)
            && checkedAt.isBefore(authorizationExpiresAt);
    }

    public int selectedInstanceCount() {
        return selectedInstances.size();
    }

    public boolean terminal() {
        return ApprovalMigrationPlanProtocol.terminal(status);
    }

    private void requireAuthorizationIdentity(ApprovalMigrationPlanAuthorization authorization) {
        if (!tenantId.equals(authorization.tenantId())
            || !planId.equals(authorization.planId())
            || !planHash.equals(authorization.planHash())
            || selectedInstanceCount() != authorization.selectedInstanceCount()
            || sourceReleaseVersion != authorization.sourceReleaseVersion()
            || !sourcePackageHash.equals(authorization.sourcePackageHash())
            || targetReleaseVersion != authorization.targetReleaseVersion()
            || !targetPackageHash.equals(authorization.targetPackageHash())) {
            throw new IllegalArgumentException("authorization does not match immutable plan identity");
        }
    }

    private static void validateInstances(List<SelectedInstance> values) {
        if (values.isEmpty() || values.size() > 1000) {
            throw new IllegalArgumentException("selectedInstances must contain between 1 and 1000 items");
        }
        Set<UUID> identities = new HashSet<>();
        SelectedInstance previous = null;
        for (SelectedInstance value : values) {
            Objects.requireNonNull(value, "selectedInstances must not contain null");
            if (!identities.add(value.approvalInstanceId())) {
                throw new IllegalArgumentException("selectedInstances contains a duplicate identity");
            }
            if (previous != null && INSTANCE_ORDER.compare(previous, value) >= 0) {
                throw new IllegalArgumentException("selectedInstances must be strictly canonical ordered");
            }
            previous = value;
        }
    }

    private static void validateAuthorizationEvidence(
        PlanStatus status,
        String requestedBy,
        Instant planExpiresAt,
        UUID authorizationId,
        String authorizationEvidenceHash,
        String authorizedBy,
        Instant authorizedAt,
        Instant authorizationExpiresAt
    ) {
        boolean absent = authorizationId == null
            && authorizationEvidenceHash == null
            && authorizedBy == null
            && authorizedAt == null
            && authorizationExpiresAt == null;
        boolean complete = authorizationId != null
            && authorizationEvidenceHash != null
            && authorizedBy != null
            && authorizedAt != null
            && authorizationExpiresAt != null;
        if (status == PlanStatus.PROPOSED && !absent) {
            throw new IllegalArgumentException("proposed migration plan cannot have authorization evidence");
        }
        if (status == PlanStatus.AUTHORIZED && !complete) {
            throw new IllegalArgumentException("authorized migration plan requires complete authorization evidence");
        }
        if (!absent) {
            ApprovalMigrationPlanRules.requireHash(
                authorizationEvidenceHash,
                "authorizationEvidenceHash"
            );
            String normalizedAuthorizedBy = ApprovalMigrationPlanRules.requireText(
                authorizedBy,
                "authorizedBy",
                256
            );
            if (requestedBy.equals(normalizedAuthorizedBy)
                || !authorizationExpiresAt.isAfter(authorizedAt)
                || authorizationExpiresAt.isAfter(planExpiresAt)) {
                throw new IllegalArgumentException("migration plan authorization evidence is inconsistent");
            }
        }
    }

    public record SelectedInstance(
        UUID approvalInstanceId,
        ExpectedInstanceStatus expectedInstanceStatus,
        List<String> expectedActiveTaskDefinitionKeys,
        String expectedBindingEvidenceHash,
        String instanceEvidenceHash
    ) {
        public SelectedInstance {
            approvalInstanceId = Objects.requireNonNull(
                approvalInstanceId,
                "approvalInstanceId must not be null"
            );
            expectedInstanceStatus = Objects.requireNonNull(
                expectedInstanceStatus,
                "expectedInstanceStatus must not be null"
            );
            expectedActiveTaskDefinitionKeys = expectedActiveTaskDefinitionKeys == null
                ? List.of()
                : List.copyOf(expectedActiveTaskDefinitionKeys);
            validateTaskKeys(expectedActiveTaskDefinitionKeys);
            expectedBindingEvidenceHash = ApprovalMigrationPlanRules.requireHash(
                expectedBindingEvidenceHash,
                "expectedBindingEvidenceHash"
            );
            instanceEvidenceHash = ApprovalMigrationPlanRules.requireHash(
                instanceEvidenceHash,
                "instanceEvidenceHash"
            );
        }

        private static void validateTaskKeys(List<String> values) {
            if (values.isEmpty() || values.size() > 100) {
                throw new IllegalArgumentException(
                    "expectedActiveTaskDefinitionKeys must contain between 1 and 100 items"
                );
            }
            String previous = null;
            for (String value : values) {
                String normalized = ApprovalMigrationPlanRules.requireText(
                    value,
                    "expectedActiveTaskDefinitionKey",
                    128
                );
                if (!normalized.equals(value)) {
                    throw new IllegalArgumentException(
                        "expectedActiveTaskDefinitionKeys must already be normalized"
                    );
                }
                if (previous != null && previous.compareTo(value) >= 0) {
                    throw new IllegalArgumentException(
                        "expectedActiveTaskDefinitionKeys must be strictly canonical ordered"
                    );
                }
                previous = value;
            }
        }
    }
}
