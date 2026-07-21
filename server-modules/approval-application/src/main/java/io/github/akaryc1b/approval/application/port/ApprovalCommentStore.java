package io.github.akaryc1b.approval.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Platform-owned approval comments, immutable revision evidence and server-side visibility queries.
 */
public interface ApprovalCommentStore {

    StoredCommentItem create(ApprovalComment comment, CommentRevision revision);

    Optional<StoredCommentItem> findComment(String tenantId, UUID instanceId, UUID commentId);

    StoredCommentPage findComments(CommentCriteria criteria);

    List<StoredCommentRevision> findRevisions(String tenantId, UUID instanceId, UUID commentId);

    StoredCommentItem update(CommentUpdate update, CommentRevision revision);

    StoredCommentItem delete(CommentDeletion deletion, CommentRevision revision);

    List<CommentParticipantIdentity> findAdditionalParticipants(String tenantId, UUID instanceId);

    CommentAttachmentAccess findAttachmentAccess(
        String tenantId,
        UUID instanceId,
        UUID attachmentId,
        String viewerId
    );

    enum CommentStatus {
        ACTIVE,
        DELETED
    }

    enum CommentVisibility {
        PARTICIPANTS,
        MENTIONED_ONLY
    }

    enum RevisionType {
        CREATE,
        EDIT,
        DELETE
    }

    record ApprovalComment(
        UUID commentId,
        String tenantId,
        UUID instanceId,
        UUID parentCommentId,
        String authorId,
        String body,
        List<String> mentionIds,
        List<UUID> attachmentIds,
        CommentStatus status,
        CommentVisibility visibility,
        int currentRevision,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt,
        String deletedBy,
        String deleteReason,
        long version
    ) {
        public ApprovalComment {
            commentId = Objects.requireNonNull(commentId, "commentId must not be null");
            tenantId = requireText(tenantId, "tenantId");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            if (commentId.equals(parentCommentId)) {
                throw new IllegalArgumentException("comment cannot reply to itself");
            }
            authorId = requireText(authorId, "authorId");
            body = requireText(body, "body");
            mentionIds = mentionIds == null ? List.of() : List.copyOf(mentionIds);
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
            status = Objects.requireNonNull(status, "status must not be null");
            visibility = Objects.requireNonNull(visibility, "visibility must not be null");
            if (currentRevision < 1) {
                throw new IllegalArgumentException("currentRevision must be positive");
            }
            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
            deletedBy = normalizeOptional(deletedBy);
            deleteReason = normalizeOptional(deleteReason);
            boolean deleted = status == CommentStatus.DELETED;
            if (deleted != (deletedAt != null && deletedBy != null && deleteReason != null)) {
                throw new IllegalArgumentException("deleted comment metadata must match status");
            }
            if (version < 1) {
                throw new IllegalArgumentException("version must be positive");
            }
        }
    }

    record CommentRevision(
        String tenantId,
        UUID commentId,
        int revisionNumber,
        RevisionType revisionType,
        String body,
        List<String> mentionIds,
        List<UUID> attachmentIds,
        CommentVisibility visibility,
        String operatorId,
        String reason,
        Instant occurredAt
    ) {
        public CommentRevision {
            tenantId = requireText(tenantId, "tenantId");
            commentId = Objects.requireNonNull(commentId, "commentId must not be null");
            if (revisionNumber < 1) {
                throw new IllegalArgumentException("revisionNumber must be positive");
            }
            revisionType = Objects.requireNonNull(revisionType, "revisionType must not be null");
            body = requireText(body, "body");
            mentionIds = mentionIds == null ? List.of() : List.copyOf(mentionIds);
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
            visibility = Objects.requireNonNull(visibility, "visibility must not be null");
            operatorId = requireText(operatorId, "operatorId");
            reason = normalizeOptional(reason);
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
            if (revisionType == RevisionType.DELETE && reason == null) {
                throw new IllegalArgumentException("delete revision requires a reason");
            }
        }
    }

    record CommentUpdate(
        String tenantId,
        UUID instanceId,
        UUID commentId,
        String body,
        List<String> mentionIds,
        List<UUID> attachmentIds,
        CommentVisibility visibility,
        Instant updatedAt,
        long expectedVersion
    ) {
        public CommentUpdate {
            tenantId = requireText(tenantId, "tenantId");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            commentId = Objects.requireNonNull(commentId, "commentId must not be null");
            body = requireText(body, "body");
            mentionIds = mentionIds == null ? List.of() : List.copyOf(mentionIds);
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
            visibility = Objects.requireNonNull(visibility, "visibility must not be null");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
            if (expectedVersion < 1) {
                throw new IllegalArgumentException("expectedVersion must be positive");
            }
        }
    }

