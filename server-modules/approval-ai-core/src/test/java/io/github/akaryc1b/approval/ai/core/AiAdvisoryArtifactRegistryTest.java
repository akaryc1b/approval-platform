package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAdvisoryArtifactRegistryTest {

    @Test
    void authorizesOnlyTheExactMetadataBundle() {
        AiVersionReferences versions = versions();
        AiAdvisoryArtifactRegistry registry = registry(versions);

        assertTrue(
            registry.authorize(versions, AiCapability.APPROVAL_SUMMARY).allowed()
        );

        AiVersionReferences changedPrompt = new AiVersionReferences(
            versions.provider(),
            versions.model(),
            new AiVersionReferences.PromptTemplateVersion(
                versions.promptTemplate().templateId(),
                "different",
                "different-hash"
            ),
            versions.knowledgeSource(),
            versions.policy(),
            versions.outputSchema()
        );
        assertFalse(
            registry.authorize(
                changedPrompt,
                AiCapability.APPROVAL_SUMMARY
            ).allowed()
        );
    }

    @Test
    void rejectsCustomerKnowledgeAndRetrievalEnablement() {
        AiVersionReferences versions = versions();
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiKnowledgeSourceDescriptor(
                new AiVersionReferences.KnowledgeSourceVersion(
                    "customer-source",
                    "1",
                    "customer-hash",
                    true
                ),
                Set.of(AiCapability.APPROVAL_SUMMARY),
                AiKnowledgeSourceDescriptor.SourceKind.TEST_FIXTURE_METADATA,
                false
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiKnowledgeSourceDescriptor(
                new AiVersionReferences.KnowledgeSourceVersion(
                    "fixture-source",
                    "1",
                    "fixture-hash",
                    false
                ),
                Set.of(AiCapability.APPROVAL_SUMMARY),
                AiKnowledgeSourceDescriptor.SourceKind.TEST_FIXTURE_METADATA,
                true
            )
        );
    }

    @Test
    void rejectsPolicyAndOutputAuthorityEscalation() {
        AiVersionReferences versions = versions();
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiPolicyDescriptor(
                versions.policy(),
                Set.of(AiCapability.APPROVAL_SUMMARY),
                false,
                false,
                false
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiPolicyDescriptor(
                versions.policy(),
                Set.of(AiCapability.APPROVAL_SUMMARY),
                true,
                true,
                false
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiOutputSchemaDescriptor(
                versions.outputSchema(),
                Set.of(AiCapability.APPROVAL_SUMMARY),
                AiOutputSchemaDescriptor.allAdvisorySections(),
                false,
                true
            )
        );
    }

    @Test
    void rejectsDuplicateExactMetadataRegistration() {
        AiVersionReferences versions = versions();
        AiPromptTemplateDescriptor prompt = new AiPromptTemplateDescriptor(
            versions.promptTemplate(),
            Set.of(AiCapability.APPROVAL_SUMMARY),
            AiPromptTemplateDescriptor.Availability.TEST_FIXTURE_METADATA
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiAdvisoryArtifactRegistry(
                List.of(prompt, prompt),
                List.of(new AiKnowledgeSourceDescriptor(
                    versions.knowledgeSource(),
                    Set.of(AiCapability.APPROVAL_SUMMARY),
                    AiKnowledgeSourceDescriptor.SourceKind.NONE,
                    false
                )),
                List.of(new AiPolicyDescriptor(
                    versions.policy(),
                    Set.of(AiCapability.APPROVAL_SUMMARY),
                    true,
                    false,
                    false
                )),
                List.of(new AiOutputSchemaDescriptor(
                    versions.outputSchema(),
                    Set.of(AiCapability.APPROVAL_SUMMARY),
                    AiOutputSchemaDescriptor.allAdvisorySections(),
                    true,
                    true
                ))
            )
        );
    }

    static AiAdvisoryArtifactRegistry registry(AiVersionReferences versions) {
        return new AiAdvisoryArtifactRegistry(
            List.of(new AiPromptTemplateDescriptor(
                versions.promptTemplate(),
                Set.of(AiCapability.APPROVAL_SUMMARY),
                AiPromptTemplateDescriptor.Availability.TEST_FIXTURE_METADATA
            )),
            List.of(new AiKnowledgeSourceDescriptor(
                versions.knowledgeSource(),
                Set.of(AiCapability.APPROVAL_SUMMARY),
                AiKnowledgeSourceDescriptor.SourceKind.NONE,
                false
            )),
            List.of(new AiPolicyDescriptor(
                versions.policy(),
                Set.of(AiCapability.APPROVAL_SUMMARY),
                true,
                false,
                false
            )),
            List.of(new AiOutputSchemaDescriptor(
                versions.outputSchema(),
                Set.of(AiCapability.APPROVAL_SUMMARY),
                AiOutputSchemaDescriptor.allAdvisorySections(),
                true,
                true
            ))
        );
    }

    static AiVersionReferences versions() {
        return new AiVersionReferences(
            new AiVersionReferences.ProviderVersion("deterministic-mock", "1.0.0"),
            new AiVersionReferences.ModelVersion(
                "deterministic-mock",
                "mock-model",
                "2026-07-23"
            ),
            new AiVersionReferences.PromptTemplateVersion(
                "test-template",
                "3",
                "test-template-hash-3"
            ),
            AiVersionReferences.KnowledgeSourceVersion.none(),
            new AiVersionReferences.PolicyVersion(
                "m6-d-third-slice",
                "3",
                "policy-hash-3"
            ),
            new AiVersionReferences.OutputSchemaVersion(
                "approval.ai.advisory",
                1
            )
        );
    }
}
