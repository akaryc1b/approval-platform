package io.github.akaryc1b.approval.application.port;

import java.util.Objects;

/** Supplies server-owned request and actor evidence to non-web application components. */
@FunctionalInterface
public interface ApprovalRequestEvidenceProvider {

    RequestEvidence current();

    record RequestEvidence(String actorId, String requestId, String traceId) {
        public RequestEvidence {
            actorId = requireText(actorId, "actorId", 200);
            requestId = requireText(requestId, "requestId", 128);
            traceId = normalizeOptional(traceId, 128);
        }

        private static String requireText(String value, String name, int maximumLength) {
            Objects.requireNonNull(value, name + " must not be null");
            String normalized = value.trim();
            if (normalized.isEmpty() || normalized.length() > maximumLength) {
                throw new IllegalArgumentException(name + " must be non-blank and bounded");
            }
            return normalized;
        }

        private static String normalizeOptional(String value, int maximumLength) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String normalized = value.trim();
            if (normalized.length() > maximumLength) {
                throw new IllegalArgumentException("traceId must be bounded");
            }
            return normalized;
        }
    }
}
