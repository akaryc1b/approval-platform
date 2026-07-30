package io.github.akaryc1b.approval.engine;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Public-engine, read-only, one-instance bounded verification port. */
public interface ProcessInstanceVerificationPort {

    ApprovalMigrationEngineSnapshot readOne(VerificationCommand command);

    record VerificationCommand(
        String tenantId,
        String engineInstanceId,
        List<String> allowlistedVariableNames
    ) {
        public VerificationCommand {
            tenantId = requireText(tenantId, "tenantId", 128);
            engineInstanceId = requireText(engineInstanceId, "engineInstanceId", 256);
            allowlistedVariableNames = canonicalAllowlist(allowlistedVariableNames);
        }
    }

    final class VerificationReadException extends RuntimeException {
        private final String stableCode;

        public VerificationReadException(String stableCode, String message, Throwable cause) {
            super(requireText(message, "message", 1000), cause);
            this.stableCode = requireText(stableCode, "stableCode", 96);
        }

        public String stableCode() {
            return stableCode;
        }
    }

    private static List<String> canonicalAllowlist(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > 32) {
            throw new IllegalArgumentException("allowlistedVariableNames exceeds maximum size 32");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            normalized.add(requireText(value, "allowlistedVariableNames", 128));
        }
        ArrayList<String> sorted = new ArrayList<>(normalized);
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    private static String requireText(String value, String name, int maximum) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " is blank or exceeds maximum length " + maximum);
        }
        return normalized;
    }
}
