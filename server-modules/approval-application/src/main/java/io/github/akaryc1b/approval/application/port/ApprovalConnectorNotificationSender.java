package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationIntent;

/** Organization-connector notification delivery port. */
@FunctionalInterface
public interface ApprovalConnectorNotificationSender {

    DeliveryResult deliver(NotificationIntent intent);

    static ApprovalConnectorNotificationSender unavailable() {
        return intent -> DeliveryResult.failed(
            true,
            "CONNECTOR_NOTIFICATION_UNAVAILABLE",
            "organization connector notification channel is unavailable"
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
