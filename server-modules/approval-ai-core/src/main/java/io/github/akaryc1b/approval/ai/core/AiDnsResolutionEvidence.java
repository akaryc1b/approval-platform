package io.github.akaryc1b.approval.ai.core;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Precomputed DNS safety evidence. This type performs no DNS or network operation. */
public record AiDnsResolutionEvidence(
    String endpointAuthorizationKey,
    String host,
    Set<String> addressEvidenceHashes,
    Status status,
    int observedTtlSeconds,
    String evidenceHash,
    boolean dnsLookupPerformed,
    boolean networkCallAttempted
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern HOST = Pattern.compile(
        "(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}"
    );

    public AiDnsResolutionEvidence {
        endpointAuthorizationKey = requireText(
            endpointAuthorizationKey,
            "endpointAuthorizationKey",
            500
        );
        host = requireHost(host);
        addressEvidenceHashes = addressEvidenceHashes == null
            ? Set.of()
            : addressEvidenceHashes.stream()
                .map(value -> requireSha256(value, "addressEvidenceHash"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (addressEvidenceHashes.size() > 32) {
            throw new IllegalArgumentException("addressEvidenceHashes must be bounded");
        }
        status = Objects.requireNonNull(status, "status must not be null");
        if (observedTtlSeconds < 0 || observedTtlSeconds > 86_400) {
            throw new IllegalArgumentException("observedTtlSeconds must be bounded");
        }
        evidenceHash = requireSha256(evidenceHash, "evidenceHash");
        if (dnsLookupPerformed || networkCallAttempted) {
            throw new IllegalArgumentException(
                "M6-D DNS evidence must be precomputed and zero-call"
            );
        }
        if (status == Status.PUBLIC_SET_MATCHED
            && (addressEvidenceHashes.isEmpty() || observedTtlSeconds == 0)) {
            throw new IllegalArgumentException(
                "matched DNS evidence requires bounded addresses and TTL"
            );
        }
    }

    public enum Status {
        PUBLIC_SET_MATCHED,
        PRIVATE_OR_LOCAL_ADDRESS,
        REBINDING_DETECTED,
        EMPTY_RESULT,
        UNKNOWN
    }

    private static String requireHost(String value) {
        String normalized = requireText(value, "host", 253).toLowerCase();
        if (!HOST.matcher(normalized).matches()) {
            throw new IllegalArgumentException("host must be an exact public DNS name");
        }
        return normalized;
    }

    private static String requireSha256(String value, String name) {
        String normalized = requireText(value, name, 64).toLowerCase();
        if (!SHA256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a SHA-256 hex digest");
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
