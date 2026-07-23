package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiAdvisoryProvider;
import io.github.akaryc1b.approval.ai.spi.AiProviderDescriptor;
import io.github.akaryc1b.approval.ai.spi.AiProviderType;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact provider-version registry combined with server-owned prompt, knowledge, policy and output
 * metadata authorization. Untrusted requests never select or register providers or artifacts.
 */
public final class AiProviderRegistry {

    private final Map<ProviderKey, AiAdvisoryProvider> providers;
    private final AiAdvisoryArtifactRegistry artifactRegistry;
    private final boolean artifactAuthorizationRequired;

    /**
     * Package-private compatibility constructor for deterministic test providers only.
     *
     * <p>Production provider types cannot use this constructor and public construction always
     * requires an exact artifact registry.</p>
     */
    AiProviderRegistry(Collection<? extends AiAdvisoryProvider> providers) {
        this(providers, null, false);
        for (AiAdvisoryProvider provider : this.providers.values()) {
            if (provider.descriptor().providerType() != AiProviderType.DETERMINISTIC_MOCK) {
                throw new IllegalArgumentException(
                    "artifact registry is required for non-test AI providers"
                );
            }
        }
    }

    public AiProviderRegistry(
        Collection<? extends AiAdvisoryProvider> providers,
        AiAdvisoryArtifactRegistry artifactRegistry
    ) {
        this(providers, artifactRegistry, true);
    }

    private AiProviderRegistry(
        Collection<? extends AiAdvisoryProvider> providers,
        AiAdvisoryArtifactRegistry artifactRegistry,
        boolean artifactAuthorizationRequired
    ) {
        Map<ProviderKey, AiAdvisoryProvider> resolved = new LinkedHashMap<>();
        if (providers != null) {
            for (AiAdvisoryProvider provider : providers) {
                Objects.requireNonNull(provider, "provider must not be null");
                AiProviderDescriptor descriptor = Objects.requireNonNull(
                    provider.descriptor(),
                    "provider descriptor must not be null"
                );
                ProviderKey key = ProviderKey.from(descriptor.providerVersion());
                if (resolved.putIfAbsent(key, provider) != null) {
                    throw new IllegalArgumentException(
                        "duplicate AI provider version registration: " + key.authorizationKey()
                    );
                }
            }
        }
        this.providers = Map.copyOf(resolved);
        this.artifactAuthorizationRequired = artifactAuthorizationRequired;
        this.artifactRegistry = artifactAuthorizationRequired
            ? Objects.requireNonNull(artifactRegistry, "artifactRegistry must not be null")
            : null;
    }

    public Optional<AiAdvisoryProvider> find(
        AiVersionReferences.ProviderVersion providerVersion
    ) {
        return Optional.ofNullable(providers.get(ProviderKey.from(providerVersion)));
    }

    public boolean matches(AiAdvisoryProvider provider, AiProviderRoute route) {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(route, "route must not be null");
        AiProviderDescriptor descriptor = Objects.requireNonNull(
            provider.descriptor(),
            "provider descriptor must not be null"
        );
        return descriptor.providerVersion().equals(route.versions().provider())
            && descriptor.supports(route.versions().model())
            && route.capabilities().stream().allMatch(capability -> descriptor
                .capabilityDescriptor(capability)
                .filter(AiProviderDescriptor.CapabilityDescriptor::enabled)
                .filter(capabilityDescriptor -> route.budget().maximumInputCharacters()
                    <= capabilityDescriptor.maximumInputCharacters())
                .filter(ignored -> !artifactAuthorizationRequired
                    || artifactRegistry.authorize(route.versions(), capability).allowed())
                .isPresent());
    }

    public int size() {
        return providers.size();
    }

    public record ProviderKey(String providerId, String providerVersion) {
        public ProviderKey {
            providerId = requireText(providerId, "providerId", 120);
            providerVersion = requireText(providerVersion, "providerVersion", 120);
        }

        public static ProviderKey from(AiVersionReferences.ProviderVersion version) {
            Objects.requireNonNull(version, "provider version must not be null");
            return new ProviderKey(version.providerId(), version.version());
        }

        public String authorizationKey() {
            return providerId + "/" + providerVersion;
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
}
