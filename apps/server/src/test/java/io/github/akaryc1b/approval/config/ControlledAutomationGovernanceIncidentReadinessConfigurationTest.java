package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistorySummary;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistoryWindow;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceIncidentReadinessContracts
    .ReadinessState;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.OperationsView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ControlledAutomationGovernanceIncidentReadinessConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-08-06T02:30:00Z");

    @Test
    void compositionUsesOneSnapshotAndPerformsNoRuntimeBinding() {
        var configuration = new ControlledAutomationGovernanceConfiguration();
        OperationsView snapshot = OperationsView.disabled(
            NOW,
            ControlledAutomationGovernanceConfiguration.inventory()
        );
        AtomicReference<HistoryWindow> captured = new AtomicReference<>();
        var historyQuery = (io.github.akaryc1b.approval.ai.core
            .ApprovalAssistanceGovernanceHistoryQuery) window -> {
                captured.set(window);
                return HistorySummary.empty(window);
            };
        var source = configuration.controlledAutomationGovernanceIncidentReadinessSource(
            ApprovalAssistanceProductionRuntime.disabled(),
            historyQuery,
            () -> snapshot
        );
        Instant from = NOW.minusSeconds(3_600);

        var view = source.readiness("tenant-a", from, NOW);

        assertEquals(new HistoryWindow("tenant-a", from, NOW, NOW), captured.get());
        assertEquals(ReadinessState.RUNTIME_NOT_CONFIGURED, view.readinessState());
        assertEquals(snapshot.evidenceHash(), view.evidenceReferences()
            .snapshotEvidenceHash());
        assertEquals(snapshot.evidenceHash(), view.evidenceReferences()
            .controlHealthEvidenceHash().equals(snapshot.evidenceHash())
                ? snapshot.evidenceHash()
                : view.evidenceReferences().snapshotEvidenceHash());
        assertFalse(view.incidentMutationAvailable());
        assertFalse(view.providerInvocationAvailable());
        assertFalse(view.rollbackExecutionAvailable());
        assertFalse(view.notificationAutomationAvailable());
    }
}
