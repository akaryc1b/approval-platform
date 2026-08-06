package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesAdvisoryProvider;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol;
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
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceUsageContracts.UsageView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlledAutomationGovernanceEvidenceAdversarialTest {

    private static final Instant NOW = Instant.parse("2026-08-06T03:30:00Z");
    private static final Instant FROM = NOW.minusSeconds(3_600);

    @Test
    void componentEvidenceHashesRejectUppercaseWrongLengthAndNonHex() {
        IncidentReadinessView view = view();
        EvidenceReferences valid = view.evidenceReferences();

        for (String invalid : List.of(
            "A".repeat(64),
            "a".repeat(63),
            "a".repeat(65),
            "g".repeat(64),
            " " + "a".repeat(64),
            "a".repeat(64) + " "
        )) {
            assertThrows(
                IllegalArgumentException.class,
                () -> new EvidenceReferences(
                    invalid,
                    valid.controlHealthEvidenceHash(),
                    valid.usageEvidenceHash(),
                    valid.historyEvidenceHash(),
                    valid.rollbackPlanEvidenceHash()
                )
            );
        }
    }

    @Test
    void replacedComponentHashCannotReusePriorCompositeHash() {
        IncidentReadinessView view = view();
        EvidenceReferences current = view.evidenceReferences();
        EvidenceReferences replaced = new EvidenceReferences(
            "f".repeat(64),
            current.controlHealthEvidenceHash(),
            current.usageEvidenceHash(),
            current.historyEvidenceHash(),
            current.rollbackPlanEvidenceHash()
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> copy(
                view,
                replaced,
                view.incidentSignals(),
                view.operatorStepCodes(),
                view.blockerCodes()
            )
        );
    }

    @Test
    void changedIncidentSignalCannotReusePriorCompositeHash() {
        IncidentReadinessView view = view();
        List<ControlledAutomationGovernanceIncidentReadinessContracts.IncidentSignal> signals =
            view.incidentSignals().stream()
                .filter(signal -> signal
                    != ControlledAutomationGovernanceIncidentReadinessContracts
                        .IncidentSignal.AI_DURABLE_HISTORY_EMPTY)
                .toList();

        assertThrows(
            IllegalArgumentException.class,
            () -> copy(
                view,
                view.evidenceReferences(),
                signals,
                view.operatorStepCodes(),
                view.blockerCodes()
            )
        );
    }

    @Test
    void changedOperatorStepCannotReusePriorCompositeHash() {
        IncidentReadinessView view = view();
        List<String> steps = new ArrayList<>(view.operatorStepCodes());
        steps.add("AI_INCIDENT_STEP_REVIEW_VERSION_HISTORY");

        assertThrows(
            IllegalArgumentException.class,
            () -> copy(
                view,
                view.evidenceReferences(),
                view.incidentSignals(),
                steps,
                view.blockerCodes()
            )
        );
    }

    @Test
    void changedBlockerCannotReusePriorCompositeHash() {
        IncidentReadinessView view = view();
        List<String> blockers = new ArrayList<>(view.blockerCodes());
        blockers.add("AI_P7_TAMPERED_BLOCKER");

        assertThrows(
            IllegalArgumentException.class,
            () -> copy(
                view,
                view.evidenceReferences(),
                view.incidentSignals(),
                view.operatorStepCodes(),
                blockers
            )
        );
    }

    private static IncidentReadinessView copy(
        IncidentReadinessView source,
        EvidenceReferences references,
        List<ControlledAutomationGovernanceIncidentReadinessContracts.IncidentSignal> signals,
        List<String> operatorSteps,
        List<String> blockers
    ) {
        return new IncidentReadinessView(
            source.viewVersion(),
            source.observedAt(),
            source.fromInclusive(),
            source.toExclusive(),
            source.runtimeState(),
            source.readinessState(),
            source.controlPosture(),
            source.usageHealth(),
            source.historyHealth(),
            source.rollbackPosture(),
            references,
            signals,
            operatorSteps,
            source.rollbackOperatorStepCodes(),
            blockers,
            source.actionWhitelistState(),
            source.p5Decision(),
            source.durableEvidenceAvailable(),
            source.processLocalUsageOnly(),
            source.incidentMutationAvailable(),
            source.providerInvocationAvailable(),
            source.rollbackExecutionAvailable(),
            source.commandExecutionAuthorized(),
            source.automaticRetryAuthorized(),
            source.notificationAutomationAvailable(),
            source.rawSecretExposed(),
            source.evidenceHash()
        );
    }

    private static IncidentReadinessView view() {
        OperationsView snapshot = OperationsView.disabled(NOW, inventory());
        var window = new ApprovalAssistanceGovernanceHistoryQuery.HistoryWindow(
            "tenant-a",
            FROM,
            NOW,
            NOW
        );
        return IncidentReadinessView.from(
            snapshot,
            ControlHealthView.disabled(snapshot),
            UsageView.disabled(snapshot),
            HistoryView.from(
                snapshot,
                ApprovalAssistanceGovernanceHistoryQuery.HistorySummary.empty(window)
            ),
            ReviewPlan.preview(Operation.ROLLBACK, snapshot)
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
}
