package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationKillSwitch;

import java.util.Objects;

/** Immutable server configuration snapshot. Changing it requires a new revision and restart. */
public final class ConfiguredApprovalMigrationKillSwitch implements ApprovalMigrationKillSwitch {

    private final Snapshot snapshot;

    public ConfiguredApprovalMigrationKillSwitch(
        boolean enabled,
        long revision,
        String reasonCode,
        ApprovalReleasePackageHasher hasher
    ) {
        ApprovalReleasePackageHasher safeHasher = Objects.requireNonNull(
            hasher,
            "hasher must not be null"
        );
        String normalizedReason = requireText(reasonCode, "reasonCode", 64);
        snapshot = new Snapshot(
            revision,
            enabled,
            normalizedReason,
            safeHasher.hashValues(
                "M5-D7-KILL-SWITCH-SNAPSHOT-V1",
                revision,
                enabled,
                normalizedReason
            )
        );
    }

    @Override
    public Snapshot snapshot() {
        return snapshot;
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
