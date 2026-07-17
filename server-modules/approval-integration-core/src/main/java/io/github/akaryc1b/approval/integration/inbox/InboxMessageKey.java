package io.github.akaryc1b.approval.integration.inbox;

import java.util.Objects;

/**
 * Globally stable identity of one incoming message for one tenant and consumer.
 */
public record InboxMessageKey(
    String tenantId,
    String consumerKey,
    String messageId
) {

    public InboxMessageKey {
        tenantId = requireText(tenantId, "tenantId");
        consumerKey = requireText(consumerKey, "consumerKey");
        messageId = requireText(messageId, "messageId");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
