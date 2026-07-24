package io.github.akaryc1b.approval.ai.spi;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact request/response validation metadata without protocol payload content. */
public record AiProviderProtocolProfile(
    String validatorId,
    String validatorVersion,
    AiVersionReferences.ProviderVersion providerVersion,
    Set<AiCapability> capabilities,
    String requestSchemaHash,
    String responseSchemaHash,
    int maximumRequestBytes,
    int maximumResponseBytes,
    boolean structuredOutputRequired,
    boolean unknownResponseFieldsRejected,
    boolean providerInvocationAllowed
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AiProviderProtocolProfile {
        validatorId = requireText(validatorId, "validatorId", 160);
        validatorVersion = requireText(validatorVersion, "validatorVersion", 120);
        providerVersion = Objects.requireNonNull(
            providerVersion,
            "providerVersion must not be null"
        );
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        if (capabilities.isEmpty() || capabilities.size() > AiCapability.values().length) {
            throw new IllegalArgumentException("capabilities must be non-empty and bounded");
        }
        requestSchemaHash = requireSha256(requestSchemaHash, "requestSchemaHash");
        responseSchemaHash = requireSha256(responseSchemaHash, "responseSchemaHash");
        if (maximumRequestBytes < 1 || maximumRequestBytes > 16_777_216) {
            throw new IllegalArgumentException("maximumRequestBytes must be bounded");
        }
        if (maximumResponseBytes < 1 || maximumResponseBytes > 16_777_216) {
            throw new IllegalArgumentException("maximumResponseBytes must be bounded");
        }
        if (!structuredOutputRequired) {
            throw new IllegalArgumentException(
                "provider protocol validation must require structured output"
            );
        }
        if (!unknownResponseFieldsRejected) {
            throw new IllegalArgumentException(
                "provider protocol validation must reject unknown response fields"
            );
        }
        if (providerInvocationAllowed) {
            throw new IllegalArgumentException(
                "provider protocol validation cannot authorize Provider invocation"
            );
        }
    }

    public String authorizationKey() {
        return validatorId + "/" + validatorVersion;
    }

    public boolean supports(AiCapability capability) {
        return capabilities.contains(capability);
    }

    private static String requireSha256(String value, String name) {
        String normalized = requireText(value, name, 64).toLowerCase(java.util.Locale.ROOT);
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
