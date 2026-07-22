package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.definition.ApprovalRuntimeBinding;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable tenant-scoped runtime binding evidence store. */
public interface ApprovalRuntimeBindingStore {

    Optional<ApprovalRuntimeBinding> find(String tenantId, UUID approvalInstanceId);

    Optional<ApprovalRuntimeBinding> findByEngineInstance(
        String tenantId,
        String engineInstanceId
    );

    BindingPage findByRelease(BindingCriteria criteria);

    long countReleaseUsage(String tenantId, String definitionKey, int releaseVersion);

    void save(ApprovalRuntimeBinding binding);

    record BindingCriteria(
        String tenantId,
        String definitionKey,
        int releaseVersion,
        int limit,
        int offset
    ) {
        public BindingCriteria {
            tenantId = requireText(tenantId, "tenantId");
            definitionKey = requireText(definitionKey, "definitionKey");
            requirePositive(releaseVersion, "releaseVersion");
            validatePage(limit, offset);
        }
    }

    record BindingPage(
        List<ApprovalRuntimeBinding> items,
        long total,
        int limit,
        int offset,
        boolean hasMore
    ) {
        public BindingPage(
            List<ApprovalRuntimeBinding> items,
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

        public BindingPage {
            items = items == null ? List.of() : List.copyOf(items);
            if (total < 0) {
                throw new IllegalArgumentException("total must not be negative");
            }
            validatePage(limit, offset);
        }
    }

    private static void validatePage(int limit, int offset) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
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
}
