package io.github.akaryc1b.approval.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Participant-authorized read model for approval instance timelines.
 */
public interface ApprovalTimelineQuery {

    Optional<ApprovalTimeline> findTimeline(TimelineIdentity identity);

    record TimelineIdentity(
        String tenantId,
        String operatorId,
        UUID instanceId
    ) {
        public TimelineIdentity {
            tenantId = requireText(tenantId, "tenantId");
            operatorId = requireText(operatorId, "operatorId");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
        }
    }

    record ApprovalTimeline(
        UUID instanceId,
        List<TimelineItem> items
    ) {
        public ApprovalTimeline {
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    record TimelineItem(
        UUID eventId,
        String action,
        String schemaName,
        int schemaVersion,
        String summary,
        String operatorId,
        String aggregateType,
        String aggregateId,
        String requestId,
        String traceId,
        Instant occurredAt,
        Map<String, String> attributes
    ) {
        public TimelineItem {
            eventId = Objects.requireNonNull(eventId, "eventId must not be null");
            action = requireText(action, "action");
            schemaName = requireText(schemaName, "schemaName");
            if (schemaVersion < 0) {
                throw new IllegalArgumentException("schemaVersion must not be negative");
            }
            summary = requireText(summary, "summary");
            operatorId = requireText(operatorId, "operatorId");
            aggregateType = requireText(aggregateType, "aggregateType");
            aggregateId = requireText(aggregateId, "aggregateId");
            requestId = requireText(requestId, "requestId");
            traceId = normalizeOptional(traceId);
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
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
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
