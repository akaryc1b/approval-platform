package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.definition.ApprovalProcessRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Tenant-scoped process release lifecycle and append-only transition evidence store. */
public interface ApprovalProcessReleaseStore {

    void lock(String tenantId, String definitionKey);

    Optional<ApprovalProcessRelease> find(
        String tenantId,
        String definitionKey,
        int releaseVersion
    );

    Optional<ApprovalProcessRelease> findActive(String tenantId, String definitionKey);

    Optional<ApprovalProcessRelease.Transition> findTransitionByIdempotency(
        String tenantId,
        String idempotencyKey
    );

    ReleasePage findReleases(ReleaseCriteria criteria);

    TransitionPage findHistory(TransitionCriteria criteria);

    void savePublished(
        ApprovalProcessRelease release,
        ApprovalProcessRelease.Transition transition
    );

    boolean transition(
        ApprovalProcessRelease release,
        long expectedRevision,
        ApprovalProcessRelease.Transition transition
    );

    record ReleaseCriteria(
        String tenantId,
        String definitionKey,
        State lifecycleState,
        int limit,
        int offset
    ) {
        public ReleaseCriteria {
            tenantId = requireText(tenantId, "tenantId");
            definitionKey = normalizeOptional(definitionKey);
            if (lifecycleState == State.DRAFT) {
                throw new IllegalArgumentException("persisted release criteria cannot use DRAFT");
            }
            validatePage(limit, offset);
        }
    }

    record ReleasePage(
        List<ApprovalProcessRelease> items,
        long total,
        int limit,
        int offset,
        boolean hasMore
    ) {
        public ReleasePage(
            List<ApprovalProcessRelease> items,
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
            requireTotal(total);
            validatePage(limit, offset);
        }
    }

    record TransitionCriteria(
        String tenantId,
        String definitionKey,
        int releaseVersion,
        int limit,
        int offset
    ) {
        public TransitionCriteria {
            tenantId = requireText(tenantId, "tenantId");
            definitionKey = requireText(definitionKey, "definitionKey");
            requirePositive(releaseVersion, "releaseVersion");
            validatePage(limit, offset);
        }
    }

    record TransitionPage(
        List<ApprovalProcessRelease.Transition> items,
        long total,
        int limit,
        int offset,
        boolean hasMore
    ) {
        public TransitionPage(
            List<ApprovalProcessRelease.Transition> items,
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

        public TransitionPage {
            items = items == null ? List.of() : List.copyOf(items);
            requireTotal(total);
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

    private static void requireTotal(long total) {
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
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

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
