package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesAdvisoryProvider;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernancePlanContracts.Operation;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernancePlanContracts.ReviewPlan;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernancePlanContracts.RollbackMechanism;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernancePlanContracts.Status;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernancePlanContracts.TargetRuntimeState;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.InventoryEntry;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.OperationsView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.RuntimeControls;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlledAutomationGovernancePlanContractsTest {

    private static final Instant NOW = Instant.parse("2026-08-05T10:00:00Z");

    @Test
    void configuredCanaryPlanIsBlockedAndCannotAllocateTraffic() {
        ReviewPlan plan = ReviewPlan.preview(Operation.CANARY, configured());

        assertEquals(Status.BLOCKED, plan.status());
        assertEquals(TargetRuntimeState.UNCHANGED, plan.targetRuntimeState());
        assertEquals(RollbackMechanism.NONE, plan.rollbackMechanism());
        assertEquals(0, plan.plannedTrafficPercent());
        assertTrue(plan.blockerCodes().contains("AI_PROVIDER_CANARY_RUNTIME_NOT_IMPLEMENTED"));
        assertTrue(plan.blockerCodes().contains("AI_PROVIDER_SECOND_VERSION_NOT_AVAILABLE"));
        assertTrue(plan.blockerCodes().contains("AI_PROVIDER_TRAFFIC_MUTATION_NOT_AVAILABLE"));
        assertTrue(plan.blockerCodes().contains("AI_PRODUCTION_REAUTHENTICATION_UNAVAILABLE"));
        assertTrue(plan.operatorStepCodes().isEmpty());
        assertNoAuthority(plan);
    }

    @Test
    void configuredRolloutPlanRequiresCanaryEvidenceAndRemainsBlocked() {
        ReviewPlan plan = ReviewPlan.preview(Operation.ROLLOUT, configured());

        assertEquals(Status.BLOCKED, plan.status());
        assertEquals(TargetRuntimeState.UNCHANGED, plan.targetRuntimeState());
        assertTrue(plan.blockerCodes().contains("AI_PROVIDER_CANARY_EVIDENCE_NOT_AVAILABLE"));
        assertTrue(plan.blockerCodes().contains("AI_PROVIDER_ROLLOUT_MUTATION_NOT_AVAILABLE"));
        assertTrue(plan.blockerCodes().contains("AI_PROVIDER_SECOND_VERSION_NOT_AVAILABLE"));
        assertTrue(plan.operatorStepCodes().isEmpty());
        assertNoAuthority(plan);
    }

    @Test
    void configuredRollbackPlanIsReviewReadyButRequiresManualReleaseProcess() {
        ReviewPlan plan = ReviewPlan.preview(Operation.ROLLBACK, configured());

        assertEquals(Status.REVIEW_READY, plan.status());
        assertTrue(plan.blockerCodes().isEmpty());
        assertEquals(TargetRuntimeState.DISABLED, plan.targetRuntimeState());
        assertEquals(
            RollbackMechanism.DISABLE_RUNTIME_FLAG_AND_REDEPLOY,
            plan.rollbackMechanism()
        );
        assertEquals(
            List.of(
                "AI_ROLLBACK_STEP_DISABLE_EXISTING_RUNTIME_FLAG",
                "AI_ROLLBACK_STEP_REDEPLOY_THROUGH_ESTABLISHED_RELEASE_PROCESS",
                "AI_ROLLBACK_STEP_VERIFY_READ_ONLY_GOVERNANCE_SNAPSHOT"
            ),
            plan.operatorStepCodes()
        );
        assertNoAuthority(plan);
    }

    @Test
    void disabledRuntimeRollbackRequiresNoActionAndNoMutation() {
        ReviewPlan plan = ReviewPlan.preview(Operation.ROLLBACK, disabled());

        assertEquals(Status.REVIEW_READY, plan.status());
        assertEquals(RollbackMechanism.ALREADY_DISABLED, plan.rollbackMechanism());
        assertEquals(
            List.of("AI_ROLLBACK_STEP_NO_ACTION_REQUIRED_RUNTIME_ALREADY_DISABLED"),
            plan.operatorStepCodes()
        );
        assertNoAuthority(plan);
    }

    @Test
    void disabledRuntimeAlsoBlocksCanaryAndRollout() {
        for (Operation operation : List.of(Operation.CANARY, Operation.ROLLOUT)) {
            ReviewPlan plan = ReviewPlan.preview(operation, disabled());
            assertEquals(Status.BLOCKED, plan.status());
            assertTrue(plan.blockerCodes().contains("AI_PROVIDER_RUNTIME_NOT_CONFIGURED"));
            assertNoAuthority(plan);
        }
    }

    @Test
    void sameSnapshotAndOperationProduceTheSameEvidenceHash() {
        OperationsView source = configured();

        ReviewPlan first = ReviewPlan.preview(Operation.ROLLBACK, source);
        ReviewPlan second = ReviewPlan.preview(Operation.ROLLBACK, source);

        assertEquals(first, second);
        assertEquals(first.evidenceHash(), second.evidenceHash());
    }

    @Test
    void sourceSnapshotTamperingAndApplyAuthorityAreRejected() {
        ReviewPlan plan = ReviewPlan.preview(Operation.ROLLBACK, configured());

        assertThrows(
            IllegalArgumentException.class,
            () -> copy(plan, "0".repeat(64), false)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> copy(plan, plan.sourceSnapshotHash(), true)
        );
    }

    private static ReviewPlan copy(
        ReviewPlan source,
        String sourceSnapshotHash,
        boolean applyAuthorized
    ) {
        return new ReviewPlan(
            source.planVersion(),
            source.observedAt(),
            source.operation(),
            source.mode(),
            source.status(),
            sourceSnapshotHash,
            source.sourceRuntimeState(),
            source.targetRuntimeState(),
            source.inventory(),
            source.plannedTrafficPercent(),
            source.rollbackMechanism(),
            source.blockerCodes(),
            source.operatorStepCodes(),
            source.actionWhitelistState(),
            source.p5Decision(),
            source.productionReauthenticationAvailable(),
            source.providerInvocationAuthorized(),
            source.secretResolutionAuthorized(),
            source.trafficMutationAuthorized(),
            source.configurationMutationAuthorized(),
            source.deploymentAuthorized(),
            applyAuthorized,
            source.commandExecutionAuthorized(),
            source.automaticRetryAuthorized(),
            source.evidenceHash()
        );
    }

    private static void assertNoAuthority(ReviewPlan plan) {
        assertFalse(plan.productionReauthenticationAvailable());
        assertFalse(plan.providerInvocationAuthorized());
        assertFalse(plan.secretResolutionAuthorized());
        assertFalse(plan.trafficMutationAuthorized());
        assertFalse(plan.configurationMutationAuthorized());
        assertFalse(plan.deploymentAuthorized());
        assertFalse(plan.applyAuthorized());
        assertFalse(plan.commandExecutionAuthorized());
        assertFalse(plan.automaticRetryAuthorized());
    }

    private static OperationsView configured() {
        return OperationsView.configured(
            NOW,
            inventory(),
            new RuntimeControls(
                7,
                hash("kill-switch"),
                hash("cost-policy"),
                hash("secret-version"),
                10,
                100,
                60,
                3,
                60,
                1_000_000
            )
        );
    }

    private static OperationsView disabled() {
        return OperationsView.disabled(NOW, inventory());
    }

    private static List<InventoryEntry> inventory() {
        AiVersionReferences.PolicyVersion policy = new AiVersionReferences.PolicyVersion(
            "approval-assistance-production",
            "p6-e-v1",
            hash("approval-assistance-production-policy/p6-e-v1/advisory-only")
        );
        return List.of(
            entry(AiCapability.APPROVAL_SUMMARY, policy),
            entry(AiCapability.MATERIAL_COMPLETENESS, policy),
            entry(AiCapability.RISK_SIGNALS, policy)
        );
    }

    private static InventoryEntry entry(
        AiCapability capability,
        AiVersionReferences.PolicyVersion policy
    ) {
        return new InventoryEntry(
            capability,
            new AiVersionReferences(
                OpenAiResponsesAdvisoryProvider.providerVersion(),
                OpenAiResponsesAdvisoryProvider.modelVersion(),
                OpenAiResponsesAdvisoryProvider.promptVersion(capability),
                AiVersionReferences.KnowledgeSourceVersion.none(),
                policy,
                OpenAiResponsesAdvisoryProvider.outputSchemaVersion()
            )
        );
    }

    private static String hash(String value) {
        return OpenAiResponsesProtocol.sha256Utf8(value);
    }
}
