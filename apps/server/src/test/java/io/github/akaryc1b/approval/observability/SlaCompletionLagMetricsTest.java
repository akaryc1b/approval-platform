package io.github.akaryc1b.approval.observability;

import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker.WorkerMetrics;
import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker.WorkerResult;
import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker.FailureClass;
import io.github.akaryc1b.approval.application.ApprovalSlaLagCalculator.Lag;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ActionType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.IntentStatus;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class SlaCompletionLagMetricsTest {
    private static final String TIMER = "approval.sla.overdue.completion.lag";
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicInteger reads = new AtomicInteger();
    @AfterEach void close() { registry.close(); }
    private SlaCompletionLagMetrics metrics(boolean working) {
        return new SlaCompletionLagMetrics(WorkerMetrics.noop(), registry, intent -> {
            reads.incrementAndGet();
            return new Lag(Duration.between(intent.scheduledAt(), intent.completedAt()), working ? Duration.ofHours(2) : null);
        });
    }

    @Test void naturalPolicyDoesNotManufactureAZeroWorkingSample() {
        metrics(false).completed(intent(IntentStatus.SUCCEEDED, false));
        assertEquals(1, reads.get());
        assertEquals(1, registry.get(TIMER).timer().count());
        assertEquals(66, registry.get(TIMER).timer().totalTime(TimeUnit.HOURS));
        assertEquals("natural_time", registry.get(TIMER).timer().getId().getTag("time_basis"));
        assertTrue(registry.find(TIMER).tag("time_basis", "working_time").timers().isEmpty());
    }
    @Test void recordsSeparateNaturalAndWorkingDurationsWithoutIdentityLabels() {
        metrics(true).completed(intent(IntentStatus.SUCCEEDED, true));
        assertEquals(66, registry.get(TIMER).tag("time_basis", "natural_time").timer().totalTime(TimeUnit.HOURS));
        assertEquals(2, registry.get(TIMER).tag("time_basis", "working_time").timer().totalTime(TimeUnit.HOURS));
        Set<String> allowed = Set.of("target", "source", "time_basis");
        registry.getMeters().forEach(m -> m.getId().getTags().forEach(t -> assertTrue(allowed.contains(t.getKey()))));
        assertFalse(registry.getMeters().toString().contains("never-in-metric"));
        assertFalse(registry.getMeters().toString().contains(TENANT));
    }
    @Test void targetScopesAndGovernedReplaysRemainDistinct() {
        var metrics = metrics(false);
        for (String target : new String[] {"process", "task", "collaboration"}) {
            for (boolean replay : new boolean[] {false, true}) {
                metrics.completed(intent(IntentStatus.SUCCEEDED, false, target, replay, ActionType.OVERDUE, FINISH, id(6)));
                assertEquals(1, registry.get(TIMER).tags("target", target, "source", replay ? "replay" : "original").timer().count());
            }
        }
        assertEquals(6, reads.get()); assertEquals(6, registry.find(TIMER).timers().size());
    }
    @Test void pendingFailedCancelledAndReminderDoNotReadCalendarOrBecomeSamples() {
        var metrics = metrics(false);
        for (IntentStatus status : new IntentStatus[] {IntentStatus.READY, IntentStatus.CLAIMED,
            IntentStatus.RETRY_WAIT, IntentStatus.DEAD, IntentStatus.CANCELLED}) metrics.completed(intent(status, false));
        metrics.completed(null);
        metrics.completed(intent(IntentStatus.SUCCEEDED, false, "task", false, ActionType.REMINDER, FINISH, id(6)));
        assertEquals(0, reads.get()); assertTrue(registry.find(TIMER).timers().isEmpty());
        assertEquals(6, metrics.droppedObservations());
    }
    @Test void outerTransactionAndSynchronizationOnlyScopesAreRejectedBeforeReads() {
        var metrics = metrics(false);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { metrics.completed(intent(IntentStatus.SUCCEEDED, false)); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        TransactionSynchronizationManager.initSynchronization();
        try { metrics.completed(intent(IntentStatus.SUCCEEDED, false)); }
        finally { TransactionSynchronizationManager.clearSynchronization(); }
        assertEquals(0, reads.get()); assertEquals(2, metrics.droppedObservations());
        metrics.completed(intent(IntentStatus.SUCCEEDED, false)); assertEquals(1, reads.get());
    }
    @Test void invalidTimestampsAndExcessiveHorizonAreNotZeroFilledOrQueried() {
        var metrics = metrics(false);
        metrics.completed(intent(IntentStatus.SUCCEEDED, false, "task", false, ActionType.OVERDUE, DEADLINE.minusNanos(1), id(6)));
        metrics.completed(intent(IntentStatus.SUCCEEDED, true, "task", false, ActionType.OVERDUE, DEADLINE.plus(Duration.ofDays(367)), id(6)));
        assertEquals(0, reads.get()); assertEquals(2, metrics.droppedObservations());
        assertTrue(registry.find(TIMER).timers().isEmpty());
    }
    @Test void failedOrInconsistentReadDropsObservationWithoutPropagatingDetails() {
        var failed = new SlaCompletionLagMetrics(WorkerMetrics.noop(), registry, i -> { throw new IllegalStateException("private-query-canary"); });
        assertDoesNotThrow(() -> failed.completed(intent(IntentStatus.SUCCEEDED, true)));
        assertEquals(1, failed.droppedObservations());
        var mismatch = new SlaCompletionLagMetrics(WorkerMetrics.noop(), registry, i -> new Lag(Duration.ZERO, null));
        mismatch.completed(intent(IntentStatus.SUCCEEDED, false));
        assertEquals(1, mismatch.droppedObservations()); assertTrue(registry.find(TIMER).timers().isEmpty());
    }
    @Test void timerRegistrationAndOriginalCounterFailureCannotChangeBusinessOutcome() {
        var metrics = new SlaCompletionLagMetrics((a, r, f) -> { throw new IllegalStateException("counter-canary"); },
            registry, i -> new Lag(Duration.ofHours(66), null));
        registry.config().meterFilter(new MeterFilter() {
            @Override public MeterFilterReply accept(Meter.Id id) {
                if (id.getName().equals(TIMER)) throw new IllegalStateException("registry-canary");
                return MeterFilterReply.NEUTRAL;
            }
        });
        assertDoesNotThrow(() -> metrics.record(ActionType.OVERDUE, WorkerResult.SUCCEEDED, FailureClass.NONE));
        assertDoesNotThrow(() -> metrics.completed(intent(IntentStatus.SUCCEEDED, false)));
        assertEquals(2, metrics.droppedObservations());
    }
}
