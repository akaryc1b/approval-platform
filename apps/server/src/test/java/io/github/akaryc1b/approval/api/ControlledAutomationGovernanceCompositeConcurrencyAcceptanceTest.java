package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistorySummary;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistoryWindow;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesAdvisoryProvider;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProductionRuntimeFactory;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesTransportControls;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceControlHealthContracts
    .ControlHealthView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceHistoryContracts.HistoryView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceIncidentReadinessContracts
    .EvidenceReferences;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceIncidentReadinessContracts
    .IncidentReadinessView;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlledAutomationGovernanceCompositeConcurrencyAcceptanceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T06:00:00Z");

    @Test
    void everyComponentFromAnotherObservationCycleIsRejected() {
        Components first = disabledComponents(NOW);
        Components second = disabledComponents(NOW.plusSeconds(1));

        assertThrows(IllegalArgumentException.class, () -> IncidentReadinessView.from(
            first.snapshot(),
            second.control(),
            first.usage(),
            first.history(),
            first.rollback()
        ));
        assertThrows(IllegalArgumentException.class, () -> IncidentReadinessView.from(
            first.snapshot(),
            first.control(),
            second.usage(),
            first.history(),
            first.rollback()
        ));
        assertThrows(IllegalArgumentException.class, () -> IncidentReadinessView.from(
            first.snapshot(),
            first.control(),
            first.usage(),
            second.history(),
            first.rollback()
        ));
        assertThrows(IllegalArgumentException.class, () -> IncidentReadinessView.from(
            first.snapshot(),
            first.control(),
            first.usage(),
            first.history(),
            second.rollback()
        ));
    }

    @Test
    void retryCannotCherryPickAHealthierRuntimeOrSplicePriorComponents() {
        Components disabled = disabledComponents(NOW);
        Components configured = configuredComponents(NOW.plusSeconds(1));

        IncidentReadinessView disabledView = disabled.view();
        IncidentReadinessView configuredView = configured.view();
        assertNotEquals(disabledView.runtimeState(), configuredView.runtimeState());
        assertNotEquals(disabledView.evidenceHash(), configuredView.evidenceHash());

        assertThrows(IllegalArgumentException.class, () -> IncidentReadinessView.from(
            disabled.snapshot(),
            configured.control(),
            configured.usage(),
            disabled.history(),
            configured.rollback()
        ));
        assertThrows(IllegalArgumentException.class, () -> IncidentReadinessView.from(
            configured.snapshot(),
            disabled.control(),
            disabled.usage(),
            configured.history(),
            disabled.rollback()
        ));
    }

    @Test
    void replacingAnyComponentReferenceCannotReuseAnOlderCompositeHash() {
        Components first = disabledComponents(NOW);
        Components second = disabledComponents(NOW.plusSeconds(1));
        IncidentReadinessView original = first.view();
        EvidenceReferences replaced = new EvidenceReferences(
            original.evidenceReferences().snapshotEvidenceHash(),
            second.view().evidenceReferences().controlHealthEvidenceHash(),
            original.evidenceReferences().usageEvidenceHash(),
            original.evidenceReferences().historyEvidenceHash(),
            original.evidenceReferences().rollbackPlanEvidenceHash()
        );

        assertThrows(IllegalArgumentException.class, () -> new IncidentReadinessView(
            original.viewVersion(),
            original.observedAt(),
            original.fromInclusive(),
            original.toExclusive(),
            original.runtimeState(),
            original.readinessState(),
            original.controlPosture(),
            original.usageHealth(),
            original.historyHealth(),
            original.rollbackPosture(),
            replaced,
            original.incidentSignals(),
            original.operatorStepCodes(),
            original.rollbackOperatorStepCodes(),
            original.blockerCodes(),
            original.actionWhitelistState(),
            original.p5Decision(),
            original.durableEvidenceAvailable(),
            original.processLocalUsageOnly(),
            original.incidentMutationAvailable(),
            original.providerInvocationAvailable(),
            original.rollbackExecutionAvailable(),
            original.commandExecutionAuthorized(),
            original.automaticRetryAuthorized(),
            original.notificationAutomationAvailable(),
            original.rawSecretExposed(),
            original.evidenceHash()
        ));
    }

    @Test
    void coherentRetriesProduceIndependentWholeSnapshotsOnly() {
        Components first = configuredComponents(NOW);
        Components second = configuredComponents(NOW.plusSeconds(1));
        IncidentReadinessView firstView = first.view();
        IncidentReadinessView secondView = second.view();

        assertEquals(NOW, firstView.observedAt());
        assertEquals(NOW.plusSeconds(1), secondView.observedAt());
        assertNotEquals(first.snapshot().evidenceHash(), second.snapshot().evidenceHash());
        assertNotEquals(firstView.evidenceHash(), secondView.evidenceHash());
        assertEquals(
            first.snapshot().evidenceHash(),
            firstView.evidenceReferences().snapshotEvidenceHash()
        );
        assertEquals(
            second.snapshot().evidenceHash(),
            secondView.evidenceReferences().snapshotEvidenceHash()
        );
    }

    private static Components disabledComponents(Instant observedAt) {
        OperationsView snapshot = OperationsView.disabled(observedAt, inventory());
        return new Components(
            snapshot,
            ControlHealthView.disabled(snapshot),
            UsageView.disabled(snapshot),
            HistoryView.from(snapshot, HistorySummary.empty(window(observedAt))),
            ReviewPlan.preview(Operation.ROLLBACK, snapshot)
        );
    }

    private static Components configuredComponents(Instant observedAt) {
        OpenAiResponsesProductionRuntimeFactory.RuntimeProfile profile = profile(observedAt);
        OpenAiResponsesProductionRuntimeFactory factory =
            new OpenAiResponsesProductionRuntimeFactory(
                profile,
                Clock.fixed(observedAt, ZoneOffset.UTC)
            );
        OperationsView snapshot = OperationsView.configured(
            observedAt,
            inventory(),
            controls(profile)
        );
        return new Components(
            snapshot,
            ControlHealthView.configured(snapshot, factory.controlSnapshot()),
            UsageView.configured(snapshot, factory.usageSnapshot("tenant-a")),
            HistoryView.from(snapshot, HistorySummary.empty(window(observedAt))),
            ReviewPlan.preview(Operation.ROLLBACK, snapshot)
        );
    }

    private static HistoryWindow window(Instant observedAt) {
        return new HistoryWindow(
            "tenant-a",
            observedAt.minusSeconds(3_600),
            observedAt,
            observedAt
        );
    }

    private static OpenAiResponsesProductionRuntimeFactory.RuntimeProfile profile(
        Instant observedAt
    ) {
        return new OpenAiResponsesProductionRuntimeFactory.RuntimeProfile(
            "key-v1",
            observedAt.minusSeconds(60),
            observedAt.plusSeconds(3_600),
            "secret-policy-v1",
            7,
            "kill-switch-policy-v1",
            "cost-v1",
            observedAt.minusSeconds(60),
            observedAt.plusSeconds(3_600),
            1,
            2,
            1_000_000,
            10,
            100,
            Duration.ofSeconds(60),
            3,
            Duration.ofSeconds(60)
        );
    }

    private static RuntimeControls controls(
        OpenAiResponsesProductionRuntimeFactory.RuntimeProfile profile
    ) {
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

    private record Components(
        OperationsView snapshot,
        ControlHealthView control,
        UsageView usage,
        HistoryView history,
        ReviewPlan rollback
    ) {
        private IncidentReadinessView view() {
            return IncidentReadinessView.from(snapshot, control, usage, history, rollback);
        }
    }
}
