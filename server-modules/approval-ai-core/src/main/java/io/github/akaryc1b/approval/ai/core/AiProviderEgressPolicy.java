package io.github.akaryc1b.approval.ai.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact endpoint allowlist metadata. This contract performs no egress or DNS operation. */
public record AiProviderEgressPolicy(
    String policyId,
    String policyVersion,
    String declaredContentHash,
    Set<AiProviderEndpointDescriptor> allowedEndpoints,
    boolean dnsRebindingProtectionRequired,
    boolean certificateValidationRequired,
    boolean proxyBypassAllowed,
    boolean networkEgressAuthorized
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AiProviderEgressPolicy {
        policyId = requireText(policyId, "policyId", 160);
        policyVersion = requireText(policyVersion, "policyVersion", 120);
        declaredContentHash = requireSha256(declaredContentHash, "declaredContentHash");
        allowedEndpoints = allowedEndpoints == null ? Set.of() : Set.copyOf(allowedEndpoints);
        if (allowedEndpoints.isEmpty() || allowedEndpoints.size() > 100) {
            throw new IllegalArgumentException("allowedEndpoints must be non-empty and bounded");
        }
        if (!dnsRebindingProtectionRequired) {
            throw new IllegalArgumentException("egress policy must require DNS rebinding protection");
        }
        if (!certificateValidationRequired) {
            throw new IllegalArgumentException("egress policy must require certificate validation");
        }
        if (proxyBypassAllowed) {
            throw new IllegalArgumentException("egress policy cannot allow proxy bypass");
        }
        if (networkEgressAuthorized) {
            throw new IllegalArgumentException(
                "M6-D egress policy metadata cannot authorize network egress"
            );
        }
    }

    public static AiProviderEgressPolicy create(
        String policyId,
        String policyVersion,
        Set<AiProviderEndpointDescriptor> allowedEndpoints
    ) {
        String hash = computeContentHash(policyId, policyVersion, allowedEndpoints);
        return new AiProviderEgressPolicy(
            policyId,
            policyVersion,
            hash,
            allowedEndpoints,
            true,
            true,
            false,
            false
        );
    }

    public String authorizationKey() {
        return policyId + "/" + policyVersion;
    }

    public boolean contentHashMatches() {
        return declaredContentHash.equals(
            computeContentHash(policyId, policyVersion, allowedEndpoints)
        );
    }

    public boolean allows(AiProviderEndpointDescriptor endpoint) {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        return contentHashMatches() && allowedEndpoints.contains(endpoint);
    }

    private static String computeContentHash(
        String policyId,
        String policyVersion,
        Set<AiProviderEndpointDescriptor> allowedEndpoints
    ) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, "policyId", requireText(policyId, "policyId", 160));
        append(canonical, "policyVersion", requireText(policyVersion, "policyVersion", 120));
        List<AiProviderEndpointDescriptor> endpoints = allowedEndpoints == null
            ? List.of()
            : allowedEndpoints.stream()
                .sorted(Comparator.comparing(AiProviderEndpointDescriptor::endpointId))
                .toList();
        for (AiProviderEndpointDescriptor endpoint : endpoints) {
            append(canonical, "endpointId", endpoint.endpointId());
            append(canonical, "providerId", endpoint.providerVersion().providerId());
            append(canonical, "providerVersion", endpoint.providerVersion().version());
            append(canonical, "endpoint", endpoint.authorizationKey());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static void append(StringBuilder target, String key, Object value) {
        String text = String.valueOf(value);
        target.append(key.length()).append(':').append(key)
            .append('=').append(text.length()).append(':').append(text).append(';');
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
