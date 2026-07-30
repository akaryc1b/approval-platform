package io.github.akaryc1b.approval.connector.contract;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic fail-closed selector.
 *
 * <p>No provider is chosen by registration order, randomness, implicit priority or fallback
 * after an explicit preferred provider becomes ineligible.</p>
 */
public final class DeterministicConnectorProviderSelector
    implements ConnectorProviderSelectionPolicy {

    @Override
    public <P, R> ConnectorProviderSelection<P, R> select(
        ConnectorProviderRegistry registry,
        ConnectorProviderSelectionRequest<P, R> request
    ) {
        registry = Objects.requireNonNull(registry, "registry must not be null");
        request = Objects.requireNonNull(request, "request must not be null");

        List<String> eligible = new ArrayList<>();
        for (String providerKey : request.allowedProviderKeys()) {
            var descriptor = registry.findDescriptor(providerKey).orElse(null);
            if (descriptor == null
                || descriptor.state() != ProviderDescriptor.ProviderState.ENABLED
                || !descriptor.supportedCapabilities().contains(
                    request.contract().operation().requiredCapability()
                )
                || protocolMismatch(descriptor, request.requiredProtocolVersion())) {
                continue;
            }
            try {
                registry.resolve(
                    providerKey,
                    request.contract().operation(),
                    request.contract().requestPayloadType(),
                    request.contract().responseType()
                );
                eligible.add(providerKey);
            } catch (IllegalArgumentException ignored) {
                // Missing or differently typed operation bindings are ineligible and fail closed.
            }
        }
        eligible = eligible.stream().distinct().sorted().toList();

        ConnectorProviderSelectionStatus status;
        String selectedProviderKey = null;
        ConnectorProviderBinding<P, R> binding = null;
        if (request.preferredProviderKey() != null) {
            if (eligible.contains(request.preferredProviderKey())) {
                status = ConnectorProviderSelectionStatus.SELECTED;
                selectedProviderKey = request.preferredProviderKey();
            } else {
                status = ConnectorProviderSelectionStatus.PREFERRED_PROVIDER_INELIGIBLE;
            }
        } else if (eligible.isEmpty()) {
            status = ConnectorProviderSelectionStatus.NO_ELIGIBLE_PROVIDER;
        } else if (eligible.size() == 1) {
            status = ConnectorProviderSelectionStatus.SELECTED;
            selectedProviderKey = eligible.getFirst();
        } else {
            status = ConnectorProviderSelectionStatus.AMBIGUOUS;
        }

        if (selectedProviderKey != null) {
            binding = registry.resolve(
                selectedProviderKey,
                request.contract().operation(),
                request.contract().requestPayloadType(),
                request.contract().responseType()
            );
        }
        var evidence = new ConnectorProviderSelectionEvidence(
            request.policyVersion(),
            registry.registryFingerprint(),
            request.contract().contractKey(),
            List.copyOf(request.allowedProviderKeys()),
            eligible,
            request.preferredProviderKey(),
            request.requiredProtocolVersion(),
            selectedProviderKey,
            status
        );
        return new ConnectorProviderSelection<>(status, binding, evidence);
    }

    private static boolean protocolMismatch(
        ProviderDescriptor descriptor,
        String requiredProtocolVersion
    ) {
        return requiredProtocolVersion != null
            && !requiredProtocolVersion.equals(descriptor.protocolVersion());
    }
}
