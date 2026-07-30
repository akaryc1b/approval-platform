package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.context.RequestContext;

import java.util.Objects;
import java.util.UUID;

/**
 * Server-side high-risk approval gate for one exact immutable migration-plan hash.
 * Implementations must fail closed and must not trust browser or mobile authorization evidence.
 */
public interface ApprovalMigrationPlanAuthorizationGate {

    AuthorizationDecision requireAuthorization(AuthorizationRequest request);

    record AuthorizationRequest(
        RequestContext context,
        UUID planId,
        String planHash,
        int selectedInstanceCount,
        int sourceReleaseVersion,
        String sourcePackageHash,
        int targetReleaseVersion,
        String targetPackageHash,
        UUID targetDeploymentRecordId,
        String targetEngineDeploymentId,
        String targetEngineDefinitionId,
        int targetEngineVersion,
        String reason
    ) {
        public AuthorizationRequest {
            context = Objects.requireNonNull(context, "context must not be null");
            planId = Objects.requireNonNull(planId, "planId must not be null");
            planHash = requireHash(planHash, "planHash");
            if (selectedInstanceCount < 1 || selectedInstanceCount > 1000) {
                throw new IllegalArgumentException(
                    "selectedInstanceCount must be between 1 and 1000"
                );
            }
            if (sourceReleaseVersion < 1 || targetReleaseVersion < 1
                || sourceReleaseVersion == targetReleaseVersion) {
                throw new IllegalArgumentException("release versions are invalid");
            }
            sourcePackageHash = requireHash(sourcePackageHash, "sourcePackageHash");
            targetPackageHash = requireHash(targetPackageHash, "targetPackageHash");
            targetDeploymentRecordId = Objects.requireNonNull(
                targetDeploymentRecordId,
                "targetDeploymentRecordId must not be null"
            );
            targetEngineDeploymentId = requireText(
                targetEngineDeploymentId,
                "targetEngineDeploymentId",
                256
            );
            targetEngineDefinitionId = requireText(
                targetEngineDefinitionId,
                "targetEngineDefinitionId",
                256
            );
            if (targetEngineVersion < 1) {
                throw new IllegalArgumentException("targetEngineVersion must be positive");
            }
            reason = requireText(reason, "reason", 1000);
        }
    }

    record AuthorizationDecision(
        String authorizedBy,
        String authorizationPolicy,
        String authorizationPolicyVersion,
        String authorizationEvidenceHash
    ) {
        public AuthorizationDecision {
            authorizedBy = requireText(authorizedBy, "authorizedBy", 256);
            authorizationPolicy = requireText(
                authorizationPolicy,
                "authorizationPolicy",
                128
            );
            authorizationPolicyVersion = requireText(
                authorizationPolicyVersion,
                "authorizationPolicyVersion",
                64
            );
            authorizationEvidenceHash = requireHash(
                authorizationEvidenceHash,
                "authorizationEvidenceHash"
            );
        }
    }

    final class MigrationPlanAuthorizationDeniedException extends RuntimeException {
        public MigrationPlanAuthorizationDeniedException(String message) {
            super(message);
        }
    }

    private static String requireHash(String value, String name) {
        String normalized = requireText(value, name, 64);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return normalized;
    }

    private static String requireText(String value, String name, int maximum) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " is blank or exceeds maximum length " + maximum);
        }
        return normalized;
    }
}
