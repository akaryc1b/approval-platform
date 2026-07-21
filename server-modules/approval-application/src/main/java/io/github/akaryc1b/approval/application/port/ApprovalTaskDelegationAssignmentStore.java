package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.application.port.ApprovalDelegationStore.DelegationScope;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Platform-owned evidence for automatic task delegation. Implementations must not query
 * workflow-engine tables.
 */
public interface ApprovalTaskDelegationAssignmentStore {

    void lockEngineTask(String tenantId, String engineTaskId);

    DelegatedTaskAssignment create(DelegatedTaskAssignment assignment);

    Optional<DelegatedTaskAssignment> findByEngineTask(
        String tenantId,
        String engineTaskId
    );

    Optional<String> findDefinitionKeyByEngineInstance(
        String tenantId,
        String engineInstanceId
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

    void markCanceled(
        String tenantId,
        String engineTaskId,
        Instant canceledAt
    );

    void cancelActiveByEngineInstance(
        String tenantId,
        String engineInstanceId,
        Instant canceledAt
    );

    record DelegatedTaskAssignment(
        UUID assignmentId,
        String tenantId,
        String engineTaskId,
        String engineInstanceId,
        String definitionKey,
        String taskDefinitionKey,
        String principalAssigneeId,
        String delegateAssigneeId,
        UUID delegationRuleId,
        DelegationScope delegationScope,
        AssignmentStatus status,
        Instant assignedAt,
        String completedBy,
        Instant completedAt,
        String supersededAssigneeId,
        Instant supersededAt,
        Instant canceledAt,
        long version
    ) {
        public DelegatedTaskAssignment {
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
            delegateAssigneeId = requireText(
                delegateAssigneeId,
                "delegateAssigneeId"
            );
            delegationRuleId = Objects.requireNonNull(
                delegationRuleId,
                "delegationRuleId must not be null"
            );
            delegationScope = Objects.requireNonNull(
                delegationScope,
                "delegationScope must not be null"
            );
            status = Objects.requireNonNull(status, "status must not be null");
            assignedAt = Objects.requireNonNull(assignedAt, "assignedAt must not be null");
            completedBy = normalizeOptional(completedBy);
            supersededAssigneeId = normalizeOptional(supersededAssigneeId);
            if (principalAssigneeId.equals(delegateAssigneeId)) {
                throw new IllegalArgumentException(
                    "delegateAssigneeId must differ from principalAssigneeId"
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

    enum AssignmentStatus {
        ACTIVE,
        COMPLETED,
        SUPERSEDED,
        CANCELED
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
