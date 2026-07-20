package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore;
import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore.ApprovalAttachment;
import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore.AttachmentSummary;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.ApprovalComment;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentDeletion;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentParticipantIdentity;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentRevision;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentStatus;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentUpdate;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentVisibility;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.RevisionType;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.StoredCommentItem;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.StoredCommentPage;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.StoredCommentRevision;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.ApprovalMessage;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.MessageType;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.UserIdentitySnapshot;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Governed approval comments, one-level replies, precise mentions and immutable revisions. */
public final class ApprovalCommentService {

    public static final int EDIT_WINDOW_MINUTES = 15;
    public static final String TOMBSTONE_BODY = "评论已删除";

    private static final String CREATE_OPERATION = "approval.comment.create.v3";
    private static final String EDIT_OPERATION = "approval.comment.edit.v1";
    private static final String DELETE_OPERATION = "approval.comment.delete.v1";
    private static final int MAX_BODY_LENGTH = 4000;
    private static final int MAX_REASON_LENGTH = 2000;
    private static final int MAX_MENTIONS = 50;
    private static final int MAX_ATTACHMENTS = 20;
    private static final Duration EDIT_WINDOW = Duration.ofMinutes(EDIT_WINDOW_MINUTES);

    private final IdempotencyGuard idempotencyGuard;
    private final ApprovalProjectionStore projections;
    private final ApprovalMessageStore messages;
    private final ApprovalCommentStore comments;
    private final ApprovalAttachmentStore attachments;
    private final AuditEventSink auditEvents;
    private final Clock clock;
    private final Supplier<UUID> identifierGenerator;

