package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalCommentStore;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.ApprovalComment;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.StoredCommentItem;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.StoredCommentPage;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.ApprovalMessage;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.MessageType;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.UserIdentitySnapshot;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
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

/**
 * Immutable approval comments with participant authorization and @mention messages.
 */
public final class ApprovalCommentService {

    private static final String COMMENT_OPERATION = "purchase-payment.comment.v1";
    private static final int MAX_BODY_LENGTH = 4000;
    private static final int MAX_MENTIONS = 50;
    private static final int MAX_ATTACHMENTS = 20;
    private static final int MAX_REFERENCE_LENGTH = 512;

    private final IdempotencyGuard idempotencyGuard;
    private final ApprovalProjectionStore projections;
    private final ApprovalMessageStore messages;
    private final ApprovalCommentStore comments;
    private final AuditEventSink auditEvents;
    private final Clock clock;
    private final Supplier<UUID> identifierGenerator;

    public ApprovalCommentService(
        IdempotencyGuard idempotencyGuard,
        ApprovalProjectionStore projections,
        ApprovalMessageStore messages,
        ApprovalCommentStore comments,
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
        this.auditEvents = Objects.requireNonNull(auditEvents, "auditEvents must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.identifierGenerator = Objects.requireNonNull(
            identifierGenerator,
            "identifierGenerator must not be null"
        );
    }

