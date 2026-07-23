package io.github.akaryc1b.approval.security;

import io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Idempotent append-only authorization evidence for governed management operations. */
public final class DefaultApprovalManagementGovernanceRecorder
    implements ApprovalManagementGovernanceRecorder {

    private static final String OPERATION_PREFIX =
        "approval.management.high-risk-authorized.v1.";

    private final IdempotencyGuard idempotency;
    private final AuditEventSink auditEvents;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public DefaultApprovalManagementGovernanceRecorder(
        IdempotencyGuard idempotency,
        AuditEventSink auditEvents,
        Clock clock,
        Supplier<UUID> identifiers
    ) {
        this.idempotency = Objects.requireNonNull(
            idempotency,
            "idempotency must not be null"
        );
        this.auditEvents = Objects.requireNonNull(
            auditEvents,
            "auditEvents must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.identifiers = Objects.requireNonNull(
            identifiers,
            "identifiers must not be null"
        );
    }

    @Override
    public void recordAuthorized(
        ApprovalPrincipal principal,
        Requirement requirement,
        ApprovalResource resource,
        ApprovalAuthorizationDecision decision,
        String reason,
        RequestContext context
    ) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(requirement, "requirement must not be null");
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(context, "context must not be null");
        if (!decision.allowed()) {
            throw new IllegalArgumentException(
                "only an allowed management decision can be recorded"
            );
        }
        if (!principal.tenantId().equals(context.tenantId())
            || !principal.operatorId().equals(context.operatorId())
            || !resource.tenantId().equals(context.tenantId())) {
            throw new IllegalArgumentException(
                "governance evidence context does not match the authenticated principal"
            );
        }
        String normalizedReason = requireText(reason, "reason");
        Map<String, String> attributes = attributes(
            requirement,
            resource,
            decision,
            normalizedReason
        );
        String requestHash = requestHash(requirement, resource, attributes);
        idempotency.execute(
            context,
            OPERATION_PREFIX + requirement.metricTag(),
            requestHash,
            String.class,
            () -> append(context, requirement, resource, attributes)
        );
    }

    private String append(
        RequestContext context,
        Requirement requirement,
        ApprovalResource resource,
        Map<String, String> attributes
    ) {
        UUID eventId = Objects.requireNonNull(
            identifiers.get(),
            "generated eventId must not be null"
        );
        auditEvents.append(new AuditEvent(
            eventId,
            context.tenantId(),
            context.operatorId(),
            "MANAGEMENT_HIGH_RISK_AUTHORIZED",
            "MANAGEMENT_AUTHORIZATION",
            aggregateId(resource),
            context.requestId(),
            context.traceId(),
            clock.instant(),
            attributes
        ));
        return eventId.toString();
    }

    private static Map<String, String> attributes(
        Requirement requirement,
        ApprovalResource resource,
        ApprovalAuthorizationDecision decision,
        String reason
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("requirement", requirement.metricTag());
        values.put("reason", reason);
        values.put("resourceScope", resource.level().metricTag());
        values.put("authorizationDecision", decision.code().metricTag());
        values.put("matchedRole", decision.roleMetricTag());
        if (resource.departmentId() != null) {
            values.put("departmentId", resource.departmentId());
        }
        return Map.copyOf(values);
    }

    private static String aggregateId(ApprovalResource resource) {
        return resource.departmentId() == null
            ? resource.tenantId()
            : resource.departmentId();
    }

    private static String requestHash(
        Requirement requirement,
        ApprovalResource resource,
        Map<String, String> attributes
    ) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        update(digest, requirement.name());
        update(digest, resource.tenantId());
        update(digest, resource.level().name());
        update(digest, resource.departmentId());
        attributes.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                update(digest, entry.getKey());
                update(digest, entry.getValue());
            });
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
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
