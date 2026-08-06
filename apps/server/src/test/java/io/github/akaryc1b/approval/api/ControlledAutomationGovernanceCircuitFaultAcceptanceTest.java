package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistorySummary;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistoryWindow;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesAdvisoryProvider;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProductionRuntimeFactory;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProductionRuntimeFactory
    .RuntimeControlSnapshot;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProductionRuntimeFactory.RuntimeProfile;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesTransportControls;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceControlHealthContracts
    .ControlHealthView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceHistoryContracts.HistoryView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceIncidentReadinessContracts
    .IncidentReadinessView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceIncidentReadinessContracts
    .IncidentSignal;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceIncidentReadinessContracts
    .ReadinessState;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernancePlanContracts.Operation;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernancePlanContracts.ReviewPlan;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.InventoryEntry;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.OperationsView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.RuntimeControls;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceUsageContracts.UsageView;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlledAutomationGovernanceCircuitFaultAcceptanceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T04:00:00Z");
    private static final Instant FROM = NOW.minusSeconds(3_600);

    @Test
    void openAndHalfOpenCircuitStatesRemainIncidentBlockedAndNonExecuting() {
        for (OpenAiResponsesTransportControls.CircuitBreaker.State state : List.of(
            OpenAiResponsesTransportControls.CircuitBreaker.State.OPEN,
            OpenAiResponsesTransportControls.CircuitBreaker.State.HALF_OPEN
        )) {
            RuntimeProfile profile = profile();
            OpenAiResponsesProductionRuntimeFactory factory =
                new OpenAiResponsesProductionRuntimeFactory(
                    profile,
                    Clock.fixed(NOW, ZoneOffset.UTC)
                );
            OperationsView snapshot = OperationsView.configured(
                NOW,
                inventory(),
                controls(profile)
            );
            RuntimeControlSnapshot current = factory.controlSnapshot();
            RuntimeControlSnapshot blocked = new RuntimeControlSnapshot(
                current.observedAt(),
                current.killSwitchEnabled(),
                current.killSwitchGeneration(),
                current.killSwitchEvidenceHash(),
                current.costPolicyEvidenceHash(),
                current.costPolicyEffectiveFrom(),
                current.costPolicyExpiresAt(),
                current.secretVersionEvidenceHash(),
                current.secretVersionEffectiveFrom(),
                current.secretVersionExpiresAt(),
                current.perTenantRateLimit(),
                current.globalRateLimit(),
                current.rateWindowSeconds(),
                current.circuitFailureThreshold(),
                current.circuitOpenSeconds(),
                current.maximumRequestMicros(),
                state,
                current.circuitGeneration() + 1,
                false,
                false
            );
            IncidentReadinessView view = IncidentReadinessView.from(
                snapshot,
                ControlHealthView.configured(snapshot, blocked),
                UsageView.configured(snapshot, factory.usageSnapshot("tenant-a")),
                HistoryView.from(snapshot, HistorySummary.empty(window())),
                ReviewPlan.preview(Operation.ROLLBACK, snapshot)
            );

            assertEquals(ReadinessState.INCIDENT_BLOCKED, view.readinessState());
            IncidentSignal expected = state
                == OpenAiResponsesTransportControls.CircuitBreaker.State.OPEN
                ? IncidentSignal.AI_PROVIDER_CIRCUIT_OPEN
                : IncidentSignal.AI_PROVIDER_CIRCUIT_HALF_OPEN;
            assertTrue(view.incidentSignals().contains(expected));
            assertTrue(view.operatorStepCodes().contains(
                "AI_INCIDENT_STEP_DO_NOT_AUTOMATICALLY_RETRY"
            ));
            assertFalse(view.providerInvocationAvailable());
            assertFalse(view.rollbackExecutionAvailable());
            assertFalse(view.commandExecutionAuthorized());
            assertFalse(view.automaticRetryAuthorized());
            assertFalse(view.notificationAutomationAvailable());
        }
    }

    private static RuntimeProfile profile() {
        return new RuntimeProfile(
            "openai-key-v1",
            NOW.minusSeconds(3_600),
            NOW.plusSeconds(3_600),
            "secret-policy-v1",
            7,
            "kill-switch-policy-v1",
            "cost-policy-v1",
            NOW.minusSeconds(3_600),
            NOW.plusSeconds(3_600),
            1,
            1,
            1_000_000,
            10,
            100,
            Duration.ofSeconds(60),
            3,
            Duration.ofSeconds(60)
        );
    }

    private static RuntimeControls controls(RuntimeProfile profile) {
        var killSwitch = new OpenAiResponsesTransportControls.KillSwitchSnapshot(
            OpenAiResponsesProtocol.PROVIDER_ID,
            OpenAiResponsesProtocol.PROVIDER_VERSION,
            profile.killSwitchGeneration(),
            true,
            profile.killSwitchPolicyRevision()
        );
        var costPolicy = new OpenAiResponsesTransportControls.CostPolicy(
            profile.costPolicyVersion(),
            OpenAiResponsesProtocol.MODEL_SNAPSHOT,
            profile.inputMicrosPerConservativeToken(),
            profile.outputMicrosPerToken(),
            profile.maximumRequestMicros(),
            profile.costPolicyEffectiveFrom(),
            profile.costPolicyExpiresAt()
        );
        return new RuntimeControls(
            killSwitch.generation(),
            killSwitch.evidenceHash(),
            costPolicy.evidenceHash(),
            OpenAiResponsesProtocol.sha256Utf8(profile.secretVersionReference()),
            profile.perTenantRateLimit(),
            profile.globalRateLimit(),
            profile.rateWindow().toSeconds(),
            profile.circuitFailureThreshold(),
            profile.circuitOpenDuration().toSeconds(),
            profile.maximumRequestMicros()
        );
    }

    private static HistoryWindow window() {
        return new HistoryWindow("tenant-a", FROM, NOW, NOW);
    }

    private static List<InventoryEntry> inventory() {
        AiVersionReferences.PolicyVersion policy = new AiVersionReferences.PolicyVersion(
            "approval-assistance-production",
            "p6-e-v1",
            OpenAiResponsesProtocol.sha256Utf8(
                "approval-assistance-production-policy/p6-e-v1/advisory-only"
            )
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
}
