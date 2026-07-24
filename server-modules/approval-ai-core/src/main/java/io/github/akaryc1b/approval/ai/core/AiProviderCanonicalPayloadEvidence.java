package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Canonical hash evidence for a minimized Provider payload without raw field values. */
public record AiProviderCanonicalPayloadEvidence(
    String policyAuthorizationKey,
    String policyHash,
    AiVersionReferences.ProviderVersion providerVersion,
    AiCapability capability,
    String requestSchemaHash,
    List<FieldEvidence> fields,
    int totalCanonicalBytes,
    String canonicalPayloadHash,
    boolean rawPayloadStored,
    boolean secretMaterialPresent,
    boolean productionEnablementAuthorized
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AiProviderCanonicalPayloadEvidence {
        policyAuthorizationKey = requireText(
            policyAuthorizationKey,
            "policyAuthorizationKey",
            300
        );
        policyHash = requireSha256(policyHash, "policyHash");
        providerVersion = Objects.requireNonNull(
            providerVersion,
            "providerVersion must not be null"
        );
        capability = Objects.requireNonNull(capability, "capability must not be null");
        requestSchemaHash = requireSha256(requestSchemaHash, "requestSchemaHash");
        fields = fields == null ? List.of() : List.copyOf(fields);
        if (fields.size() > 500) {
            throw new IllegalArgumentException("fields must be bounded");
        }
        if (totalCanonicalBytes < 0 || totalCanonicalBytes > 16_777_216) {
            throw new IllegalArgumentException("totalCanonicalBytes must be bounded");
        }
        canonicalPayloadHash = requireSha256(
            canonicalPayloadHash,
            "canonicalPayloadHash"
        );
        if (rawPayloadStored || secretMaterialPresent) {
            throw new IllegalArgumentException(
                "canonical payload evidence cannot contain raw values or Secret material"
            );
        }
        if (productionEnablementAuthorized) {
            throw new IllegalArgumentException(
                "canonical payload evidence cannot authorize production enablement"
            );
        }
        String computed = computeHash(
            policyAuthorizationKey,
            policyHash,
            providerVersion,
            capability,
            requestSchemaHash,
            fields,
            totalCanonicalBytes
        );
        if (!canonicalPayloadHash.equals(computed)) {
            throw new IllegalArgumentException(
                "canonicalPayloadHash must match exact field evidence"
            );
        }
    }

    public static AiProviderCanonicalPayloadEvidence canonicalize(
        AiProviderPayloadCanonicalizationPolicy policy,
        AiVersionReferences.ProviderVersion providerVersion,
        AiCapability capability,
        String requestSchemaHash,
        List<FieldEvidence> fields
    ) {
        Objects.requireNonNull(policy, "policy must not be null");
        List<FieldEvidence> copy = fields == null ? List.of() : List.copyOf(fields);
        if (copy.size() > policy.maximumFields()) {
            throw new IllegalArgumentException("field count exceeds canonicalization policy");
        }
        Set<String> pointers = new HashSet<>();
        int totalBytes = 0;
        for (FieldEvidence field : copy) {
            Objects.requireNonNull(field, "field evidence must not be null");
            if (!pointers.add(field.jsonPointer())) {
                throw new IllegalArgumentException("JSON pointers must be unique");
            }
            if (!policy.allowedClassifications().contains(field.classification())) {
                throw new IllegalArgumentException(
                    "field classification is not authorized by canonicalization policy"
                );
            }
            if ((field.classification()
                == AiProviderPayloadCanonicalizationPolicy.DataClassification.CONFIDENTIAL
                || field.classification()
                    == AiProviderPayloadCanonicalizationPolicy.DataClassification.RESTRICTED)
                && !field.redacted()) {
                throw new IllegalArgumentException(
                    "confidential and restricted field evidence must be redacted"
                );
            }
            totalBytes = Math.addExact(totalBytes, field.canonicalBytes());
        }
        if (totalBytes > policy.maximumCanonicalBytes()) {
            throw new IllegalArgumentException(
                "canonical byte count exceeds canonicalization policy"
            );
        }
        String normalizedSchemaHash = requireSha256(
            requestSchemaHash,
            "requestSchemaHash"
        );
        String hash = computeHash(
            policy.authorizationKey(),
            policy.policyHash(),
            providerVersion,
            capability,
            normalizedSchemaHash,
            copy,
            totalBytes
        );
        return new AiProviderCanonicalPayloadEvidence(
            policy.authorizationKey(),
            policy.policyHash(),
            providerVersion,
            capability,
            normalizedSchemaHash,
            copy,
            totalBytes,
            hash,
            false,
            false,
            false
        );
    }

    public record FieldEvidence(
        String jsonPointer,
        ValueType valueType,
        String valueHash,
        int canonicalBytes,
        AiProviderPayloadCanonicalizationPolicy.DataClassification classification,
        boolean redacted
    ) {
        public FieldEvidence {
            jsonPointer = requirePointer(jsonPointer);
            valueType = Objects.requireNonNull(valueType, "valueType must not be null");
            valueHash = requireSha256(valueHash, "valueHash");
            if (canonicalBytes < 0 || canonicalBytes > 1_048_576) {
                throw new IllegalArgumentException("canonicalBytes must be bounded");
            }
            classification = Objects.requireNonNull(
                classification,
                "classification must not be null"
            );
        }
    }

    public enum ValueType {
        STRING_HASH,
        NUMBER_HASH,
        BOOLEAN_HASH,
        NULL_VALUE,
        OBJECT_HASH,
        ARRAY_HASH
    }

    private static String computeHash(
        String policyAuthorizationKey,
        String policyHash,
        AiVersionReferences.ProviderVersion providerVersion,
        AiCapability capability,
        String requestSchemaHash,
        List<FieldEvidence> fields,
        int totalBytes
    ) {
        Objects.requireNonNull(providerVersion, "providerVersion must not be null");
        Objects.requireNonNull(capability, "capability must not be null");
        List<String> parts = new ArrayList<>();
        parts.add(requireText(policyAuthorizationKey, "policyAuthorizationKey", 300));
        parts.add(requireSha256(policyHash, "policyHash"));
        parts.add(requireText(providerVersion.providerId(), "providerId", 160));
        parts.add(requireText(providerVersion.version(), "providerVersion", 120));
        parts.add(capability.name());
        parts.add(requireSha256(requestSchemaHash, "requestSchemaHash"));
        parts.add(Integer.toString(totalBytes));
        fields.stream()
            .sorted(Comparator.comparing(FieldEvidence::jsonPointer))
            .forEach(field -> {
                parts.add(field.jsonPointer());
                parts.add(field.valueType().name());
                parts.add(field.valueHash());
                parts.add(Integer.toString(field.canonicalBytes()));
                parts.add(field.classification().name());
                parts.add(Boolean.toString(field.redacted()));
            });
        return sha256(String.join("\n", parts));
    }

    private static String requirePointer(String value) {
        String pointer = requireText(value, "jsonPointer", 500);
        if (!pointer.startsWith("/")
            || pointer.contains("//")
            || pointer.contains("..")
            || pointer.contains("#")) {
            throw new IllegalArgumentException("jsonPointer must be exact and bounded");
        }
        return pointer;
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
