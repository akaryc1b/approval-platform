package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .StableFailureCode;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts
    .Capability;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts
    .DiagnosticsCriteria;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts
    .InvocationOutcome;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts.Sort;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts
    .ProviderApiFamily;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts
    .TransportProfile;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict allowlisted query decoder with no wildcard or arbitrary search. */
final class ConnectorOperationsDiagnosticsParameters {

    private static final Set<String> ALLOWED = Set.of(
        "pageSize",
        "pageToken",
        "sort",
        "provider",
        "capability",
        "connectorOperation",
        "apiFamily",
        "transportProfile",
        "invocationOutcome",
        "dispatchAttempted",
        "stableFailureCode"
    );
    private static final int MAX_VALUE_LENGTH = 1_024;

    private ConnectorOperationsDiagnosticsParameters() {
    }

    static DiagnosticsCriteria parse(MultiValueMap<String, String> parameters) {
        Objects.requireNonNull(parameters, "parameters must not be null");
        for (Map.Entry<String, List<String>> entry : parameters.entrySet()) {
            if (!ALLOWED.contains(entry.getKey())) {
                throw new IllegalArgumentException("unsupported query parameter");
            }
            if (entry.getValue() == null || entry.getValue().size() != 1) {
                throw new IllegalArgumentException("duplicate query parameter");
            }
            String value = entry.getValue().getFirst();
            if (value == null || value.isBlank() || value.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("query parameter is blank or too long");
            }
            if (value.contains("*") || value.contains("%") || value.contains("?")) {
                throw new IllegalArgumentException("wildcard search is not supported");
            }
        }
        return new DiagnosticsCriteria(
            integer(optional(parameters, "pageSize"), 50),
            optional(parameters, "pageToken"),
            optional(parameters, "provider"),
            enumeration(optional(parameters, "capability"), Capability.class),
            enumeration(optional(parameters, "connectorOperation"), ConnectorOperation.class),
            enumeration(optional(parameters, "apiFamily"), ProviderApiFamily.class),
            enumeration(optional(parameters, "transportProfile"), TransportProfile.class),
            enumeration(optional(parameters, "invocationOutcome"), InvocationOutcome.class),
            bool(optional(parameters, "dispatchAttempted")),
            enumeration(optional(parameters, "stableFailureCode"), StableFailureCode.class),
            enumeration(optional(parameters, "sort"), Sort.class, Sort.CREATED_AT_DESC)
        );
    }

    private static String optional(MultiValueMap<String, String> parameters, String name) {
        List<String> values = parameters.get(name);
        return values == null ? null : values.getFirst();
    }

    private static int integer(String value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("pageSize must be an integer");
        }
    }

    private static Boolean bool(String value) {
        if (value == null) {
            return null;
        }
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException("dispatchAttempted must be true or false");
    }

    private static <T extends Enum<T>> T enumeration(String value, Class<T> type) {
        return enumeration(value, type, null);
    }

    private static <T extends Enum<T>> T enumeration(
        String value,
        Class<T> type,
        T defaultValue
    ) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("query value is outside the closed set");
        }
    }
}
