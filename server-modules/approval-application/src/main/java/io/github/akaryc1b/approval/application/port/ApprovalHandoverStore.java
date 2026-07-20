package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityReference;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Platform-owned employee handover policy and task responsibility evidence. */
public interface ApprovalHandoverStore {

    void lockPrincipal(String tenantId, String principalId);

    PrincipalHandover create(PrincipalHandover handover);

    Optional<PrincipalHandover> findById(String tenantId, UUID handoverId);

    Optional<PrincipalHandover> findActiveByPrincipal(String tenantId, String principalId);

    List<PrincipalHandover> findByPrincipal(
        String tenantId,
        String principalId,
        boolean includeRevoked
    );

    PrincipalHandover revoke(
        String tenantId,
        UUID handoverId,
        String revokedBy,
        String revokeReason,
        Instant revokedAt
    );

    List<PendingTask> findPendingTasksByPrincipal(String tenantId, String principalId);

    void lockEngineTask(String tenantId, String engineTaskId);

    HandoverTaskAssignment createAssignment(HandoverTaskAssignment assignment);

    Optional<HandoverTaskAssignment> findAssignmentByEngineTask(
        String tenantId,
        String engineTaskId
    );

    void markCompleted(
        String tenantId,
        String engineTaskId,
        String completedBy,
        Instant completedAt
    );

    void markSuperseded(
        String tenantId,
        String engineTaskId,
        String targetAssigneeId,
        Instant supersededAt
    );

    void markCanceled(String tenantId, String engineTaskId, Instant canceledAt);

    void cancelActiveByEngineInstance(
        String tenantId,
        String engineInstanceId,
        Instant canceledAt
    );

    record PendingTask(
        UUID taskId,
        UUID instanceId,
        String engineTaskId,
        String engineInstanceId,
        String definitionKey,
        String taskDefinitionKey,
        String taskName,
        String assigneeId,
        long version
    ) {
        public PendingTask {
            taskId = Objects.requireNonNull(taskId, "taskId must not be null");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            engineTaskId = requireText(engineTaskId, "engineTaskId");
            engineInstanceId = requireText(engineInstanceId, "engineInstanceId");
            definitionKey = requireText(definitionKey, "definitionKey");
            taskDefinitionKey = requireText(taskDefinitionKey, "taskDefinitionKey");
            taskName = requireText(taskName, "taskName");
            assigneeId = requireText(assigneeId, "assigneeId");
            if (version < 1) {
                throw new IllegalArgumentException("version must be positive");
            }
        }
    }

    record PrincipalHandover(
        UUID handoverId,
        String tenantId,
        String connectorKey,
        String principalId,
        IdentityReference principalIdentity,
        String successorId,
        IdentityReference successorIdentity,
        String reason,
        HandoverStatus status,
        String createdBy,
        Instant createdAt,
        String revokedBy,
        Instant revokedAt,
        String revokeReason,
        long version
    ) {
        public PrincipalHandover {
            handoverId = Objects.requireNonNull(handoverId, "handoverId must not be null");
            tenantId = requireText(tenantId, "tenantId");
            connectorKey = requireText(connectorKey, "connectorKey");
            principalId = requireText(principalId, "principalId");
            principalIdentity = Objects.requireNonNull(
                principalIdentity,
                "principalIdentity must not be null"
            );
            successorId = requireText(successorId, "successorId");
            successorIdentity = Objects.requireNonNull(
                successorIdentity,
                "successorIdentity must not be null"
            );
            reason = requireText(reason, "reason");
            status = Objects.requireNonNull(status, "status must not be null");
            createdBy = requireText(createdBy, "createdBy");
            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
            revokedBy = normalizeOptional(revokedBy);
            revokeReason = normalizeOptional(revokeReason);
            if (principalId.equals(successorId)) {
                throw new IllegalArgumentException("successorId must differ from principalId");
            }
            boolean anyRevocation = revokedBy != null || revokedAt != null || revokeReason != null;
            boolean completeRevocation = revokedBy != null
                && revokedAt != null
                && revokeReason != null;
            if (status == HandoverStatus.ACTIVE && anyRevocation) {
                throw new IllegalArgumentException(
                    "active handover must not contain revocation metadata"
                );
            }
            if (status == HandoverStatus.REVOKED && !completeRevocation) {
                throw new IllegalArgumentException(
                    "revoked handover requires complete revocation metadata"
                );
            }
            if (version < 1) {
                throw new IllegalArgumentException("version must be positive");
            }
        }
    }

