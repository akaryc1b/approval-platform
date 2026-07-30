package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Exact metadata registry for prompt, knowledge, policy and structured-output authorization. */
public final class AiAdvisoryArtifactRegistry {

    private final Map<AiVersionReferences.PromptTemplateVersion, AiPromptTemplateDescriptor> prompts;
    private final Map<AiVersionReferences.KnowledgeSourceVersion, AiKnowledgeSourceDescriptor> knowledge;
    private final Map<AiVersionReferences.PolicyVersion, AiPolicyDescriptor> policies;
    private final Map<AiVersionReferences.OutputSchemaVersion, AiOutputSchemaDescriptor> outputs;

    public AiAdvisoryArtifactRegistry(
        Collection<AiPromptTemplateDescriptor> prompts,
        Collection<AiKnowledgeSourceDescriptor> knowledge,
        Collection<AiPolicyDescriptor> policies,
        Collection<AiOutputSchemaDescriptor> outputs
    ) {
        this.prompts = index(
            prompts,
            AiPromptTemplateDescriptor::version,
            "prompt template"
        );
        this.knowledge = index(
            knowledge,
            AiKnowledgeSourceDescriptor::version,
            "knowledge source"
        );
        this.policies = index(policies, AiPolicyDescriptor::version, "policy");
        this.outputs = index(outputs, AiOutputSchemaDescriptor::version, "output schema");
    }

    public AuthorizationResult authorize(
        AiVersionReferences versions,
        AiCapability capability
    ) {
        Objects.requireNonNull(versions, "versions must not be null");
        Objects.requireNonNull(capability, "capability must not be null");

        AiPromptTemplateDescriptor prompt = prompts.get(versions.promptTemplate());
        if (prompt == null) {
            return AuthorizationResult.denied(
                "AI_PROMPT_TEMPLATE_NOT_REGISTERED",
                "exact AI prompt template metadata is not registered"
            );
        }
        if (!prompt.supports(capability)) {
            return AuthorizationResult.denied(
                "AI_PROMPT_CAPABILITY_NOT_AUTHORIZED",
                "AI prompt template is not authorized for the requested capability"
            );
        }

        AiKnowledgeSourceDescriptor source = knowledge.get(versions.knowledgeSource());
        if (source == null) {
            return AuthorizationResult.denied(
                "AI_KNOWLEDGE_SOURCE_NOT_REGISTERED",
                "exact AI knowledge-source metadata is not registered"
            );
        }
        if (!source.supports(capability)) {
            return AuthorizationResult.denied(
                "AI_KNOWLEDGE_CAPABILITY_NOT_AUTHORIZED",
                "AI knowledge source is not authorized for the requested capability"
            );
        }

        AiPolicyDescriptor policy = policies.get(versions.policy());
        if (policy == null) {
            return AuthorizationResult.denied(
                "AI_POLICY_NOT_REGISTERED",
                "exact AI policy metadata is not registered"
            );
        }
        if (!policy.supports(capability)) {
            return AuthorizationResult.denied(
                "AI_POLICY_CAPABILITY_NOT_AUTHORIZED",
                "AI policy is not authorized for the requested capability"
            );
        }

        AiOutputSchemaDescriptor output = outputs.get(versions.outputSchema());
        if (output == null) {
            return AuthorizationResult.denied(
                "AI_OUTPUT_SCHEMA_NOT_REGISTERED",
                "exact AI output-schema metadata is not registered"
            );
        }
        if (!output.supports(capability)) {
            return AuthorizationResult.denied(
                "AI_OUTPUT_CAPABILITY_NOT_AUTHORIZED",
                "AI output schema is not authorized for the requested capability"
            );
        }

        return AuthorizationResult.permitted();
    }

    public record AuthorizationResult(boolean allowed, String code, String message) {
        public AuthorizationResult {
            if (allowed) {
                if (code != null || message != null) {
                    throw new IllegalArgumentException(
                        "allowed artifact authorization cannot contain failure evidence"
                    );
                }
            } else {
                code = requireText(code, "code", 120);
                message = requireText(message, "message", 500);
            }
        }

        public static AuthorizationResult permitted() {
            return new AuthorizationResult(true, null, null);
        }

        public static AuthorizationResult denied(String code, String message) {
            return new AuthorizationResult(false, code, message);
        }
    }

    private static <K, V> Map<K, V> index(
        Collection<V> values,
        java.util.function.Function<V, K> key,
        String name
    ) {
        Map<K, V> result = new LinkedHashMap<>();
        if (values != null) {
            for (V value : values) {
                V normalized = Objects.requireNonNull(value, name + " must not be null");
                K exactKey = Objects.requireNonNull(key.apply(normalized), name + " key");
                if (result.putIfAbsent(exactKey, normalized) != null) {
                    throw new IllegalArgumentException("duplicate exact " + name + " registration");
                }
            }
        }
        return Map.copyOf(result);
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return normalized;
    }
}
