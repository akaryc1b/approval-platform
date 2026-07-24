package io.github.akaryc1b.approval.connector.contract;

import io.github.akaryc1b.approval.connector.ConnectorProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Provider-neutral, credential-free description used for capability discovery.
 */
public record ProviderDescriptor(
    String providerKey,
    ProviderType providerType,
    String protocolVersion,
    Set<ConnectorProvider.Capability> supportedCapabilities,
    ProviderState state,
    Map<String, String> compatibilityMetadata
) {

    public ProviderDescriptor {
        providerKey = ConnectorContractSupport.requireSafeIdentifier(providerKey, "providerKey");
        providerType = Objects.requireNonNull(providerType, "providerType must not be null");
        protocolVersion = ConnectorContractSupport.requireText(
            protocolVersion,
            "protocolVersion",
            64
        );
        if (supportedCapabilities != null
            && supportedCapabilities.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("supportedCapabilities must not contain null");
        }
        supportedCapabilities = supportedCapabilities == null
            ? Set.of()
            : Set.copyOf(supportedCapabilities);
        state = Objects.requireNonNull(state, "state must not be null");
        compatibilityMetadata = ConnectorContractSupport.boundedMetadata(
            compatibilityMetadata,
            "compatibilityMetadata",
            16,
            64,
            256,
            true
        );
    }

    public static ProviderDescriptor fromLegacy(ConnectorProvider.ConnectorDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        ProviderType type = descriptor.key().startsWith("generic-")
            ? ProviderType.GENERIC_REST
            : ProviderType.HOST_SYSTEM;
        return new ProviderDescriptor(
            descriptor.key(),
            type,
            descriptor.version(),
            descriptor.capabilities(),
            ProviderState.ENABLED,
            descriptor.attributes()
        );
    }

    public void requireEnabledCapability(ConnectorProvider.Capability capability) {
        Objects.requireNonNull(capability, "capability must not be null");
        if (state != ProviderState.ENABLED) {
            throw new IllegalStateException("provider is disabled");
        }
        if (!supportedCapabilities.contains(capability)) {
            throw new IllegalArgumentException("unsupported provider capability: " + capability.name());
        }
    }

    /**
     * Stable JSON for evidence hashes and compatibility fixtures. Metadata and capabilities are sorted.
     */
    public String canonicalJson() {
        StringBuilder json = new StringBuilder(256);
        json.append('{')
            .append("\"providerKey\":").append(ConnectorContractSupport.json(providerKey))
            .append(",\"providerType\":").append(ConnectorContractSupport.json(providerType.name()))
            .append(",\"protocolVersion\":").append(ConnectorContractSupport.json(protocolVersion))
            .append(",\"supportedCapabilities\":[");
        var capabilities = new ArrayList<>(supportedCapabilities);
        capabilities.sort(Comparator.comparing(Enum::name));
        for (int index = 0; index < capabilities.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(ConnectorContractSupport.json(capabilities.get(index).name()));
        }
        json.append("]")
            .append(",\"state\":").append(ConnectorContractSupport.json(state.name()))
            .append(",\"compatibilityMetadata\":{");
        int index = 0;
        for (var entry : compatibilityMetadata.entrySet()) {
            if (index++ > 0) {
                json.append(',');
            }
            json.append(ConnectorContractSupport.json(entry.getKey()))
                .append(':')
                .append(ConnectorContractSupport.json(entry.getValue()));
        }
        return json.append("}}").toString();
    }

    public enum ProviderType {
        GENERIC_REST,
        OFFICE_PLATFORM,
        HOST_SYSTEM,
        TEST
    }

    public enum ProviderState {
        ENABLED,
        DISABLED
    }
}
