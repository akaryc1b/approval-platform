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

    default Optional<TaskProjection> findTask(String tenantId, UUID taskId) {
        throw new UnsupportedOperationException("task lookup is not supported");
    }

    TaskProjection claimPendingTask(
        String tenantId,
        UUID taskId,
        String operatorId,
        Instant claimedAt
    );

    default TaskProjection claimPendingTaskForControl(
        String tenantId,
        UUID taskId,
        Instant claimedAt
    ) {
        throw new UnsupportedOperationException("task control claim is not supported");
    }

    default TaskProjection transferPendingTask(
        String tenantId,
        UUID taskId,
        String currentAssigneeId,
        String targetAssigneeId,
        Instant transferredAt
    ) {
        throw new UnsupportedOperationException("task transfer is not supported");
    }

    void completeTaskAndSynchronize(
        String tenantId,
        UUID instanceId,
        UUID completedTaskId,
        long claimedTaskVersion,
        List<TaskProjection> activeTasks,
        InstanceStatus instanceStatus,
        Instant completedAt
    );

    default void cancelClaimedTaskAndSynchronize(
        String tenantId,
        UUID instanceId,
        UUID canceledTaskId,
        long claimedTaskVersion,
        List<TaskProjection> activeTasks,
        Instant changedAt
    ) {
        throw new UnsupportedOperationException("controlled task replacement is not supported");
    }

    default void withdrawRunningInstance(
        String tenantId,
        UUID instanceId,
        String initiatorId,
        Instant withdrawnAt
    ) {
        throw new UnsupportedOperationException("instance withdrawal is not supported");
    }

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
        Integer releaseVersion,
        String releasePackageHash,
        Integer formPackageVersion,
        String formPackageHash,
        Integer uiSchemaVersion,
        String uiSchemaHash,
        String engineDefinitionId,
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
            boolean anyReleaseSnapshot = releaseVersion != null
                || releasePackageHash != null
                || formPackageVersion != null
                || formPackageHash != null
                || uiSchemaVersion != null
                || uiSchemaHash != null
                || engineDefinitionId != null;
            boolean completeReleaseSnapshot = releaseVersion != null
                && releasePackageHash != null
                && formPackageVersion != null
                && formPackageHash != null
                && uiSchemaVersion != null
                && uiSchemaHash != null
                && engineDefinitionId != null;
            if (anyReleaseSnapshot && !completeReleaseSnapshot) {
                throw new IllegalArgumentException(
                    "instance release snapshot must be either complete or absent"
                );
            }
        }

        public InstanceProjection(
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
            this(
                instanceId, tenantId, businessKey, engineInstanceId,
                definitionKey, definitionVersion, formKey, formVersion,
                compilerVersion, contentHash, null, null, null, null,
                null, null, null, initiatorId, amount, supplier,
                purchaseOrderReference, attachmentIds, assigneeSnapshot,
                requestHash, status, version, createdAt, updatedAt
            );
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
