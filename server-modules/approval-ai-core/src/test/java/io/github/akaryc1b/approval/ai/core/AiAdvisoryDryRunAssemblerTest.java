package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAdvisoryDryRunAssemblerTest {

    @Test
    void createsDeterministicCandidatePlanWithoutProviderInvocation() {
        AiVersionReferences primaryVersions = AiConfigurationTestFixtures.versions(
            "provider-primary",
            "model-primary"
        );
        AiVersionReferences backupVersions = new AiVersionReferences(
            new AiVersionReferences.ProviderVersion("provider-backup", "1.0.0"),
            new AiVersionReferences.ModelVersion(
                "provider-backup",
                "model-backup",
                "2026-07-23"
            ),
            primaryVersions.promptTemplate(),
            primaryVersions.knowledgeSource(),
            primaryVersions.policy(),
            primaryVersions.outputSchema()
        );
        DeterministicMockAiProvider primary = AiConfigurationTestFixtures.provider(
            primaryVersions
        );
        DeterministicMockAiProvider backup = AiConfigurationTestFixtures.provider(
            backupVersions
        );
        AiAdvisoryArtifactRegistry artifacts = AiConfigurationTestFixtures.artifactRegistry(
            primaryVersions,
            backupVersions
        );
        AiProviderRoutingPolicy routing = new AiProviderRoutingPolicy(
            true,
            true,
            false,
            List.of(
                AiConfigurationTestFixtures.route("backup", 20, backupVersions),
                AiConfigurationTestFixtures.route("primary", 10, primaryVersions)
            )
        );
        AiAdvisoryConfigurationSnapshot snapshot = AiConfigurationTestFixtures.snapshot(
            routing,
            primaryVersions,
            backupVersions
        );
        AiAdvisoryPreflightReport preflight = new AiAdvisoryStartupPreflight().inspect(
            snapshot,
            AiConfigurationTestFixtures.providerRegistry(artifacts, primary, backup),
            artifacts
        );

        AiAdvisoryDryRunReport report = new AiAdvisoryDryRunAssembler().assemble(
            snapshot,
            preflight
        );

        assertEquals(AiAdvisoryDryRunReport.Status.READY, report.status());
        assertEquals(1, report.capabilityPlans().size());
        AiAdvisoryDryRunReport.CapabilityPlan plan = report.capabilityPlans().get(0);
        assertEquals(AiCapability.APPROVAL_SUMMARY, plan.capability());
        assertEquals("primary", plan.primaryRouteId());
        assertEquals(List.of("primary", "backup"), plan.orderedCandidateRouteIds());
        assertEquals(0, primary.invocations());
        assertEquals(0, backup.invocations());
        assertFalse(report.providerInvocationAttempted());
        assertFalse(report.productionEnablementAuthorized());
        assertFalse(report.approvalAutomationAuthorized());
    }

    @Test
    void blockedPreflightCannotProduceAReadyDryRun() {
        AiVersionReferences versions = AiConfigurationTestFixtures.versions(
            "provider-blocked",
            "model-blocked"
        );
        AiProviderRoutingPolicy routing = new AiProviderRoutingPolicy(
            true,
            false,
            false,
            List.of(AiConfigurationTestFixtures.route("blocked", 1, versions))
        );
        AiAdvisoryConfigurationSnapshot snapshot = AiConfigurationTestFixtures.snapshot(
            routing,
            versions
        );
        AiAdvisoryPreflightReport blocked = new AiAdvisoryPreflightReport(
            snapshot.snapshotId(),
            snapshot.snapshotVersion(),
            snapshot.declaredContentHash(),
            snapshot.computedContentHash(),
            AiAdvisoryPreflightReport.Status.BLOCKED,
            List.of(),
            List.of(new AiAdvisoryPreflightReport.Issue(
                "AI_TEST_BLOCK",
                "test preflight is blocked",
                "blocked",
                AiCapability.APPROVAL_SUMMARY,
                AiAdvisoryPreflightReport.Severity.ERROR
            )),
            false,
            false,
            false
        );

        AiAdvisoryDryRunReport report = new AiAdvisoryDryRunAssembler().assemble(
            snapshot,
            blocked
        );

        assertEquals(AiAdvisoryDryRunReport.Status.BLOCKED, report.status());
        assertTrue(report.capabilityPlans().isEmpty());
        assertEquals(List.of("AI_TEST_BLOCK"), report.blockingCodes());
    }

    @Test
    void dryRunReportRejectsInvocationAndAuthorityFlags() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiAdvisoryDryRunReport(
                "snapshot",
                "4",
                "a".repeat(64),
                AiAdvisoryDryRunReport.Status.DISABLED,
                List.of(),
                List.of(),
                true,
                false,
                false
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiAdvisoryDryRunReport(
                "snapshot",
                "4",
                "a".repeat(64),
                AiAdvisoryDryRunReport.Status.DISABLED,
                List.of(),
                List.of(),
                false,
                true,
                false
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiAdvisoryDryRunReport(
                "snapshot",
                "4",
                "a".repeat(64),
                AiAdvisoryDryRunReport.Status.DISABLED,
                List.of(),
                List.of(),
                false,
                false,
                true
            )
        );
    }
}
