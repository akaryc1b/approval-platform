package io.github.akaryc1b.approval.ai.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** Immutable policy for hash-only Provider payload canonicalization evidence. */
public record AiProviderPayloadCanonicalizationPolicy(
    String policyId,
    String policyVersion,
    int maximumFields,
    int maximumCanonicalBytes,
    Set<DataClassification> allowedClassifications,
    boolean confidentialRedactionRequired,
    boolean rawValueStorageAllowed,
    boolean secretMaterialAllowed,
    boolean signatureComputationAuthorized,
    boolean networkAccessAuthorized,
    boolean productionEnablementAuthorized
) {

    public AiProviderPayloadCanonicalizationPolicy {
        policyId = requireText(policyId, "policyId", 160);
        policyVersion = requireText(policyVersion, "policyVersion", 120);
        if (maximumFields < 1 || maximumFields > 500) {
            throw new IllegalArgumentException("maximumFields must be bounded");
        }
        if (maximumCanonicalBytes < 1 || maximumCanonicalBytes > 16_777_216) {
            throw new IllegalArgumentException("maximumCanonicalBytes must be bounded");
        }
        allowedClassifications = allowedClassifications == null
            ? Set.of()
            : Set.copyOf(allowedClassifications);
        if (allowedClassifications.isEmpty()
            || allowedClassifications.size() > DataClassification.values().length) {
            throw new IllegalArgumentException(
                "allowedClassifications must be non-empty and bounded"
            );
        }
        if (!confidentialRedactionRequired) {
            throw new IllegalArgumentException(
                "confidential and restricted fields must require redaction"
            );
        }
        if (rawValueStorageAllowed || secretMaterialAllowed) {
            throw new IllegalArgumentException(
                "M6-D canonicalization cannot authorize raw values or Secret material"
            );
        }
        if (signatureComputationAuthorized || networkAccessAuthorized) {
            throw new IllegalArgumentException(
                "M6-D canonicalization cannot authorize signing or network access"
            );
        }
        if (productionEnablementAuthorized) {
            throw new IllegalArgumentException(
                "M6-D canonicalization cannot authorize production enablement"
            );
        }
    }

    public String authorizationKey() {
        return policyId + "/" + policyVersion;
    }

    public String policyHash() {
        String canonical = authorizationKey()
            + "\n" + maximumFields
            + "\n" + maximumCanonicalBytes
            + "\n" + confidentialRedactionRequired
            + "\n" + allowedClassifications.stream()
                .map(Enum::name)
                .sorted()
                .reduce("", (left, right) -> left + "\n" + right);
        return sha256(canonical);
    }

    public enum DataClassification {
        PUBLIC,
        INTERNAL,
        CONFIDENTIAL,
        RESTRICTED
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

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return normalized;
    }
}