    record CommentDeletion(
        String tenantId,
        UUID instanceId,
        UUID commentId,
        String deletedBy,
        String deleteReason,
        String tombstoneBody,
        Instant deletedAt,
        long expectedVersion
    ) {
        public CommentDeletion {
            tenantId = requireText(tenantId, "tenantId");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            commentId = Objects.requireNonNull(commentId, "commentId must not be null");
            deletedBy = requireText(deletedBy, "deletedBy");
            deleteReason = requireText(deleteReason, "deleteReason");
            tombstoneBody = requireText(tombstoneBody, "tombstoneBody");
            deletedAt = Objects.requireNonNull(deletedAt, "deletedAt must not be null");
            if (expectedVersion < 1) {
                throw new IllegalArgumentException("expectedVersion must be positive");
            }
        }
    }

    record CommentCriteria(
        String tenantId,
        UUID instanceId,
        String viewerId,
        int limit,
        int offset
    ) {
        public CommentCriteria {
            tenantId = requireText(tenantId, "tenantId");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            viewerId = requireText(viewerId, "viewerId");
            validatePage(limit, offset);
        }
    }

    record StoredCommentItem(
        UUID commentId,
        UUID instanceId,
        UUID parentCommentId,
        String parentAuthorId,
        String authorId,
        String body,
        List<String> mentionIds,
        List<UUID> attachmentIds,
        CommentStatus status,
        CommentVisibility visibility,
        int currentRevision,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt,
        String deletedBy,
        String deleteReason,
        long version
    ) {
        public StoredCommentItem {
            commentId = Objects.requireNonNull(commentId, "commentId must not be null");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            parentAuthorId = normalizeOptional(parentAuthorId);
            authorId = requireText(authorId, "authorId");
            body = requireText(body, "body");
            mentionIds = mentionIds == null ? List.of() : List.copyOf(mentionIds);
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
            status = Objects.requireNonNull(status, "status must not be null");
            visibility = Objects.requireNonNull(visibility, "visibility must not be null");
            if (currentRevision < 1) {
                throw new IllegalArgumentException("currentRevision must be positive");
            }
            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
            deletedBy = normalizeOptional(deletedBy);
            deleteReason = normalizeOptional(deleteReason);
            if (version < 1) {
                throw new IllegalArgumentException("version must be positive");
            }
        }
    }

    record StoredCommentRevision(
        int revisionNumber,
        RevisionType revisionType,
        String body,
        List<String> mentionIds,
        List<UUID> attachmentIds,
        CommentVisibility visibility,
        String operatorId,
        String reason,
        Instant occurredAt
    ) {
        public StoredCommentRevision {
            if (revisionNumber < 1) {
                throw new IllegalArgumentException("revisionNumber must be positive");
            }
            revisionType = Objects.requireNonNull(revisionType, "revisionType must not be null");
            body = requireText(body, "body");
            mentionIds = mentionIds == null ? List.of() : List.copyOf(mentionIds);
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
            visibility = Objects.requireNonNull(visibility, "visibility must not be null");
            operatorId = requireText(operatorId, "operatorId");
            reason = normalizeOptional(reason);
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
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

    record CommentParticipantIdentity(
        String userId,
        String displayName,
        String identitySource,
        String objectType,
        String externalIdentityValue
    ) {
        public CommentParticipantIdentity {
            userId = requireText(userId, "userId");
            displayName = requireText(displayName, "displayName");
            identitySource = requireText(identitySource, "identitySource");
            objectType = requireText(objectType, "objectType");
            externalIdentityValue = requireText(externalIdentityValue, "externalIdentityValue");
        }
    }

    record CommentAttachmentAccess(boolean referenced, boolean readable) {
        public CommentAttachmentAccess {
            if (!referenced && readable) {
                throw new IllegalArgumentException("unreferenced attachment cannot be comment-readable");
            }
        }
    }

    final class CommentNotFoundException extends RuntimeException {
        public CommentNotFoundException(String message) {
            super(message);
        }
    }

    final class CommentConflictException extends RuntimeException {
        private final String code;

        public CommentConflictException(String code, String message) {
            super(requireText(message, "message"));
            this.code = requireText(code, "code");
        }

        public String code() {
            return code;
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
