package io.github.akaryc1b.approval.ai.spi;

import java.util.Objects;
import java.util.regex.Pattern;

/** Secret-reference metadata request with no authority to retrieve material. */
public record AiExternalSecretResolutionRequest(
    AiVersionReferences.ProviderVersion providerVersion,
    String referenceId,
    String referenceVersion,
    Purpose purpose,
    String requestEvidenceHash,
    boolean secretMaterialAccessAllowed,
    boolean networkAccessAllowed
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AiExternalSecretResolutionRequest {
        providerVersion = Objects.requireNonNull(
            providerVersion,
            "providerVersion must not be null"
        );
        referenceId = requireText(referenceId, "referenceId", 240);
        referenceVersion = requireText(referenceVersion, "referenceVersion", 120);
        purpose = Objects.requireNonNull(purpose, "purpose must not be null");
        requestEvidenceHash = requireSha256(requestEvidenceHash, "requestEvidenceHash");
        if (secretMaterialAccessAllowed) {
            throw new IllegalArgumentException(
                "M6-D secret inspection cannot authorize secret material access"
            );
        }
        if (networkAccessAllowed) {
            throw new IllegalArgumentException(
                "M6-D secret inspection cannot authorize network access"
            );
        }
    }

    public String referenceAuthorizationKey() {
        return referenceId + "/" + referenceVersion;
    }

    public enum Purpose {
        PROVIDER_AUTHENTICATION,
        REQUEST_SIGNING,
        CLIENT_CERTIFICATE
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
