package io.github.akaryc1b.approval.application.port;

import java.util.List;
import java.util.Objects;

/**
 * Server-authoritative approval identity directory backed by an organization connector.
 */
public interface ApprovalIdentityDirectory {

    List<IdentityCandidate> search(IdentitySearch search);

    IdentityCandidate requireUser(IdentityLookup lookup);

    record IdentityReference(String source, String objectType, String value) {
        public IdentityReference {
            source = requireText(source, "source");
            objectType = requireText(objectType, "objectType");
            value = requireText(value, "value");
        }

        public String canonicalValue() {
            return source + ":" + objectType + ":" + value;
        }
    }

    record IdentitySearch(
        String tenantId,
        String connectorKey,
        String requestId,
        String traceId,
        String keyword,
        boolean activeOnly,
        int limit
    ) {
        public IdentitySearch {
            tenantId = requireText(tenantId, "tenantId");
            connectorKey = requireText(connectorKey, "connectorKey");
            requestId = requireText(requestId, "requestId");
            traceId = normalizeOptional(traceId);
            keyword = normalizeOptional(keyword);
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("limit must be between 1 and 100");
            }
        }
    }

    record IdentityLookup(
        String tenantId,
        String connectorKey,
        String requestId,
        String traceId,
        IdentityReference reference,
        boolean requireActive
    ) {
        public IdentityLookup {
            tenantId = requireText(tenantId, "tenantId");
            connectorKey = requireText(connectorKey, "connectorKey");
            requestId = requireText(requestId, "requestId");
            traceId = normalizeOptional(traceId);
            reference = Objects.requireNonNull(reference, "reference must not be null");
        }
    }

    record IdentityCandidate(
        IdentityReference reference,
        String userId,
        String username,
        String displayName,
        String email,
        String mobile,
        boolean active,
        List<String> departmentIds,
        List<String> roleCodes,
        List<String> positionCodes
    ) {
        public IdentityCandidate {
            reference = Objects.requireNonNull(reference, "reference must not be null");
            userId = requireText(userId, "userId");
            username = requireText(username, "username");
            displayName = requireText(displayName, "displayName");
            email = normalizeOptional(email);
            mobile = normalizeOptional(mobile);
            departmentIds = departmentIds == null ? List.of() : List.copyOf(departmentIds);
            roleCodes = roleCodes == null ? List.of() : List.copyOf(roleCodes);
            positionCodes = positionCodes == null ? List.of() : List.copyOf(positionCodes);
        }
    }

    final class IdentityResolutionException extends RuntimeException {

        private final String code;
        private final boolean retryable;

        public IdentityResolutionException(String code, String message, boolean retryable) {
            super(requireText(message, "message"));
            this.code = requireText(code, "code");
            this.retryable = retryable;
        }

        public String code() {
            return code;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
