package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalMessageStore;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.ApprovalMessage;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.MessageIdentity;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.MessagePage;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.MessageReadResult;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.MessageReceipt;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.MessageType;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Idempotent urge, copy, message-center and read-receipt use cases.
 */
public final class ApprovalMessageService {

    private static final String URGE_OPERATION = "purchase-payment.urge.v1";
    private static final String COPY_OPERATION = "purchase-payment.copy.v1";
    private static final long URGE_WINDOW_SECONDS = 600;

    private final IdempotencyGuard idempotencyGuard;
    private final ApprovalProjectionStore projections;
    private final ApprovalMessageStore messages;
    private final AuditEventSink auditEvents;
    private final Clock clock;
    private final Supplier<UUID> identifierGenerator;

    public ApprovalMessageService(
        IdempotencyGuard idempotencyGuard,
        ApprovalProjectionStore projections,
        ApprovalMessageStore messages,
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
        this.auditEvents = Objects.requireNonNull(auditEvents, "auditEvents must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.identifierGenerator = Objects.requireNonNull(
            identifierGenerator,
            "identifierGenerator must not be null"
        );
    }

    public CollaborationOptions findOptions(String tenantId, String operatorId, UUID instanceId) {
        InstanceProjection instance = findCollaborationInstance(tenantId, operatorId, instanceId);
        List<TaskProjection> tasks = projections.findTasks(tenantId, instanceId);
        List<UserOption> candidates = userOptions(instance, operatorId);
        List<UserOption> activeAssignees = tasks.stream()
            .filter(task -> task.status() == TaskStatus.PENDING)
            .map(TaskProjection::assigneeId)
            .distinct()
            .map(userId -> option(instance, userId))
            .sorted(Comparator.comparing(UserOption::displayName).thenComparing(UserOption::userId))
            .toList();
        boolean canUrge = instance.status() == InstanceStatus.RUNNING
            && activeAssignees.stream().anyMatch(option -> !option.userId().equals(operatorId));
        return new CollaborationOptions(instanceId, candidates, activeAssignees, canUrge);
    }

    public MessageActionResult urge(UrgeCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String comment = normalizeOptional(command.comment());
        String requestHash = hashValues(command.instanceId().toString(), comment);
        return idempotencyGuard.execute(
            command.context(),
            URGE_OPERATION,
            requestHash,
            MessageActionResult.class,
            () -> executeUrge(command, comment)
        );
    }

    public MessageActionResult copy(CopyCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        List<String> recipients = normalizeRecipients(command.recipientIds());
        String comment = normalizeOptional(command.comment());
        List<String> hashInputs = new ArrayList<>();
        hashInputs.add(command.instanceId().toString());
        hashInputs.add(comment);
        hashInputs.addAll(recipients);
        String requestHash = hashValues(hashInputs.toArray(String[]::new));
        return idempotencyGuard.execute(
            command.context(),
            COPY_OPERATION,
            requestHash,
            MessageActionResult.class,
            () -> executeCopy(command, recipients, comment)
        );
    }

    public MessagePage findMessages(
        String tenantId,
        String recipientId,
        boolean unreadOnly,
        int limit,
        int offset
    ) {
        return messages.findMessages(new ApprovalMessageStore.MessageCriteria(
            tenantId,
            recipientId,
            unreadOnly,
            limit,
            offset
        ));
    }

    public UnreadCount unreadCount(String tenantId, String recipientId) {
        return new UnreadCount(messages.countUnread(new MessageIdentity(tenantId, recipientId)));
    }

    public Optional<ReadResult> markRead(
        String tenantId,
        String recipientId,
        UUID messageId
    ) {
        Instant now = clock.instant();
        Optional<MessageReadResult> result = messages.markRead(
            tenantId,
            recipientId,
            messageId,
            now
        );
        result.filter(MessageReadResult::firstRead).ifPresent(read -> auditEvents.append(new AuditEvent(
            identifierGenerator.get(),
            tenantId,
            recipientId,
            "MESSAGE_READ",
            "APPROVAL_MESSAGE",
            read.messageId().toString(),
            "message-read-" + read.messageId(),
            null,
            now,
            Map.of(
                "instanceId", read.instanceId().toString(),
                "messageType", read.messageType().name(),
                "senderId", read.senderId()
            )
        )));
        return result.map(read -> new ReadResult(read.messageId(), read.firstRead(), read.readAt()));
    }

