package io.github.akaryc1b.approval.connector.port;

import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.model.DepartmentSnapshot;
import io.github.akaryc1b.approval.connector.model.ExternalId;
import io.github.akaryc1b.approval.connector.model.PageRequest;
import io.github.akaryc1b.approval.connector.model.PageResult;
import io.github.akaryc1b.approval.connector.model.PositionSnapshot;
import io.github.akaryc1b.approval.connector.model.RoleSnapshot;
import io.github.akaryc1b.approval.connector.model.UserSnapshot;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public interface OrganizationConnector {

    Optional<UserSnapshot> findUser(ConnectorContext context, ExternalId userId);

    PageResult<UserSnapshot> searchUsers(
        ConnectorContext context,
        UserQuery query,
        PageRequest pageRequest
    );

    Optional<DepartmentSnapshot> findDepartment(
        ConnectorContext context,
        ExternalId departmentId
    );

    Optional<RoleSnapshot> findRole(ConnectorContext context, String roleCode);

    Optional<PositionSnapshot> findPosition(ConnectorContext context, String positionCode);

    List<UserSnapshot> resolveRoleMembers(ConnectorContext context, String roleCode);

    List<UserSnapshot> resolvePositionMembers(ConnectorContext context, String positionCode);

    List<UserSnapshot> resolveManagerChain(
        ConnectorContext context,
        ExternalId userId,
        int maximumLevels
    );

    default NotificationDeliveryResult sendNotification(
        ConnectorContext context,
        UserNotification notification
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(notification, "notification must not be null");
        return NotificationDeliveryResult.failed(
            false,
            "CONNECTOR_NOTIFICATION_UNSUPPORTED",
            "organization connector does not support notification delivery"
        );
    }

    record UserQuery(
        String keyword,
        ExternalId departmentId,
        String roleCode,
        String positionCode,
        Boolean active
    ) {
        public UserQuery {
            keyword = normalize(keyword);
            roleCode = normalize(roleCode);
            positionCode = normalize(positionCode);
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }

    record UserNotification(
        String recipientUserId,
        String eventType,
        String title,
        String body,
        Map<String, String> metadata,
        String deduplicationKey
    ) {
        public UserNotification {
            recipientUserId = requireText(recipientUserId, "recipientUserId");
            eventType = requireText(eventType, "eventType");
            title = requireText(title, "title");
            body = requireText(body, "body");
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            deduplicationKey = requireText(deduplicationKey, "deduplicationKey");
        }
    }

    record NotificationDeliveryResult(
        boolean successful,
        boolean retryable,
        String providerMessageId,
        String errorCode,
        String errorMessage
    ) {
        public static NotificationDeliveryResult delivered(String providerMessageId) {
            return new NotificationDeliveryResult(true, false, providerMessageId, null, null);
        }

        public static NotificationDeliveryResult failed(
            boolean retryable,
            String errorCode,
            String errorMessage
        ) {
            return new NotificationDeliveryResult(
                false,
                retryable,
                null,
                requireText(errorCode, "errorCode"),
                requireText(errorMessage, "errorMessage")
            );
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
