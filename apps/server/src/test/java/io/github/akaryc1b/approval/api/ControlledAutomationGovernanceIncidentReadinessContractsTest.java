package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.UseCase;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistorySummary;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistoryWindow;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.OutcomeCount;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.UseCaseCount;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.VersionStability;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesAdvisoryProvider;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProductionRuntimeFactory;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProductionRuntimeFactory
    .RuntimeControlSnapshot;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProductionRuntimeFactory.RuntimeProfile;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesRuntimeUsageLedger.UsageSnapshot;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesTransportControls;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesTransportControls.CircuitBreaker;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceControlHealthContracts
    .ControlHealthView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceHistoryContracts.HistoryView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceIncidentReadinessContracts
    .ControlPosture;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceIncidentReadinessContracts
    .IncidentReadinessView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceIncidentReadinessContracts
    .IncidentSignal;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceIncidentReadinessContracts
    .ReadinessState;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceIncidentReadinessContracts
    .RollbackPosture;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernancePlanContracts.Operation;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernancePlanContracts.ReviewPlan;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.InventoryEntry;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.OperationsView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.RuntimeControls;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceUsageContracts.UsageHealth;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceUsageContracts.UsageView;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlledAutomationGovernanceIncidentReadinessContractsTest {

    private static final Instant NOW = Instant.parse("2026-08-06T02:00:00Z");
    private static final Instant FROM = NOW.minusSeconds(3_600);
    private static final String TENANT_HASH = OpenAiResponsesProtocol.sha256Utf8("tenant-a");

    @Test
    void disabledRuntimeProducesManualNoActionReadiness() {
        OperationsView snapshot = OperationsView.disabled(NOW, inventory());
        IncidentReadinessView view = IncidentReadinessView.from(
            snapshot,
            ControlHealthView.disabled(snapshot),
            UsageView.disabled(snapshot),
            HistoryView.from(snapshot, HistorySummary.empty(window())),
            ReviewPlan.preview(Operation.ROLLBACK, snapshot)
        );

        assertEquals(ReadinessState.RUNTIME_NOT_CONFIGURED, view.readinessState());
        assertEquals(ControlPosture.NOT_CONFIGURED, view.controlPosture());
        assertEquals(UsageHealth.NOT_CONFIGURED, view.usageHealth());
        assertEquals(RollbackPosture.ALREADY_DISABLED, view.rollbackPosture());
        assertTrue(
            view.incidentSignals().contains(
                IncidentSignal.AI_PROVIDER_RUNTIME_NOT_CONFIGURED
            )
        );
        assertTrue(
            view.rollbackOperatorStepCodes().contains(
                "AI_ROLLBACK_STEP_NO_ACTION_REQUIRED_RUNTIME_ALREADY_DISABLED"
            )
        );
        assertFalse(view.providerInvocationAvailable());
        assertFalse(view.rollbackExecutionAvailable());
        assertFalse(view.commandExecutionAuthorized());
        assertFalse(view.automaticRetryAuthorized());
    }

    @Test
    void healthyRuntimeRemainsObservationReadyAndAdvisoryOnly() {
        RuntimeProfile profile = profile();
        OpenAiResponsesProductionRuntimeFactory factory = factory(profile);
        OperationsView snapshot = OperationsView.configured(
            NOW,
            inventory(),
            controls(profile)
        );
        IncidentReadinessView view = IncidentReadinessView.from(
            snapshot,
            ControlHealthView.configured(snapshot, factory.controlSnapshot()),
            UsageView.configured(snapshot, factory.usageSnapshot("tenant-a")),
            HistoryView.from(snapshot, HistorySummary.empty(window())),
            ReviewPlan.preview(Operation.ROLLBACK, snapshot)
        );

        assertEquals(
            ReadinessState.OBSERVATION_READY_ADVISORY_ONLY,
            view.readinessState()
        );
        assertEquals(ControlPosture.HEALTHY, view.controlPosture());
        assertEquals(UsageHealth.WITHIN_DERIVED_ENVELOPE, view.usageHealth());
        assertEquals(
            RollbackPosture.REVIEW_READY_MANUAL_RELEASE,
            view.rollbackPosture()
        );
        assertTrue(
            view.incidentSignals().contains(IncidentSignal.AI_DURABLE_HISTORY_EMPTY)
        );
        assertTrue(
            view.operatorStepCodes().contains(
                "AI_INCIDENT_STEP_CONTINUE_READ_ONLY_MONITORING"
            )
        );
        assertEquals(snapshot.evidenceHash(), view.evidenceReferences()
            .snapshotEvidenceHash());
        assertEquals(64, view.evidenceHash().length());
    }

    @Test
    void circuitRateHistoryAndRetentionSignalsFailClosed() {
        RuntimeProfile profile = profile();
        OpenAiResponsesProductionRuntimeFactory factory = factory(profile);
        OperationsView snapshot = OperationsView.configured(
            NOW,
            inventory(),
            controls(profile)
        );
        RuntimeControlSnapshot current = factory.controlSnapshot();
        RuntimeControlSnapshot openCircuit = new RuntimeControlSnapshot(
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
            CircuitBreaker.State.OPEN,
            current.circuitGeneration(),
            false,
            false
        );
        UsageSnapshot saturated = new UsageSnapshot(
            NOW,
            TENANT_HASH,
            NOW,
            NOW.plusSeconds(60),
            10,
            10,
            100,
            10_000_000,
            10_000_000,
            100_000_000,
            false,
            true,
            false,
            false,
            OpenAiResponsesProtocol.sha256Utf8("saturated-usage")
        );
        IncidentReadinessView view = IncidentReadinessView.from(
            snapshot,
            ControlHealthView.configured(snapshot, openCircuit),
            UsageView.configured(snapshot, saturated),
            HistoryView.from(snapshot, driftedHistory()),
            ReviewPlan.preview(Operation.ROLLBACK, snapshot)
        );

        assertEquals(ReadinessState.INCIDENT_BLOCKED, view.readinessState());
        assertEquals(ControlPosture.BLOCKED, view.controlPosture());
        assertTrue(view.incidentSignals().contains(IncidentSignal.AI_PROVIDER_CIRCUIT_OPEN));
        assertTrue(
            view.incidentSignals().contains(
                IncidentSignal.AI_TENANT_RATE_WINDOW_SATURATED
            )
        );
        assertTrue(
            view.incidentSignals().contains(
                IncidentSignal.AI_DURABLE_HISTORY_VERSION_DRIFT_DETECTED
            )
        );
        assertTrue(
            view.incidentSignals().contains(IncidentSignal.AI_RETENTION_TOMBSTONE_DUE)
        );
        assertTrue(
            view.operatorStepCodes().contains(
                "AI_INCIDENT_STEP_REVIEW_NON_EXECUTABLE_ROLLBACK_PLAN"
            )
        );
        assertTrue(
            view.operatorStepCodes().contains(
                "AI_INCIDENT_STEP_REVIEW_RETENTION_TOMBSTONES"
            )
        );
        assertTrue(
            view.operatorStepCodes().contains(
                "AI_INCIDENT_STEP_DO_NOT_AUTOMATICALLY_RETRY"
            )
        );
    }

    @Test
    void mismatchedComponentSnapshotIsRejected() {
        OperationsView first = OperationsView.disabled(NOW, inventory());
        OperationsView second = OperationsView.disabled(NOW.plusSeconds(1), inventory());

        assertThrows(
            IllegalArgumentException.class,
            () -> IncidentReadinessView.from(
                first,
                ControlHealthView.disabled(second),
                UsageView.disabled(first),
                HistoryView.from(first, HistorySummary.empty(window())),
                ReviewPlan.preview(Operation.ROLLBACK, first)
            )
        );
    }

    private static OpenAiResponsesProductionRuntimeFactory factory(RuntimeProfile profile) {
        return new OpenAiResponsesProductionRuntimeFactory(
            profile,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
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

    private static HistorySummary driftedHistory() {
        return new HistorySummary(
            window(),
            2,
            2,
            0,
            2,
            2,
            1,
            0,
            0,
            1,
            NOW.minusSeconds(1_200),
            NOW.minusSeconds(300),
            outcomes(),
            List.of(
                new UseCaseCount(
                    UseCase.SUMMARY,
                    2,
                    2,
                    1,
                    2,
                    VersionStability.MULTIPLE_VERSION_BUNDLES
                ),
                UseCaseCount.empty(UseCase.MATERIAL_COMPLETENESS),
                UseCaseCount.empty(UseCase.RISK_REVIEW)
            )
        );
    }

    private static List<OutcomeCount> outcomes() {
        return Arrays.stream(AiOutcomeClassification.values())
            .map(classification -> new OutcomeCount(
                classification,
                classification == AiOutcomeClassification.SUCCESS
                    ? 1
                    : classification == AiOutcomeClassification.REJECTED
                        ? 1
                        : 0
            ))
            .toList();
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
