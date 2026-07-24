package io.github.akaryc1b.approval.sdk.v1;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;

/** Read-only server-produced event envelope for schema version 1.0. */
public final class EventEnvelopeV1 {
    public static final String SCHEMA_VERSION = "1.0";

    private final String eventId;
    private final String eventType;
    private final Instant occurredAt;
    private final TenantContext tenant;
    private final ResourceReference resource;
    private final String requestId;
    private final String traceId;
    private final Object payload;
    private final String payloadHash;
    private final ProducerIdentity producer;
    private final String orderingKey;
    private final String causationId;
    private final String correlationId;

    EventEnvelopeV1(
        String eventId,
        String eventType,
        String occurredAt,
        TenantContext tenant,
        ResourceReference resource,
        String requestId,
        String traceId,
        Object payload,
        String payloadHash,
        ProducerIdentity producer,
        String orderingKey,
        String causationId,
        String correlationId
    ) {
        this.eventId = required(eventId, "eventId");
        this.eventType = required(eventType, "eventType");
        try {
            this.occurredAt = Instant.parse(required(occurredAt, "occurredAt"));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("occurredAt must be an RFC 3339 instant", exception);
        }
        this.tenant = Objects.requireNonNull(tenant, "tenant");
        this.resource = Objects.requireNonNull(resource, "resource");
        this.requestId = required(requestId, "requestId");
        this.traceId = required(traceId, "traceId");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.payloadHash = required(payloadHash, "payloadHash");
        this.producer = Objects.requireNonNull(producer, "producer");
        this.orderingKey = optional(orderingKey);
        this.causationId = optional(causationId);
        this.correlationId = optional(correlationId);
    }

    public String schemaVersion() {
        return SCHEMA_VERSION;
    }

    public String eventId() {
        return eventId;
    }

    public String eventType() {
        return eventType;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public TenantContext tenant() {
        return tenant;
    }

    public ResourceReference resource() {
        return resource;
    }

    public String requestId() {
        return requestId;
    }

    public String traceId() {
        return traceId;
    }

    public Object payload() {
        return payload;
    }

    public String payloadHash() {
        return payloadHash;
    }

    public ProducerIdentity producer() {
        return producer;
    }

    public String orderingKey() {
        return orderingKey;
    }

    public String causationId() {
        return causationId;
    }

    public String correlationId() {
        return correlationId;
    }

    public record TenantContext(String tenantId) {
        public TenantContext {
            tenantId = required(tenantId, "tenant.tenantId");
        }
    }

    public record ResourceReference(String resourceType, String resourceId, Long version) {
        public ResourceReference {
            resourceType = required(resourceType, "resource.resourceType");
            resourceId = required(resourceId, "resource.resourceId");
            if (version != null && (version < 0 || version > CanonicalJson.MAX_SAFE_INTEGER)) {
                throw new IllegalArgumentException("resource.version must be a non-negative safe integer");
            }
        }
    }

    public record ProducerIdentity(String service, String instance) {
        public ProducerIdentity {
            service = required(service, "producer.service");
            instance = required(instance, "producer.instance");
        }
    }

    static EventEnvelopeV1 fromMap(Map<String, Object> envelope) {
        String version = string(envelope, "schemaVersion");
        if (!SCHEMA_VERSION.equals(version)) {
            throw new UnsupportedSchemaVersionException(version);
        }
        Map<String, Object> tenant = object(envelope, "tenant");
        Map<String, Object> resource = object(envelope, "resource");
        Map<String, Object> producer = object(envelope, "producer");
        Long resourceVersion = nullableLong(resource.get("version"), "resource.version");
        return new EventEnvelopeV1(
            string(envelope, "eventId"),
            string(envelope, "eventType"),
            string(envelope, "occurredAt"),
            new TenantContext(string(tenant, "tenantId")),
            new ResourceReference(
                string(resource, "resourceType"),
                string(resource, "resourceId"),
                resourceVersion
            ),
            string(envelope, "requestId"),
            string(envelope, "traceId"),
            Objects.requireNonNull(envelope.get("payload"), "payload"),
            string(envelope, "payloadHash"),
            new ProducerIdentity(string(producer, "service"), string(producer, "instance")),
            nullableString(envelope.get("orderingKey"), "orderingKey"),
            nullableString(envelope.get("causationId"), "causationId"),
            nullableString(envelope.get("correlationId"), "correlationId")
        );
    }

    static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return (Map<String, Object>) value;
    }

    private static String string(Map<String, Object> values, String field) {
        return required(nullableString(values.get(field), field), field);
    }

    private static String nullableString(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return string;
    }

    private static Long nullableLong(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return number.longValue();
    }

    public static final class UnsupportedSchemaVersionException extends IllegalArgumentException {
        public UnsupportedSchemaVersionException(String version) {
            super("Unsupported event schema version: " + version);
        }
    }
}
