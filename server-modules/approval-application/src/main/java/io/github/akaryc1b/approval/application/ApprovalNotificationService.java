package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalConnectorNotificationSender;
import io.github.akaryc1b.approval.application.port.ApprovalEmailNotificationSender;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.DeliveryAttempt;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationChannel;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationEventType;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationHistoryCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationHistoryPage;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationIntent;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationPreference;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationStatus;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.PreferenceBundle;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Notification preferences, intent generation, reliable delivery and history use cases. */
public final class ApprovalNotificationService {

    private static final int MAX_ATTEMPTS = 5;
    private static final Duration PROCESSING_LEASE = Duration.ofMinutes(5);
    private static final String SYSTEM_SENDER = "approval-platform";

    private final ApprovalNotificationStore notifications;
    private final ApprovalProjectionStore projections;
    private final ApprovalConnectorNotificationSender connectorSender;
    private final ApprovalEmailNotificationSender emailSender;
    private final Clock clock;
    private final Supplier<UUID> identifierGenerator;

    public ApprovalNotificationService(
        ApprovalNotificationStore notifications,
        ApprovalProjectionStore projections,
        ApprovalConnectorNotificationSender connectorSender,
        ApprovalEmailNotificationSender emailSender,
        Clock clock,
        Supplier<UUID> identifierGenerator
    ) {
        this.notifications = Objects.requireNonNull(
            notifications,
            "notifications must not be null"
        );
        this.projections = Objects.requireNonNull(projections, "projections must not be null");
        this.connectorSender = Objects.requireNonNull(
            connectorSender,
            "connectorSender must not be null"
        );
        this.emailSender = Objects.requireNonNull(emailSender, "emailSender must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.identifierGenerator = Objects.requireNonNull(
            identifierGenerator,
            "identifierGenerator must not be null"
        );
    }

    public PreferenceBundle findPreferences(String tenantId, String userId) {
        String tenant = requireText(tenantId, "tenantId");
        String user = requireText(userId, "userId");
        return notifications.findPreferences(tenant, user)
            .map(this::normalizeBundle)
            .orElseGet(() -> defaultBundle(tenant, user, clock.instant()));
    }

    public PreferenceBundle updatePreferences(UpdatePreferencesCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        PreferenceBundle current = findPreferences(command.tenantId(), command.userId());
        if (current.version() != command.expectedVersion()) {
            throw new ApprovalNotificationStore.NotificationConflictException(
                "notification preferences changed concurrently"
            );
        }
        ZoneId.of(command.timezone());
        if (command.quietHoursEnabled()
            && Objects.equals(command.quietHoursStart(), command.quietHoursEnd())) {
            throw new IllegalArgumentException("quiet hours start and end must be different");
        }
        Instant now = clock.instant();
        PreferenceBundle requested = new PreferenceBundle(
            command.tenantId(),
            command.userId(),
            command.timezone(),
            command.quietHoursEnabled(),
            command.quietHoursEnabled() ? command.quietHoursStart() : null,
            command.quietHoursEnabled() ? command.quietHoursEnd() : null,
            command.emergencyBypass(),
            command.digestEnabled(),
            current.version(),
            current.createdAt(),
            now,
            normalizePreferences(command.preferences())
        );
        return notifications.savePreferences(requested, command.expectedVersion());
    }

    public int enqueueFromAudit(AuditEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        List<NotificationSeed> seeds = seeds(event);
        if (seeds.isEmpty()) {
            return 0;
        }
        List<NotificationIntent> intents = new ArrayList<>();
        for (NotificationSeed seed : seeds) {
            PreferenceBundle bundle = findPreferences(event.tenantId(), seed.recipientId());
            for (NotificationPreference preference : bundle.preferences()) {
                if (preference.eventType() != seed.eventType() || !preference.enabled()) {
                    continue;
                }
                Instant scheduledAt = schedule(bundle, seed.urgent(), event.occurredAt());
                intents.add(intent(event, seed, preference.channel(), scheduledAt));
            }
        }
        return notifications.enqueue(intents);
    }

