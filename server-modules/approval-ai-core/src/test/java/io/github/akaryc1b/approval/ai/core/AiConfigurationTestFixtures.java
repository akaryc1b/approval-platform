package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AiConfigurationTestFixtures {

    private AiConfigurationTestFixtures() {
    }

    static AiVersionReferences versions(String providerId, String modelId) {
        return new AiVersionReferences(
            new AiVersionReferences.ProviderVersion(providerId, "1.0.0"),
            new AiVersionReferences.ModelVersion(providerId, modelId, "2026-07-23"),
            new AiVersionReferences.PromptTemplateVersion(
                "test-template",
                "4",
                "test-template-hash-4"
            ),
            AiVersionReferences.KnowledgeSourceVersion.none(),
            new AiVersionReferences.PolicyVersion(
                "m6-d-fourth-slice",
                "4",
                "policy-hash-4"
            ),
            new AiVersionReferences.OutputSchemaVersion("approval.ai.advisory", 1)
        );
    }

    static AiProviderRoute route(
        String routeId,
        int priority,
        AiVersionReferences versions
    ) {
        return new AiProviderRoute(
            routeId,
            priority,
            true,
            Set.of(AiCapability.APPROVAL_SUMMARY),
            versions,
            new AiInvocationBudget(Duration.ofMillis(200), 16_000, 32, 0.60d)
        );
    }

    static AiDataMinimizationPolicy dataPolicy(AiVersionReferences versions) {
        return new AiDataMinimizationPolicy(
            versions.policy(),
            Map.of("description", AiDataMinimizationPolicy.FieldRule.INCLUDE),
            new AiDataMinimizationPolicy.InputLimits(32, 2_000, 16_000, 50, 4),
            true
        );
    }

    static AiAdvisoryArtifactRegistry artifactRegistry(AiVersionReferences... versions) {
        return new AiAdvisoryArtifactRegistry(
            java.util.Arrays.stream(versions)
                .map(value -> new AiPromptTemplateDescriptor(
                    value.promptTemplate(),
                    Set.of(AiCapability.APPROVAL_SUMMARY),
                    AiPromptTemplateDescriptor.Availability.TEST_FIXTURE_METADATA
                ))
                .distinct()
                .toList(),
            java.util.Arrays.stream(versions)
                .map(value -> new AiKnowledgeSourceDescriptor(
                    value.knowledgeSource(),
                    Set.of(AiCapability.APPROVAL_SUMMARY),
                    AiKnowledgeSourceDescriptor.SourceKind.NONE,
                    false
                ))
                .distinct()
                .toList(),
            java.util.Arrays.stream(versions)
                .map(value -> new AiPolicyDescriptor(
                    value.policy(),
                    Set.of(AiCapability.APPROVAL_SUMMARY),
                    true,
                    false,
                    false
                ))
                .distinct()
                .toList(),
            java.util.Arrays.stream(versions)
                .map(value -> new AiOutputSchemaDescriptor(
                    value.outputSchema(),
                    Set.of(AiCapability.APPROVAL_SUMMARY),
                    AiOutputSchemaDescriptor.allAdvisorySections(),
                    true,
                    true
                ))
                .distinct()
                .toList()
        );
    }

    static DeterministicMockAiProvider provider(AiVersionReferences versions) {
        return new DeterministicMockAiProvider(
            DeterministicMockAiProvider.Mode.SUCCESS,
            versions,
            Set.of(AiCapability.APPROVAL_SUMMARY),
            null
        );
    }

    static AiProviderRegistry providerRegistry(
        AiAdvisoryArtifactRegistry artifacts,
        DeterministicMockAiProvider... providers
    ) {
        return new AiProviderRegistry(List.of(providers), artifacts);
    }

    static AiAdvisoryConfigurationSnapshot snapshot(
        AiProviderRoutingPolicy routing,
        AiVersionReferences... versions
    ) {
        Map<AiVersionReferences.PolicyVersion, AiDataMinimizationPolicy> policies =
            java.util.Arrays.stream(versions)
                .collect(java.util.stream.Collectors.toMap(
                    AiVersionReferences::policy,
                    AiConfigurationTestFixtures::dataPolicy,
                    (left, right) -> left
                ));
        return AiAdvisoryConfigurationSnapshot.create(
            "snapshot-four",
            "4",
            routing,
            policies
        );
    }
}