    public ApprovalCommentService(
        IdempotencyGuard idempotencyGuard,
        ApprovalProjectionStore projections,
        ApprovalMessageStore messages,
        ApprovalCommentStore comments,
        ApprovalAttachmentStore attachments,
        AuditEventSink auditEvents,
        Clock clock,
        Supplier<UUID> identifierGenerator
    ) {
        this.idempotencyGuard = Objects.requireNonNull(
            idempotencyGuard,
            "idempotencyGuard must not be null"
        );
        this.projections = Objects.requireNonNull(projections, "projections must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.comments = Objects.requireNonNull(comments, "comments must not be null");
        this.attachments = Objects.requireNonNull(attachments, "attachments must not be null");
        this.auditEvents = Objects.requireNonNull(auditEvents, "auditEvents must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.identifierGenerator = Objects.requireNonNull(
            identifierGenerator,
            "identifierGenerator must not be null"
        );
    }

    public CommentOptions findOptions(String tenantId, String operatorId, UUID instanceId) {
        InstanceProjection instance = findParticipantInstance(tenantId, operatorId, instanceId);
        List<CommentUserOption> candidates = participantOptions(instance).values().stream()
            .filter(option -> !option.userId().equals(operatorId))
            .sorted(Comparator.comparing(CommentUserOption::displayName)
                .thenComparing(CommentUserOption::userId))
            .toList();
        return new CommentOptions(
            instanceId,
            instance.status() != InstanceStatus.RUNNING,
            EDIT_WINDOW_MINUTES,
            candidates
        );
    }

    public CommentPage findComments(
        String tenantId,
        String operatorId,
        UUID instanceId,
        int limit,
        int offset
    ) {
        InstanceProjection instance = findParticipantInstance(tenantId, operatorId, instanceId);
        StoredCommentPage page = comments.findComments(new CommentCriteria(
            tenantId,
            instanceId,
            operatorId,
            limit,
            offset
        ));
        Instant now = clock.instant();
        List<CommentItem> items = page.items().stream()
            .map(item -> publicItem(instance, operatorId, item, now))
            .toList();
        return new CommentPage(
            items,
            page.total(),
            page.limit(),
            page.offset(),
            page.hasMore(),
            instance.status() != InstanceStatus.RUNNING
        );
    }

    public List<CommentRevisionItem> findRevisions(
        String tenantId,
        String operatorId,
        UUID instanceId,
        UUID commentId
    ) {
        InstanceProjection instance = findParticipantInstance(tenantId, operatorId, instanceId);
        StoredCommentItem comment = requireComment(tenantId, instanceId, commentId);
        if (!comment.authorId().equals(operatorId) && !instance.initiatorId().equals(operatorId)) {
            throw error(
                403,
                "APPROVAL_COMMENT_UNAUTHORIZED",
                "only the comment author or approval initiator may read revisions"
            );
        }
        Map<String, CommentUserOption> participants = participantOptions(instance);
        return comments.findRevisions(tenantId, instanceId, commentId).stream()
            .map(revision -> revisionItem(participants, revision))
            .toList();
    }

    public CommentItem comment(CommentCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String body = requireBody(command.body());
        List<String> mentions = normalizeMentions(command.mentionIds());
        List<UUID> attachmentIds = normalizeAttachments(command.attachmentIds());
        CommentVisibility visibility = command.visibility() == null
            ? CommentVisibility.PARTICIPANTS
            : command.visibility();
        String requestHash = hashValues(
            command.instanceId().toString(),
            command.parentCommentId() == null ? "" : command.parentCommentId().toString(),
            body,
            visibility.name(),
            String.join("\u001f", mentions),
            attachmentIds.stream().map(UUID::toString).collect(Collectors.joining("\u001f"))
        );
        return idempotencyGuard.execute(
            command.context(),
            CREATE_OPERATION,
            requestHash,
            CommentItem.class,
            () -> executeCreate(command, body, mentions, attachmentIds, visibility)
        );
    }

    public CommentItem edit(EditCommentCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String body = requireBody(command.body());
        List<String> mentions = normalizeMentions(command.mentionIds());
        List<UUID> attachmentIds = normalizeAttachments(command.attachmentIds());
        CommentVisibility visibility = Objects.requireNonNull(
            command.visibility(),
            "visibility must not be null"
        );
        String requestHash = hashValues(
            command.instanceId().toString(),
            command.commentId().toString(),
            Long.toString(command.expectedVersion()),
            body,
            visibility.name(),
            String.join("\u001f", mentions),
            attachmentIds.stream().map(UUID::toString).collect(Collectors.joining("\u001f")),
            normalizeOptional(command.reason()) == null ? "" : command.reason().trim()
        );
        return idempotencyGuard.execute(
            command.context(),
            EDIT_OPERATION,
            requestHash,
            CommentItem.class,
            () -> executeEdit(command, body, mentions, attachmentIds, visibility)
        );
    }

    public CommentItem delete(DeleteCommentCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String reason = requireReason(command.reason());
        String requestHash = hashValues(
            command.instanceId().toString(),
            command.commentId().toString(),
            Long.toString(command.expectedVersion()),
            reason
        );
        return idempotencyGuard.execute(
            command.context(),
            DELETE_OPERATION,
            requestHash,
            CommentItem.class,
            () -> executeDelete(command, reason)
        );
    }

    private CommentItem executeCreate(
        CommentCommand command,
        String body,
        List<String> requestedMentions,
        List<UUID> attachmentIds,
        CommentVisibility requestedVisibility
    ) {
        RequestContext context = command.context();
        InstanceProjection instance = findParticipantInstance(
            context.tenantId(),
            context.operatorId(),
            command.instanceId()
        );
        requireWritable(instance);
        StoredCommentItem parent = validateParent(
            context.tenantId(),
            instance.instanceId(),
            context.operatorId(),
            command.parentCommentId()
        );
        CommentAudience audience = createAudience(
            parent,
            context.operatorId(),
            requestedVisibility,
            requestedMentions
        );
        validateMentions(instance, audience.mentionIds());
        validateAttachments(
            context.tenantId(),
            context.operatorId(),
            instance.instanceId(),
            attachmentIds
        );

        Instant now = clock.instant();
        attachments.bindToInstance(
            context.tenantId(),
            context.operatorId(),
            instance.instanceId(),
            attachmentIds,
            now
        );
        UUID commentId = identifierGenerator.get();
        ApprovalComment comment = new ApprovalComment(
            commentId,
            context.tenantId(),
            instance.instanceId(),
            parent == null ? null : parent.commentId(),
            context.operatorId(),
            body,
            audience.mentionIds(),
            attachmentIds,
            CommentStatus.ACTIVE,
            audience.visibility(),
            1,
            now,
            now,
            null,
            null,
            null,
            1
        );
        StoredCommentItem stored = comments.create(comment, new CommentRevision(
            context.tenantId(),
            commentId,
            1,
            RevisionType.CREATE,
            body,
            audience.mentionIds(),
            attachmentIds,
            audience.visibility(),
            context.operatorId(),
            null,
            now
        ));
        appendMentionMessages(instance, stored, audience.mentionIds(), 1, now);
        appendAudit(
            context,
            instance,
            stored,
            "INSTANCE_COMMENT_CREATED",
            audience.mentionIds(),
            null,
            now
        );
        return publicItem(instance, context.operatorId(), stored, now);
    }

    private CommentItem executeEdit(
        EditCommentCommand command,
        String body,
        List<String> requestedMentions,
        List<UUID> attachmentIds,
        CommentVisibility requestedVisibility
    ) {
        RequestContext context = command.context();
        InstanceProjection instance = findParticipantInstance(
            context.tenantId(),
            context.operatorId(),
            command.instanceId()
        );
        requireWritable(instance);
        StoredCommentItem current = requireComment(
            context.tenantId(),
            instance.instanceId(),
            command.commentId()
        );
        requireMutable(current, context.operatorId(), command.expectedVersion());
        StoredCommentItem parent = validateParent(
            context.tenantId(),
            instance.instanceId(),
            context.operatorId(),
            current.parentCommentId()
        );
        CommentAudience audience = editAudience(
            current,
            parent,
            context.operatorId(),
            requestedVisibility,
            requestedMentions
        );
        validateMentions(instance, audience.mentionIds());
        validateAttachments(
            context.tenantId(),
            context.operatorId(),
            instance.instanceId(),
            attachmentIds
        );
        Instant now = clock.instant();
        attachments.bindToInstance(
            context.tenantId(),
            context.operatorId(),
            instance.instanceId(),
            attachmentIds,
            now
        );
        int revisionNumber = current.currentRevision() + 1;
        StoredCommentItem updated = comments.update(new CommentUpdate(
            context.tenantId(),
            instance.instanceId(),
            current.commentId(),
            body,
            audience.mentionIds(),
            attachmentIds,
            audience.visibility(),
            now,
            command.expectedVersion()
        ), new CommentRevision(
            context.tenantId(),
            current.commentId(),
            revisionNumber,
            RevisionType.EDIT,
            body,
            audience.mentionIds(),
            attachmentIds,
            audience.visibility(),
            context.operatorId(),
            normalizeReason(command.reason()),
            now
        ));
        List<String> newMentions = audience.mentionIds().stream()
            .filter(mention -> !current.mentionIds().contains(mention))
            .toList();
        appendMentionMessages(instance, updated, newMentions, revisionNumber, now);
        appendAudit(
            context,
            instance,
            updated,
            "INSTANCE_COMMENT_EDITED",
            newMentions,
            normalizeReason(command.reason()),
            now
        );
        return publicItem(instance, context.operatorId(), updated, now);
    }

    private CommentItem executeDelete(DeleteCommentCommand command, String reason) {
        RequestContext context = command.context();
        InstanceProjection instance = findParticipantInstance(
            context.tenantId(),
            context.operatorId(),
            command.instanceId()
        );
        requireWritable(instance);
        StoredCommentItem current = requireComment(
            context.tenantId(),
            instance.instanceId(),
            command.commentId()
        );
        requireMutable(current, context.operatorId(), command.expectedVersion());
        Instant now = clock.instant();
        int revisionNumber = current.currentRevision() + 1;
        StoredCommentItem deleted = comments.delete(new CommentDeletion(
            context.tenantId(),
            instance.instanceId(),
            current.commentId(),
            context.operatorId(),
            reason,
            TOMBSTONE_BODY,
            now,
            command.expectedVersion()
        ), new CommentRevision(
            context.tenantId(),
            current.commentId(),
            revisionNumber,
            RevisionType.DELETE,
            current.body(),
            current.mentionIds(),
            current.attachmentIds(),
            current.visibility(),
            context.operatorId(),
            reason,
            now
        ));
        appendAudit(
            context,
            instance,
            deleted,
            "INSTANCE_COMMENT_DELETED",
            List.of(),
            reason,
            now
        );
        return publicItem(instance, context.operatorId(), deleted, now);
    }

    private CommentAudience createAudience(
        StoredCommentItem parent,
        String operatorId,
        CommentVisibility requestedVisibility,
        List<String> requestedMentions
    ) {
        if (parent == null) {
            requirePrivateAudience(requestedVisibility, requestedMentions);
            return new CommentAudience(requestedVisibility, requestedMentions);
        }
        if (requestedVisibility != parent.visibility()) {
            throw error(
                409,
                "APPROVAL_COMMENT_PRIVATE_AUDIENCE_EXPANSION",
                "reply visibility must inherit the parent comment"
            );
        }
        if (parent.visibility() == CommentVisibility.MENTIONED_ONLY) {
            validatePrivateReplyAudience(parent, operatorId, requestedMentions);
        }
        return new CommentAudience(parent.visibility(), requestedMentions);
    }

    private CommentAudience editAudience(
        StoredCommentItem current,
        StoredCommentItem parent,
        String operatorId,
        CommentVisibility requestedVisibility,
        List<String> requestedMentions
    ) {
        if (current.visibility() == CommentVisibility.MENTIONED_ONLY
            && requestedVisibility == CommentVisibility.PARTICIPANTS) {
            throw error(
                409,
                "APPROVAL_COMMENT_PRIVATE_AUDIENCE_EXPANSION",
                "a private comment cannot be changed to participant visibility"
            );
        }
        if (parent != null && requestedVisibility != parent.visibility()) {
            throw error(
                409,
                "APPROVAL_COMMENT_PRIVATE_AUDIENCE_EXPANSION",
                "reply visibility must continue to inherit the parent comment"
            );
        }
        if (parent != null && parent.visibility() == CommentVisibility.MENTIONED_ONLY) {
            validatePrivateReplyAudience(parent, operatorId, requestedMentions);
        }
        requirePrivateAudience(requestedVisibility, requestedMentions);
        return new CommentAudience(requestedVisibility, requestedMentions);
    }

    private void validatePrivateReplyAudience(
        StoredCommentItem parent,
        String operatorId,
        List<String> requestedMentions
    ) {
        LinkedHashSet<String> allowed = new LinkedHashSet<>(parent.mentionIds());
        allowed.add(parent.authorId());
        List<String> expanded = requestedMentions.stream()
            .filter(mention -> !allowed.contains(mention))
            .toList();
        if (!expanded.isEmpty()) {
            throw error(
                409,
                "APPROVAL_COMMENT_PRIVATE_AUDIENCE_EXPANSION",
                "private reply cannot add recipients outside the parent audience"
            );
        }
        if (!parent.authorId().equals(operatorId) && !requestedMentions.contains(parent.authorId())) {
            throw error(
                409,
                "APPROVAL_COMMENT_PRIVATE_AUDIENCE_EXPANSION",
                "private reply must continue to mention the parent author"
            );
        }
        requirePrivateAudience(CommentVisibility.MENTIONED_ONLY, requestedMentions);
    }

    private static void requirePrivateAudience(
        CommentVisibility visibility,
        List<String> mentionIds
    ) {
        if (visibility == CommentVisibility.MENTIONED_ONLY && mentionIds.isEmpty()) {
            throw error(
                400,
                "APPROVAL_COMMENT_INVALID_MENTION",
                "private comments must mention at least one participant"
            );
        }
    }

    private StoredCommentItem validateParent(
        String tenantId,
        UUID instanceId,
        String operatorId,
        UUID parentCommentId
    ) {
        if (parentCommentId == null) {
            return null;
        }
        StoredCommentItem parent = requireComment(tenantId, instanceId, parentCommentId);
        if (!visibleTo(parent, operatorId)) {
            throw error(404, "APPROVAL_COMMENT_NOT_FOUND", "parent comment was not found");
        }
        if (parent.status() == CommentStatus.DELETED) {
            throw error(
                409,
                "APPROVAL_COMMENT_DELETED_STATE_CONFLICT",
                "deleted comments cannot receive replies"
            );
        }
        if (parent.parentCommentId() != null) {
            throw error(400, "INVALID_REQUEST", "comment replies are limited to one level");
        }
        return parent;
    }

    private void validateMentions(InstanceProjection instance, List<String> mentionIds) {
        Set<String> allowed = participantOptions(instance).keySet();
        List<String> invalid = mentionIds.stream()
            .filter(mention -> !allowed.contains(mention))
            .toList();
        if (!invalid.isEmpty()) {
            throw error(
                400,
                "APPROVAL_COMMENT_INVALID_MENTION",
                "mentioned users are not approval participants: " + String.join(",", invalid)
            );
        }
    }

    private void validateAttachments(
        String tenantId,
        String authorId,
        UUID instanceId,
        List<UUID> attachmentIds
    ) {
        for (UUID attachmentId : attachmentIds) {
            ApprovalAttachment attachment = attachments.find(tenantId, attachmentId).orElseThrow(() ->
                error(
                    409,
                    "APPROVAL_COMMENT_ATTACHMENT_OWNERSHIP_CONFLICT",
                    "comment attachment was not found for the tenant"
                )
            );
            if (!attachment.uploaderId().equals(authorId)) {
                throw error(
                    409,
                    "APPROVAL_COMMENT_ATTACHMENT_OWNERSHIP_CONFLICT",
                    "comment attachment must be uploaded by the comment author"
                );
            }
            if (attachment.instanceId() != null && !attachment.instanceId().equals(instanceId)) {
                throw error(
                    409,
                    "APPROVAL_COMMENT_ATTACHMENT_OWNERSHIP_CONFLICT",
                    "comment attachment belongs to another approval instance"
                );
            }
        }
    }

    private void requireWritable(InstanceProjection instance) {
        if (instance.status() != InstanceStatus.RUNNING) {
            throw error(
                409,
                "APPROVAL_COMMENT_INSTANCE_READ_ONLY",
                "comments are read-only after the approval instance is terminal"
            );
        }
    }

    private void requireMutable(
        StoredCommentItem comment,
        String operatorId,
        long expectedVersion
    ) {
        if (!comment.authorId().equals(operatorId)) {
            throw error(
                403,
                "APPROVAL_COMMENT_UNAUTHORIZED",
                "only the original author may edit or delete a comment"
            );
        }
        if (comment.status() == CommentStatus.DELETED) {
            throw error(
                409,
                "APPROVAL_COMMENT_DELETED_STATE_CONFLICT",
                "deleted comments cannot be changed"
            );
        }
        if (comment.version() != expectedVersion) {
            throw error(
                409,
                "APPROVAL_COMMENT_CONCURRENT_MODIFICATION",
                "approval comment changed concurrently"
            );
        }
        if (clock.instant().isAfter(comment.createdAt().plus(EDIT_WINDOW))) {
            throw error(
                409,
                "APPROVAL_COMMENT_EDIT_WINDOW_EXPIRED",
                "the 15 minute comment edit window has expired"
            );
        }
    }

    private InstanceProjection findParticipantInstance(
        String tenantId,
        String operatorId,
        UUID instanceId
    ) {
        InstanceProjection instance = projections.findInstance(tenantId, instanceId)
            .orElseThrow(() -> error(
                404,
                "APPROVAL_COMMENT_NOT_FOUND",
                "approval instance was not found"
            ));
        boolean taskParticipant = projections.findTasks(tenantId, instanceId).stream()
            .map(TaskProjection::assigneeId)
            .anyMatch(operatorId::equals);
        boolean messageRecipient = messages.isRecipient(tenantId, operatorId, instanceId);
        boolean additionalParticipant = comments.findAdditionalParticipants(tenantId, instanceId)
            .stream()
            .map(CommentParticipantIdentity::userId)
            .anyMatch(operatorId::equals);
        if (!instance.initiatorId().equals(operatorId)
            && !taskParticipant
            && !messageRecipient
            && !additionalParticipant) {
            throw error(
                403,
                "APPROVAL_COMMENT_UNAUTHORIZED",
                "operator is not a participant in the approval instance"
            );
        }
        return instance;
    }

    private Map<String, CommentUserOption> participantOptions(InstanceProjection instance) {
        LinkedHashMap<String, CommentUserOption> options = new LinkedHashMap<>();
        addSnapshotOption(options, instance, instance.initiatorId());
        AssigneeSnapshot snapshot = instance.assigneeSnapshot();
        addSnapshotOption(options, instance, snapshot.managerAssignee());
        addSnapshotOption(options, instance, snapshot.financeReviewer());
        snapshot.financeApprovers().forEach(userId -> addSnapshotOption(options, instance, userId));
        snapshot.identities().keySet().forEach(userId -> addSnapshotOption(options, instance, userId));
        projections.findTasks(instance.tenantId(), instance.instanceId()).stream()
            .map(TaskProjection::assigneeId)
            .forEach(userId -> addSnapshotOption(options, instance, userId));
        for (CommentParticipantIdentity identity : comments.findAdditionalParticipants(
            instance.tenantId(),
            instance.instanceId()
        )) {
            CommentUserOption existing = options.get(identity.userId());
            String displayName = existing == null
                ? identity.displayName()
                : existing.displayName();
            options.put(identity.userId(), new CommentUserOption(
                identity.userId(),
                displayName,
                identity.identitySource(),
                identity.objectType(),
                identity.externalIdentityValue()
            ));
        }
        return Map.copyOf(options);
    }

    private static void addSnapshotOption(
        Map<String, CommentUserOption> options,
        InstanceProjection instance,
        String userId
    ) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        UserIdentitySnapshot identity = instance.assigneeSnapshot().identities().get(userId);
        String displayName = identity == null || normalizeOptional(identity.displayName()) == null
            ? userId
            : identity.displayName().trim();
        String externalValue = identity == null || normalizeOptional(identity.externalId()) == null
            ? userId
            : identity.externalId().trim();
        options.putIfAbsent(userId, new CommentUserOption(
            userId,
            displayName,
            "APPROVAL_SNAPSHOT",
            "USER",
            externalValue
        ));
    }

