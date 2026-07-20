package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.definition.ApprovalEffectiveRelease;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Platform-owned effective release projection and immutable activation history store. */
public interface ApprovalEffectiveReleaseStore {

    void lock(String tenantId, String definitionKey);

    Optional<ApprovalEffectiveRelease> find(String tenantId, String definitionKey);

    void save(
        ApprovalEffectiveRelease effectiveRelease,
        ApprovalEffectiveRelease.Activation activation
    );

    boolean update(
        ApprovalEffectiveRelease effectiveRelease,
        long expectedRevision,
        ApprovalEffectiveRelease.Activation activation
    );

    boolean wasActivated(String tenantId, String definitionKey, int releaseVersion);

    ActivationPage findHistory(ActivationCriteria criteria);

    record ActivationCriteria(
        String tenantId,
        String definitionKey,
        int limit,
        int offset
    ) {
        public ActivationCriteria {
            tenantId = requireText(tenantId, "tenantId");
            definitionKey = requireText(definitionKey, "definitionKey");
            validatePage(limit, offset);
        }
    }

    record ActivationPage(
        List<ApprovalEffectiveRelease.Activation> items,
        long total,
        int limit,
        int offset,
        boolean hasMore
    ) {
        public ActivationPage(
            List<ApprovalEffectiveRelease.Activation> items,
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

        public ActivationPage {
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

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
