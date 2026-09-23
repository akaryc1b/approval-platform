package io.github.akaryc1b.approval.integration.outbox;

import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector;
import io.github.akaryc1b.approval.connector.port.OrganizationConnector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Reserved Outbox route to the existing organization notification API, never the payment callback. */
public final class SlaTimeoutNotificationConnector implements BusinessCallbackConnector {

    public static final String ROUTE = "approval-sla-timeout";
    private static final Set<String> FIELDS = Set.of("schemaVersion", "slaInstanceId", "approvalInstanceId",
        "policyId", "policyVersion", "actionSequence", "targetType", "recipientId", "dueAt", "overdueAt",
        "connectorKey");
    private final OrganizationConnector connector;
    private final String connectorKey;
    private final Clock clock;

    public SlaTimeoutNotificationConnector(OrganizationConnector connector, String connectorKey, Clock clock) {
        this.connector = Objects.requireNonNull(connector, "connector must not be null");
        this.connectorKey = routeKey(connectorKey);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public static OutboxMessage message(String tenant, String request, String trace, Instant detectedAt,
        Timeout timeout) {
        Objects.requireNonNull(detectedAt, "detectedAt must not be null");
        if (detectedAt.isBefore(timeout.overdueAt())) {
            throw new IllegalArgumentException("timeout has not reached its bound deadline");
        }
        var context = new ConnectorContext(ROUTE, text(tenant, 128), text(request, 128), trace, detectedAt);
        return OutboxMessage.create(context, new BusinessEvent(UUID.randomUUID(), timeout.eventType(),
            "SLA_TIMEOUT", timeout.slaInstanceId().toString(), detectedAt,
            timeout.key(context.tenantId()), timeout.payload()), detectedAt);
    }

    @Override
    public CallbackReceipt deliver(ConnectorContext context, BusinessEvent event) {
        Timeout timeout;
        try {
            if (!ROUTE.equals(context.connectorKey())) throw new IllegalArgumentException("wrong route");
            timeout = decode(event.payload());
            if (!connectorKey.equals(timeout.connectorKey()) || !event.eventType().equals(timeout.eventType())
                || !"SLA_TIMEOUT".equals(event.aggregateType())
                || !event.aggregateId().equals(timeout.slaInstanceId().toString())
                || !event.idempotencyKey().equals(timeout.key(context.tenantId()))
                || event.occurredAt().isBefore(timeout.overdueAt())) {
                throw new IllegalArgumentException("event identity mismatch");
            }
        } catch (RuntimeException invalid) {
            return receipt(DeliveryStatus.PERMANENT_FAILURE, null, "SLA_TIMEOUT_EVENT_REJECTED");
        }
        // The Outbox dispatcher owns retry. No external call is made by the source transaction.
        try {
            Map<String, String> metadata = new LinkedHashMap<>();
            timeout.payload().forEach((key, value) -> metadata.put(key, (String) value));
            OrganizationConnector.NotificationDeliveryResult result = connector.sendNotification(
                new ConnectorContext(connectorKey, context.tenantId(), context.requestId(),
                    context.traceId(), clock.instant()),
                new OrganizationConnector.UserNotification(timeout.recipientId(), timeout.eventType(),
                    "审批 SLA 已超时", "审批事项已超过绑定的 SLA 期限，请通过审批平台查看并处理。",
                    metadata, event.idempotencyKey())
            );
            if (result == null || (result.successful() && result.retryable())) {
                return receipt(DeliveryStatus.PERMANENT_FAILURE, null, "SLA_NOTIFICATION_RECEIPT_INVALID");
            }
            if (result.successful()) {
                String providerId = result.providerMessageId();
                if (providerId != null && providerId.length() > 256) {
                    return receipt(DeliveryStatus.PERMANENT_FAILURE, null, "SLA_NOTIFICATION_RECEIPT_INVALID");
                }
                return receipt(DeliveryStatus.DELIVERED, providerId, null);
            }
            return receipt(result.retryable() ? DeliveryStatus.RETRYABLE_FAILURE : DeliveryStatus.PERMANENT_FAILURE,
                null, "SLA_NOTIFICATION_DELIVERY_FAILED");
        } catch (RuntimeException unavailable) {
            // Never persist exception text, remote response content or credentials in the Outbox error.
            return receipt(DeliveryStatus.RETRYABLE_FAILURE, null, "SLA_NOTIFICATION_DELIVERY_UNAVAILABLE");
        }
    }

    private CallbackReceipt receipt(DeliveryStatus status, String providerId, String error) {
        // OrganizationConnector does not expose HTTP status. Zero is deliberately not a fabricated wire code.
        return new CallbackReceipt(status, providerId, 0, clock.instant(), error);
    }

    private static Timeout decode(Map<String, Object> payload) {
        Set<String> expected = new HashSet<>(FIELDS);
        if (payload.containsKey("taskId")) expected.add("taskId");
        if (payload.containsKey("collaborationParticipantId")) expected.add("collaborationParticipantId");
        if (!payload.keySet().equals(expected) || !"1".equals(payload.get("schemaVersion"))) {
            throw new IllegalArgumentException("invalid timeout schema");
        }
        return new Timeout(uuid(payload, "slaInstanceId"), uuid(payload, "approvalInstanceId"),
            payload.containsKey("taskId") ? uuid(payload, "taskId") : null,
            payload.containsKey("collaborationParticipantId") ? uuid(payload, "collaborationParticipantId") : null,
            uuid(payload, "policyId"), Integer.parseInt(string(payload, "policyVersion")),
            Integer.parseInt(string(payload, "actionSequence")), string(payload, "targetType"),
            string(payload, "recipientId"), Instant.parse(string(payload, "dueAt")),
            Instant.parse(string(payload, "overdueAt")), string(payload, "connectorKey"));
    }

    private static UUID uuid(Map<String, Object> values, String name) {
        String value = string(values, name);
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException("noncanonical identifier");
        return id;
    }

    private static String string(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (!(value instanceof String text)) throw new IllegalArgumentException("string field required");
        return text;
    }

    public static String routeKey(String key) {
        String checked = text(key, 128);
        if (ROUTE.equals(checked)) throw new IllegalArgumentException("notification route must be reserved");
        return checked;
    }

    private static String text(String value, int maximum) {
        Objects.requireNonNull(value, "text must not be null");
        if (value.isBlank() || value.length() > maximum || !value.equals(value.trim())) {
            throw new IllegalArgumentException("text must be canonical and bounded");
        }
        return value;
    }

    public record Timeout(UUID slaInstanceId, UUID approvalInstanceId, UUID taskId,
        UUID collaborationParticipantId, UUID policyId, int policyVersion, int actionSequence,
        String targetType, String recipientId, Instant dueAt, Instant overdueAt, String connectorKey) {
        public Timeout {
            Objects.requireNonNull(slaInstanceId, "slaInstanceId");
            Objects.requireNonNull(approvalInstanceId, "approvalInstanceId");
            Objects.requireNonNull(policyId, "policyId");
            Objects.requireNonNull(dueAt, "dueAt");
            Objects.requireNonNull(overdueAt, "overdueAt");
            recipientId = text(recipientId, 200);
            connectorKey = routeKey(connectorKey);
            boolean targetMatches = switch (text(targetType, 64)) {
                case "PROCESS" -> taskId == null && collaborationParticipantId == null;
                case "TASK" -> taskId != null && collaborationParticipantId == null;
                case "COLLABORATION_PARTICIPANT" -> taskId != null && collaborationParticipantId != null;
                default -> false;
            };
            if (!targetMatches || policyVersion < 1 || actionSequence < 1 || overdueAt.isBefore(dueAt)) {
                throw new IllegalArgumentException("timeout evidence is inconsistent");
            }
        }

        public String eventType() {
            return switch (targetType) {
                case "PROCESS" -> "PROCESS_TIMEOUT_DETECTED.v1";
                case "TASK" -> "TASK_TIMEOUT_DETECTED.v1";
                default -> "COLLABORATION_TIMEOUT_DETECTED.v1";
            };
        }

        public String key(String tenant) {
            String input = "AP-SLA-TIMEOUT-V1\n" + text(tenant, 128) + '\n' + slaInstanceId + '\n' + actionSequence;
            try {
                return "sla-timeout-v1:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 unavailable", impossible);
            }
        }

        public Map<String, Object> payload() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("schemaVersion", "1"); values.put("slaInstanceId", slaInstanceId.toString());
            values.put("approvalInstanceId", approvalInstanceId.toString());
            if (taskId != null) values.put("taskId", taskId.toString());
            if (collaborationParticipantId != null) values.put("collaborationParticipantId", collaborationParticipantId.toString());
            values.put("policyId", policyId.toString()); values.put("policyVersion", Integer.toString(policyVersion));
            values.put("actionSequence", Integer.toString(actionSequence)); values.put("targetType", targetType);
            values.put("recipientId", recipientId); values.put("dueAt", dueAt.toString());
            values.put("overdueAt", overdueAt.toString()); values.put("connectorKey", connectorKey);
            return Map.copyOf(values);
        }
    }
}
