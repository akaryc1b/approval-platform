package io.github.akaryc1b.approval.ai.spi;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Non-dispatching structural transport mapping result. */
public record AiProviderTransportMappingResult(
    String mapperAuthorizationKey,
    AiVersionReferences.ProviderVersion providerVersion,
    Status status,
    String requestEvidenceHash,
    String transportEnvelopeHash,
    String responseSchemaHash,
    List<String> codes,
    boolean rawRequestStored,
    boolean rawResponseStored,
    boolean secretMaterialAccessed,
    boolean networkCallAttempted,
    boolean providerInvocationAttempted,
    boolean retryAuthorized,
    boolean postInvocationFallbackAuthorized,
    boolean productionEnablementAuthorized,
    boolean approvalAutomationAuthorized
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AiProviderTransportMappingResult {
        mapperAuthorizationKey = requireText(
            mapperAuthorizationKey,
            "mapperAuthorizationKey",
            300
        );
        providerVersion = Objects.requireNonNull(
            providerVersion,
            "providerVersion must not be null"
        );
        status = Objects.requireNonNull(status, "status must not be null");
        requestEvidenceHash = requireSha256(requestEvidenceHash, "requestEvidenceHash");
        transportEnvelopeHash = normalizeOptionalSha256(
            transportEnvelopeHash,
            "transportEnvelopeHash"
        );
        responseSchemaHash = requireSha256(responseSchemaHash, "responseSchemaHash");
        codes = codes == null
            ? List.of()
            : codes.stream().map(AiProviderTransportMappingResult::requireCode).toList();
        if (codes.size() > 100) {
            throw new IllegalArgumentException("codes must be bounded");
        }
        if (rawRequestStored || rawResponseStored || secretMaterialAccessed) {
            throw new IllegalArgumentException(
                "transport mapping results cannot store payloads or Secret material"
            );
        }
        if (networkCallAttempted || providerInvocationAttempted) {
            throw new IllegalArgumentException(
                "transport mapping results must remain zero-call"
            );
        }
        if (retryAuthorized || postInvocationFallbackAuthorized) {
            throw new IllegalArgumentException(
                "transport mapping cannot authorize retry or post-invocation fallback"
            );
        }
        if (productionEnablementAuthorized || approvalAutomationAuthorized) {
            throw new IllegalArgumentException(
                "transport mapping cannot authorize production or approval automation"
            );
        }
        if (status == Status.MAPPED_FOR_OFFLINE_VALIDATION
            && transportEnvelopeHash == null) {
            throw new IllegalArgumentException(
                "mapped results require an exact transport envelope hash"
            );
        }
        if (status != Status.MAPPED_FOR_OFFLINE_VALIDATION && codes.isEmpty()) {
            throw new IllegalArgumentException(
                "non-mapped results require at least one bounded code"
            );
        }
    }

    public enum Status {
        MAPPED_FOR_OFFLINE_VALIDATION,
        REJECTED,
        UNSUPPORTED,
        UNKNOWN
    }

    private static String requireCode(String value) {
        String code = requireText(value, "code", 120);
        if (!code.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException("codes must use uppercase stable identifiers");
        }
        return code;
    }

    private static String normalizeOptionalSha256(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireSha256(value, name);
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
