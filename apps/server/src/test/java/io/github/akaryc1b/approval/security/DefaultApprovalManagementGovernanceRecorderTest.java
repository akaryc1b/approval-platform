package io.github.akaryc1b.approval.security;

import io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultApprovalManagementGovernanceRecorderTest {

    private static final Instant NOW = Instant.parse("2026-07-21T06:30:00Z");
    private static final UUID EVENT_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000000401"
    );

    @Test
    void recordsHighRiskReasonOnceWithVersionedAuditContract() {
        InMemoryIdempotencyGuard idempotency = new InMemoryIdempotencyGuard();
        List<AuditEvent> events = new ArrayList<>();
        AuditEventSink sink = events::add;
        DefaultApprovalManagementGovernanceRecorder recorder =
            new DefaultApprovalManagementGovernanceRecorder(
                idempotency,
                sink,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> EVENT_ID
            );
        ApprovalPrincipal principal = tenantAdmin();
        ApprovalResource resource = ApprovalResource.tenant("tenant-a");
        ApprovalAuthorizationDecision decision =
            ApprovalAuthorizationDecision.responsibility(
                Requirement.PUBLISH,
                ApprovalEnterpriseRole.TENANT_ADMIN,
                ApprovalResourceScope.Kind.TENANT
            );
        RequestContext context = new RequestContext(
            "tenant-a",
            "operator-a",
            "request-a",
            "idempotency-a",
            "trace-a"
        );

        recorder.recordAuthorized(
            principal,
            Requirement.PUBLISH,
            resource,
            decision,
            "Publish the reviewed release for quarter end",
            context
        );
        recorder.recordAuthorized(
            principal,
            Requirement.PUBLISH,
            resource,
            decision,
            "Publish the reviewed release for quarter end",
            context
        );

        assertEquals(1, events.size());
        AuditEvent event = events.getFirst();
        assertEquals(EVENT_ID, event.eventId());
        assertEquals("MANAGEMENT_HIGH_RISK_AUTHORIZED", event.action());
        assertEquals("approval.management-security", event.schemaName());
        assertEquals(1, event.schemaVersion());
        assertEquals("MANAGEMENT_AUTHORIZATION", event.aggregateType());
        assertEquals("tenant-a", event.aggregateId());
        assertEquals("request-a", event.requestId());
        assertEquals("trace-a", event.traceId());
        assertEquals("publish", event.attributes().get("requirement"));
        assertEquals(
            "Publish the reviewed release for quarter end",
            event.attributes().get("reason")
        );
        assertEquals("tenant", event.attributes().get("resourceScope"));
        assertEquals("responsibility", event.attributes().get("authorizationDecision"));
        assertEquals("tenant-admin", event.attributes().get("matchedRole"));
    }

    @Test
    void conflictingReplayAndMismatchedPrincipalFailClosed() {
        InMemoryIdempotencyGuard idempotency = new InMemoryIdempotencyGuard();
        DefaultApprovalManagementGovernanceRecorder recorder =
            new DefaultApprovalManagementGovernanceRecorder(
                idempotency,
                event -> {
                },
                Clock.fixed(NOW, ZoneOffset.UTC),
                UUID::randomUUID
            );
        ApprovalPrincipal principal = tenantAdmin();
        ApprovalAuthorizationDecision decision =
            ApprovalAuthorizationDecision.responsibility(
                Requirement.PUBLISH,
                ApprovalEnterpriseRole.TENANT_ADMIN,
                ApprovalResourceScope.Kind.TENANT
            );
        RequestContext context = new RequestContext(
            "tenant-a",
            "operator-a",
            "request-a",
            "idempotency-a",
            "trace-a"
        );
        recorder.recordAuthorized(
            principal,
            Requirement.PUBLISH,
            ApprovalResource.tenant("tenant-a"),
            decision,
            "Publish the reviewed release for quarter end",
            context
        );

        assertThrows(
            IdempotencyGuard.IdempotencyConflictException.class,
            () -> recorder.recordAuthorized(
                principal,
                Requirement.PUBLISH,
                ApprovalResource.tenant("tenant-a"),
                decision,
                "Publish a different unreviewed release",
                context
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> recorder.recordAuthorized(
                principal,
                Requirement.PUBLISH,
                ApprovalResource.tenant("tenant-a"),
                decision,
                "Publish the reviewed release for quarter end",
                new RequestContext(
                    "tenant-a",
                    "other-operator",
                    "request-b",
                    "idempotency-b",
                    "trace-b"
                )
            )
        );
    }

    private static ApprovalPrincipal tenantAdmin() {
        return ApprovalPrincipal.active(
            "tenant-a",
            "operator-a",
            Set.of(),
            Set.of(new ApprovalResponsibilityAssignment(
                ApprovalEnterpriseRole.TENANT_ADMIN,
                ApprovalResponsibilitySourceType.ROLE,
                "tenant-admin-role",
                ApprovalResourceScope.tenant()
            )),
            null
        );
    }

    private static final class InMemoryIdempotencyGuard implements IdempotencyGuard {

        private final Map<String, Entry> entries = new HashMap<>();

        @Override
        public <T> T execute(
            RequestContext context,
            String operation,
            String requestHash,
            Class<T> resultType,
            Supplier<T> action
        ) {
            String key = context.tenantId() + ':' + operation + ':' + context.idempotencyKey();
            Entry existing = entries.get(key);
            if (existing != null) {
                if (!existing.requestHash().equals(requestHash)) {
                    throw new IdempotencyConflictException(
                        "idempotency key was already used with another request"
                    );
                }
                return resultType.cast(existing.result());
            }
            T result = action.get();
            entries.put(key, new Entry(requestHash, result));
            return result;
        }
    }

    private record Entry(String requestHash, Object result) {
    }
}
