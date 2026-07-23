package io.github.akaryc1b.approval.connector.contract;

import java.util.Objects;

/**
 * Selection outcome. A binding exists only for a deterministic SELECTED result.
 */
public record ConnectorProviderSelection<P, R>(
    ConnectorProviderSelectionStatus status,
    ConnectorProviderBinding<P, R> binding,
    ConnectorProviderSelectionEvidence evidence
) {

    public ConnectorProviderSelection {
        status = Objects.requireNonNull(status, "status must not be null");
        evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        if (status != evidence.status()) {
            throw new IllegalArgumentException("selection status does not match evidence");
        }
        if (status == ConnectorProviderSelectionStatus.SELECTED) {
            binding = Objects.requireNonNull(binding, "selected binding must not be null");
            if (!binding.descriptor().providerKey().equals(evidence.selectedProviderKey())) {
                throw new IllegalArgumentException(
                    "selected binding does not match evidence provider"
                );
            }
        } else if (binding != null) {
            throw new IllegalArgumentException(
                "non-selected result must not contain a provider binding"
            );
        }
    }

    public boolean selected() {
        return status == ConnectorProviderSelectionStatus.SELECTED;
    }

    public ConnectorProviderBinding<P, R> requireBinding() {
        if (binding == null) {
            throw new IllegalStateException("provider selection did not produce a binding");
        }
        return binding;
    }
}
