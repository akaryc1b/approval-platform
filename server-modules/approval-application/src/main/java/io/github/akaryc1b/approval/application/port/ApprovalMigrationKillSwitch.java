package io.github.akaryc1b.approval.application.port;

import java.util.Objects;

/** Server-owned immutable kill-switch snapshot used only before new engine dispatch. */
public interface ApprovalMigrationKillSwitch {

    Snapshot snapshot();

    record Snapshot(
        long revision,
        boolean enabled,
        String reasonCode,
        String evidenceHash
    ) {
        public Snapshot {
            if (revision < 1) {
                throw new IllegalArgumentException("kill-switch revision must be positive");
            }
            reasonCode = requireText(reasonCode, "reasonCode", 64);
            evidenceHash = requireHash(evidenceHash, "evidenceHash");
        }
    }

    private static String requireHash(String value, String name) {
        String normalized = requireText(value, name, 64);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return normalized;
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