    private CommentItem publicItem(
        InstanceProjection instance,
        String operatorId,
        StoredCommentItem item,
        Instant now
    ) {
        Map<String, CommentUserOption> participants = participantOptions(instance);
        List<CommentUserOption> mentionedUsers = item.mentionIds().stream()
            .map(userId -> option(participants, userId))
            .toList();
        List<AttachmentSummary> attachmentItems = attachments.findSummaries(
            instance.tenantId(),
            item.attachmentIds()
        );
        boolean mutable = instance.status() == InstanceStatus.RUNNING
            && item.status() == CommentStatus.ACTIVE
            && item.authorId().equals(operatorId)
            && !now.isAfter(item.createdAt().plus(EDIT_WINDOW));
        return new CommentItem(
            item.commentId(),
            item.instanceId(),
            item.parentCommentId(),
            item.parentAuthorId(),
            item.parentAuthorId() == null ? null : option(participants, item.parentAuthorId()).displayName(),
            item.authorId(),
            option(participants, item.authorId()).displayName(),
            item.body(),
            mentionedUsers,
            attachmentItems,
            item.status(),
            item.visibility(),
            item.currentRevision(),
            item.currentRevision() > 1,
            item.createdAt(),
            item.updatedAt(),
            item.deletedAt(),
            item.deletedBy(),
            item.deleteReason(),
            item.version(),
            mutable,
            mutable
        );
    }