    public CommentOptions findOptions(String tenantId, String operatorId, UUID instanceId) {
        InstanceProjection instance = findParticipantInstance(tenantId, operatorId, instanceId);
        return new CommentOptions(instanceId, userOptions(instance, operatorId));
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
            limit,
            offset
        ));
        List<CommentItem> items = page.items().stream()
            .map(item -> publicItem(instance, item))
            .toList();
        return new CommentPage(items, page.total(), page.limit(), page.offset(), page.hasMore());
    }

    public CommentItem comment(CommentCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String body = requireBody(command.body());
        List<String> mentions = normalizeMentions(command.mentionIds());
        List<String> attachments = normalizeAttachments(command.attachmentIds());
        List<String> hashInputs = new ArrayList<>();
        hashInputs.add(command.instanceId().toString());
        hashInputs.add(body);
        hashInputs.addAll(mentions);
        hashInputs.add("--attachments--");
        hashInputs.addAll(attachments);
        String requestHash = hashValues(hashInputs.toArray(String[]::new));
        return idempotencyGuard.execute(
            command.context(),
            COMMENT_OPERATION,
            requestHash,
            CommentItem.class,
            () -> executeComment(command, body, mentions, attachments)
        );
    }

    private CommentItem executeComment(
        CommentCommand command,
        String body,
        List<String> mentionIds,
        List<String> attachmentIds
    ) {
        RequestContext context = command.context();
        InstanceProjection instance = findParticipantInstance(
            context.tenantId(),
            context.operatorId(),
            command.instanceId()
        );
        Set<String> allowedMentions = userOptions(instance, context.operatorId()).stream()
            .map(CommentUserOption::userId)
            .collect(Collectors.toSet());
        List<String> invalidMentions = mentionIds.stream()
            .filter(mention -> !allowedMentions.contains(mention))
            .toList();
        if (!invalidMentions.isEmpty()) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "mentioned users are not present in the immutable approval identity snapshot: "
                    + String.join(",", invalidMentions)
            );
        }

        Instant now = clock.instant();
        UUID commentId = identifierGenerator.get();
        ApprovalComment comment = new ApprovalComment(
            commentId,
            context.tenantId(),
            instance.instanceId(),
            context.operatorId(),
            body,
            mentionIds,
            attachmentIds,
            now
        );
        comments.append(comment);

        List<ApprovalMessage> mentionMessages = mentionIds.stream()
            .map(recipient -> mentionMessage(instance, comment, recipient, now))
            .toList();
        int createdMessages = messages.append(mentionMessages);
        if (createdMessages != mentionMessages.size()) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "one or more mention messages could not be created"
            );
        }
        appendAudit(context, instance, comment, now);
        return publicItem(instance, new StoredCommentItem(
            comment.commentId(),
            comment.instanceId(),
            comment.authorId(),
            comment.body(),
            comment.mentionIds(),
            comment.attachmentIds(),
            comment.createdAt()
        ));
    }

    private ApprovalMessage mentionMessage(
        InstanceProjection instance,
        ApprovalComment comment,
        String recipientId,
        Instant now
    ) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("businessKey", instance.businessKey());
        metadata.put("commentId", comment.commentId().toString());
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
            "mention:" + comment.commentId() + ':' + recipientId,
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
        ApprovalComment comment,
        Instant now
    ) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("businessKey", instance.businessKey());
        attributes.put("commentId", comment.commentId().toString());
        attributes.put("commentBody", comment.body());
        attributes.put("mentions", String.join(",", comment.mentionIds()));
        attributes.put("attachmentIds", String.join(",", comment.attachmentIds()));
        attributes.put("attachmentCount", Integer.toString(comment.attachmentIds().size()));
        auditEvents.append(new AuditEvent(
            identifierGenerator.get(),
            context.tenantId(),
            context.operatorId(),
            "INSTANCE_COMMENTED",
            "APPROVAL_INSTANCE",
            instance.instanceId().toString(),
            context.requestId(),
            context.traceId(),
            now,
            Map.copyOf(attributes)
        ));
    }

    private InstanceProjection findParticipantInstance(
        String tenantId,
        String operatorId,
        UUID instanceId
    ) {
        InstanceProjection instance = projections.findInstance(tenantId, instanceId)
            .orElseThrow(() -> new ApprovalProjectionStore.ProjectionConflictException(
                "approval instance was not found"
            ));
        boolean taskParticipant = projections.findTasks(tenantId, instanceId).stream()
            .anyMatch(task -> task.assigneeId().equals(operatorId));
        boolean messageRecipient = messages.isRecipient(tenantId, operatorId, instanceId);
        if (!instance.initiatorId().equals(operatorId) && !taskParticipant && !messageRecipient) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "operator is not a participant in the approval instance"
            );
        }
        return instance;
    }

    private CommentItem publicItem(InstanceProjection instance, StoredCommentItem item) {
        List<CommentUserOption> mentionedUsers = item.mentionIds().stream()
            .map(userId -> option(instance, userId))
            .toList();
        return new CommentItem(
            item.commentId(),
            item.instanceId(),
            item.authorId(),
            option(instance, item.authorId()).displayName(),
            item.body(),
            mentionedUsers,
            item.attachmentIds(),
            item.createdAt()
        );
    }

    private List<CommentUserOption> userOptions(InstanceProjection instance, String operatorId) {
        AssigneeSnapshot snapshot = instance.assigneeSnapshot();
        LinkedHashSet<String> identifiers = new LinkedHashSet<>();
        identifiers.add(instance.initiatorId());
        identifiers.add(snapshot.managerAssignee());
        identifiers.add(snapshot.financeReviewer());
        identifiers.addAll(snapshot.financeApprovers());
        identifiers.addAll(snapshot.identities().keySet());
        return identifiers.stream()
            .filter(Objects::nonNull)
            .filter(value -> !value.isBlank())
            .filter(value -> !value.equals(operatorId))
            .map(value -> option(instance, value))
            .sorted(Comparator.comparing(CommentUserOption::displayName)
                .thenComparing(CommentUserOption::userId))
            .toList();
    }

    private static CommentUserOption option(InstanceProjection instance, String userId) {
        UserIdentitySnapshot identity = instance.assigneeSnapshot().identities().get(userId);
        if (identity == null) {
            return new CommentUserOption(userId, userId);
        }
        String displayName = normalizeOptional(identity.displayName());
        return new CommentUserOption(userId, displayName == null ? userId : displayName);
    }

    private static String requireBody(String value) {
        String body = requireText(value, "comment body");
        if (body.length() > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("comment body must not exceed 4000 characters");
        }
        return body;
    }

    private static List<String> normalizeMentions(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> mentions = values.stream()
            .map(value -> requireReference(value, "mentionId"))
            .distinct()
            .sorted()
            .toList();
        if (mentions.size() > MAX_MENTIONS) {
            throw new IllegalArgumentException("mentions must not exceed 50 users");
        }
        return mentions;
    }

    private static List<String> normalizeAttachments(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> attachments = new ArrayList<>(new LinkedHashSet<>(values.stream()
            .map(value -> requireReference(value, "attachmentId"))
            .toList()));
        if (attachments.size() > MAX_ATTACHMENTS) {
            throw new IllegalArgumentException("comment attachments must not exceed 20 references");
        }
        return List.copyOf(attachments);
    }

    private static String requireReference(String value, String name) {
        String reference = requireText(value, name);
        if (reference.length() > MAX_REFERENCE_LENGTH) {
            throw new IllegalArgumentException(name + " must not exceed 512 characters");
        }
        return reference;
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

    public record CommentUserOption(String userId, String displayName) {
    }

    public record CommentOptions(UUID instanceId, List<CommentUserOption> mentionCandidates) {
        public CommentOptions {
            mentionCandidates = mentionCandidates == null ? List.of() : List.copyOf(mentionCandidates);
        }
    }

    public record CommentItem(
        UUID commentId,
        UUID instanceId,
        String authorId,
        String authorDisplayName,
        String body,
        List<CommentUserOption> mentionedUsers,
        List<String> attachmentIds,
        Instant createdAt
    ) {
        public CommentItem {
            mentionedUsers = mentionedUsers == null ? List.of() : List.copyOf(mentionedUsers);
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
        }
    }

    public record CommentPage(
        List<CommentItem> items,
        long total,
        int limit,
        int offset,
        boolean hasMore
    ) {
        public CommentPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record CommentCommand(
        RequestContext context,
        UUID instanceId,
        String body,
        List<String> mentionIds,
        List<String> attachmentIds
    ) {
        public CommentCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            mentionIds = mentionIds == null ? List.of() : List.copyOf(mentionIds);
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
        }
    }
}
