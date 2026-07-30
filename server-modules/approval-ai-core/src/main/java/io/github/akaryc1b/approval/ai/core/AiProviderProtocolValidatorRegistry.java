package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiProviderProtocolProfile;
import io.github.akaryc1b.approval.ai.spi.AiProviderProtocolValidator;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Exact validator-profile registry; validators remain structural and zero-call. */
public final class AiProviderProtocolValidatorRegistry {

    private final Map<String, AiProviderProtocolValidator> validators;

    public AiProviderProtocolValidatorRegistry(
        Collection<? extends AiProviderProtocolValidator> validators
    ) {
        Map<String, AiProviderProtocolValidator> resolved = new LinkedHashMap<>();
        if (validators != null) {
            for (AiProviderProtocolValidator validator : validators) {
                Objects.requireNonNull(validator, "validator must not be null");
                AiProviderProtocolProfile profile = Objects.requireNonNull(
                    validator.profile(),
                    "validator profile must not be null"
                );
                if (resolved.putIfAbsent(profile.authorizationKey(), validator) != null) {
                    throw new IllegalArgumentException(
                        "duplicate AI Provider protocol validator registration: "
                            + profile.authorizationKey()
                    );
                }
            }
        }
        this.validators = Map.copyOf(resolved);
    }

    public Optional<AiProviderProtocolValidator> find(String authorizationKey) {
        Objects.requireNonNull(authorizationKey, "authorizationKey must not be null");
        return Optional.ofNullable(validators.get(authorizationKey));
    }

    public int size() {
        return validators.size();
    }
}
