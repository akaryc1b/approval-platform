package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiAdvisoryProvider;
import io.github.akaryc1b.approval.ai.spi.AiProviderDescriptor;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Exact provider-version registry; untrusted requests never select or register providers. */
public final class AiProviderRegistry {

    private final Map<ProviderKey, AiAdvisoryProvider> providers;

    public AiProviderRegistry(Collection<? extends AiAdvisoryProvider> providers) {
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