    record HandoverTaskAssignment(
        UUID assignmentId,
        String tenantId,
        String engineTaskId,
        String engineInstanceId,
        String definitionKey,
        String taskDefinitionKey,
        String principalAssigneeId,
        String successorAssigneeId,
        UUID handoverId,
        AssignmentStatus status,
        Instant assignedAt,
        String completedBy,
        Instant completedAt,
        String supersededAssigneeId,
        Instant supersededAt,
        Instant canceledAt,
        long version
    ) {
        public HandoverTaskAssignment {
            assignmentId = Objects.requireNonNull(
                assignmentId,
                "assignmentId must not be null"
            );
            tenantId = requireText(tenantId, "tenantId");
            engineTaskId = requireText(engineTaskId, "engineTaskId");
            engineInstanceId = requireText(engineInstanceId, "engineInstanceId");
            definitionKey = requireText(definitionKey, "definitionKey");
            taskDefinitionKey = requireText(taskDefinitionKey, "taskDefinitionKey");
            principalAssigneeId = requireText(
                principalAssigneeId,
                "principalAssigneeId"
            );
            successorAssigneeId = requireText(
                successorAssigneeId,
                "successorAssigneeId"
            );
            handoverId = Objects.requireNonNull(handoverId, "handoverId must not be null");
            status = Objects.requireNonNull(status, "status must not be null");
            assignedAt = Objects.requireNonNull(assignedAt, "assignedAt must not be null");
            completedBy = normalizeOptional(completedBy);
            supersededAssigneeId = normalizeOptional(supersededAssigneeId);
            if (principalAssigneeId.equals(successorAssigneeId)) {
                throw new IllegalArgumentException(
                    "successorAssigneeId must differ from principalAssigneeId"
                );
            }
            validateTerminalMetadata(
                status,
                completedBy,
                completedAt,
                supersededAssigneeId,
                supersededAt,
                canceledAt
            );
            if (version < 1) {
                throw new IllegalArgumentException("version must be positive");
            }
        }
    }

    enum HandoverStatus {
        ACTIVE,
        REVOKED
    }

    enum AssignmentStatus {
        ACTIVE,
        COMPLETED,
        SUPERSEDED,
        CANCELED
    }

    final class HandoverConflictException extends RuntimeException {

        public HandoverConflictException(String message) {
            super(message);
        }
    }

    final class HandoverNotFoundException extends RuntimeException {

        public HandoverNotFoundException(String message) {
            super(message);
        }
    }

    private static void validateTerminalMetadata(
        AssignmentStatus status,
        String completedBy,
        Instant completedAt,
        String supersededAssigneeId,
        Instant supersededAt,
        Instant canceledAt
    ) {
        boolean completion = completedBy != null || completedAt != null;
        boolean completeCompletion = completedBy != null && completedAt != null;
        boolean supersession = supersededAssigneeId != null || supersededAt != null;
        boolean completeSupersession = supersededAssigneeId != null && supersededAt != null;
        switch (status) {
            case ACTIVE -> {
                if (completion || supersession || canceledAt != null) {
                    throw new IllegalArgumentException(
                        "active assignment must not contain terminal metadata"
                    );
                }
            }
            case COMPLETED -> {
                if (!completeCompletion || supersession || canceledAt != null) {
                    throw new IllegalArgumentException(
                        "completed assignment requires only completion metadata"
                    );
                }
            }
            case SUPERSEDED -> {
                if (!completeSupersession || completion || canceledAt != null) {
                    throw new IllegalArgumentException(
                        "superseded assignment requires only supersession metadata"
                    );
                }
            }
            case CANCELED -> {
                if (canceledAt == null || completion || supersession) {
                    throw new IllegalArgumentException(
                        "canceled assignment requires only canceledAt"
                    );
                }
            }
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
