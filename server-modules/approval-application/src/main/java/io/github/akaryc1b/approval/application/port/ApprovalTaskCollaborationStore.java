package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityReference;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Platform-owned dynamic add-sign policy and participant responsibility evidence. */
public interface ApprovalTaskCollaborationStore {

    void lockTask(String tenantId, UUID taskId);

    TaskCollaboration create(TaskCollaboration collaboration);

    Optional<TaskCollaboration> findByTask(String tenantId, UUID taskId);

    Optional<TaskCollaboration> findByParticipant(String tenantId, UUID participantId);

    List<PendingCollaborationTask> findPendingByParticipant(
        String tenantId,
        String participantUserId,
        int limit
    );

    TaskCollaboration removeParticipant(
        String tenantId,
        UUID participantId,
        String removedBy,
        String reason,
        Instant removedAt
    );

    TaskCollaboration decideParticipant(
        String tenantId,
        UUID participantId,
        String participantUserId,
        ParticipantDecision decision,
        String comment,
        Instant decidedAt
    );

    Optional<TaskCollaboration> cancelActiveByTask(
        String tenantId,
        UUID taskId,
        String canceledBy,
        String reason,
        Instant canceledAt
    );

    record TaskCollaboration(
        UUID policyId,
        String tenantId,
        UUID taskId,
        UUID instanceId,
        String engineTaskId,
        String engineInstanceId,
        String definitionKey,
        String taskDefinitionKey,
        String taskName,
        String ownerAssigneeId,
        CollaborationMode mode,
        CollaborationStatus status,
        String reason,
        String createdBy,
        Instant createdAt,
        String terminalBy,
        Instant terminalAt,
        String terminalReason,
        long version,
        List<CollaborationParticipant> participants
    ) {
        public TaskCollaboration {
            policyId = Objects.requireNonNull(policyId, "policyId must not be null");
            tenantId = requireText(tenantId, "tenantId");
            taskId = Objects.requireNonNull(taskId, "taskId must not be null");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            engineTaskId = requireText(engineTaskId, "engineTaskId");
            engineInstanceId = requireText(engineInstanceId, "engineInstanceId");
            definitionKey = requireText(definitionKey, "definitionKey");
            taskDefinitionKey = requireText(taskDefinitionKey, "taskDefinitionKey");
            taskName = requireText(taskName, "taskName");
            ownerAssigneeId = requireText(ownerAssigneeId, "ownerAssigneeId");
            mode = Objects.requireNonNull(mode, "mode must not be null");
            status = Objects.requireNonNull(status, "status must not be null");
            reason = requireText(reason, "reason");
            createdBy = requireText(createdBy, "createdBy");
            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
            terminalBy = normalizeOptional(terminalBy);
            terminalReason = normalizeOptional(terminalReason);
            participants = participants == null ? List.of() : List.copyOf(participants);
            if (participants.isEmpty()) {
                throw new IllegalArgumentException("participants must not be empty");
            }
            if (version < 1) {
                throw new IllegalArgumentException("version must be positive");
            }
            boolean terminal = status != CollaborationStatus.ACTIVE;
            if (terminal != (terminalBy != null && terminalAt != null && terminalReason != null)) {
                throw new IllegalArgumentException(
                    "terminal collaboration metadata must match collaboration status"
                );
            }
        }
    }

    record CollaborationParticipant(
        UUID participantId,
        UUID policyId,
        String tenantId,
        String participantUserId,
        IdentityReference identity,
        ParticipantStatus status,
        String addedBy,
        Instant addedAt,
        String decisionComment,
        Instant decidedAt,
        String removedBy,
        Instant removedAt,
        String removalReason,
        Instant canceledAt,
        long version
    ) {
        public CollaborationParticipant {
            participantId = Objects.requireNonNull(
                participantId,
                "participantId must not be null"
            );
            policyId = Objects.requireNonNull(policyId, "policyId must not be null");
            tenantId = requireText(tenantId, "tenantId");
            participantUserId = requireText(participantUserId, "participantUserId");
            identity = Objects.requireNonNull(identity, "identity must not be null");
            status = Objects.requireNonNull(status, "status must not be null");
            addedBy = requireText(addedBy, "addedBy");
            addedAt = Objects.requireNonNull(addedAt, "addedAt must not be null");
            decisionComment = normalizeOptional(decisionComment);
            removedBy = normalizeOptional(removedBy);
            removalReason = normalizeOptional(removalReason);
            validateParticipantTerminalMetadata(
                status,
                decisionComment,
                decidedAt,
                removedBy,
                removedAt,
                removalReason,
                canceledAt
            );
            if (version < 1) {
                throw new IllegalArgumentException("version must be positive");
            }
        }
    }

    record PendingCollaborationTask(
        UUID participantId,
        UUID policyId,
        UUID taskId,
        UUID instanceId,
        String definitionKey,
        String taskDefinitionKey,
        String taskName,
        String ownerAssigneeId,
        CollaborationMode mode,
        String reason,
        Instant addedAt
    ) {
        public PendingCollaborationTask {
            participantId = Objects.requireNonNull(
                participantId,
                "participantId must not be null"
            );
            policyId = Objects.requireNonNull(policyId, "policyId must not be null");
            taskId = Objects.requireNonNull(taskId, "taskId must not be null");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            definitionKey = requireText(definitionKey, "definitionKey");
            taskDefinitionKey = requireText(taskDefinitionKey, "taskDefinitionKey");
            taskName = requireText(taskName, "taskName");
            ownerAssigneeId = requireText(ownerAssigneeId, "ownerAssigneeId");
            mode = Objects.requireNonNull(mode, "mode must not be null");
            reason = requireText(reason, "reason");
            addedAt = Objects.requireNonNull(addedAt, "addedAt must not be null");
        }
    }

    enum CollaborationMode {
        ALL,
        ANY
    }

    enum CollaborationStatus {
        ACTIVE,
        SATISFIED,
        REJECTED,
        CANCELED
    }

    enum ParticipantStatus {
        PENDING,
        APPROVED,
        REJECTED,
        REMOVED,
        CANCELED
    }

    enum ParticipantDecision {
        APPROVED,
        REJECTED
    }

    final class CollaborationConflictException extends RuntimeException {

        public CollaborationConflictException(String message) {
            super(message);
        }
    }

    final class CollaborationNotFoundException extends RuntimeException {

        public CollaborationNotFoundException(String message) {
            super(message);
        }
    }

    private static void validateParticipantTerminalMetadata(
        ParticipantStatus status,
        String decisionComment,
        Instant decidedAt,
        String removedBy,
        Instant removedAt,
        String removalReason,
        Instant canceledAt
    ) {
        boolean decision = decisionComment != null || decidedAt != null;
        boolean removal = removedBy != null || removedAt != null || removalReason != null;
        switch (status) {
            case PENDING -> {
                if (decision || removal || canceledAt != null) {
                    throw new IllegalArgumentException(
                        "pending participant must not contain terminal metadata"
                    );
                }
            }
            case APPROVED, REJECTED -> {
                if (decisionComment == null || decidedAt == null || removal || canceledAt != null) {
                    throw new IllegalArgumentException(
                        "decided participant requires only decision metadata"
                    );
                }
            }
            case REMOVED -> {
                if (removedBy == null || removedAt == null || removalReason == null
                    || decision || canceledAt != null) {
                    throw new IllegalArgumentException(
                        "removed participant requires only removal metadata"
                    );
                }
            }
            case CANCELED -> {
                if (canceledAt == null || decision || removal) {
                    throw new IllegalArgumentException(
                        "canceled participant requires only canceledAt"
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
