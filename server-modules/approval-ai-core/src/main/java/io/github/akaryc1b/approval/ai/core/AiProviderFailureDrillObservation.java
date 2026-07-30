package io.github.akaryc1b.approval.ai.core;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Precomputed zero-call readiness evidence for one offline fault drill case. */
public record AiProviderFailureDrillObservation(
    String caseId,
    String fixtureHash,
    AiProviderDeploymentReadinessReport readinessReport
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AiProviderFailureDrillObservation {
        caseId = requireText(caseId, "caseId", 160);
        fixtureHash = requireSha256(fixtureHash, "fixtureHash");
        readinessReport = Objects.requireNonNull(
            readinessReport,
            "readinessReport must not be null"
        );
    }

    private static String requireSha256(String value, String name) {
        String normalized = requireText(value, name, 64).toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hash");
        }
        return normalized;
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return normalized;
    }
}
