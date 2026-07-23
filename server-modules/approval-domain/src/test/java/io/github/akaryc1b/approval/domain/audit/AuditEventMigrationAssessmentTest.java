package io.github.akaryc1b.approval.domain.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuditEventMigrationAssessmentTest {

    @Test
    void migrationDryRunRequiresDetectOnlyReportEvidence() {
        Map<String, String> attributes = attributes();
        AuditEvent event = new AuditEvent(
            UUID.randomUUID(),
            "tenant-a",
            "operator-a",
            "PROCESS_RELEASE_MIGRATION_DRY_RUN_EXECUTED",
            "APPROVAL_PROCESS_RELEASE_MIGRATION",
            "purchasePayment:1->2",
            "request-a",
            "trace-a",
            Instant.parse("2026-07-23T01:00:00Z"),
            attributes
        );

        assertEquals("approval.process-lifecycle", event.schemaName());
        assertEquals("true", event.attributes().get("detectOnly"));
        Map<String, String> missingReportHash = new LinkedHashMap<>(attributes);
        missingReportHash.remove("reportHash");
        assertThrows(
            IllegalArgumentException.class,
            () -> new AuditEvent(
                UUID.randomUUID(),
                "tenant-a",
                "operator-a",
                "PROCESS_RELEASE_MIGRATION_DRY_RUN_EXECUTED",
                "APPROVAL_PROCESS_RELEASE_MIGRATION",
                "purchasePayment:1->2",
                "request-b",
                "trace-b",
                Instant.parse("2026-07-23T01:00:00Z"),
                missingReportHash
            )
        );
    }

    private static Map<String, String> attributes() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("operation", "DRY_RUN");
        values.put("definitionKey", "purchasePayment");
        values.put("sourceReleaseVersion", "1");
        values.put("sourceReleasePackageHash", "1".repeat(64));
        values.put("targetReleaseVersion", "2");
        values.put("targetReleasePackageHash", "2".repeat(64));
        values.put("status", "READY");
        values.put("totalBindingCount", "1");
        values.put("evaluatedCount", "1");
        values.put("eligibleCount", "1");
        values.put("blockedCount", "0");
        values.put("terminalCount", "0");
        values.put("highImpactChangeCount", "3");
        values.put("reportHash", "a".repeat(64));
        values.put("detectOnly", "true");
        values.put("reason", "Assess compatibility without runtime mutation");
        return Map.copyOf(values);
    }
}
