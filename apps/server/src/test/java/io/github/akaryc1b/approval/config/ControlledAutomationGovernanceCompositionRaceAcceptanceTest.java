package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistorySummary;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistoryWindow;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceIncidentReadinessContracts
    .IncidentReadinessView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.OperationsView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlledAutomationGovernanceCompositionRaceAcceptanceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T06:30:00Z");

    @Test
    void changingSnapshotSourceIsReadExactlyOncePerCompositeAttempt() {
        var configuration = new ControlledAutomationGovernanceConfiguration();
        OperationsView first = disabled(NOW);
        OperationsView second = disabled(NOW.plusSeconds(1));
        AtomicInteger snapshotReads = new AtomicInteger();
        AtomicReference<HistoryWindow> historyWindow = new AtomicReference<>();
        ApprovalAssistanceGovernanceHistoryQuery history = window -> {
            historyWindow.set(window);
            return HistorySummary.empty(window);
        };
        var source = configuration.controlledAutomationGovernanceIncidentReadinessSource(
            ApprovalAssistanceProductionRuntime.disabled(),
            history,
            () -> snapshotReads.getAndIncrement() == 0 ? first : second
        );

        IncidentReadinessView firstView = source.readiness(
            "tenant-a",
            NOW.minusSeconds(3_600),
            NOW
        );
        assertEquals(1, snapshotReads.get());
        assertEquals(NOW, firstView.observedAt());
        assertEquals(NOW, historyWindow.get().observedAt());
        assertEquals(
            first.evidenceHash(),
            firstView.evidenceReferences().snapshotEvidenceHash()
        );

        IncidentReadinessView secondView = source.readiness(
            "tenant-a",
            NOW.minusSeconds(3_600),
            NOW
        );
        assertEquals(2, snapshotReads.get());
        assertEquals(NOW.plusSeconds(1), secondView.observedAt());
        assertEquals(NOW.plusSeconds(1), historyWindow.get().observedAt());
        assertEquals(
            second.evidenceHash(),
            secondView.evidenceReferences().snapshotEvidenceHash()
        );
        assertNotEquals(firstView.evidenceHash(), secondView.evidenceHash());
    }

    @Test
    void concurrentAttemptsNeverReuseOrSpliceComponentsFromAnotherAttempt()
        throws Exception {
        int attempts = 16;
        var configuration = new ControlledAutomationGovernanceConfiguration();
        List<OperationsView> snapshots = new ArrayList<>();
        for (int index = 0; index < attempts; index++) {
            snapshots.add(disabled(NOW.plusSeconds(index)));
        }
        AtomicInteger snapshotReads = new AtomicInteger();
        Set<Instant> historyObservations = ConcurrentHashMap.newKeySet();
        ApprovalAssistanceGovernanceHistoryQuery history = window -> {
            historyObservations.add(window.observedAt());
            return HistorySummary.empty(window);
        };
        var source = configuration.controlledAutomationGovernanceIncidentReadinessSource(
            ApprovalAssistanceProductionRuntime.disabled(),
            history,
            () -> snapshots.get(snapshotReads.getAndIncrement())
        );
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IncidentReadinessView>> results = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < attempts; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return source.readiness(
                        "tenant-a",
                        NOW.minusSeconds(3_600),
                        NOW
                    );
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            Set<String> snapshotHashes = new HashSet<>();
            Set<String> compositeHashes = new HashSet<>();
            for (Future<IncidentReadinessView> result : results) {
                IncidentReadinessView view = result.get();
                snapshotHashes.add(view.evidenceReferences().snapshotEvidenceHash());
                compositeHashes.add(view.evidenceHash());
                assertEquals(
                    view.observedAt(),
                    snapshots.stream()
                        .filter(snapshot -> snapshot.evidenceHash().equals(
                            view.evidenceReferences().snapshotEvidenceHash()
                        ))
                        .findFirst()
                        .orElseThrow()
                        .observedAt()
                );
            }
            assertEquals(attempts, snapshotHashes.size());
            assertEquals(attempts, compositeHashes.size());
        }

        assertEquals(attempts, snapshotReads.get());
        assertEquals(attempts, historyObservations.size());
    }

    private static OperationsView disabled(Instant observedAt) {
        return OperationsView.disabled(
            observedAt,
            ControlledAutomationGovernanceConfiguration.inventory()
        );
    }
}
