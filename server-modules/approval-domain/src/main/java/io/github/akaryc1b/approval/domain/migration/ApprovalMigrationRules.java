package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Package-private validation shared by immutable migration protocol records. */
final class ApprovalMigrationRules {

    private ApprovalMigrationRules() {
    }

    static void requireTransition(boolean allowed, String type, Enum<?> from, Enum<?> to) {
        Objects.requireNonNull(from, "from state must not be null");
        Objects.requireNonNull(to, "to state must not be null");
        if (!allowed) {
            throw new IllegalArgumentException(type + " transition is not permitted: " + from + " -> " + to);
        }
    }

    static void validateAttemptEvidence(
        AttemptStatus status,
        EngineOutcome engineOutcome,
        String leaseOwner,
        java.time.Instant leaseUntil,
        String requestReference,
        FailureClass failureClass,
        String errorSummary
    ) {
        if (status == AttemptStatus.CLAIMED) {
            if (leaseOwner == null || leaseUntil == null) {
                throw new IllegalArgumentException("claimed attempt requires lease evidence");
            }
        } else if (leaseOwner != null || leaseUntil != null) {
            throw new IllegalArgumentException("only a claimed attempt can retain a lease");
        }
        boolean requestExpected = status == AttemptStatus.ENGINE_REQUESTED
            || status == AttemptStatus.VERIFYING || status == AttemptStatus.UNKNOWN
            || status == AttemptStatus.RECONCILING || status == AttemptStatus.SUCCEEDED;
        if (requestExpected != (requestReference != null)) {
            throw new IllegalArgumentException("attempt engine request evidence is inconsistent");
        }
        if (status == AttemptStatus.UNKNOWN
            && (engineOutcome != EngineOutcome.UNKNOWN
                || failureClass != FailureClass.ENGINE_OUTCOME_UNKNOWN)) {
            throw new IllegalArgumentException("UNKNOWN requires durable engine-outcome-unknown evidence");
        }
        boolean failed = status == AttemptStatus.BLOCKED_STALE
            || status == AttemptStatus.FAILED_RETRYABLE
            || status == AttemptStatus.FAILED_TERMINAL
            || status == AttemptStatus.UNKNOWN
            || status == AttemptStatus.RECONCILING;
        if (failed != (failureClass != FailureClass.NONE && errorSummary != null)) {
            throw new IllegalArgumentException("attempt failure evidence is inconsistent");
        }
    }

    static List<String> canonicalKeys(List<String> values, String name) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            normalized.add(requireText(value, name, 128));
        }
        ArrayList<String> sorted = new ArrayList<>(normalized);
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
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

    static String optionalHash(String value, String name) {
        return value == null || value.isBlank() ? null : requireHash(value, name);
    }

    static String requireText(String value, String name, int maximum) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " must be non-blank and at most " + maximum);
        }
        return normalized;
    }

    static String optionalText(String value, String name, int maximum) {
        return value == null || value.isBlank() ? null : requireText(value, name, maximum);
    }
}