    private static CommentRevisionItem revisionItem(
        Map<String, CommentUserOption> participants,
        StoredCommentRevision revision
    ) {
        return new CommentRevisionItem(
            revision.revisionNumber(),
            revision.revisionType(),
            revision.body(),
            revision.mentionIds().stream()
                .map(userId -> option(participants, userId))
                .toList(),
            revision.attachmentIds(),
            revision.visibility(),
            revision.operatorId(),
            revision.reason(),
            revision.occurredAt()
        );
    }

    private static CommentUserOption option(
        Map<String, CommentUserOption> participants,
        String userId
    ) {
        return participants.getOrDefault(userId, new CommentUserOption(
            userId,
            userId,
            "LEGACY_APPROVAL_EVIDENCE",
            "USER",
            userId
        ));
    }

    private void appendMentionMessages(
        InstanceProjection instance,
        StoredCommentItem comment,
        List<String> recipients,
        int revisionNumber,
        Instant now
    ) {
        if (recipients.isEmpty()) {
            return;
        }
        List<ApprovalMessage> mentionMessages = recipients.stream()
            .filter(recipient -> !recipient.equals(comment.authorId()))
            .map(recipient -> mentionMessage(
                instance,
                comment,
                recipient,
                revisionNumber,
                now
            ))
            .toList();
        int createdMessages = messages.append(mentionMessages);
        if (createdMessages != mentionMessages.size()) {
            throw error(
                409,
                "APPROVAL_COMMENT_CONCURRENT_MODIFICATION",
                "one or more comment mention messages already exist"
            );
        }
    }

