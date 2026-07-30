package io.github.akaryc1b.approval.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalMigrationOperationsTelemetryClassifierTest {

    private static final String MANAGEMENT =
        "/api/approval/management/process-instance-operations";
    private static final String MOBILE =
        "/api/approval/mobile/process-instance-operations";

    @Test
    void classifiesEveryE1AndE2RouteWithoutIdentityTags() {
        Map<String, String> expected = Map.of(
            "/summary", "summary",
            "/plans", "plan_list",
            "/plans/plan-a", "plan_detail",
            "/plans/plan-a/instances", "instance_list",
            "/plans/plan-a/diagnostics", "plan_diagnostics",
            "/plans/plan-a/diagnostics/instances", "diagnostic_instance_list",
            "/plans/plan-a/instances/instance-a/diagnostics", "instance_diagnostics"
        );
        for (String prefix : java.util.List.of(MANAGEMENT, MOBILE)) {
            expected.forEach((suffix, operation) -> assertEquals(
                operation,
                ApprovalMigrationOperationsTelemetryClassifier.Operation.resolve(prefix + suffix)
                    .metricValue()
            ));
        }
    }

    @Test
    void rejectsUnknownNestedOverlongAndPrefixConfusionRoutes() {
        for (String path : java.util.List.of(
            MANAGEMENT,
            MANAGEMENT + "-evil/summary",
            MANAGEMENT + "/commands/execute",
            MANAGEMENT + "/plans/plan-a/retry",
            MANAGEMENT + "/plans/plan-a/instances/instance-a",
            MANAGEMENT + "/plans/" + "x".repeat(129) + "/diagnostics",
            "/actuator/metrics",
            "/api/approval/other"
        )) {
            assertEquals(
                "none",
                ApprovalMigrationOperationsTelemetryClassifier.Operation.resolve(path)
                    .metricValue(),
                path
            );
        }
        assertFalse(
            ApprovalMigrationOperationsTelemetryClassifier.isReadOperationsPath(
                "POST",
                MANAGEMENT + "/summary"
            )
        );
        assertTrue(
            ApprovalMigrationOperationsTelemetryClassifier.isReadOperationsPath(
                "GET",
                MOBILE + "/plans/plan-a/diagnostics"
            )
        );
    }

    @Test
    void mapsHttpStatusesIntoAClosedFailureSet() {
        Map<Integer, String> expected = Map.ofEntries(
            Map.entry(200, "none"),
            Map.entry(304, "none"),
            Map.entry(400, "invalid_request"),
            Map.entry(401, "unauthenticated"),
            Map.entry(403, "forbidden"),
            Map.entry(404, "not_found"),
            Map.entry(405, "method_not_allowed"),
            Map.entry(409, "conflict"),
            Map.entry(422, "invalid_request"),
            Map.entry(429, "rate_limited"),
            Map.entry(500, "server_error"),
            Map.entry(599, "server_error")
        );
        expected.forEach((status, failureClass) -> {
            var classification = ApprovalMigrationOperationsTelemetryClassifier.classify(
                MANAGEMENT + "/plans/plan-a/diagnostics",
                status
            );
            assertEquals(failureClass, classification.failureClass().metricValue());
            assertEquals(status < 400 ? "success" : "failure", classification.result().metricValue());
        });
    }
}
