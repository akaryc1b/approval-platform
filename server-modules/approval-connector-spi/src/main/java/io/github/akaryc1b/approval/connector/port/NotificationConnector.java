package io.github.akaryc1b.approval.connector.port;

import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.model.ExternalId;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public interface NotificationConnector {

    NotificationReceipt send(ConnectorContext context, NotificationMessage message);

    record NotificationMessage(
        String messageType,
        String templateCode,
        List<ExternalId> recipients,
        String title,
        String body,
        URIAction action,
        Map<String, String> variables,
        String idempotencyKey
    ) {
        public NotificationMessage {
            messageType = requireText(messageType, "messageType");
            templateCode = normalize(templateCode);
            recipients = recipients == null ? List.of() : List.copyOf(recipients);
            if (recipients.isEmpty()) {
                throw new IllegalArgumentException("recipients must not be empty");
            }
            title = requireText(title, "title");
            body = requireText(body, "body");
            variables = variables == null ? Map.of() : Map.copyOf(variables);
            idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        }
    }

    record URIAction(String label, String url) {
        public URIAction {
            label = requireText(label, "label");
            url = requireText(url, "url");
        }
    }

    record NotificationReceipt(
        String providerMessageId,
        DeliveryStatus status,
        Instant acceptedAt,
        Map<String, String> attributes
    ) {
        public NotificationReceipt {
            providerMessageId = normalize(providerMessageId);
            status = Objects.requireNonNull(status, "status must not be null");
            acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    enum DeliveryStatus {
        ACCEPTED,
        DELIVERED,
        REJECTED,
        UNKNOWN
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
