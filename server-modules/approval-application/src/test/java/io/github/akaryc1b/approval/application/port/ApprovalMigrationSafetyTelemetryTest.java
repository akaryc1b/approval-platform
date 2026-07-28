package io.github.akaryc1b.approval.application.port;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ApprovalMigrationSafetyTelemetryTest {

    @Test
    void safeRecordSwallowsTelemetryFailureWithoutRetryOrFallback() {
        ApprovalMigrationSafetyTelemetry broken = event -> {
            throw new IllegalStateException("synthetic observability outage");
        };

        assertDoesNotThrow(() -> ApprovalMigrationSafetyTelemetry.safeRecord(
            broken,
            ApprovalMigrationSafetyTelemetry.Event.UNKNOWN_ENTERED
        ));
    }
}
