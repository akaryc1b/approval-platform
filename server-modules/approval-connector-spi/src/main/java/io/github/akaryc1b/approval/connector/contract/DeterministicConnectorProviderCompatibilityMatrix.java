package io.github.akaryc1b.approval.connector.contract;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Registry-only provider compatibility assessment. No network, health or runtime observation occurs.
 */
public final class DeterministicConnectorProviderCompatibilityMatrix {

    private final ConnectorProviderRegistry registry;

    public DeterministicConnectorProviderCompatibilityMatrix(ConnectorProviderRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    public <P, R> ConnectorProviderCompatibilityReport evaluate(
        ConnectorOperationContract<P, R> contract,
        Collection<String> providerKeys,
        String requiredProtocolVersion,
        String matrixVersion
    ) {
        ConnectorOperationContract<P, R> normalizedContract = Objects.requireNonNull(
            contract,
            "contract must not be null"
        );
        Objects.requireNonNull(providerKeys, "providerKeys must not be null");
        if (providerKeys.isEmpty() || providerKeys.size() > 32) {
            throw new IllegalArgumentException(
                "providerKeys must contain between 1 and 32 entries"
            );
        }
        requiredProtocolVersion = ConnectorContractSupport.optionalText(
            requiredProtocolVersion,
            "requiredProtocolVersion",
            64
        );
        matrixVersion = ConnectorContractSupport.requireSafeIdentifier(
            matrixVersion,
            "matrixVersion"
        );
        List<String> normalizedKeys = providerKeys.stream()
            .map(key -> ConnectorContractSupport.requireSafeIdentifier(key, "providerKey"))
            .distinct()
            .sorted()
            .toList();
        if (normalizedKeys.size() != providerKeys.size()) {
            throw new IllegalArgumentException("providerKeys must not contain duplicates");
        }
        final String protocol = requiredProtocolVersion;
        List<ConnectorProviderCompatibilityEntry> entries = normalizedKeys.stream()
            .map(providerKey -> assess(providerKey, normalizedContract, protocol))
            .toList();
        return new ConnectorProviderCompatibilityReport(
            matrixVersion,
            registry.registryFingerprint(),
            normalizedContract.contractKey(),
            CanonicalPayloadHash.sha256Utf8(normalizedContract.canonicalValue()),
            protocol,
            entries
        );
    }

    private <P, R> ConnectorProviderCompatibilityEntry assess(
        String providerKey,
        ConnectorOperationContract<P, R> contract,
        String requiredProtocolVersion
    ) {
        ProviderDescriptor descriptor = registry.findDescriptor(providerKey).orElse(null);
        if (descriptor == null) {
            return entry(
                providerKey,
                null,
                ConnectorProviderCompatibilityStatus.PROVIDER_UNKNOWN
            );
        }
        if (descriptor.state() != ProviderDescriptor.ProviderState.ENABLED) {
            return entry(
                providerKey,
                descriptor.protocolVersion(),
                ConnectorProviderCompatibilityStatus.PROVIDER_DISABLED
            );
        }
        if (!descriptor.supportedCapabilities().contains(
            contract.operation().requiredCapability()
        )) {
            return entry(
                providerKey,
                descriptor.protocolVersion(),
                ConnectorProviderCompatibilityStatus.CAPABILITY_UNSUPPORTED
            );
        }
        ConnectorProviderBinding<?, ?> binding = registry.findBinding(
            providerKey,
            contract.operation()
        ).orElse(null);
        if (binding == null) {
            return entry(
                providerKey,
                descriptor.protocolVersion(),
                ConnectorProviderCompatibilityStatus.OPERATION_UNREGISTERED
            );
        }
        if (!binding.requestPayloadType().equals(contract.requestPayloadType())
            || !binding.responseType().equals(contract.responseType())) {
            return entry(
                providerKey,
                descriptor.protocolVersion(),
                ConnectorProviderCompatibilityStatus.CONTRACT_TYPE_MISMATCH
            );
        }
        if (requiredProtocolVersion != null
            && !requiredProtocolVersion.equals(descriptor.protocolVersion())) {
            return entry(
                providerKey,
                descriptor.protocolVersion(),
                ConnectorProviderCompatibilityStatus.PROTOCOL_MISMATCH
            );
        }
        return entry(
            providerKey,
            descriptor.protocolVersion(),
            ConnectorProviderCompatibilityStatus.COMPATIBLE
        );
    }

    private static ConnectorProviderCompatibilityEntry entry(
        String providerKey,
        String actualProtocolVersion,
        ConnectorProviderCompatibilityStatus status
    ) {
        return new ConnectorProviderCompatibilityEntry(
            providerKey,
            actualProtocolVersion,
            status
        );
    }
}
