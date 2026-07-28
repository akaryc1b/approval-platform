package io.github.akaryc1b.approval.application.port;

import java.util.Objects;

/**
 * Identity-free best-effort safety telemetry. Implementations must never change migration
 * control flow, persistence, retries or reconciliation decisions.
 */
@FunctionalInterface
public interface ApprovalMigrationSafetyTelemetry {

    ApprovalMigrationSafetyTelemetry NOOP = event -> {
    };

    void record(Event event);

    enum Event {
        UNKNOWN_ENTERED,
        RECONCILIATION_OBSERVATION_RECORDED,
        RECONCILIATION_MANUAL_REVIEW_REQUIRED,
        CANARY_LIMIT_REACHED,
        ORCHESTRATION_BOUNDED_STOP,
        KILL_SWITCH_BLOCKED,
        PLAN_AGGREGATION_COMPLETED,
        STALE_OWNERSHIP_REJECTED,
        DUPLICATE_OUTCOME_PREVENTED,
        VERIFICATION_MISMATCH,
        RUNTIME_BINDING_CAS_FAILED,
        COMPLETION_EVIDENCE_FAILED
    }

    static ApprovalMigrationSafetyTelemetry require(
        ApprovalMigrationSafetyTelemetry telemetry
    ) {
        return Objects.requireNonNull(telemetry, "telemetry must not be null");
    }

    static void safeRecord(ApprovalMigrationSafetyTelemetry telemetry, Event event) {
        try {
            require(telemetry).record(Objects.requireNonNull(event, "event must not be null"));
        } catch (RuntimeException ignored) {
            // Observability is non-authoritative and must never affect migration safety state.
        }
    }
}
