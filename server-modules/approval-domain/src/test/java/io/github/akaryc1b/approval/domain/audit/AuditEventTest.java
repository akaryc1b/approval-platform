package io.github.akaryc1b.approval.domain.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuditEventTest {

    @Test
    void copiesAttributesToPreserveAuditFacts() {
        Map<String, String> source = new HashMap<>();
        source.put("taskId", "task-1");

        var event = new AuditEvent(
            UUID.randomUUID(),
            "tenant-a",
            "user-1",
            "TASK_APPROVED",
            "TASK",
            "task-1",
            "request-1",
            "trace-1",
            Instant.parse("2026-07-17T00:00:00Z"),
            source
        );
        source.put("taskId", "modified");

        assertEquals("task-1", event.attributes().get("taskId"));
        assertThrows(
            UnsupportedOperationException.class,
            () -> event.attributes().put("new", "value")
        );
    }

    @Test
    void managementAuthorizationRequiresVersionedGovernanceEvidence() {
        Map<String, String> attributes = Map.of(
            "requirement", "publish",
            "reason", "Publish the reviewed enterprise release",
            "resourceScope", "tenant",
            "authorizationDecision", "responsibility",
            "matchedRole", "tenant-admin"
        );
        AuditEvent event = new AuditEvent(
            UUID.randomUUID(),
            "tenant-a",
            "operator-a",
            "MANAGEMENT_HIGH_RISK_AUTHORIZED",
            "MANAGEMENT_AUTHORIZATION",
            "tenant-a",
            "request-a",
            "trace-a",
            Instant.parse("2026-07-21T06:30:00Z"),
            attributes
        );

        assertEquals("approval.management-security", event.schemaName());
        assertEquals(1, event.schemaVersion());
        assertThrows(
            IllegalArgumentException.class,
            () -> new AuditEvent(
                UUID.randomUUID(),
                "tenant-a",
                "operator-a",
                "MANAGEMENT_HIGH_RISK_AUTHORIZED",
                "MANAGEMENT_AUTHORIZATION",
                "tenant-a",
                "request-a",
                "trace-a",
                Instant.parse("2026-07-21T06:30:00Z"),
                Map.of(
                    "requirement", "publish",
                    "resourceScope", "tenant",
                    "authorizationDecision", "responsibility",
                    "matchedRole", "tenant-admin"
                )
            )
        );
    }
}
