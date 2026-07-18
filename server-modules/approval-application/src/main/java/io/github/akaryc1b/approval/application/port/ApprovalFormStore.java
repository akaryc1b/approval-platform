package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.form.FormDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Platform-owned immutable form schema repository.
 */
public interface ApprovalFormStore {

    void lockVersion(String tenantId, String formKey, int version);

    Optional<PublishedForm> find(String tenantId, String formKey, int version);

    void save(PublishedForm form);

    FormPage findForms(FormCriteria criteria);

    record PublishedForm(
        String tenantId,
        FormDefinition definition,
        String contentHash,
        String publishedBy,
        Instant publishedAt
    ) {
        public PublishedForm {
            tenantId = requireText(tenantId, "tenantId");
            definition = Objects.requireNonNull(definition, "definition must not be null");
            contentHash = requireText(contentHash, "contentHash");
            publishedBy = requireText(publishedBy, "publishedBy");
            publishedAt = Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        }
    }

    record FormCriteria(
        String tenantId,
        String keyword,
        int limit,
        int offset
    ) {
        public FormCriteria {
            tenantId = requireText(tenantId, "tenantId");
            keyword = normalizeOptional(keyword);
            validatePage(limit, offset);
        }
    }

    record FormSummary(
        String formKey,
        int version,
        String name,
        String schemaVersion,
        int fieldCount,
        String contentHash,
        String publishedBy,
        Instant publishedAt
    ) {
    }

    record FormPage(
        List<FormSummary> items,
        long total,
        int limit,
        int offset,
        boolean hasMore
    ) {
        public FormPage(List<FormSummary> items, long total, int limit, int offset) {
            this(items, total, limit, offset, offset + (items == null ? 0 : items.size()) < total);
        }

        public FormPage {
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
