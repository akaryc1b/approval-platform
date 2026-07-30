package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.util.Objects;
import java.util.Set;

/** External secret metadata only. No secret material can be stored or resolved by this contract. */
public record AiExternalSecretReference(
    String referenceId,
    String referenceVersion,
    StoreKind storeKind,
    AiVersionReferences.ProviderVersion providerVersion,
    Set<Purpose> purposes,
    RotationState rotationState,
    boolean inlineSecretMaterialPresent,
    boolean runtimeResolutionAuthorized
) {

    public AiExternalSecretReference {
        referenceId = requireText(referenceId, "referenceId", 240);
        referenceVersion = requireText(referenceVersion, "referenceVersion", 120);
        storeKind = Objects.requireNonNull(storeKind, "storeKind must not be null");
        providerVersion = Objects.requireNonNull(
            providerVersion,
            "providerVersion must not be null"
        );
        purposes = purposes == null ? Set.of() : Set.copyOf(purposes);
        if (purposes.isEmpty() || purposes.size() > Purpose.values().length) {
            throw new IllegalArgumentException("purposes must be non-empty and bounded");
        }
        rotationState = Objects.requireNonNull(rotationState, "rotationState must not be null");
        if (inlineSecretMaterialPresent) {
            throw new IllegalArgumentException(
                "external secret references cannot contain inline secret material"
            );
        }
        if (runtimeResolutionAuthorized) {
            throw new IllegalArgumentException(
                "M6-D secret references cannot authorize runtime secret resolution"
            );
        }
    }

    public String authorizationKey() {
        return referenceId + "/" + referenceVersion;
    }

    public enum StoreKind {
        EXTERNAL_SECRET_MANAGER,
        KMS_WRAPPED_REFERENCE,
        PLATFORM_SECRET_REFERENCE
    }

    public enum Purpose {
        PROVIDER_AUTHENTICATION,
        REQUEST_SIGNING,
        CLIENT_CERTIFICATE
    }

    public enum RotationState {
        CURRENT,
        ROTATION_DUE,
        EXPIRED,
        REVOKED
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
