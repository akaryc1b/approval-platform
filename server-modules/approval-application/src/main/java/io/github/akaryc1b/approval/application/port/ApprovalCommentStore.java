package io.github.akaryc1b.approval.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Platform-owned immutable approval comment store.
 */
public interface ApprovalCommentStore {

    void append(ApprovalComment comment);

    StoredCommentPage findComments(CommentCriteria criteria);

    record ApprovalComment(
        UUID commentId,
        String tenantId,
        UUID instanceId,
        String authorId,
        String body,
        List<String> mentionIds,
        List<String> attachmentIds,
        Instant createdAt
    ) {
        public ApprovalComment {
            commentId = Objects.requireNonNull(commentId, "commentId must not be null");
            tenantId = requireText(tenantId, "tenantId");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            authorId = requireText(authorId, "authorId");
            body = requireText(body, "body");
            mentionIds = mentionIds == null ? List.of() : List.copyOf(mentionIds);
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        }
    }

    record CommentCriteria(
        String tenantId,
        UUID instanceId,
        int limit,
        int offset
    ) {
        public CommentCriteria {
            tenantId = requireText(tenantId, "tenantId");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            validatePage(limit, offset);
        }
    }

    record StoredCommentItem(
        UUID commentId,
        UUID instanceId,
        String authorId,
        String body,
        List<String> mentionIds,
        List<String> attachmentIds,
        Instant createdAt
    ) {
        public StoredCommentItem {
            commentId = Objects.requireNonNull(commentId, "commentId must not be null");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            authorId = requireText(authorId, "authorId");
            body = requireText(body, "body");
            mentionIds = mentionIds == null ? List.of() : List.copyOf(mentionIds);
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        }
    }

    record StoredCommentPage(
        List<StoredCommentItem> items,
        long total,
        int limit,
        int offset,
        boolean hasMore
    ) {
        public StoredCommentPage(
            List<StoredCommentItem> items,
            long total,
            int limit,
            int offset
        ) {
            this(items, total, limit, offset, offset + (items == null ? 0 : items.size()) < total);
        }

        public StoredCommentPage {
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
