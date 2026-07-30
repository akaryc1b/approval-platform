package io.github.akaryc1b.approval.ai.spi;

import java.util.Objects;

/**
 * Exact versions used for one advisory request and result.
 *
 * <p>Each version category is represented independently so audit evidence cannot collapse the
 * provider, model, prompt, knowledge, policy and output schema into one opaque string.</p>
 */
public record AiVersionReferences(
    ProviderVersion provider,
    ModelVersion model,
    PromptTemplateVersion promptTemplate,
    KnowledgeSourceVersion knowledgeSource,
    PolicyVersion policy,
    OutputSchemaVersion outputSchema
) {

    public AiVersionReferences {
        provider = Objects.requireNonNull(provider, "provider must not be null");
        model = Objects.requireNonNull(model, "model must not be null");
        promptTemplate = Objects.requireNonNull(
            promptTemplate,
            "promptTemplate must not be null"
        );
        knowledgeSource = Objects.requireNonNull(
            knowledgeSource,
            "knowledgeSource must not be null"
        );
        policy = Objects.requireNonNull(policy, "policy must not be null");
        outputSchema = Objects.requireNonNull(outputSchema, "outputSchema must not be null");
        if (!provider.providerId().equals(model.providerId())) {
            throw new IllegalArgumentException(
                "provider and model version references must use the same providerId"
            );
        }
    }

    public record ProviderVersion(String providerId, String version) {
        public ProviderVersion {
            providerId = requireText(providerId, "providerId", 120);
            version = requireText(version, "providerVersion", 120);
        }
    }

    public record ModelVersion(String providerId, String modelId, String version) {
        public ModelVersion {
            providerId = requireText(providerId, "providerId", 120);
            modelId = requireText(modelId, "modelId", 160);
            version = requireText(version, "modelVersion", 160);
        }

        public String authorizationKey() {
            return providerId + "/" + modelId + "/" + version;
        }
    }

    public record PromptTemplateVersion(String templateId, String version, String contentHash) {
        public PromptTemplateVersion {
            templateId = requireText(templateId, "templateId", 160);
            version = requireText(version, "promptTemplateVersion", 120);
            contentHash = requireText(contentHash, "promptTemplateHash", 160);
        }
    }

    public record KnowledgeSourceVersion(
        String sourceId,
        String version,
        String contentHash,
        boolean containsCustomerData
    ) {
        public KnowledgeSourceVersion {
            sourceId = requireText(sourceId, "knowledgeSourceId", 160);
            version = requireText(version, "knowledgeSourceVersion", 120);
            contentHash = requireText(contentHash, "knowledgeSourceHash", 160);
        }

        public static KnowledgeSourceVersion none() {
            return new KnowledgeSourceVersion("none", "none", "none", false);
        }
    }

    public record PolicyVersion(String policyId, String version, String contentHash) {
        public PolicyVersion {
            policyId = requireText(policyId, "policyId", 160);
            version = requireText(version, "policyVersion", 120);
            contentHash = requireText(contentHash, "policyHash", 160);
        }
    }

    public record OutputSchemaVersion(String schemaId, int version) {
        public OutputSchemaVersion {
            schemaId = requireText(schemaId, "outputSchemaId", 160);
            if (version < 1) {
                throw new IllegalArgumentException("outputSchemaVersion must be positive");
            }
        }
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
