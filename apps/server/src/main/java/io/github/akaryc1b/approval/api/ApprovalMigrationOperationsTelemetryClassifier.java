package io.github.akaryc1b.approval.api;

import java.util.Objects;

/** Closed, identity-free telemetry classification for M5 read-only operations endpoints. */
final class ApprovalMigrationOperationsTelemetryClassifier {

    static final String READ_COUNT_METRIC = "approval.migration.operations.read";
    static final String READ_LATENCY_METRIC = "approval.migration.operations.read.latency";
    static final String MANAGEMENT_PREFIX =
        "/api/approval/management/process-instance-operations";
    static final String MOBILE_PREFIX =
        "/api/approval/mobile/process-instance-operations";

    private ApprovalMigrationOperationsTelemetryClassifier() {
    }

    static Classification classify(String path, int status) {
        Operation operation = Operation.resolve(path);
        FailureClass failureClass = FailureClass.resolve(status);
        boolean success = status >= 200 && status < 400;
        return new Classification(
            operation,
            success ? Result.SUCCESS : Result.FAILURE,
            success ? FailureClass.NONE : failureClass
        );
    }

    static boolean isReadOperationsPath(String method, String path) {
        return "GET".equalsIgnoreCase(method) && Operation.resolve(path) != Operation.NONE;
    }

    record Classification(Operation operation, Result result, FailureClass failureClass) {
        Classification {
            operation = Objects.requireNonNull(operation, "operation must not be null");
            result = Objects.requireNonNull(result, "result must not be null");
            failureClass = Objects.requireNonNull(
                failureClass,
                "failureClass must not be null"
            );
        }
    }

    enum Operation {
        NONE("none"),
        SUMMARY("summary"),
        PLAN_LIST("plan_list"),
        PLAN_DETAIL("plan_detail"),
        INSTANCE_LIST("instance_list"),
        PLAN_DIAGNOSTICS("plan_diagnostics"),
        DIAGNOSTIC_INSTANCE_LIST("diagnostic_instance_list"),
        INSTANCE_DIAGNOSTICS("instance_diagnostics");

        private final String metricValue;

        Operation(String metricValue) {
            this.metricValue = metricValue;
        }

        static Operation resolve(String path) {
            String relative = relativePath(path);
            if (relative == null) {
                return NONE;
            }
            if ("/summary".equals(relative)) {
                return SUMMARY;
            }
            if ("/plans".equals(relative)) {
                return PLAN_LIST;
            }
            if (!relative.startsWith("/plans/")) {
                return NONE;
            }
            String[] parts = relative.substring(1).split("/", -1);
            if (parts.length == 2 && validSegment(parts[1])) {
                return PLAN_DETAIL;
            }
            if (parts.length == 3
                && validSegment(parts[1])
                && "instances".equals(parts[2])) {
                return INSTANCE_LIST;
            }
            if (parts.length == 3
                && validSegment(parts[1])
                && "diagnostics".equals(parts[2])) {
                return PLAN_DIAGNOSTICS;
            }
            if (parts.length == 4
                && validSegment(parts[1])
                && "diagnostics".equals(parts[2])
                && "instances".equals(parts[3])) {
                return DIAGNOSTIC_INSTANCE_LIST;
            }
            if (parts.length == 5
                && validSegment(parts[1])
                && "instances".equals(parts[2])
                && validSegment(parts[3])
                && "diagnostics".equals(parts[4])) {
                return INSTANCE_DIAGNOSTICS;
            }
            return NONE;
        }

        String metricValue() {
            return metricValue;
        }

        private static boolean validSegment(String value) {
            return value != null && !value.isBlank() && value.length() <= 128;
        }

        private static String relativePath(String path) {
            if (path == null) {
                return null;
            }
            if (path.equals(MANAGEMENT_PREFIX)) {
                return "";
            }
            if (path.startsWith(MANAGEMENT_PREFIX + "/")) {
                return path.substring(MANAGEMENT_PREFIX.length());
            }
            if (path.equals(MOBILE_PREFIX)) {
                return "";
            }
            if (path.startsWith(MOBILE_PREFIX + "/")) {
                return path.substring(MOBILE_PREFIX.length());
            }
            return null;
        }
    }

    enum Result {
        SUCCESS("success"),
        FAILURE("failure");

        private final String metricValue;

        Result(String metricValue) {
            this.metricValue = metricValue;
        }

        String metricValue() {
            return metricValue;
        }
    }

    enum FailureClass {
        NONE("none"),
        INVALID_REQUEST("invalid_request"),
        UNAUTHENTICATED("unauthenticated"),
        FORBIDDEN("forbidden"),
        NOT_FOUND("not_found"),
        METHOD_NOT_ALLOWED("method_not_allowed"),
        CONFLICT("conflict"),
        RATE_LIMITED("rate_limited"),
        SERVER_ERROR("server_error");

        private final String metricValue;

        FailureClass(String metricValue) {
            this.metricValue = metricValue;
        }

        static FailureClass resolve(int status) {
            return switch (status) {
                case 400, 422 -> INVALID_REQUEST;
                case 401 -> UNAUTHENTICATED;
                case 403 -> FORBIDDEN;
                case 404 -> NOT_FOUND;
                case 405 -> METHOD_NOT_ALLOWED;
                case 409 -> CONFLICT;
                case 429 -> RATE_LIMITED;
                default -> status >= 400 ? SERVER_ERROR : NONE;
            };
        }

        String metricValue() {
            return metricValue;
        }
    }
}
