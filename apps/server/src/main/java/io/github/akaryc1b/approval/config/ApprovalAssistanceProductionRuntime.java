package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProductionRuntimeFactory;

import java.util.Objects;
import java.util.Optional;

/** Single server-owned holder for the default-disabled production AI runtime. */
public record ApprovalAssistanceProductionRuntime(
    Optional<OpenAiResponsesProductionRuntimeFactory> factory
) {
    public ApprovalAssistanceProductionRuntime {
        factory = Objects.requireNonNull(factory, "factory must not be null");
    }

    public static ApprovalAssistanceProductionRuntime disabled() {
        return new ApprovalAssistanceProductionRuntime(Optional.empty());
    }

    public static ApprovalAssistanceProductionRuntime configured(
        OpenAiResponsesProductionRuntimeFactory factory
    ) {
        return new ApprovalAssistanceProductionRuntime(Optional.of(
            Objects.requireNonNull(factory, "factory must not be null")
        ));
    }
}
