package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.observability.EngineJobMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flowable.engine.ManagementService;
import org.flowable.job.api.DeadLetterJobQuery;
import org.flowable.job.api.JobQuery;
import org.flowable.job.api.SuspendedJobQuery;
import org.flowable.job.api.TimerJobQuery;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ApprovalEngineJobObservabilityConfigurationTest {
    private final ManagementService management = mock(ManagementService.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ApprovalEngineJobObservabilityConfiguration.class);

    @Test
    void defaultOffNeedsNeitherEngineNorRegistry() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(EngineJobMetrics.class).isEmpty());
        });
    }

    @Test
    void enabledUsesOnlyFourCountsAndScrapesDoNotReachTheEngine() {
        var job = mock(JobQuery.class); var timer = mock(TimerJobQuery.class);
        var suspended = mock(SuspendedJobQuery.class); var dead = mock(DeadLetterJobQuery.class);
        when(management.createJobQuery()).thenReturn(job); when(job.count()).thenReturn(3L);
        when(management.createTimerJobQuery()).thenReturn(timer); when(timer.count()).thenReturn(4L);
        when(management.createSuspendedJobQuery()).thenReturn(suspended); when(suspended.count()).thenReturn(5L);
        when(management.createDeadLetterJobQuery()).thenReturn(dead); when(dead.count()).thenReturn(6L);
        configured().run(context -> {
            assertNull(context.getStartupFailure());
            var registry = context.getBean(MeterRegistry.class); var metrics = context.getBean(EngineJobMetrics.class);
            assertTrue(Double.isNaN(registry.get("approval.engine.jobs").tags("queue", "executable").gauge().value()));
            metrics.refresh();
            assertEquals(6.0d, registry.get("approval.engine.jobs").tags("queue", "dead_letter").gauge().value());
            assertEquals(1.0d, registry.get("approval.engine.jobs.sample.up").gauge().value());
            for (var meter : registry.getMeters()) for (var tag : meter.getId().getTags()) {
                assertEquals("queue", tag.getKey());
                assertTrue(Set.of("executable", "timer", "suspended", "dead_letter").contains(tag.getValue()));
            }
            when(dead.count()).thenThrow(new IllegalStateException("fixture-sensitive-value"));
            metrics.refresh();
            for (String queue : Set.of("executable", "timer", "suspended", "dead_letter")) {
                assertTrue(Double.isNaN(registry.get("approval.engine.jobs").tags("queue", queue).gauge().value()));
            }
            assertEquals(0.0d, registry.get("approval.engine.jobs.sample.up").gauge().value());
            assertEquals(1.0d, registry.get("approval.engine.jobs.sample.errors").functionCounter().count());
        });
        verify(management, org.mockito.Mockito.times(2)).createJobQuery();
        verify(management, org.mockito.Mockito.times(2)).createTimerJobQuery();
        verify(management, org.mockito.Mockito.times(2)).createSuspendedJobQuery();
        verify(management, org.mockito.Mockito.times(2)).createDeadLetterJobQuery();
        verifyNoMoreInteractions(management);
    }

    @Test
    void refusesManualRefreshInsideBusinessTransactionBeforeEngineAccess() {
        configured().run(context -> {
            var metrics = context.getBean(EngineJobMetrics.class);
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try { metrics.refresh(); }
            finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
            verifyNoInteractions(management);
            assertEquals(0.0d, context.getBean(MeterRegistry.class).get("approval.engine.jobs.sample.up").gauge().value());
        });
    }

    @Test
    void rejectsInvalidIntervalsAndEnabledWithoutAnEngine() {
        runner.withPropertyValues("approval.observability.engine-jobs.enabled=true")
            .run(context -> assertNotNull(context.getStartupFailure()));
        for (String interval : new String[] {"0", "4999", "300001"}) {
            configured().withPropertyValues("approval.observability.engine-jobs.interval-ms=" + interval)
                .run(context -> assertNotNull(context.getStartupFailure()));
        }
    }

    private ApplicationContextRunner configured() {
        return runner.withPropertyValues("approval.observability.engine-jobs.enabled=true")
            .withBean(ManagementService.class, () -> management)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new).withBean(Clock.class, Clock::systemUTC);
    }
}
