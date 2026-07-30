package io.github.akaryc1b.approval.ai.spi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Metadata-only input for offline Provider transport mapping. */
public record AiProviderTransportMappingRequest(
    String mapperId,
    String mapperVersion,
    AiVersionReferences.ProviderVersion providerVersion,
    AiCapability capability,
    String endpointAuthorizationKey,
    String protocolProfileAuthorizationKey,
    String requestSchemaHash,
    String responseSchemaHash,
    String canonicalPayloadHash,
    String signingInputHash,
    int canonicalPayloadBytes,
    int fieldCount,
    Duration timeout,
    boolean cancellationRequested,
    boolean rawPayloadPresent,
    boolean secretMaterialAvailable,
    boolean networkDispatchAuthorized,
    boolean providerInvocationAuthorized,
    boolean productionEnablementAuthorized
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AiProviderTransportMappingRequest {
        mapperId = requireText(mapperId, "mapperId", 160);
        mapperVersion = requireText(mapperVersion, "mapperVersion", 120);
        providerVersion = Objects.requireNonNull(
            providerVersion,
            "providerVersion must not be null"
        );
        capability = Objects.requireNonNull(capability, "capability must not be null");
        endpointAuthorizationKey = requireText(
            endpointAuthorizationKey,
            "endpointAuthorizationKey",
            500
        );
        protocolProfileAuthorizationKey = requireText(
            protocolProfileAuthorizationKey,
            "protocolProfileAuthorizationKey",
            360
        );
        requestSchemaHash = requireSha256(requestSchemaHash, "requestSchemaHash");
        responseSchemaHash = requireSha256(responseSchemaHash, "responseSchemaHash");
        canonicalPayloadHash = requireSha256(
            canonicalPayloadHash,
            "canonicalPayloadHash"
        );
        signingInputHash = requireSha256(signingInputHash, "signingInputHash");
        if (canonicalPayloadBytes < 0 || canonicalPayloadBytes > 16_777_216) {
            throw new IllegalArgumentException("canonicalPayloadBytes must be bounded");
        }
        if (fieldCount < 0 || fieldCount > 500) {
            throw new IllegalArgumentException("fieldCount must be bounded");
        }
        timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero()
            || timeout.isNegative()
            || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("timeout must be positive and bounded");
        }
        if (rawPayloadPresent || secretMaterialAvailable) {
            throw new IllegalArgumentException(
                "transport mapping requests must contain hashes and metadata only"
            );
        }
        if (networkDispatchAuthorized || providerInvocationAuthorized) {
            throw new IllegalArgumentException(
                "M6-D transport mapping cannot authorize dispatch or Provider invocation"
            );
        }
        if (productionEnablementAuthorized) {
            throw new IllegalArgumentException(
                "M6-D transport mapping cannot authorize production enablement"
            );
        }
    }

    public String mapperAuthorizationKey() {
        return mapperId + "/" + mapperVersion;
    }

    public String requestEvidenceHash() {
        String canonical = String.join(
            "\n",
            mapperAuthorizationKey(),
            providerVersion.providerId(),
            providerVersion.version(),
            capability.name(),
            endpointAuthorizationKey,
            protocolProfileAuthorizationKey,
            requestSchemaHash,
            responseSchemaHash,
            canonicalPayloadHash,
            signingInputHash,
            Integer.toString(canonicalPayloadBytes),
            Integer.toString(fieldCount),
            Long.toString(timeout.toMillis()),
            Boolean.toString(cancellationRequested)
        );
        return sha256(canonical);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
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
