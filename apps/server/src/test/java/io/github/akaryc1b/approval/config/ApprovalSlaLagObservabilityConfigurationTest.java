package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker;
import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker.WorkerMetrics;
import io.github.akaryc1b.approval.application.ApprovalSlaLagCalculator;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator;
import io.github.akaryc1b.approval.application.port.ApprovalSlaActionDispatcher;
import io.github.akaryc1b.approval.application.port.ApprovalSlaActionDispatcher.DispatchResult;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.IntentStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore;
import io.github.akaryc1b.approval.observability.SlaCompletionLagMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApprovalSlaLagObservabilityConfigurationTest {
    private final ApprovalSlaStore store = mock(ApprovalSlaStore.class);
    private final ApprovalSlaExecutionStore executions = mock(ApprovalSlaExecutionStore.class);
    private final ApprovalSlaLagCalculator calculator = new ApprovalSlaLagCalculator(new ApprovalWorkingTimeCalculator());
    private ApplicationContextRunner base() {
        return new ApplicationContextRunner().withUserConfiguration(ApprovalSlaLagObservabilityConfiguration.class,
            ApprovalSlaExecutionMetricsConfiguration.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(Clock.class, () -> Clock.fixed(FINISH, ZoneOffset.UTC))
            .withBean(ApprovalSlaExecutionStore.class, () -> executions)
            .withBean(ApprovalSlaActionDispatcher.class, () -> i -> DispatchResult.succeeded());
    }
    @Test void defaultOffKeepsOriginalMetricsWithoutCalendarOrTransactionDependencies() {
        base().run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(WorkerMetrics.class).size());
            assertFalse(context.getBean(WorkerMetrics.class) instanceof SlaCompletionLagMetrics);
            verifyNoInteractions(executions, store);
        });
    }
    @Test void enabledSelectsDecoratorOnTheActualMeteredWorkerAndUsesIndependentReadOnlyTransaction() {
        var manager = new RecordingManager();
        when(executions.claimDue(anyString(), any(), anyInt(), anyString(), any()))
            .thenReturn(List.of(intent(IntentStatus.CLAIMED, true)));
        when(executions.markSucceeded(anyString(), any(), anyLong(), anyString(), any(), any(), any(), any(), anyString(), any()))
            .thenReturn(intent(IntentStatus.SUCCEEDED, true));
        when(store.findPolicyVersion(TENANT, POLICY, 1)).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return Optional.of(policy(true));
        });
        when(store.findCalendarVersion(TENANT, CALENDAR, 1)).thenReturn(Optional.of(calendar()));
        base().withPropertyValues("approval.observability.sla-lag.enabled=true", "approval.sla.execution.enabled=true")
            .withBean(ApprovalSlaStore.class, () -> store)
            .withBean(ApprovalWorkingTimeCalculator.class, ApprovalWorkingTimeCalculator::new)
            .withBean(PlatformTransactionManager.class, () -> manager).run(context -> {
                assertNull(context.getStartupFailure());
                assertInstanceOf(SlaCompletionLagMetrics.class, context.getBean(WorkerMetrics.class));
                assertEquals(1, context.getBean(ApprovalSlaExecutionWorker.class).processTenant(TENANT).succeeded());
                var registry = context.getBean(MeterRegistry.class);
                assertEquals(2, registry.get("approval.sla.overdue.completion.lag").tag("time_basis", "working_time")
                    .timer().totalTime(TimeUnit.HOURS));
                assertEquals(1, registry.get("approval.sla.execution.worker").tags("action", "overdue", "result", "succeeded")
                    .counter().count());
                assertEquals(1, manager.commits); assertEquals(2, manager.timeout);
                assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, manager.propagation);
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                verify(store).findPolicyVersion(TENANT, POLICY, 1);
                verify(store).findCalendarVersion(TENANT, CALENDAR, 1);
                verifyNoMoreInteractions(store);
            });
    }
    @Test void incompleteEnabledConfigurationFailsRatherThanPretendingTimingIsEnabled() {
        base().withPropertyValues("approval.observability.sla-lag.enabled=true")
            .run(context -> assertNotNull(context.getStartupFailure()));
    }
    @Test void policyIdentityVersionAndPublicationAreCheckedBeforeCalendarAccess() {
        for (var wrong : List.of(policy(true, "other", 1, true), policy(true, TENANT, 2, true), policy(true, TENANT, 1, false), policy(false))) {
            reset(store);
            when(store.findPolicyVersion(TENANT, POLICY, 1)).thenReturn(Optional.of(wrong));
            assertThrows(IllegalStateException.class, () -> ApprovalSlaLagObservabilityConfiguration.readLag(store, calculator,
                intent(IntentStatus.SUCCEEDED, true)));
            verify(store, never()).findCalendarVersion(anyString(), any(), anyInt());
        }
    }
    @Test void missingOrWrongImmutableCalendarNeverFallsBackToNaturalTimeOrTheActiveVersion() {
        when(store.findPolicyVersion(TENANT, POLICY, 1)).thenReturn(Optional.of(policy(true)));
        when(store.findCalendarVersion(TENANT, CALENDAR, 1)).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> ApprovalSlaLagObservabilityConfiguration.readLag(store, calculator,
            intent(IntentStatus.SUCCEEDED, true)));
        for (var wrong : List.of(calendar("other", 1, true), calendar(TENANT, 2, true), calendar(TENANT, 1, false))) {
            when(store.findCalendarVersion(TENANT, CALENDAR, 1)).thenReturn(Optional.of(wrong));
            assertThrows(IllegalStateException.class, () -> ApprovalSlaLagObservabilityConfiguration.readLag(store, calculator,
                intent(IntentStatus.SUCCEEDED, true)));
        }
    }
    @Test void naturalPolicyUsesOnlyItsExactImmutablePolicyVersion() {
        when(store.findPolicyVersion(TENANT, POLICY, 1)).thenReturn(Optional.of(policy(false)));
        var lag = ApprovalSlaLagObservabilityConfiguration.readLag(store, calculator, intent(IntentStatus.SUCCEEDED, false));
        assertEquals(Duration.ofHours(66), lag.natural()); assertNull(lag.working());
        verify(store).findPolicyVersion(TENANT, POLICY, 1); verifyNoMoreInteractions(store);
    }
    static final class RecordingManager extends AbstractPlatformTransactionManager {
        int commits, timeout, propagation;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            assertTrue(definition.isReadOnly()); timeout = definition.getTimeout(); propagation = definition.getPropagationBehavior();
        }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
