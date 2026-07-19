package io.github.akaryc1b.approval.domain.definition;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Platform-owned projection of one immutable Release Package deployment. */
public record ApprovalReleaseDeployment(
    UUID deploymentRecordId,
    String tenantId,
    String definitionKey,
    int releaseVersion,
    String releasePackageHash,
    Status status,
    int attemptCount,
    String engineDeploymentId,
    String engineDefinitionId,
    Integer engineVersion,
    String lastErrorCode,
    String lastErrorMessage,
    String requestedBy,
    Instant createdAt,
    Instant updatedAt,
    Instant deployedAt
) {

    public ApprovalReleaseDeployment {
        deploymentRecordId = Objects.requireNonNull(
            deploymentRecordId,
            "deploymentRecordId must not be null"
        );
        tenantId = requireText(tenantId, "tenantId");
        definitionKey = requireText(definitionKey, "definitionKey");
        if (releaseVersion < 1) {
            throw new IllegalArgumentException("releaseVersion must be positive");
        }
        releasePackageHash = requireHash(releasePackageHash, "releasePackageHash");
        status = Objects.requireNonNull(status, "status must not be null");
        if (attemptCount < 1) {
            throw new IllegalArgumentException("attemptCount must be positive");
        }
        requestedBy = requireText(requestedBy, "requestedBy");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        validateState(
            status,
            engineDeploymentId,
            engineDefinitionId,
            engineVersion,
            lastErrorCode,
            lastErrorMessage,
            deployedAt
        );
    }

    public enum Status {
        PENDING,
        DEPLOYED,
        FAILED
    }

    private static void validateState(
        Status status,
        String engineDeploymentId,
        String engineDefinitionId,
        Integer engineVersion,
        String errorCode,
        String errorMessage,
        Instant deployedAt
    ) {
        boolean anyEngineIdentity = engineDeploymentId != null
            || engineDefinitionId != null
            || engineVersion != null;
        boolean completeEngineIdentity = hasText(engineDeploymentId)
            && hasText(engineDefinitionId)
            && engineVersion != null
            && engineVersion > 0;
        boolean anyError = errorCode != null || errorMessage != null;
        boolean completeError = hasText(errorCode) && hasText(errorMessage);

        if (status == Status.DEPLOYED) {
            if (!completeEngineIdentity || anyError || deployedAt == null) {
                throw new IllegalArgumentException(
                    "deployed projection requires complete engine identity and deployedAt only"
                );
            }
            return;
        }
        if (anyEngineIdentity || deployedAt != null) {
            throw new IllegalArgumentException(
                "only deployed projection can contain engine identity"
            );
        }
        if (status == Status.FAILED) {
            if (!completeError) {
                throw new IllegalArgumentException("failed projection requires a complete error");
            }
            return;
        }
        if (anyError) {
            throw new IllegalArgumentException("pending projection cannot contain an error");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String requireHash(String value, String name) {
        String normalized = requireText(value, name);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return normalized;
    }
}
