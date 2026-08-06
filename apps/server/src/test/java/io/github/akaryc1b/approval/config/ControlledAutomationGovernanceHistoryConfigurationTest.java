package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistorySummary;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistoryWindow;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceHistoryContracts.HistoryHealth;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.OperationsView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceSnapshotSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ControlledAutomationGovernanceHistoryConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-08-06T01:15:00Z");

    @Test
    void historySourceUsesExactTenantWindowAndCurrentSnapshotWithoutRuntimeBinding() {
        AtomicReference<HistoryWindow> captured = new AtomicReference<>();
        var configuration = new ControlledAutomationGovernanceConfiguration();
        ControlledAutomationGovernanceSnapshotSource snapshotSource =
            () -> OperationsView.disabled(
                NOW,
                ControlledAutomationGovernanceConfiguration.inventory()
            );
        ApprovalAssistanceGovernanceHistoryQuery query = window -> {
            captured.set(window);
            return HistorySummary.empty(window);
        };
        var source = configuration.controlledAutomationGovernanceHistorySource(
            query,
            snapshotSource
        );
        Instant from = NOW.minusSeconds(3_600);

        var view = source.history("tenant-a", from, NOW);

        assertEquals("tenant-a", captured.get().tenantId());
        assertEquals(from, captured.get().fromInclusive());
        assertEquals(NOW, captured.get().toExclusive());
        assertEquals(NOW, captured.get().observedAt());
        assertEquals(HistoryHealth.EMPTY, view.historyHealth());
        assertFalse(view.providerInvocationAvailable());
        assertFalse(view.historyMutationAvailable());
    }
}
