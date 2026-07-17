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
}
