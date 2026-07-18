package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Platform-owned user message and read-receipt store.
 */
public interface ApprovalMessageStore {

    int append(List<ApprovalMessage> messages);

    MessagePage findMessages(MessageCriteria criteria);

    long countUnread(MessageIdentity identity);

    Optional<MessageReadResult> markRead(
        String tenantId,
        String recipientId,
        UUID messageId,
        Instant readAt
    );

    int markAllRead(String tenantId, String recipientId, Instant readAt);

    List<MessageReceipt> findReceipts(String tenantId, UUID instanceId);

    boolean isRecipient(String tenantId, String recipientId, UUID instanceId);

    record ApprovalMessage(
        UUID messageId,
        String tenantId,
        String recipientId,
        String senderId,
        UUID instanceId,
        UUID taskId,
        MessageType messageType,
        String title,
        String body,
        Map<String, String> metadata,
        String dedupKey,
        Instant createdAt
    ) {
        public ApprovalMessage {
            messageId = Objects.requireNonNull(messageId, "messageId must not be null");
            tenantId = requireText(tenantId, "tenantId");
            recipientId = requireText(recipientId, "recipientId");
            senderId = requireText(senderId, "senderId");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            messageType = Objects.requireNonNull(messageType, "messageType must not be null");
            title = requireText(title, "title");
            body = requireText(body, "body");
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            dedupKey = requireText(dedupKey, "dedupKey");
            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        }
    }

    record MessageCriteria(
        String tenantId,
        String recipientId,
        boolean unreadOnly,
        int limit,
        int offset
    ) {
        public MessageCriteria {
            tenantId = requireText(tenantId, "tenantId");
            recipientId = requireText(recipientId, "recipientId");
            validatePage(limit, offset);
        }
    }

    record MessageIdentity(String tenantId, String recipientId) {
        public MessageIdentity {
            tenantId = requireText(tenantId, "tenantId");
            recipientId = requireText(recipientId, "recipientId");
        }
    }

    record MessageItem(
        UUID messageId,
        MessageType messageType,
        UUID instanceId,
        UUID taskId,
        String senderId,
        String title,
        String body,
        Map<String, String> metadata,
        boolean read,
        Instant readAt,
        Instant createdAt,
        String businessKey,
        BigDecimal amount,
        String supplier,
        String purchaseOrderReference,
        InstanceStatus instanceStatus
    ) {
        public MessageItem {
            messageId = Objects.requireNonNull(messageId, "messageId must not be null");
            messageType = Objects.requireNonNull(messageType, "messageType must not be null");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
            instanceStatus = Objects.requireNonNull(instanceStatus, "instanceStatus must not be null");
        }
    }

    record MessagePage(
        List<MessageItem> items,
        long total,
        int limit,
        int offset,
        boolean hasMore
    ) {
        public MessagePage(List<MessageItem> items, long total, int limit, int offset) {
            this(items, total, limit, offset, offset + (items == null ? 0 : items.size()) < total);
        }

        public MessagePage {
            items = items == null ? List.of() : List.copyOf(items);
            if (total < 0) {
                throw new IllegalArgumentException("total must not be negative");
            }
            validatePage(limit, offset);
        }
    }

    record MessageReadResult(
        UUID messageId,
        UUID instanceId,
        MessageType messageType,
        String senderId,
        boolean firstRead,
        Instant readAt
    ) {
    }

    record MessageReceipt(
        UUID messageId,
        MessageType messageType,
        String recipientId,
        String senderId,
        Instant sentAt,
        Instant readAt
    ) {
        public boolean read() {
            return readAt != null;
        }
    }

    enum MessageType {
        URGE,
        COPY
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
