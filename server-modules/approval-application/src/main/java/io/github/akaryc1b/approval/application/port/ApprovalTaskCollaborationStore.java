package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityReference;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Platform-owned dynamic collaboration policy and immutable participant responsibility evidence. */
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

    TaskCollaboration addParticipants(
        String tenantId,
        UUID policyId,
        List<CollaborationParticipant> participants
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
        Integer approvalThreshold,
        Integer approvalWeightThreshold,
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
            validateThresholds(mode, approvalThreshold, approvalWeightThreshold, participants);
            boolean terminal = status != CollaborationStatus.ACTIVE;
            if (terminal != (terminalBy != null && terminalAt != null && terminalReason != null)) {
                throw new IllegalArgumentException(
                    "terminal collaboration metadata must match collaboration status"
                );
            }
        }

        /** Real-time aggregate derived from participant evidence; never persisted as a mutable cache. */
        public CollaborationProgress progress() {
            int eligibleParticipants = 0;
            int approvedCount = 0;
            int rejectedCount = 0;
            int pendingCount = 0;
            int totalWeight = 0;
            int approvedWeight = 0;
            int rejectedWeight = 0;
            int pendingWeight = 0;
            for (CollaborationParticipant participant : participants) {
                if (participant.status() != ParticipantStatus.REMOVED) {
                    eligibleParticipants++;
                    totalWeight += participant.weight();
                }
                switch (participant.status()) {
                    case APPROVED -> {
                        approvedCount++;
                        approvedWeight += participant.weight();
                    }
                    case REJECTED -> {
                        rejectedCount++;
                        rejectedWeight += participant.weight();
                    }
                    case PENDING -> {
                        pendingCount++;
                        pendingWeight += participant.weight();
                    }
                    case REMOVED, CANCELED -> {
                        // Historical evidence is intentionally excluded from reachable totals.
                    }
                }
            }
            return new CollaborationProgress(
                eligibleParticipants,
                approvedCount,
                rejectedCount,
                pendingCount,
                totalWeight,
                approvedWeight,
                rejectedWeight,
                pendingWeight,
                approvedCount + pendingCount,
                approvedWeight + pendingWeight
            );
        }
    }

    record CollaborationParticipant(
        UUID participantId,
        UUID policyId,
        String tenantId,
        String participantUserId,
        IdentityReference identity,
        int weight,
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
            if (weight < 1) {
                throw new IllegalArgumentException("participant weight must be positive");
            }
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

    record CollaborationProgress(
        int eligibleParticipantCount,
        int approvedCount,
        int rejectedCount,
        int pendingCount,
        int totalWeight,
        int approvedWeight,
        int rejectedWeight,
        int pendingWeight,
        int maximumReachableApprovalCount,
        int maximumReachableApprovalWeight
    ) {
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
        Integer approvalThreshold,
        Integer approvalWeightThreshold,
        int participantWeight,
        CollaborationProgress progress,
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
            if (participantWeight < 1) {
                throw new IllegalArgumentException("participantWeight must be positive");
            }
            progress = Objects.requireNonNull(progress, "progress must not be null");
            reason = requireText(reason, "reason");
            addedAt = Objects.requireNonNull(addedAt, "addedAt must not be null");
        }
    }

    enum CollaborationMode {
        ALL,
        ANY,
        VOTE,
        WEIGHTED
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

    final class CollaborationValidationException extends IllegalArgumentException {

        private final String code;

        public CollaborationValidationException(String code, String message) {
            super(message);
            this.code = requireText(code, "code");
        }

        public String code() {
            return code;
        }
    }

    final class CollaborationAuthorizationException extends RuntimeException {

        public CollaborationAuthorizationException(String message) {
            super(message);
        }
    }

    final class CollaborationConflictException extends RuntimeException {

        private final String code;

        public CollaborationConflictException(String message) {
            this("APPROVAL_TASK_COLLABORATION_STATE_CONFLICT", message);
        }

        public CollaborationConflictException(String code, String message) {
            super(message);
            this.code = requireText(code, "code");
        }

        public String code() {
            return code;
        }
    }

    final class CollaborationNotFoundException extends RuntimeException {

        public CollaborationNotFoundException(String message) {
            super(message);
        }
    }

    private static void validateThresholds(
        CollaborationMode mode,
        Integer approvalThreshold,
        Integer approvalWeightThreshold,
        List<CollaborationParticipant> participants
    ) {
        int eligibleCount = (int) participants.stream()
            .filter(item -> item.status() != ParticipantStatus.REMOVED)
            .count();
        int totalWeight = participants.stream()
            .filter(item -> item.status() != ParticipantStatus.REMOVED)
            .mapToInt(CollaborationParticipant::weight)
            .sum();
        switch (mode) {
            case ALL, ANY -> {
                if (approvalThreshold != null || approvalWeightThreshold != null) {
                    throw new IllegalArgumentException(
                        "ALL and ANY collaboration must not contain thresholds"
                    );
                }
            }
            case VOTE -> {
                if (approvalThreshold == null || approvalThreshold < 1
                    || approvalThreshold > eligibleCount || approvalWeightThreshold != null) {
                    throw new IllegalArgumentException(
                        "VOTE collaboration requires a reachable approvalThreshold"
                    );
                }
                if (participants.stream().anyMatch(item -> item.weight() != 1)) {
                    throw new IllegalArgumentException("VOTE participants must have weight 1");
                }
            }
            case WEIGHTED -> {
                if (approvalWeightThreshold == null || approvalWeightThreshold < 1
                    || approvalWeightThreshold > totalWeight || approvalThreshold != null) {
                    throw new IllegalArgumentException(
                        "WEIGHTED collaboration requires a reachable approvalWeightThreshold"
                    );
                }
            }
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
