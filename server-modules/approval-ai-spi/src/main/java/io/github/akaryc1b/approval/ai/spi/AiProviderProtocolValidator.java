package io.github.akaryc1b.approval.ai.spi;

/**
 * Provider-specific structural validation SPI.
 *
 * <p>The validator receives metadata and size evidence only. It must not invoke a Provider, resolve
 * a secret, perform network I/O or authorize production use.</p>
 */
public interface AiProviderProtocolValidator {

    AiProviderProtocolProfile profile();

    AiProviderProtocolValidationResult validate(
        AiProviderProtocolValidationRequest request
    );
}
