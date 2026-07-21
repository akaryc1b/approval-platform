package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationIntent;

/** Pluggable email notification provider. */
@FunctionalInterface
public interface ApprovalEmailNotificationSender {

    DeliveryResult deliver(NotificationIntent intent);

    static ApprovalEmailNotificationSender unavailable() {
        return intent -> DeliveryResult.failed(
            false,
            "EMAIL_PROVIDER_NOT_CONFIGURED",
            "email notification provider is not configured"
        );
    }

    record DeliveryResult(
        boolean successful,
        boolean retryable,
        String providerMessageId,
        String errorCode,
        String errorMessage
    ) {
        public static DeliveryResult delivered(String providerMessageId) {
            return new DeliveryResult(true, false, providerMessageId, null, null);
        }

        public static DeliveryResult failed(
            boolean retryable,
            String errorCode,
            String errorMessage
        ) {
            return new DeliveryResult(false, retryable, null, errorCode, errorMessage);
        }
    }
}
