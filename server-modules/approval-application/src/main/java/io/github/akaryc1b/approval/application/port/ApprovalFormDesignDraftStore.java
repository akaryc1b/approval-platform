package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.form.FormDesignDraft;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Tenant-isolated mutable design draft repository with optimistic revision updates. */
public interface ApprovalFormDesignDraftStore {

    void save(FormDesignDraft draft);

    Optional<FormDesignDraft> find(String tenantId, UUID draftId);

    DraftPage findDrafts(DraftCriteria criteria);

    void lock(String tenantId, UUID draftId);

    boolean update(FormDesignDraft draft, long expectedRevision);

    record DraftCriteria(
        String tenantId,
        String keyword,
        FormDesignDraft.Status status,
        int limit,
        int offset
    ) {
        public DraftCriteria {
            tenantId = requireText(tenantId, "tenantId");
            keyword = normalizeOptional(keyword);
            validatePage(limit, offset);
        }
    }

    record DraftSummary(
        UUID draftId,
        String formKey,
        String name,
        int formVersion,
        int uiSchemaVersion,
        long revision,
        FormDesignDraft.Status status,
        Integer publishedPackageVersion,
        String updatedBy,
        Instant updatedAt
    ) {
    }

    record DraftPage(
        List<DraftSummary> items,
        long total,
        int limit,
        int offset,
        boolean hasMore
    ) {
        public DraftPage(List<DraftSummary> items, long total, int limit, int offset) {
            this(items, total, limit, offset, offset + (items == null ? 0 : items.size()) < total);
        }

        public DraftPage {
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
