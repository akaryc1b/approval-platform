package io.github.akaryc1b.approval.ai.spi;

/**
 * Metadata-only external secret reference inspection SPI.
 *
 * <p>M6-D implementations cannot return secret material, access a network, or authorize Provider
 * activation. The deterministic implementation remains test-only.</p>
 */
@FunctionalInterface
public interface AiExternalSecretResolver {

    AiExternalSecretResolutionResult inspectReference(
        AiExternalSecretResolutionRequest request
    );
}
