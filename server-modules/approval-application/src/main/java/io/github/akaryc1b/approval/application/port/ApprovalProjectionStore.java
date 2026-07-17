package io.github.akaryc1b.approval.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    PendingTaskPage findPendingTasks(
        String tenantId,
        String assigneeId,
        String keyword,
        int limit,
        int offset
    );

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
        Map<String, String> attributes,
        Map<String, UserIdentitySnapshot> identities
    ) {
        public AssigneeSnapshot {
            financeApprovers = financeApprovers == null ? List.of() : List.copyOf(financeApprovers);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            identities = identities == null ? Map.of() : Map.copyOf(identities);
        }

        public AssigneeSnapshot(
            String managerAssignee,
            String financeReviewer,
            List<String> financeApprovers,
            Map<String, String> attributes
        ) {
            this(managerAssignee, financeReviewer, financeApprovers, attributes, Map.of());
        }
    }

    record UserIdentitySnapshot(
        String externalId,
        String username,
        String displayName,
        String email,
        String mobile,
        List<String> departmentIds,
        Set<String> roleCodes,
        Set<String> positionCodes,
        Map<String, String> attributes
    ) {
        public UserIdentitySnapshot {
            departmentIds = departmentIds == null ? List.of() : List.copyOf(departmentIds);
            roleCodes = roleCodes == null ? Set.of() : Set.copyOf(roleCodes);
            positionCodes = positionCodes == null ? Set.of() : Set.copyOf(positionCodes);
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

    record PendingTaskItem(
        UUID taskId,
        UUID instanceId,
        String definitionKey,
        String taskDefinitionKey,
        String taskName,
        String businessKey,
        String initiatorId,
        BigDecimal amount,
        String supplier,
        String purchaseOrderReference,
        Instant taskCreatedAt,
        Instant taskUpdatedAt
    ) {
    }

    record PendingTaskPage(
        List<PendingTaskItem> items,
        long total,
        int limit,
        int offset
    ) {
        public PendingTaskPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
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
