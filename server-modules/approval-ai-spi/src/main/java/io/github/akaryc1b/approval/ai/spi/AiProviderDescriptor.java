package io.github.akaryc1b.approval.ai.spi;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Immutable provider capability and model inventory. */
public record AiProviderDescriptor(
    String providerId,
    AiProviderType providerType,
    AiVersionReferences.ProviderVersion providerVersion,
    Set<CapabilityDescriptor> capabilities,
    Set<AiVersionReferences.ModelVersion> models
) {

    public AiProviderDescriptor {
        providerId = requireText(providerId, "providerId", 120);
        providerType = Objects.requireNonNull(providerType, "providerType must not be null");
        providerVersion = Objects.requireNonNull(
            providerVersion,
            "providerVersion must not be null"
        );
        if (!providerId.equals(providerVersion.providerId())) {
            throw new IllegalArgumentException("providerVersion providerId must match providerId");
        }
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        models = models == null ? Set.of() : Set.copyOf(models);
        for (AiVersionReferences.ModelVersion model : models) {
            if (!providerId.equals(model.providerId())) {
                throw new IllegalArgumentException("model providerId must match providerId");
            }
        }
        Map<AiCapability, CapabilityDescriptor> byCapability = capabilities.stream()
            .collect(Collectors.toMap(
                CapabilityDescriptor::capability,
                Function.identity(),
                (left, right) -> {
                    throw new IllegalArgumentException(
                        "duplicate capability descriptor: " + left.capability()
                    );
                }
            ));
        if (byCapability.size() != capabilities.size()) {
            throw new IllegalArgumentException("capability descriptors must be unique");
        }
    }

    public Optional<CapabilityDescriptor> capabilityDescriptor(AiCapability capability) {
        Objects.requireNonNull(capability, "capability must not be null");
        return capabilities.stream()
            .filter(descriptor -> descriptor.capability() == capability)
            .findFirst();
    }

    public boolean supports(AiCapability capability) {
        return capabilityDescriptor(capability)
            .map(CapabilityDescriptor::enabled)
            .orElse(false);
    }

    public boolean supports(AiVersionReferences.ModelVersion model) {
        return models.contains(model);
    }

    public record CapabilityDescriptor(
        AiCapability capability,
        boolean enabled,
        int maximumInputCharacters,
        int maximumCollectionSize,
        int maximumDepth,
        boolean knowledgeRetrievalAllowed,
        boolean attachmentContentAllowed
    ) {
        public CapabilityDescriptor {
            capability = Objects.requireNonNull(capability, "capability must not be null");
            if (maximumInputCharacters < 1) {
                throw new IllegalArgumentException("maximumInputCharacters must be positive");
            }
            if (maximumCollectionSize < 1) {
                throw new IllegalArgumentException("maximumCollectionSize must be positive");
            }
            if (maximumDepth < 1) {
                throw new IllegalArgumentException("maximumDepth must be positive");
            }
            if (attachmentContentAllowed) {
                throw new IllegalArgumentException(
                    "attachment content is not allowed in the M6-D first safe slice"
                );
            }
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
