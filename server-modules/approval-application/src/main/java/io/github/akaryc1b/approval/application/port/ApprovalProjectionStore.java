package io.github.akaryc1b.approval.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Platform-owned query and concurrency model. Implementations must not read Flowable tables.
 */
public interface ApprovalProjectionStore {

    void lockDefinition(String tenantId, String definitionKey, int definitionVersion);

    Optional<PublishedDefinition> findDefinition(
        String tenantId,
        String definitionKey,
        int definitionVersion
    );

    void saveDefinition(PublishedDefinition definition);

    void lockBusinessKey(String tenantId, String businessKey);

    Optional<InstanceProjection> findByBusinessKey(String tenantId, String businessKey);

    void createInstance(InstanceProjection instance, List<TaskProjection> tasks);

    Optional<InstanceProjection> findInstance(String tenantId, UUID instanceId);

    List<TaskProjection> findTasks(String tenantId, UUID instanceId);

    TaskProjection claimPendingTask(
        String tenantId,
        UUID taskId,
        String operatorId,
        Instant claimedAt
    );

    void completeTaskAndSynchronize(
        String tenantId,
        UUID instanceId,
        UUID completedTaskId,
        long claimedTaskVersion,
        List<TaskProjection> activeTasks,
        InstanceStatus instanceStatus,
        Instant completedAt
    );

    record PublishedDefinition(
        String tenantId,
        String definitionKey,
        int definitionVersion,
        String formKey,
        int formVersion,
        String compilerVersion,
        String contentHash,
        String deploymentId,
        String engineDefinitionId,
        int engineVersion,
        String publishedBy,
        Instant publishedAt
    ) {
    }

    record InstanceProjection(
        UUID instanceId,
        String tenantId,
        String businessKey,
        String engineInstanceId,
        String definitionKey,
        int definitionVersion,
        String formKey,
        int formVersion,
        String compilerVersion,
        String contentHash,
        String initiatorId,
        BigDecimal amount,
        String supplier,
        String purchaseOrderReference,
        List<String> attachmentIds,
        AssigneeSnapshot assigneeSnapshot,
        String requestHash,
        InstanceStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt
    ) {
        public InstanceProjection {
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
        }
    }

    record AssigneeSnapshot(
        String managerAssignee,
        String financeReviewer,
        List<String> financeApprovers,
        Map<String, String> attributes
    ) {
        public AssigneeSnapshot {
            financeApprovers = financeApprovers == null ? List.of() : List.copyOf(financeApprovers);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    record TaskProjection(
        UUID taskId,
        UUID instanceId,
        String tenantId,
        String engineTaskId,
        String taskDefinitionKey,
        String name,
        String assigneeId,
        TaskStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
    ) {
    }

    enum InstanceStatus {
        RUNNING,
        COMPLETED,
        REJECTED,
        WITHDRAWN
    }

    enum TaskStatus {
        PENDING,
        COMPLETING,
        COMPLETED,
        CANCELED
    }

    final class ProjectionConflictException extends RuntimeException {

        public ProjectionConflictException(String message) {
            super(message);
        }
    }
}
