package io.github.akaryc1b.approval.connector.contract;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Explicit server-owned selection input. No tenant or browser-provided trust identity is accepted.
 */
public record ConnectorProviderSelectionRequest<P, R>(
    ConnectorOperationContract<P, R> contract,
    Set<String> allowedProviderKeys,
    String preferredProviderKey,
    String requiredProtocolVersion,
    String policyVersion
) {

    public ConnectorProviderSelectionRequest {
        contract = Objects.requireNonNull(contract, "contract must not be null");
        if (allowedProviderKeys == null
            || allowedProviderKeys.isEmpty()
            || allowedProviderKeys.size() > 32) {
            throw new IllegalArgumentException(
                "allowedProviderKeys must contain between 1 and 32 entries"
            );
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String providerKey : allowedProviderKeys) {
            sorted.add(ConnectorContractSupport.requireSafeIdentifier(
                providerKey,
                "allowedProviderKey"
            ));
        }
        allowedProviderKeys = Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
        preferredProviderKey = ConnectorContractSupport.optionalText(
            preferredProviderKey,
            "preferredProviderKey",
            128
        );
        if (preferredProviderKey != null) {
            preferredProviderKey = ConnectorContractSupport.requireSafeIdentifier(
                preferredProviderKey,
                "preferredProviderKey"
            );
            if (!allowedProviderKeys.contains(preferredProviderKey)) {
                throw new IllegalArgumentException(
                    "preferredProviderKey must be present in allowedProviderKeys"
                );
            }
        }
        requiredProtocolVersion = ConnectorContractSupport.optionalText(
            requiredProtocolVersion,
            "requiredProtocolVersion",
            64
        );
        policyVersion = ConnectorContractSupport.requireSafeIdentifier(
            policyVersion,
            "policyVersion"
        );
    }

    public String canonicalPolicyInput() {
        return "contract=" + contract.canonicalValue()
            + "\nallowed=" + String.join(",", allowedProviderKeys)
            + "\npreferred=" + (preferredProviderKey == null ? "" : preferredProviderKey)
            + "\nprotocol=" + (requiredProtocolVersion == null ? "" : requiredProtocolVersion)
            + "\npolicyVersion=" + policyVersion;
    }
}