    public ReadAllResult markAllRead(String tenantId, String recipientId) {
        Instant now = clock.instant();
        int updated = messages.markAllRead(tenantId, recipientId, now);
        if (updated > 0) {
            auditEvents.append(new AuditEvent(
                identifierGenerator.get(),
                tenantId,
                recipientId,
                "MESSAGES_READ_ALL",
                "APPROVAL_MESSAGE_CENTER",
                recipientId,
                "messages-read-all-" + now.toEpochMilli(),
                null,
                now,
                Map.of("count", Integer.toString(updated))
            ));
        }
        return new ReadAllResult(updated, now);
    }

    public List<MessageReceipt> findReceipts(String tenantId, String operatorId, UUID instanceId) {
        InstanceProjection instance = projections.findInstance(tenantId, instanceId)
            .orElseThrow(() -> new ApprovalProjectionStore.ProjectionConflictException(
                "approval instance was not found"
            ));
        if (!instance.initiatorId().equals(operatorId)) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "only the process initiator can view message receipts"
            );
        }
        return messages.findReceipts(tenantId, instanceId);
    }

    private MessageActionResult executeUrge(UrgeCommand command, String comment) {
        RequestContext context = command.context();
        InstanceProjection instance = findCollaborationInstance(
            context.tenantId(),
            context.operatorId(),
            command.instanceId()
        );
        if (instance.status() != InstanceStatus.RUNNING) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "only a running approval can be urged"
            );
        }
        List<TaskProjection> pending = projections.findTasks(
            context.tenantId(),
            command.instanceId()
        ).stream().filter(task -> task.status() == TaskStatus.PENDING).toList();
        Map<String, TaskProjection> recipientTasks = pending.stream()
            .filter(task -> !task.assigneeId().equals(context.operatorId()))
            .collect(Collectors.toMap(
                TaskProjection::assigneeId,
                task -> task,
                (left, right) -> left,
                LinkedHashMap::new
            ));
        if (recipientTasks.isEmpty()) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "there is no other pending assignee to urge"
            );
        }
        Instant now = clock.instant();
        long window = now.getEpochSecond() / URGE_WINDOW_SECONDS;
        List<ApprovalMessage> outbound = recipientTasks.entrySet().stream().map(entry -> {
            TaskProjection task = entry.getValue();
            String body = comment == null
                ? context.operatorId() + " 催办了该审批，请及时处理。"
                : comment;
            return new ApprovalMessage(
                identifierGenerator.get(),
                context.tenantId(),
                entry.getKey(),
                context.operatorId(),
                instance.instanceId(),
                task.taskId(),
                MessageType.URGE,
                "审批催办 · " + instance.supplier(),
                body,
                Map.of(
                    "businessKey", instance.businessKey(),
                    "taskDefinitionKey", task.taskDefinitionKey()
                ),
                "urge:" + instance.instanceId() + ':' + context.operatorId() + ':' + entry.getKey()
                    + ':' + window,
                now
            );
        }).toList();
        int created = messages.append(outbound);
        if (created == 0) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "the current assignees were already urged within the last 10 minutes"
            );
        }
        List<String> recipients = outbound.stream().map(ApprovalMessage::recipientId).toList();
        appendInstanceAudit(context, instance, "INSTANCE_URGED", recipients, comment, created, now);
        return new MessageActionResult(instance.instanceId(), created, recipients, now);
    }

    private MessageActionResult executeCopy(
        CopyCommand command,
        List<String> recipients,
        String comment
    ) {
        RequestContext context = command.context();
        InstanceProjection instance = findCollaborationInstance(
            context.tenantId(),
            context.operatorId(),
            command.instanceId()
        );
        Set<String> allowed = userOptions(instance, context.operatorId()).stream()
            .map(UserOption::userId)
            .collect(Collectors.toSet());
        List<String> invalid = recipients.stream().filter(recipient -> !allowed.contains(recipient)).toList();
        if (!invalid.isEmpty()) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "copy recipients are not present in the immutable approval identity snapshot: "
                    + String.join(",", invalid)
            );
        }
        Instant now = clock.instant();
        List<ApprovalMessage> outbound = recipients.stream().map(recipient -> new ApprovalMessage(
            identifierGenerator.get(),
            context.tenantId(),
            recipient,
            context.operatorId(),
            instance.instanceId(),
            null,
            MessageType.COPY,
            "审批抄送 · " + instance.supplier(),
            comment == null ? "你被抄送了该采购付款审批。" : comment,
            Map.of("businessKey", instance.businessKey()),
            "copy:" + instance.instanceId() + ':' + recipient,
            now
        )).toList();
        int created = messages.append(outbound);
        if (created == 0) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "all selected users have already been copied on this approval"
            );
        }
        appendInstanceAudit(context, instance, "INSTANCE_COPIED", recipients, comment, created, now);
        return new MessageActionResult(instance.instanceId(), created, recipients, now);
    }

    private InstanceProjection findCollaborationInstance(
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
        if (!instance.initiatorId().equals(operatorId) && !taskParticipant) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "operator is not allowed to collaborate on the approval instance"
            );
        }
        return instance;
    }

    private List<UserOption> userOptions(InstanceProjection instance, String operatorId) {
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
            .sorted(Comparator.comparing(UserOption::displayName).thenComparing(UserOption::userId))
            .toList();
    }

    private static UserOption option(InstanceProjection instance, String userId) {
        UserIdentitySnapshot identity = instance.assigneeSnapshot().identities().get(userId);
        if (identity == null) {
            return new UserOption(userId, userId);
        }
        String displayName = normalizeOptional(identity.displayName());
        return new UserOption(userId, displayName == null ? userId : displayName);
    }

    private void appendInstanceAudit(
        RequestContext context,
        InstanceProjection instance,
        String action,
        List<String> recipients,
        String comment,
        int created,
        Instant now
    ) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("recipients", String.join(",", recipients));
        attributes.put("messageCount", Integer.toString(created));
        attributes.put("businessKey", instance.businessKey());
        if (comment != null) {
            attributes.put("comment", comment);
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

    private static List<String> normalizeRecipients(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("at least one copy recipient is required");
        }
        LinkedHashSet<String> normalized = values.stream()
            .map(value -> requireText(value, "recipientId"))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (normalized.size() > 50) {
            throw new IllegalArgumentException("copy recipients must not exceed 50 users");
        }
        return List.copyOf(normalized);
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

    public record UserOption(String userId, String displayName) {
    }

    public record CollaborationOptions(
        UUID instanceId,
        List<UserOption> copyCandidates,
        List<UserOption> activeAssignees,
        boolean canUrge
    ) {
        public CollaborationOptions {
            copyCandidates = copyCandidates == null ? List.of() : List.copyOf(copyCandidates);
            activeAssignees = activeAssignees == null ? List.of() : List.copyOf(activeAssignees);
        }
    }

    public record MessageActionResult(
        UUID instanceId,
        int createdMessages,
        List<String> recipients,
        Instant createdAt
    ) {
        public MessageActionResult {
            recipients = recipients == null ? List.of() : List.copyOf(recipients);
        }
    }

    public record UnreadCount(long unread) {
    }

    public record ReadResult(UUID messageId, boolean firstRead, Instant readAt) {
    }

    public record ReadAllResult(int updatedMessages, Instant readAt) {
    }

    public record UrgeCommand(RequestContext context, UUID instanceId, String comment) {
        public UrgeCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
        }
    }

    public record CopyCommand(
        RequestContext context,
        UUID instanceId,
        List<String> recipientIds,
        String comment
    ) {
        public CopyCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            recipientIds = recipientIds == null ? List.of() : List.copyOf(recipientIds);
        }
    }
}
