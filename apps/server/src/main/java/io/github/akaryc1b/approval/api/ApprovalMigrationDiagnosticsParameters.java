package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.AttemptStatusFilter;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.FailureClass;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.InstanceCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.InstanceSort;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.ReconciliationState;
import org.springframework.util.MultiValueMap;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Strict, bounded and duplicate-rejecting M5-E2 query decoder. */
final class ApprovalMigrationDiagnosticsParameters {

    private static final Set<String> ALLOWED = Set.of(
        "page",
        "pageSize",
        "sort",
        "status",
        "instanceId",
        "from",
        "to",
        "failureClass",
        "reconciliationState"
    );
    private static final int MAX_PARAMETER_LENGTH = 128;

    private ApprovalMigrationDiagnosticsParameters() {
    }

    static InstanceCriteria parse(
        String tenantId,
        UUID planId,
        MultiValueMap<String, String> parameters
    ) {
        Objects.requireNonNull(parameters, "parameters must not be null");
        for (Map.Entry<String, java.util.List<String>> entry : parameters.entrySet()) {
            if (!ALLOWED.contains(entry.getKey())) {
                throw invalid("unsupported query parameter");
            }
            if (entry.getValue() == null || entry.getValue().size() != 1) {
                throw invalid("duplicate query parameter");
            }
            String value = entry.getValue().getFirst();
            if (value == null || value.isBlank() || value.length() > MAX_PARAMETER_LENGTH) {
                throw invalid("query parameter is blank or too long");
            }
        }
        return new InstanceCriteria(
            tenantId,
            planId,
            uuid(optional(parameters, "instanceId"), "instanceId"),
            enumeration(optional(parameters, "status"), AttemptStatusFilter.class, "status"),
            enumeration(optional(parameters, "failureClass"), FailureClass.class, "failureClass"),
            enumeration(
                optional(parameters, "reconciliationState"),
                ReconciliationState.class,
                "reconciliationState"
            ),
            timestamp(optional(parameters, "from"), "from"),
            timestamp(optional(parameters, "to"), "to"),
            enumeration(
                optional(parameters, "sort"),
                InstanceSort.class,
                "sort",
                InstanceSort.SEQUENCE_ASC
            ),
            integer(optional(parameters, "page"), "page", 1),
            integer(optional(parameters, "pageSize"), "pageSize", 50)
        );
    }

    private static String optional(MultiValueMap<String, String> parameters, String name) {
        java.util.List<String> values = parameters.get(name);
        return values == null ? null : values.getFirst();
    }

    private static int integer(String value, String name, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw invalid(name + " must be an integer");
        }
    }

    private static UUID uuid(String value, String name) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw invalid(name + " must be a UUID");
        }
    }

    private static OffsetDateTime timestamp(String value, String name) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            throw invalid(name + " must include an explicit ISO-8601 offset");
        }
    }

    private static <T extends Enum<T>> T enumeration(
        String value,
        Class<T> type,
        String name
    ) {
        return enumeration(value, type, name, null);
    }

    private static <T extends Enum<T>> T enumeration(
        String value,
        Class<T> type,
        String name,
        T defaultValue
    ) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw invalid(name + " is outside the closed set");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
