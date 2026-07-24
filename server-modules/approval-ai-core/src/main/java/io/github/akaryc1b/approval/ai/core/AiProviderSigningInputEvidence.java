package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Canonical request-signing input metadata without a signature or Secret material. */
public record AiProviderSigningInputEvidence(
    AiVersionReferences.ProviderVersion providerVersion,
    Method method,
    String endpointAuthorizationKey,
    String mapperAuthorizationKey,
    String protocolProfileAuthorizationKey,
    String requestSchemaHash,
    String canonicalPayloadHash,
    String nonceHash,
    long createdAtEpochSecond,
    Set<String> signedHeaderNames,
    Scheme scheme,
    String signingInputHash,
    boolean signatureComputed,
    boolean secretMaterialAccessed,
    boolean networkCallAttempted,
    boolean productionEnablementAuthorized
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern HEADER = Pattern.compile("[a-z0-9-]{1,80}");
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
        "authorization",
        "cookie",
        "proxy-authorization",
        "x-api-key"
    );

    public AiProviderSigningInputEvidence {
        providerVersion = Objects.requireNonNull(
            providerVersion,
            "providerVersion must not be null"
        );
        method = Objects.requireNonNull(method, "method must not be null");
        endpointAuthorizationKey = requireText(
            endpointAuthorizationKey,
            "endpointAuthorizationKey",
            500
        );
        mapperAuthorizationKey = requireText(
            mapperAuthorizationKey,
            "mapperAuthorizationKey",
            300
        );
        protocolProfileAuthorizationKey = requireText(
            protocolProfileAuthorizationKey,
            "protocolProfileAuthorizationKey",
            360
        );
        requestSchemaHash = requireSha256(requestSchemaHash, "requestSchemaHash");
        canonicalPayloadHash = requireSha256(
            canonicalPayloadHash,
            "canonicalPayloadHash"
        );
        nonceHash = requireSha256(nonceHash, "nonceHash");
        if (createdAtEpochSecond < 0) {
            throw new IllegalArgumentException("createdAtEpochSecond must not be negative");
        }
        signedHeaderNames = signedHeaderNames == null
            ? Set.of()
            : signedHeaderNames.stream()
                .map(AiProviderSigningInputEvidence::requireHeaderName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (signedHeaderNames.isEmpty() || signedHeaderNames.size() > 20) {
            throw new IllegalArgumentException(
                "signedHeaderNames must be non-empty and bounded"
            );
        }
        scheme = Objects.requireNonNull(scheme, "scheme must not be null");
        signingInputHash = requireSha256(signingInputHash, "signingInputHash");
        if (signatureComputed || secretMaterialAccessed || networkCallAttempted) {
            throw new IllegalArgumentException(
                "M6-D signing input evidence cannot compute signatures or access runtime data"
            );
        }
        if (productionEnablementAuthorized) {
            throw new IllegalArgumentException(
                "signing input evidence cannot authorize production enablement"
            );
        }
        if (!signingInputHash.equals(computeHash(
            providerVersion,
            method,
            endpointAuthorizationKey,
            mapperAuthorizationKey,
            protocolProfileAuthorizationKey,
            requestSchemaHash,
            canonicalPayloadHash,
            nonceHash,
            createdAtEpochSecond,
            signedHeaderNames,
            scheme
        ))) {
            throw new IllegalArgumentException(
                "signingInputHash must match exact canonical signing metadata"
            );
        }
    }

    public static AiProviderSigningInputEvidence create(
        AiVersionReferences.ProviderVersion providerVersion,
        Method method,
        String endpointAuthorizationKey,
        String mapperAuthorizationKey,
        String protocolProfileAuthorizationKey,
        String requestSchemaHash,
        String canonicalPayloadHash,
        String nonceHash,
        long createdAtEpochSecond,
        Set<String> signedHeaderNames,
        Scheme scheme
    ) {
        String hash = computeHash(
            providerVersion,
            method,
            endpointAuthorizationKey,
            mapperAuthorizationKey,
            protocolProfileAuthorizationKey,
            requestSchemaHash,
            canonicalPayloadHash,
            nonceHash,
            createdAtEpochSecond,
            signedHeaderNames,
            scheme
        );
        return new AiProviderSigningInputEvidence(
            providerVersion,
            method,
            endpointAuthorizationKey,
            mapperAuthorizationKey,
            protocolProfileAuthorizationKey,
            requestSchemaHash,
            canonicalPayloadHash,
            nonceHash,
            createdAtEpochSecond,
            signedHeaderNames,
            scheme,
            hash,
            false,
            false,
            false,
            false
        );
    }

    public enum Method {
        POST
    }

    public enum Scheme {
        HMAC_SHA256_REFERENCE_ONLY,
        ASYMMETRIC_REFERENCE_ONLY
    }

    private static String computeHash(
        AiVersionReferences.ProviderVersion providerVersion,
        Method method,
        String endpointAuthorizationKey,
        String mapperAuthorizationKey,
        String protocolProfileAuthorizationKey,
        String requestSchemaHash,
        String canonicalPayloadHash,
        String nonceHash,
        long createdAtEpochSecond,
        Set<String> signedHeaderNames,
        Scheme scheme
    ) {
        Objects.requireNonNull(providerVersion, "providerVersion must not be null");
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(scheme, "scheme must not be null");
        Set<String> headers = signedHeaderNames == null
            ? Set.of()
            : signedHeaderNames.stream()
                .map(AiProviderSigningInputEvidence::requireHeaderName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        StringBuilder canonical = new StringBuilder();
        append(canonical, providerVersion.providerId());
        append(canonical, providerVersion.version());
        append(canonical, method.name());
        append(canonical, requireText(endpointAuthorizationKey, "endpoint", 500));
        append(canonical, requireText(mapperAuthorizationKey, "mapper", 300));
        append(canonical, requireText(protocolProfileAuthorizationKey, "profile", 360));
        append(canonical, requireSha256(requestSchemaHash, "requestSchemaHash"));
        append(canonical, requireSha256(canonicalPayloadHash, "canonicalPayloadHash"));
        append(canonical, requireSha256(nonceHash, "nonceHash"));
        append(canonical, Long.toString(createdAtEpochSecond));
        headers.stream().sorted(Comparator.naturalOrder()).forEach(value -> append(
            canonical,
            value
        ));
        append(canonical, scheme.name());
        return sha256(canonical.toString());
    }

    private static String requireHeaderName(String value) {
        String header = requireText(value, "signedHeaderName", 80)
            .toLowerCase(Locale.ROOT);
        if (!HEADER.matcher(header).matches() || SENSITIVE_HEADERS.contains(header)) {
            throw new IllegalArgumentException(
                "signed header names must be safe lowercase metadata names"
            );
        }
        return header;
    }

    private static void append(StringBuilder target, String value) {
        String normalized = Objects.requireNonNull(value, "canonical value must not be null");
        target.append(normalized.length()).append(':').append(normalized).append('\n');
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
