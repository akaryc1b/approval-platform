package io.github.akaryc1b.approval.domain.migration;

import java.util.Objects;

final class ApprovalMigrationPlanRules {

    private ApprovalMigrationPlanRules() {
    }

    static void requirePositive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    static String requireHash(String value, String name) {
        String normalized = requireText(value, name, 64);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return normalized;
    }

    static String requireText(String value, String name, int maximum) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " exceeds maximum length " + maximum);
        }
        return normalized;
    }

    static String optionalText(String value, String name, int maximum) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireText(value, name, maximum);
    }
}