    private ApprovalMessage mentionMessage(
        InstanceProjection instance,
        StoredCommentItem comment,
        String recipientId,
        int revisionNumber,
        Instant now
    ) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("businessKey", instance.businessKey());
        metadata.put("commentId", comment.commentId().toString());
        metadata.put("commentRevision", Integer.toString(revisionNumber));
        metadata.put("visibility", comment.visibility().name());
        if (comment.parentCommentId() != null) {
            metadata.put("parentCommentId", comment.parentCommentId().toString());
        }
        metadata.put("attachmentCount", Integer.toString(comment.attachmentIds().size()));
        return new ApprovalMessage(
            identifierGenerator.get(),
            instance.tenantId(),
            recipientId,
            comment.authorId(),
            instance.instanceId(),
            null,
            MessageType.MENTION,
            "审批评论提及 · " + instance.supplier(),
            mentionBody(comment.authorId(), comment.body()),
            Map.copyOf(metadata),
            "mention:" + comment.commentId() + ':' + revisionNumber + ':' + recipientId,
            now
        );
    }

    private static String mentionBody(String authorId, String body) {
        String excerpt = body.length() <= 160 ? body : body.substring(0, 157) + "...";
        return authorId + " 在审批评论中提到了你：" + excerpt;
    }

    private void appendAudit(
        RequestContext context,
        InstanceProjection instance,
        StoredCommentItem comment,
        String action,
        List<String> notificationMentions,
        String reason,
        Instant now
    ) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("businessKey", instance.businessKey());
        attributes.put("commentId", comment.commentId().toString());
        attributes.put("commentRevision", Integer.toString(comment.currentRevision()));
        attributes.put("commentStatus", comment.status().name());
        attributes.put("visibility", comment.visibility().name());
        attributes.put("commentBody", comment.body());
        attributes.put("mentions", String.join(",", notificationMentions));
        attributes.put("allMentions", String.join(",", comment.mentionIds()));
        attributes.put(
            "attachmentIds",
            comment.attachmentIds().stream().map(UUID::toString).collect(Collectors.joining(","))
        );
        attributes.put("attachmentCount", Integer.toString(comment.attachmentIds().size()));
        attributes.put("version", Long.toString(comment.version()));
        if (comment.parentCommentId() != null) {
            attributes.put("parentCommentId", comment.parentCommentId().toString());
        }
        if (reason != null) {
            attributes.put("reason", reason);
        }
        auditEvents.append(new AuditEvent(
            identifierGenerator.get(),
            context.tenantId(),
            context.operatorId(),
            action,
            "APPROVAL_INSTANCE",
            instance.instanceId().toString(),
            context.requestId(),
            context.traceId(),
            now,
            Map.copyOf(attributes)
        ));
    }

    private StoredCommentItem requireComment(String tenantId, UUID instanceId, UUID commentId) {
        return comments.findComment(tenantId, instanceId, commentId).orElseThrow(() ->
            error(404, "APPROVAL_COMMENT_NOT_FOUND", "approval comment was not found")
        );
    }

    private static boolean visibleTo(StoredCommentItem comment, String operatorId) {
        return comment.visibility() == CommentVisibility.PARTICIPANTS
            || comment.authorId().equals(operatorId)
            || comment.mentionIds().contains(operatorId);
    }

    private static String requireBody(String value) {
        String body = requireText(value, "comment body");
        if (body.length() > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("comment body must not exceed 4000 characters");
        }
        return body;
    }

    private static String requireReason(String value) {
        String reason = requireText(value, "delete reason");
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("delete reason must not exceed 2000 characters");
        }
        return reason;
    }

    private static String normalizeReason(String value) {
        String reason = normalizeOptional(value);
        if (reason != null && reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("revision reason must not exceed 2000 characters");
        }
        return reason;
    }

    private static List<String> normalizeMentions(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> mentions = values.stream()
            .map(value -> requireText(value, "mentionId"))
            .distinct()
            .sorted()
            .toList();
        if (mentions.size() > MAX_MENTIONS) {
            throw new IllegalArgumentException("mentions must not exceed 50 users");
        }
        return mentions;
    }

    private static List<UUID> normalizeAttachments(List<UUID> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<UUID> attachmentIds = new ArrayList<>(new LinkedHashSet<>(values));
        if (attachmentIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("attachmentIds must not contain null");
        }
        if (attachmentIds.size() > MAX_ATTACHMENTS) {
            throw new IllegalArgumentException("comment attachments must not exceed 20 files");
        }
        return List.copyOf(attachmentIds);
    }

    private static String hashValues(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                String normalized = value == null ? "" : value;
                byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static CommentOperationException error(int status, String code, String message) {
        return new CommentOperationException(status, code, message);
    }

    private record CommentAudience(
        CommentVisibility visibility,
        List<String> mentionIds
    ) {
        private CommentAudience {
            visibility = Objects.requireNonNull(visibility, "visibility must not be null");
            mentionIds = mentionIds == null ? List.of() : List.copyOf(mentionIds);
        }
    }

    public record CommentUserOption(
        String userId,
        String displayName,
        String identitySource,
        String objectType,
        String externalIdentityValue
    ) {
        public CommentUserOption {
            userId = requireText(userId, "userId");
            displayName = requireText(displayName, "displayName");
            identitySource = requireText(identitySource, "identitySource");
            objectType = requireText(objectType, "objectType");
            externalIdentityValue = requireText(externalIdentityValue, "externalIdentityValue");
        }
    }

    public record CommentOptions(
        UUID instanceId,
        boolean readOnly,
        int editWindowMinutes,
        List<CommentUserOption> mentionCandidates
    ) {
        public CommentOptions {
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            mentionCandidates = mentionCandidates == null ? List.of() : List.copyOf(mentionCandidates);
        }
    }

    public record CommentItem(
        UUID commentId,
        UUID instanceId,
        UUID parentCommentId,
        String replyToAuthorId,
        String replyToAuthorDisplayName,
        String authorId,
        String authorDisplayName,
        String body,
        List<CommentUserOption> mentionedUsers,
        List<AttachmentSummary> attachments,
        CommentStatus status,
        CommentVisibility visibility,
        int currentRevision,
        boolean edited,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt,
        String deletedBy,
        String deleteReason,
        long version,
        boolean canEdit,
        boolean canDelete
    ) {
        public CommentItem {
            mentionedUsers = mentionedUsers == null ? List.of() : List.copyOf(mentionedUsers);
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        public boolean reply() {
            return parentCommentId != null;
        }

        public boolean deleted() {
            return status == CommentStatus.DELETED;
        }

        public boolean privateComment() {
            return visibility == CommentVisibility.MENTIONED_ONLY;
        }
    }

    public record CommentPage(
        List<CommentItem> items,
        long total,
        int limit,
        int offset,
        boolean hasMore,
        boolean readOnly
    ) {
        public CommentPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record CommentRevisionItem(
        int revisionNumber,
        RevisionType revisionType,
        String body,
        List<CommentUserOption> mentionedUsers,
        List<UUID> attachmentIds,
        CommentVisibility visibility,
        String operatorId,
        String reason,
        Instant occurredAt
    ) {
        public CommentRevisionItem {
            mentionedUsers = mentionedUsers == null ? List.of() : List.copyOf(mentionedUsers);
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
        }
    }

    public record CommentCommand(
        RequestContext context,
        UUID instanceId,
        UUID parentCommentId,
        String body,
        List<String> mentionIds,
        List<UUID> attachmentIds,
        CommentVisibility visibility
    ) {
        public CommentCommand(
            RequestContext context,
            UUID instanceId,
            UUID parentCommentId,
            String body,
            List<String> mentionIds,
            List<UUID> attachmentIds
        ) {
            this(
                context,
                instanceId,
                parentCommentId,
                body,
                mentionIds,
                attachmentIds,
                CommentVisibility.PARTICIPANTS
            );
        }

        public CommentCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            mentionIds = mentionIds == null ? List.of() : List.copyOf(mentionIds);
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
        }
    }

    public record EditCommentCommand(
        RequestContext context,
        UUID instanceId,
        UUID commentId,
        String body,
        List<String> mentionIds,
        List<UUID> attachmentIds,
        CommentVisibility visibility,
        long expectedVersion,
        String reason
    ) {
        public EditCommentCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            commentId = Objects.requireNonNull(commentId, "commentId must not be null");
            mentionIds = mentionIds == null ? List.of() : List.copyOf(mentionIds);
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
            visibility = Objects.requireNonNull(visibility, "visibility must not be null");
            if (expectedVersion < 1) {
                throw new IllegalArgumentException("expectedVersion must be positive");
            }
        }
    }

    public record DeleteCommentCommand(
        RequestContext context,
        UUID instanceId,
        UUID commentId,
        long expectedVersion,
        String reason
    ) {
        public DeleteCommentCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            commentId = Objects.requireNonNull(commentId, "commentId must not be null");
            if (expectedVersion < 1) {
                throw new IllegalArgumentException("expectedVersion must be positive");
            }
        }
    }

    public static final class CommentOperationException extends RuntimeException {
        private final int status;
        private final String code;

        public CommentOperationException(int status, String code, String message) {
            super(requireText(message, "message"));
            if (status < 400 || status > 499) {
                throw new IllegalArgumentException("comment error status must be a 4xx status");
            }
            this.status = status;
            this.code = requireText(code, "code");
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }
}
