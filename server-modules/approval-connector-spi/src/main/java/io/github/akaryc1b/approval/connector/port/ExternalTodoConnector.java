package io.github.akaryc1b.approval.connector.port;

import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.model.ExternalId;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public interface ExternalTodoConnector {

    TodoReceipt upsert(ConnectorContext context, ExternalTodo todo);

    TodoReceipt cancel(ConnectorContext context, String externalTaskKey, String reason);

    record ExternalTodo(
        String externalTaskKey,
        ExternalId recipientId,
        String title,
        String description,
        String actionUrl,
        TodoStatus status,
        Instant createdAt,
        Instant dueAt,
        String idempotencyKey,
        Map<String, String> attributes
    ) {
        public ExternalTodo {
            externalTaskKey = requireText(externalTaskKey, "externalTaskKey");
            recipientId = Objects.requireNonNull(recipientId, "recipientId must not be null");
            title = requireText(title, "title");
            description = description == null ? "" : description;
            actionUrl = requireText(actionUrl, "actionUrl");
            status = Objects.requireNonNull(status, "status must not be null");
            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
            idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    record TodoReceipt(
        String providerTaskId,
        TodoStatus status,
        Instant synchronizedAt,
        String errorMessage
    ) {
        public TodoReceipt {
            providerTaskId = normalize(providerTaskId);
            status = Objects.requireNonNull(status, "status must not be null");
            synchronizedAt = Objects.requireNonNull(
                synchronizedAt,
                "synchronizedAt must not be null"
            );
            errorMessage = normalize(errorMessage);
        }
    }

    enum TodoStatus {
        PENDING,
        APPROVED,
        REJECTED,
        TRANSFERRED,
        COMPLETED,
        CANCELED
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
