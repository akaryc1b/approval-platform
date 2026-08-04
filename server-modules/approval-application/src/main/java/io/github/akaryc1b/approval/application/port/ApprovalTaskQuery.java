package io.github.akaryc1b.approval.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Read model for user-facing approval task centers. Implementations must query platform projections.
 */
public interface ApprovalTaskQuery {

    PendingTaskPage findPendingTasks(PendingTaskCriteria criteria);

    Optional<PendingTaskDetails> findPendingTask(PendingTaskIdentity identity);

    record PendingTaskCriteria(
        String tenantId,
        String assigneeId,
        String keyword,
        int limit,
        int offset
    ) {
        public PendingTaskCriteria {
            tenantId = requireText(tenantId, "tenantId");
            assigneeId = requireText(assigneeId, "assigneeId");
            keyword = normalizeOptional(keyword);
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("limit must be between 1 and 100");
            }
            if (offset < 0) {
                throw new IllegalArgumentException("offset must not be negative");
            }
        }
    }

    record PendingTaskIdentity(
        String tenantId,
        String assigneeId,
        UUID taskId
    ) {
        public PendingTaskIdentity {
            tenantId = requireText(tenantId, "tenantId");
            assigneeId = requireText(assigneeId, "assigneeId");
            taskId = Objects.requireNonNull(taskId, "taskId must not be null");
        }
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

    record TransferCandidate(String userId, String displayName) {
        public TransferCandidate {
            userId = requireText(userId, "userId");
            displayName = requireText(displayName, "displayName");
        }
    }

    record PendingTaskDetails(
        UUID taskId,
        UUID instanceId,
        String definitionKey,
        int definitionVersion,
        String formKey,
        int formVersion,
        String compilerVersion,
        String contentHash,
        String taskDefinitionKey,
        String taskName,
        String businessKey,
        String initiatorId,
        BigDecimal amount,
        String supplier,
        String purchaseOrderReference,
        List<String> attachmentIds,
        List<TransferCandidate> transferCandidates,
        Instant instanceCreatedAt,
        Instant instanceUpdatedAt,
        Instant taskCreatedAt,
        Instant taskUpdatedAt,
        Integer releaseVersion,
        String releasePackageHash,
        Integer formPackageVersion,
        String formPackageHash,
        Integer uiSchemaVersion,
        String uiSchemaHash,
        String formSchemaVersion,
        Integer formSchemaFieldCount
    ) {
        public PendingTaskDetails {
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
            transferCandidates = transferCandidates == null
                ? List.of()
                : List.copyOf(transferCandidates);
            releasePackageHash = normalizeOptional(releasePackageHash);
            formPackageHash = normalizeOptional(formPackageHash);
            uiSchemaHash = normalizeOptional(uiSchemaHash);
            formSchemaVersion = normalizeOptional(formSchemaVersion);

            boolean anyReleaseProvenance = releaseVersion != null
                || releasePackageHash != null;
            boolean completeReleaseProvenance = releaseVersion != null
                && releasePackageHash != null;
            if (anyReleaseProvenance && !completeReleaseProvenance) {
                throw new IllegalArgumentException(
                    "release provenance must be either complete or absent"
                );
            }
            if (releaseVersion != null && releaseVersion < 1) {
                throw new IllegalArgumentException("releaseVersion must be positive");
            }

            boolean anyFormProvenance = formPackageVersion != null
                || formPackageHash != null
                || uiSchemaVersion != null
                || uiSchemaHash != null
                || formSchemaVersion != null
                || formSchemaFieldCount != null;
            boolean completeFormProvenance = formPackageVersion != null
                && formPackageHash != null
                && uiSchemaVersion != null
                && uiSchemaHash != null
                && formSchemaVersion != null
                && formSchemaFieldCount != null;
            if (anyFormProvenance && !completeFormProvenance) {
                throw new IllegalArgumentException(
                    "form provenance must be either complete or absent"
                );
            }
            if (formPackageVersion != null && formPackageVersion < 1) {
                throw new IllegalArgumentException("formPackageVersion must be positive");
            }
            if (uiSchemaVersion != null && uiSchemaVersion < 1) {
                throw new IllegalArgumentException("uiSchemaVersion must be positive");
            }
            if (formSchemaFieldCount != null && formSchemaFieldCount < 0) {
                throw new IllegalArgumentException(
                    "formSchemaFieldCount must not be negative"
                );
            }
        }

        public PendingTaskDetails(
            UUID taskId,
            UUID instanceId,
            String definitionKey,
            int definitionVersion,
            String formKey,
            int formVersion,
            String compilerVersion,
            String contentHash,
            String taskDefinitionKey,
            String taskName,
            String businessKey,
            String initiatorId,
            BigDecimal amount,
            String supplier,
            String purchaseOrderReference,
            List<String> attachmentIds,
            List<TransferCandidate> transferCandidates,
            Instant instanceCreatedAt,
            Instant instanceUpdatedAt,
            Instant taskCreatedAt,
            Instant taskUpdatedAt
        ) {
            this(
                taskId,
                instanceId,
                definitionKey,
                definitionVersion,
                formKey,
                formVersion,
                compilerVersion,
                contentHash,
                taskDefinitionKey,
                taskName,
                businessKey,
                initiatorId,
                amount,
                supplier,
                purchaseOrderReference,
                attachmentIds,
                transferCandidates,
                instanceCreatedAt,
                instanceUpdatedAt,
                taskCreatedAt,
                taskUpdatedAt,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
            );
        }
    }

    record PendingTaskPage(
        List<PendingTaskItem> items,
        long total,
        int limit,
        int offset,
        boolean hasMore
    ) {
        public PendingTaskPage(
            List<PendingTaskItem> items,
            long total,
            int limit,
            int offset
        ) {
            this(
                items,
                total,
                limit,
                offset,
                offset + (items == null ? 0 : items.size()) < total
            );
        }

        public PendingTaskPage {
            items = items == null ? List.of() : List.copyOf(items);
            if (total < 0) {
                throw new IllegalArgumentException("total must not be negative");
            }
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("limit must be between 1 and 100");
            }
            if (offset < 0) {
                throw new IllegalArgumentException("offset must not be negative");
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
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
