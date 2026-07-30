package io.github.akaryc1b.approval.ai.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact endpoint pinning policy metadata with no network authority. */
public record AiEndpointTrustPolicy(
    String policyId,
    String policyVersion,
    String endpointAuthorizationKey,
    String exactHost,
    Set<String> allowedAddressEvidenceHashes,
    Set<String> allowedCertificateSpkiHashes,
    int maximumTtlSeconds,
    boolean redirectsAllowed,
    boolean rebindingAllowed,
    boolean privateAddressAllowed,
    boolean networkAccessAuthorized,
    boolean productionEnablementAuthorized
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AiEndpointTrustPolicy {
        policyId = requireText(policyId, "policyId", 160);
        policyVersion = requireText(policyVersion, "policyVersion", 120);
        endpointAuthorizationKey = requireText(
            endpointAuthorizationKey,
            "endpointAuthorizationKey",
            500
        );
        exactHost = requireText(exactHost, "exactHost", 253).toLowerCase();
        allowedAddressEvidenceHashes = boundedHashes(
            allowedAddressEvidenceHashes,
            "allowedAddressEvidenceHashes",
            32
        );
        allowedCertificateSpkiHashes = boundedHashes(
            allowedCertificateSpkiHashes,
            "allowedCertificateSpkiHashes",
            16
        );
        if (maximumTtlSeconds < 1 || maximumTtlSeconds > 86_400) {
            throw new IllegalArgumentException("maximumTtlSeconds must be bounded");
        }
        if (redirectsAllowed || rebindingAllowed || privateAddressAllowed) {
            throw new IllegalArgumentException(
                "M6-D endpoint trust policies must fail closed"
            );
        }
        if (networkAccessAuthorized) {
            throw new IllegalArgumentException(
                "endpoint trust policy cannot authorize network access"
            );
        }
        if (productionEnablementAuthorized) {
            throw new IllegalArgumentException(
                "endpoint trust policy cannot authorize production enablement"
            );
        }
    }

    public String contentHash() {
        StringBuilder canonical = new StringBuilder();
        append(canonical, policyId);
        append(canonical, policyVersion);
        append(canonical, endpointAuthorizationKey);
        append(canonical, exactHost);
        allowedAddressEvidenceHashes.stream().sorted().forEach(value -> append(canonical, value));
        allowedCertificateSpkiHashes.stream().sorted().forEach(value -> append(canonical, value));
        append(canonical, Integer.toString(maximumTtlSeconds));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static Set<String> boundedHashes(Set<String> values, String name, int maximumSize) {
        Set<String> copy = values == null
            ? Set.of()
            : values.stream()
                .map(value -> requireSha256(value, name))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (copy.isEmpty() || copy.size() > maximumSize) {
            throw new IllegalArgumentException(name + " must be non-empty and bounded");
        }
        return copy;
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append(';');
    }

    private static String requireSha256(String value, String name) {
        String normalized = requireText(value, name, 64).toLowerCase();
        if (!SHA256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must contain SHA-256 hex digests");
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
