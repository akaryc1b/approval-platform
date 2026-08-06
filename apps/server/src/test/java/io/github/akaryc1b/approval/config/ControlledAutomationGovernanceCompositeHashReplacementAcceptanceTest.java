package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistorySummary;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistoryWindow;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceControlHealthContracts
    .ControlHealthView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceHistoryContracts.HistoryView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceIncidentReadinessContracts
    .EvidenceReferences;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceIncidentReadinessContracts
    .IncidentReadinessView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernancePlanContracts.Operation;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernancePlanContracts.ReviewPlan;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.OperationsView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceUsageContracts.UsageView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlledAutomationGovernanceCompositeHashReplacementAcceptanceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T07:00:00Z");

    @Test
    void everyComponentHashReplacementInvalidatesTheOriginalCompositeEvidence() {
        IncidentReadinessView original = view(NOW);
        IncidentReadinessView replacement = view(NOW.plusSeconds(1));
        EvidenceReferences first = original.evidenceReferences();
        EvidenceReferences second = replacement.evidenceReferences();
        List<EvidenceReferences> replacements = List.of(
            new EvidenceReferences(
                second.snapshotEvidenceHash(),
                first.controlHealthEvidenceHash(),
                first.usageEvidenceHash(),
                first.historyEvidenceHash(),
                first.rollbackPlanEvidenceHash()
            ),
            new EvidenceReferences(
                first.snapshotEvidenceHash(),
                second.controlHealthEvidenceHash(),
                first.usageEvidenceHash(),
                first.historyEvidenceHash(),
                first.rollbackPlanEvidenceHash()
            ),
            new EvidenceReferences(
                first.snapshotEvidenceHash(),
                first.controlHealthEvidenceHash(),
                second.usageEvidenceHash(),
                first.historyEvidenceHash(),
                first.rollbackPlanEvidenceHash()
            ),
            new EvidenceReferences(
                first.snapshotEvidenceHash(),
                first.controlHealthEvidenceHash(),
                first.usageEvidenceHash(),
                second.historyEvidenceHash(),
                first.rollbackPlanEvidenceHash()
            ),
            new EvidenceReferences(
                first.snapshotEvidenceHash(),
                first.controlHealthEvidenceHash(),
                first.usageEvidenceHash(),
                first.historyEvidenceHash(),
                second.rollbackPlanEvidenceHash()
            )
        );

        assertEquals(5, replacements.size());
        for (EvidenceReferences changed : replacements) {
            assertThrows(
                IllegalArgumentException.class,
                () -> copyWithReferences(original, changed)
            );
        }
    }

    private static IncidentReadinessView copyWithReferences(
        IncidentReadinessView source,
        EvidenceReferences references
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
            source.incidentSignals(),
            source.operatorStepCodes(),
            source.rollbackOperatorStepCodes(),
            source.blockerCodes(),
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

    private static IncidentReadinessView view(Instant observedAt) {
        OperationsView snapshot = OperationsView.disabled(
            observedAt,
            ControlledAutomationGovernanceConfiguration.inventory()
        );
        return IncidentReadinessView.from(
            snapshot,
            ControlHealthView.disabled(snapshot),
            UsageView.disabled(snapshot),
            HistoryView.from(
                snapshot,
                HistorySummary.empty(new HistoryWindow(
                    "tenant-a",
                    observedAt.minusSeconds(3_600),
                    observedAt,
                    observedAt
                ))
            ),
            ReviewPlan.preview(Operation.ROLLBACK, snapshot)
        );
    }
}
