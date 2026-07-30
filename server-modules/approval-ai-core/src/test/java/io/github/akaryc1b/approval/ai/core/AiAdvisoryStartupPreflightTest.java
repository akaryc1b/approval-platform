package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAdvisoryStartupPreflightTest {

    private final AiAdvisoryStartupPreflight preflight = new AiAdvisoryStartupPreflight();

    @Test
    void acceptsOnlyACompleteExactSnapshotWithoutInvokingProvider() {
        AiVersionReferences versions = AiConfigurationTestFixtures.versions(
            "provider-ready",
            "model-ready"
        );
        DeterministicMockAiProvider provider = AiConfigurationTestFixtures.provider(versions);
        AiAdvisoryArtifactRegistry artifacts = AiConfigurationTestFixtures.artifactRegistry(
            versions
        );
        AiProviderRoutingPolicy routing = routing(versions);
        AiAdvisoryConfigurationSnapshot snapshot = AiConfigurationTestFixtures.snapshot(
            routing,
            versions
        );

        AiAdvisoryPreflightReport report = preflight.inspect(
            snapshot,
            AiConfigurationTestFixtures.providerRegistry(artifacts, provider),
            artifacts
        );

        assertEquals(
            AiAdvisoryPreflightReport.Status.READY_FOR_DRY_RUN,
            report.status()
        );
        assertEquals(1, report.routeChecks().size());
        assertEquals(
            AiAdvisoryPreflightReport.RouteStatus.READY,
            report.routeChecks().get(0).status()
        );
        assertTrue(report.issues().isEmpty());
        assertEquals(0, provider.invocations());
        assertFalse(report.providerInvocationAttempted());
        assertFalse(report.productionEnablementAuthorized());
        assertFalse(report.approvalAutomationAuthorized());
    }

    @Test
    void blocksMissingDataPolicyAndTamperedSnapshotHash() {
        AiVersionReferences versions = AiConfigurationTestFixtures.versions(
            "provider-policy",
            "model-policy"
        );
        DeterministicMockAiProvider provider = AiConfigurationTestFixtures.provider(versions);
        AiAdvisoryArtifactRegistry artifacts = AiConfigurationTestFixtures.artifactRegistry(
            versions
        );
        AiProviderRoutingPolicy routing = routing(versions);
        AiAdvisoryConfigurationSnapshot valid = AiConfigurationTestFixtures.snapshot(
            routing,
            versions
        );
        AiAdvisoryConfigurationSnapshot invalid = new AiAdvisoryConfigurationSnapshot(
            valid.snapshotId(),
            valid.snapshotVersion(),
            "f".repeat(64),
            valid.routingPolicy(),
            Map.of(),
            false,
            false
        );

        AiAdvisoryPreflightReport report = preflight.inspect(
            invalid,
            AiConfigurationTestFixtures.providerRegistry(artifacts, provider),
            artifacts
        );

        assertEquals(AiAdvisoryPreflightReport.Status.BLOCKED, report.status());
        assertTrue(report.issues().stream().anyMatch(
            issue -> "AI_CONFIGURATION_HASH_MISMATCH".equals(issue.code())
        ));
        assertTrue(report.issues().stream().anyMatch(
            issue -> "AI_DATA_POLICY_NOT_REGISTERED".equals(issue.code())
        ));
        assertEquals(0, provider.invocations());
    }

    @Test
    void blocksMissingProviderAndMissingPromptMetadata() {
        AiVersionReferences versions = AiConfigurationTestFixtures.versions(
            "provider-missing",
            "model-missing"
        );
        AiAdvisoryArtifactRegistry incompleteArtifacts = new AiAdvisoryArtifactRegistry(
            List.of(),
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
        AiAdvisoryConfigurationSnapshot snapshot = AiConfigurationTestFixtures.snapshot(
            routing(versions),
            versions
        );

        AiAdvisoryPreflightReport report = preflight.inspect(
            snapshot,
            new AiProviderRegistry(List.of(), incompleteArtifacts),
            incompleteArtifacts
        );

        assertEquals(AiAdvisoryPreflightReport.Status.BLOCKED, report.status());
        assertTrue(report.issues().stream().anyMatch(
            issue -> "AI_PROVIDER_VERSION_NOT_REGISTERED".equals(issue.code())
        ));
        assertTrue(report.issues().stream().anyMatch(
            issue -> "AI_PROMPT_TEMPLATE_NOT_REGISTERED".equals(issue.code())
        ));
    }


    @Test
    void blocksRouteBudgetThatIsLooserThanTheExactDataPolicy() {
        AiVersionReferences versions = AiConfigurationTestFixtures.versions(
            "provider-budget-policy",
            "model-budget-policy"
        );
        DeterministicMockAiProvider provider = AiConfigurationTestFixtures.provider(versions);
        AiAdvisoryArtifactRegistry artifacts = AiConfigurationTestFixtures.artifactRegistry(
            versions
        );
        AiProviderRoute route = new AiProviderRoute(
            "loose-budget",
            1,
            true,
            Set.of(AiCapability.APPROVAL_SUMMARY),
            versions,
            new AiInvocationBudget(java.time.Duration.ofMillis(200), 16_001, 33, 0.60d)
        );
        AiProviderRoutingPolicy routing = new AiProviderRoutingPolicy(
            true,
            false,
            false,
            List.of(route)
        );
        AiAdvisoryConfigurationSnapshot snapshot = AiConfigurationTestFixtures.snapshot(
            routing,
            versions
        );

        AiAdvisoryPreflightReport report = preflight.inspect(
            snapshot,
            AiConfigurationTestFixtures.providerRegistry(artifacts, provider),
            artifacts
        );

        assertEquals(AiAdvisoryPreflightReport.Status.BLOCKED, report.status());
        assertTrue(report.issues().stream().anyMatch(
            issue -> "AI_ROUTE_EXCEEDS_DATA_POLICY_CHARACTER_LIMIT".equals(issue.code())
        ));
        assertTrue(report.issues().stream().anyMatch(
            issue -> "AI_ROUTE_EXCEEDS_DATA_POLICY_FIELD_LIMIT".equals(issue.code())
        ));
        assertEquals(0, provider.invocations());
    }

    @Test
    void disabledRoutingIsSafeAndNonAuthorizing() {
        AiAdvisoryConfigurationSnapshot snapshot = AiAdvisoryConfigurationSnapshot.create(
            "disabled",
            "4",
            AiProviderRoutingPolicy.disabled(),
            Map.of()
        );
        AiAdvisoryArtifactRegistry artifacts = new AiAdvisoryArtifactRegistry(
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );

        AiAdvisoryPreflightReport report = preflight.inspect(
            snapshot,
            new AiProviderRegistry(List.of(), artifacts),
            artifacts
        );

        assertEquals(AiAdvisoryPreflightReport.Status.DISABLED, report.status());
        assertTrue(report.routeChecks().isEmpty());
        assertFalse(report.providerInvocationAttempted());
    }

    private static AiProviderRoutingPolicy routing(AiVersionReferences versions) {
        return new AiProviderRoutingPolicy(
            true,
            false,
            false,
            List.of(AiConfigurationTestFixtures.route("primary", 1, versions))
        );
    }
}
