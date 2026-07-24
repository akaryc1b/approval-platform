package io.github.akaryc1b.approval.ai.spi;

/**
 * Provider-specific structural transport mapping SPI.
 *
 * <p>The contract maps metadata and hashes only. It cannot dispatch a request,
 * access Secret material, invoke a Provider or authorize production.</p>
 */
@FunctionalInterface
public interface AiProviderTransportMapper {

    AiProviderTransportMappingResult map(AiProviderTransportMappingRequest request);
}
