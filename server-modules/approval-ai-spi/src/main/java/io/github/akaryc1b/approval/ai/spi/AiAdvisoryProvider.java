package io.github.akaryc1b.approval.ai.spi;

/** Provider-neutral advisory SPI. */
public interface AiAdvisoryProvider {

    AiProviderDescriptor descriptor();

    AiProviderOutcome advise(AiProviderRequest request, AiCancellation cancellation);
}
