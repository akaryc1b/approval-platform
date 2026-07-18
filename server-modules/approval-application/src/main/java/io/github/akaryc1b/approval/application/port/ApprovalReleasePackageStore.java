package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable deployable Approval Release Package repository. */
public interface ApprovalReleasePackageStore {

    void lockVersion(String tenantId, String definitionKey, int releaseVersion);

    Optional<ApprovalReleasePackage> find(
        String tenantId,
        String definitionKey,
        int releaseVersion
    );

    Optional<ApprovalReleasePackage> findLatest(String tenantId, String definitionKey);

    Optional<ApprovalReleasePackage> findByDraft(String tenantId, UUID draftId);

    ReleasePage findReleases(ReleaseCriteria criteria);

    void save(ApprovalReleasePackage releasePackage);

    record ReleaseCriteria(
        String tenantId,
        String definitionKey,
        int limit,
        int offset
    ) {
        public ReleaseCriteria {
            tenantId = requireText(tenantId, "tenantId");
            definitionKey = normalizeOptional(definitionKey);
            validatePage(limit, offset);
        }
    }

    record ReleasePage(
        List<ApprovalReleasePackage> items,
        long total,
        int limit,
        int offset,
        boolean hasMore
    ) {
        public ReleasePage(
            List<ApprovalReleasePackage> items,
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

        public ReleasePage {
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

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
