package io.github.akaryc1b.approval.application.port;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable user notification preferences, intents and delivery evidence. */
public interface ApprovalNotificationStore {

    Optional<PreferenceBundle> findPreferences(String tenantId, String userId);

    PreferenceBundle savePreferences(PreferenceBundle bundle, long expectedVersion);

    int enqueue(List<NotificationIntent> intents);

    List<NotificationIntent> claimDue(
        Instant now,
        int limit,
        String workerId,
        Instant lockedUntil
    );

    NotificationIntent markDelivered(
        String tenantId,
        UUID intentId,
        long expectedVersion,
        UUID attemptId,
        Instant startedAt,
        Instant deliveredAt,
        String providerMessageId
    );

    NotificationIntent markFailed(
        String tenantId,
        UUID intentId,
        long expectedVersion,
        UUID attemptId,
        Instant startedAt,
        Instant completedAt,
        boolean retryable,
        Instant nextAttemptAt,
        String errorCode,
        String errorMessage
    );

    NotificationHistoryPage findHistory(NotificationHistoryCriteria criteria);

    long countUnread(String tenantId, String recipientId);

    Optional<NotificationIntent> markRead(
        String tenantId,
        String recipientId,
        UUID intentId,
        Instant readAt
    );

    int markAllRead(String tenantId, String recipientId, Instant readAt);

    NotificationIntent replayDeadLetter(
        String tenantId,
        String recipientId,
        UUID intentId,
        Instant nextAttemptAt
    );

    List<DeliveryAttempt> findAttempts(String tenantId, String recipientId, UUID intentId);

    record PreferenceBundle(
        String tenantId,
        String userId,
        String timezone,
        boolean quietHoursEnabled,
        LocalTime quietHoursStart,
        LocalTime quietHoursEnd,
        boolean emergencyBypass,
        boolean digestEnabled,
        long version,
        Instant createdAt,
        Instant updatedAt,
        List<NotificationPreference> preferences
    ) {
        public PreferenceBundle {
            tenantId = requireText(tenantId, "tenantId");
            userId = requireText(userId, "userId");
            timezone = requireText(timezone, "timezone");
            preferences = preferences == null ? List.of() : List.copyOf(preferences);
            if (quietHoursEnabled && (quietHoursStart == null || quietHoursEnd == null)) {
                throw new IllegalArgumentException("quiet hours require start and end times");
            }
            if (!quietHoursEnabled && (quietHoursStart != null || quietHoursEnd != null)) {
                throw new IllegalArgumentException("disabled quiet hours must not store times");
            }
            if (version < 0) {
                throw new IllegalArgumentException("version must not be negative");
            }
            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        }
    }

    record NotificationPreference(
        NotificationEventType eventType,
        NotificationChannel channel,
        boolean enabled
    ) {
        public NotificationPreference {
            eventType = Objects.requireNonNull(eventType, "eventType must not be null");
            channel = Objects.requireNonNull(channel, "channel must not be null");
        }
    }

    record NotificationIntent(
        UUID intentId,
        String tenantId,
        NotificationEventType eventType,
        NotificationChannel channel,
        String recipientId,
        String senderId,
        UUID instanceId,
        UUID taskId,
        String aggregateType,
        String aggregateId,
        String templateKey,
        int templateVersion,
        String title,
        String body,
        Map<String, String> metadata,
        String businessEventKey,
        boolean urgent,
        NotificationStatus status,
        int attemptCount,
        int maxAttempts,
        Instant nextAttemptAt,
        Instant deliveredAt,
        Instant readAt,
        String lastErrorCode,
        String lastErrorMessage,
        String lockedBy,
        Instant lockedUntil,
        Instant createdAt,
        Instant updatedAt,
        long version
    ) {
        public NotificationIntent {
            intentId = Objects.requireNonNull(intentId, "intentId must not be null");
            tenantId = requireText(tenantId, "tenantId");
            eventType = Objects.requireNonNull(eventType, "eventType must not be null");
            channel = Objects.requireNonNull(channel, "channel must not be null");
            recipientId = requireText(recipientId, "recipientId");
            senderId = requireText(senderId, "senderId");
            aggregateType = requireText(aggregateType, "aggregateType");
            aggregateId = requireText(aggregateId, "aggregateId");
            templateKey = requireText(templateKey, "templateKey");
            if (templateVersion < 1) {
                throw new IllegalArgumentException("templateVersion must be positive");
            }
            title = requireText(title, "title");
            body = requireText(body, "body");
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            businessEventKey = requireText(businessEventKey, "businessEventKey");
            status = Objects.requireNonNull(status, "status must not be null");
            if (attemptCount < 0 || maxAttempts < 1 || attemptCount > maxAttempts) {
                throw new IllegalArgumentException("notification attempt values are invalid");
            }
            nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null");
            lastErrorCode = normalizeOptional(lastErrorCode);
            lastErrorMessage = normalizeOptional(lastErrorMessage);
            lockedBy = normalizeOptional(lockedBy);
            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
            if (version < 1) {
                throw new IllegalArgumentException("version must be positive");
            }
        }

        public boolean unread() {
            return readAt == null;
        }
    }

    record DeliveryAttempt(
        UUID attemptId,
        UUID intentId,
        String tenantId,
        int attemptNumber,
        Instant startedAt,
        Instant completedAt,
        boolean successful,
        boolean retryable,
        String providerMessageId,
        String errorCode,
        String errorMessage
    ) {
        public DeliveryAttempt {
            attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
            intentId = Objects.requireNonNull(intentId, "intentId must not be null");
            tenantId = requireText(tenantId, "tenantId");
            if (attemptNumber < 1) {
                throw new IllegalArgumentException("attemptNumber must be positive");
            }
            startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
            completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
            providerMessageId = normalizeOptional(providerMessageId);
            errorCode = normalizeOptional(errorCode);
            errorMessage = normalizeOptional(errorMessage);
        }
    }

    record NotificationHistoryCriteria(
        String tenantId,
        String recipientId,
        boolean unreadOnly,
        int limit,
        int offset
    ) {
        public NotificationHistoryCriteria {
            tenantId = requireText(tenantId, "tenantId");
            recipientId = requireText(recipientId, "recipientId");
            validatePage(limit, offset);
        }
    }

    record NotificationHistoryPage(
        List<NotificationIntent> items,
        long total,
        int limit,
        int offset,
        boolean hasMore
    ) {
        public NotificationHistoryPage(List<NotificationIntent> items, long total, int limit, int offset) {
            this(items, total, limit, offset, offset + (items == null ? 0 : items.size()) < total);
        }

        public NotificationHistoryPage {
            items = items == null ? List.of() : List.copyOf(items);
            if (total < 0) {
                throw new IllegalArgumentException("total must not be negative");
            }
            validatePage(limit, offset);
        }
    }

    enum NotificationChannel {
        IN_APP,
        CONNECTOR,
        EMAIL
    }

    enum NotificationEventType {
        TASK_ASSIGNED,
        AUTOMATIC_DELEGATION,
        EMPLOYEE_HANDOVER,
        TASK_COLLABORATION_ASSIGNED,
        TASK_COLLABORATION_RESULT,
        APPROVAL_COMPLETED,
        APPROVAL_REJECTED,
        COMMENT_MENTION
    }

    enum NotificationStatus {
        PENDING,
        PROCESSING,
        RETRY,
        DELIVERED,
        DEAD_LETTER
    }

    final class NotificationConflictException extends RuntimeException {
        public NotificationConflictException(String message) {
            super(message);
        }
    }

    final class NotificationNotFoundException extends RuntimeException {
        public NotificationNotFoundException(String message) {
            super(message);
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