    public int processDue(int limit, String workerId) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        String worker = requireText(workerId, "workerId");
        Instant now = clock.instant();
        List<NotificationIntent> due = notifications.claimDue(
            now,
            limit,
            worker,
            now.plus(PROCESSING_LEASE)
        );
        for (NotificationIntent intent : due) {
            deliver(intent);
        }
        return due.size();
    }

    public NotificationHistoryPage findHistory(
        String tenantId,
        String recipientId,
        boolean unreadOnly,
        int limit,
        int offset
    ) {
        return notifications.findHistory(new NotificationHistoryCriteria(
            tenantId,
            recipientId,
            unreadOnly,
            limit,
            offset
        ));
    }

    public UnreadCount unreadCount(String tenantId, String recipientId) {
        return new UnreadCount(notifications.countUnread(tenantId, recipientId));
    }

    public Optional<NotificationIntent> markRead(
        String tenantId,
        String recipientId,
        UUID intentId
    ) {
        return notifications.markRead(tenantId, recipientId, intentId, clock.instant());
    }

    public ReadAllResult markAllRead(String tenantId, String recipientId) {
        Instant now = clock.instant();
        return new ReadAllResult(notifications.markAllRead(tenantId, recipientId, now), now);
    }

    public NotificationIntent replay(String tenantId, String recipientId, UUID intentId) {
        return notifications.replayDeadLetter(
            tenantId,
            recipientId,
            intentId,
            clock.instant()
        );
    }

    public List<DeliveryAttempt> findAttempts(
        String tenantId,
        String recipientId,
        UUID intentId
    ) {
        return notifications.findAttempts(tenantId, recipientId, intentId);
    }

    private void deliver(NotificationIntent intent) {
        Instant startedAt = clock.instant();
        try {
            DeliveryOutcome outcome = switch (intent.channel()) {
                case IN_APP -> DeliveryOutcome.delivered("in-app:" + intent.intentId());
                case CONNECTOR -> connectorOutcome(connectorSender.deliver(intent));
                case EMAIL -> emailOutcome(emailSender.deliver(intent));
            };
            if (outcome.successful()) {
                notifications.markDelivered(
                    intent.tenantId(),
                    intent.intentId(),
                    intent.version(),
                    identifierGenerator.get(),
                    startedAt,
                    clock.instant(),
                    outcome.providerMessageId()
                );
                return;
            }
            fail(intent, startedAt, outcome.retryable(), outcome.errorCode(), outcome.errorMessage());
        } catch (RuntimeException exception) {
            fail(
                intent,
                startedAt,
                true,
                "NOTIFICATION_DELIVERY_EXCEPTION",
                safeMessage(exception)
            );
        }
    }

    private void fail(
        NotificationIntent intent,
        Instant startedAt,
        boolean providerRetryable,
        String errorCode,
        String errorMessage
    ) {
        int nextAttempt = intent.attemptCount() + 1;
        boolean retryable = providerRetryable && nextAttempt < intent.maxAttempts();
        Instant completedAt = clock.instant();
        Instant nextAttemptAt = retryable
            ? completedAt.plusSeconds(backoffSeconds(nextAttempt))
            : completedAt;
        notifications.markFailed(
            intent.tenantId(),
            intent.intentId(),
            intent.version(),
            identifierGenerator.get(),
            startedAt,
            completedAt,
            retryable,
            nextAttemptAt,
            normalizeError(errorCode, "NOTIFICATION_DELIVERY_FAILED"),
            normalizeError(errorMessage, "notification delivery failed")
        );
    }

    private List<NotificationSeed> seeds(AuditEvent event) {
        return switch (event.action()) {
            case "INSTANCE_STARTED" -> assignmentSeeds(event, parseUuid(event.aggregateId()));
            case "TASK_APPROVED", "TASK_RESUBMITTED" -> transitionSeeds(event, false);
            case "TASK_REJECTED" -> transitionSeeds(event, true);
            case "TASK_DELEGATED" -> singleAttributeSeed(
                event,
                "delegateAssigneeId",
                NotificationEventType.AUTOMATIC_DELEGATION,
                "审批任务已自动代理",
                "你收到了一项自动代理的审批任务。",
                true
            );
            case "TASK_HANDOVER_ASSIGNED" -> singleAttributeSeed(
                event,
                "successorAssigneeId",
                NotificationEventType.EMPLOYEE_HANDOVER,
                "审批任务已完成离职交接",
                "你收到了一项正式离职交接的审批任务。",
                true
            );
            case "TASK_COLLABORATION_CREATED" -> collaborationSeeds(event, false);
            case "TASK_COLLABORATOR_ADDED" -> singleAttributeSeed(
                event,
                "participantUserId",
                NotificationEventType.TASK_COLLABORATION_ASSIGNED,
                "新的加签协作待办",
                "你被追加为审批协作参与人。",
                true
            );
            case "TASK_COLLABORATION_THRESHOLD_REACHED" -> collaborationResultSeeds(
                event,
                "加签协作已满足",
                "协作策略已达到通过阈值。"
            );
            case "TASK_COLLABORATION_THRESHOLD_IMPOSSIBLE" -> collaborationResultSeeds(
                event,
                "加签协作未通过",
                "协作策略已无法达到通过阈值。"
            );
            case "INSTANCE_COMMENTED" -> mentionSeeds(event);
            default -> List.of();
        };
    }

    private List<NotificationSeed> transitionSeeds(AuditEvent event, boolean rejected) {
        UUID taskId = parseUuid(event.aggregateId());
        if (taskId == null) {
            return List.of();
        }
        Optional<TaskProjection> task = projections.findTask(event.tenantId(), taskId);
        if (task.isEmpty()) {
            return List.of();
        }
        Optional<InstanceProjection> instance = projections.findInstance(
            event.tenantId(),
            task.get().instanceId()
        );
        if (instance.isEmpty()) {
            return List.of();
        }
        List<NotificationSeed> result = new ArrayList<>();
        if (rejected || instance.get().status() == InstanceStatus.REJECTED) {
            result.add(instanceResultSeed(
                event,
                instance.get(),
                NotificationEventType.APPROVAL_REJECTED,
                "审批已驳回",
                "你发起的审批已被驳回。",
                true
            ));
        } else if (instance.get().status() == InstanceStatus.COMPLETED) {
            result.add(instanceResultSeed(
                event,
                instance.get(),
                NotificationEventType.APPROVAL_COMPLETED,
                "审批已完成",
                "你发起的审批已完成。",
                false
            ));
        }
        result.addAll(assignmentSeeds(event, instance.get().instanceId()));
        return List.copyOf(result);
    }

    private List<NotificationSeed> assignmentSeeds(AuditEvent event, UUID instanceId) {
        if (instanceId == null) {
            return List.of();
        }
        Optional<InstanceProjection> instance = projections.findInstance(event.tenantId(), instanceId);
        if (instance.isEmpty()) {
            return List.of();
        }
        List<NotificationSeed> result = new ArrayList<>();
        for (TaskProjection task : projections.findTasks(event.tenantId(), instanceId)) {
            if (task.status() != TaskStatus.PENDING) {
                continue;
            }
            Map<String, String> metadata = instanceMetadata(instance.get());
            metadata.put("taskId", task.taskId().toString());
            metadata.put("taskName", task.name());
            metadata.put("taskDefinitionKey", task.taskDefinitionKey());
            result.add(new NotificationSeed(
                NotificationEventType.TASK_ASSIGNED,
                task.assigneeId(),
                event.operatorId(),
                instanceId,
                task.taskId(),
                "新的审批待办",
                "你有一项新的审批任务：" + task.name(),
                Map.copyOf(metadata),
                "TASK_ASSIGNED:" + task.taskId() + ':' + task.assigneeId(),
                true
            ));
        }
        return List.copyOf(result);
    }

    private NotificationSeed instanceResultSeed(
        AuditEvent event,
        InstanceProjection instance,
        NotificationEventType eventType,
        String title,
        String body,
        boolean urgent
    ) {
        return new NotificationSeed(
            eventType,
            instance.initiatorId(),
            event.operatorId(),
            instance.instanceId(),
            null,
            title,
            body,
            Map.copyOf(instanceMetadata(instance)),
            eventType.name() + ':' + instance.instanceId(),
            urgent
        );
    }

    private List<NotificationSeed> collaborationSeeds(AuditEvent event, boolean result) {
        Set<String> participants = parseParticipantWeights(event.attributes().get("participantWeights"));
        if (participants.isEmpty()) {
            return List.of();
        }
        UUID taskId = parseUuid(event.aggregateId());
        UUID instanceId = taskId == null
            ? null
            : projections.findTask(event.tenantId(), taskId).map(TaskProjection::instanceId).orElse(null);
        List<NotificationSeed> seeds = new ArrayList<>();
        for (String participant : participants) {
            seeds.add(new NotificationSeed(
                result
                    ? NotificationEventType.TASK_COLLABORATION_RESULT
                    : NotificationEventType.TASK_COLLABORATION_ASSIGNED,
                participant,
                event.operatorId(),
                instanceId,
                taskId,
                result ? "加签协作结果" : "新的加签协作待办",
                result ? "加签协作状态已更新。" : "你被设置为审批协作参与人。",
                event.attributes(),
                event.action() + ':' + event.attributes().getOrDefault("policyId", event.eventId().toString())
                    + ':' + participant,
                !result
            ));
        }
        return List.copyOf(seeds);
    }

    private List<NotificationSeed> collaborationResultSeeds(
        AuditEvent event,
        String title,
        String body
    ) {
        LinkedHashSet<String> recipients = new LinkedHashSet<>(parseParticipantWeights(
            event.attributes().get("participantWeights")
        ));
        addOptional(recipients, event.attributes().get("ownerAssigneeId"));
        if (recipients.isEmpty()) {
            return List.of();
        }
        UUID taskId = parseUuid(event.aggregateId());
        UUID instanceId = taskId == null
            ? null
            : projections.findTask(event.tenantId(), taskId).map(TaskProjection::instanceId).orElse(null);
        List<NotificationSeed> result = new ArrayList<>();
        for (String recipient : recipients) {
            result.add(new NotificationSeed(
                NotificationEventType.TASK_COLLABORATION_RESULT,
                recipient,
                event.operatorId(),
                instanceId,
                taskId,
                title,
                body,
                event.attributes(),
                event.action() + ':' + event.attributes().getOrDefault("policyId", event.eventId().toString())
                    + ':' + recipient,
                false
            ));
        }
        return List.copyOf(result);
    }

    private List<NotificationSeed> mentionSeeds(AuditEvent event) {
        String mentions = event.attributes().get("mentions");
        if (mentions == null || mentions.isBlank()) {
            return List.of();
        }
        UUID instanceId = parseUuid(event.aggregateId());
        String commentId = event.attributes().getOrDefault("commentId", event.eventId().toString());
        List<NotificationSeed> result = new ArrayList<>();
        for (String mention : mentions.split(",")) {
            String recipient = mention.trim();
            if (recipient.isEmpty()) {
                continue;
            }
            result.add(new NotificationSeed(
                NotificationEventType.COMMENT_MENTION,
                recipient,
                event.operatorId(),
                instanceId,
                null,
                "审批评论中有人提到了你",
                "请查看审批评论中的最新协作内容。",
                event.attributes(),
                "COMMENT_MENTION:" + commentId + ':' + recipient,
                false
            ));
        }
        return List.copyOf(result);
    }

    private List<NotificationSeed> singleAttributeSeed(
        AuditEvent event,
        String recipientAttribute,
        NotificationEventType eventType,
        String title,
        String body,
        boolean urgent
    ) {
        String recipient = normalizeOptional(event.attributes().get(recipientAttribute));
        if (recipient == null) {
            return List.of();
        }
        UUID taskId = parseUuid(event.aggregateId());
        UUID instanceId = taskId == null
            ? null
            : projections.findTask(event.tenantId(), taskId).map(TaskProjection::instanceId).orElse(null);
        return List.of(new NotificationSeed(
            eventType,
            recipient,
            event.operatorId(),
            instanceId,
            taskId,
            title,
            body,
            event.attributes(),
            eventType.name() + ':' + event.aggregateId() + ':' + recipient,
            urgent
        ));
    }

    private NotificationIntent intent(
        AuditEvent event,
        NotificationSeed seed,
        NotificationChannel channel,
        Instant scheduledAt
    ) {
        Map<String, String> metadata = new LinkedHashMap<>(seed.metadata());
        metadata.put("eventType", seed.eventType().name());
        metadata.put("channel", channel.name());
        if (seed.instanceId() != null) {
            metadata.put("instanceId", seed.instanceId().toString());
            metadata.putIfAbsent("route", "/approval/discussion/detail?instanceId=" + seed.instanceId());
        }
        if (seed.taskId() != null) {
            metadata.put("taskId", seed.taskId().toString());
        }
        Instant now = clock.instant();
        return new NotificationIntent(
            identifierGenerator.get(),
            event.tenantId(),
            seed.eventType(),
            channel,
            seed.recipientId(),
            normalizeOptional(seed.senderId()) == null ? SYSTEM_SENDER : seed.senderId(),
            seed.instanceId(),
            seed.taskId(),
            event.aggregateType(),
            event.aggregateId(),
            seed.eventType().name().toLowerCase() + ".v1",
            1,
            seed.title(),
            seed.body(),
            Map.copyOf(metadata),
            seed.businessEventKey(),
            seed.urgent(),
            NotificationStatus.PENDING,
            0,
            MAX_ATTEMPTS,
            scheduledAt,
            null,
            null,
            null,
            null,
            null,
            null,
            now,
            now,
            1
        );
    }

    private Instant schedule(PreferenceBundle bundle, boolean urgent, Instant occurredAt) {
        if (!bundle.quietHoursEnabled() || (urgent && bundle.emergencyBypass())) {
            return occurredAt;
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(bundle.timezone());
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("notification timezone is invalid", exception);
        }
        ZonedDateTime local = occurredAt.atZone(zone);
        LocalTime time = local.toLocalTime();
        LocalTime start = bundle.quietHoursStart();
        LocalTime end = bundle.quietHoursEnd();
        boolean overnight = start.isAfter(end);
        boolean quiet = overnight
            ? !time.isBefore(start) || time.isBefore(end)
            : !time.isBefore(start) && time.isBefore(end);
        if (!quiet) {
            return occurredAt;
        }
        ZonedDateTime resume;
        if (overnight && !time.isBefore(start)) {
            resume = local.toLocalDate().plusDays(1).atTime(end).atZone(zone);
        } else {
            resume = local.toLocalDate().atTime(end).atZone(zone);
        }
        return resume.toInstant();
    }

    private PreferenceBundle normalizeBundle(PreferenceBundle stored) {
        return new PreferenceBundle(
            stored.tenantId(),
            stored.userId(),
            stored.timezone(),
            stored.quietHoursEnabled(),
            stored.quietHoursStart(),
            stored.quietHoursEnd(),
            stored.emergencyBypass(),
            stored.digestEnabled(),
            stored.version(),
            stored.createdAt(),
            stored.updatedAt(),
            mergeDefaults(stored.preferences())
        );
    }

    private PreferenceBundle defaultBundle(String tenantId, String userId, Instant now) {
        return new PreferenceBundle(
            tenantId,
            userId,
            "UTC",
            false,
            null,
            null,
            true,
            false,
            0,
            now,
            now,
            mergeDefaults(List.of())
        );
    }

    private List<NotificationPreference> normalizePreferences(
        List<NotificationPreference> preferences
    ) {
        if (preferences == null) {
            return mergeDefaults(List.of());
        }
        Map<String, NotificationPreference> distinct = new LinkedHashMap<>();
        for (NotificationPreference preference : preferences) {
            Objects.requireNonNull(preference, "notification preference must not be null");
            String key = preference.eventType().name() + ':' + preference.channel().name();
            if (distinct.putIfAbsent(key, preference) != null) {
                throw new IllegalArgumentException("notification preferences must be unique");
            }
        }
        return mergeDefaults(List.copyOf(distinct.values()));
    }

    private List<NotificationPreference> mergeDefaults(List<NotificationPreference> stored) {
        Map<NotificationEventType, EnumMap<NotificationChannel, Boolean>> values = new EnumMap<>(
            NotificationEventType.class
        );
        for (NotificationEventType eventType : NotificationEventType.values()) {
            EnumMap<NotificationChannel, Boolean> channels = new EnumMap<>(NotificationChannel.class);
            channels.put(NotificationChannel.IN_APP, true);
            channels.put(NotificationChannel.CONNECTOR, false);
            channels.put(NotificationChannel.EMAIL, false);
            values.put(eventType, channels);
        }
        for (NotificationPreference preference : stored) {
            values.get(preference.eventType()).put(preference.channel(), preference.enabled());
        }
        List<NotificationPreference> result = new ArrayList<>();
        for (NotificationEventType eventType : NotificationEventType.values()) {
            for (NotificationChannel channel : NotificationChannel.values()) {
                result.add(new NotificationPreference(
                    eventType,
                    channel,
                    values.get(eventType).get(channel)
                ));
            }
        }
        return List.copyOf(result);
    }

    private static DeliveryOutcome connectorOutcome(
        ApprovalConnectorNotificationSender.DeliveryResult result
    ) {
        return new DeliveryOutcome(
            result.successful(),
            result.retryable(),
            result.providerMessageId(),
            result.errorCode(),
            result.errorMessage()
        );
    }

    private static DeliveryOutcome emailOutcome(ApprovalEmailNotificationSender.DeliveryResult result) {
        return new DeliveryOutcome(
            result.successful(),
            result.retryable(),
            result.providerMessageId(),
            result.errorCode(),
            result.errorMessage()
        );
    }

    private static Map<String, String> instanceMetadata(InstanceProjection instance) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("businessKey", instance.businessKey());
        metadata.put("definitionKey", instance.definitionKey());
        metadata.put("supplier", instance.supplier());
        metadata.put("purchaseOrderReference", instance.purchaseOrderReference());
        metadata.put("instanceStatus", instance.status().name());
        String connectorKey = instance.assigneeSnapshot().attributes().get("connectorKey");
        if (connectorKey != null && !connectorKey.isBlank()) {
            metadata.put("connectorKey", connectorKey);
        }
        return metadata;
    }

    private static Set<String> parseParticipantWeights(String value) {
        LinkedHashSet<String> users = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return users;
        }
        for (String item : value.split(",")) {
            int separator = item.lastIndexOf(':');
            String user = separator < 0 ? item : item.substring(0, separator);
            addOptional(users, user);
        }
        return users;
    }

    private static void addOptional(Set<String> values, String value) {
        String normalized = normalizeOptional(value);
        if (normalized != null) {
            values.add(normalized);
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static long backoffSeconds(int attemptNumber) {
        long seconds = 60L * (1L << Math.min(attemptNumber - 1, 6));
        return Math.min(seconds, 3600L);
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
            ? exception.getClass().getSimpleName()
            : message;
    }

    private static String normalizeError(String value, String fallback) {
        String normalized = normalizeOptional(value);
        return normalized == null ? fallback : normalized;
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

    public record UpdatePreferencesCommand(
        String tenantId,
        String userId,
        String timezone,
        boolean quietHoursEnabled,
        LocalTime quietHoursStart,
        LocalTime quietHoursEnd,
        boolean emergencyBypass,
        boolean digestEnabled,
        long expectedVersion,
        List<NotificationPreference> preferences
    ) {
        public UpdatePreferencesCommand {
            tenantId = requireText(tenantId, "tenantId");
            userId = requireText(userId, "userId");
            timezone = requireText(timezone, "timezone");
            preferences = preferences == null ? List.of() : List.copyOf(preferences);
            if (expectedVersion < 0) {
                throw new IllegalArgumentException("expectedVersion must not be negative");
            }
        }
    }

    public record UnreadCount(long unread) {
        public UnreadCount {
            if (unread < 0) {
                throw new IllegalArgumentException("unread must not be negative");
            }
        }
    }

    public record ReadAllResult(int updatedNotifications, Instant readAt) {
        public ReadAllResult {
            if (updatedNotifications < 0) {
                throw new IllegalArgumentException("updatedNotifications must not be negative");
            }
            readAt = Objects.requireNonNull(readAt, "readAt must not be null");
        }
    }

    private record NotificationSeed(
        NotificationEventType eventType,
        String recipientId,
        String senderId,
        UUID instanceId,
        UUID taskId,
        String title,
        String body,
        Map<String, String> metadata,
        String businessEventKey,
        boolean urgent
    ) {
        private NotificationSeed {
            eventType = Objects.requireNonNull(eventType, "eventType must not be null");
            recipientId = requireText(recipientId, "recipientId");
            title = requireText(title, "title");
            body = requireText(body, "body");
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            businessEventKey = requireText(businessEventKey, "businessEventKey");
        }
    }

    private record DeliveryOutcome(
        boolean successful,
        boolean retryable,
        String providerMessageId,
        String errorCode,
        String errorMessage
    ) {
        private static DeliveryOutcome delivered(String providerMessageId) {
            return new DeliveryOutcome(true, false, providerMessageId, null, null);
        }
    }
}
