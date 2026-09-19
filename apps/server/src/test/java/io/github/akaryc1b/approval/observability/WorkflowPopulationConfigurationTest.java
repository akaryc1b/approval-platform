package io.github.akaryc1b.approval.observability;

import io.github.akaryc1b.approval.application.port.ApprovalWorkflowPopulationReader.Snapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WorkflowPopulationConfigurationTest {
    @Test
    void disabledByDefaultWithoutDatabaseOrMeters() {
        new ApplicationContextRunner().withUserConfiguration(WorkflowPopulationConfiguration.class).run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(WorkflowPopulationMetrics.class).isEmpty());
        });
    }

    @Test
    void optInKeepsTheBusinessDataSourceAndDoesNotQueryBeforeReady() {
        DataSource business = mock(DataSource.class);
        runner("jdbc:postgresql://127.0.0.1:1/not_contacted")
            .withBean(DataSource.class, () -> business).run(context -> {
                assertNull(context.getStartupFailure());
                assertEquals(1, context.getBeansOfType(DataSource.class).size());
                assertSame(business, context.getBean(DataSource.class));
                assertNotNull(context.getBean(WorkflowPopulationMetrics.class));
                MeterRegistry registry = context.getBean(MeterRegistry.class);
                assertEquals(0, registry.get("approval.workflow.sample.up").gauge().value());
                assertTrue(Double.isNaN(registry.get("approval.process.active").gauge().value()));
                assertEquals(0, registry.get("approval.workflow.sample.errors").functionCounter().count());
            });
    }

    @Test
    void unsafeUrlsUnsupportedBackendsAndIntervalsFailClosed() {
        for (String url : new String[] {"jdbc:mysql://127.0.0.1/db", "jdbc:postgresql://127.0.0.1/db?connectTimeout=0",
            "jdbc:postgresql://127.0.0.1/db?socketTimeout=0", "jdbc:postgresql://127.0.0.1/db?cancelSignalTimeout=0",
            "jdbc:postgresql://127.0.0.1/db?%73ocketTimeout=0"}) {
            runner(url).run(context -> assertNotNull(context.getStartupFailure()));
        }
        for (long interval : new long[] {0, 4999, 300001}) {
            runner("jdbc:postgresql://127.0.0.1:1/not_contacted")
                .withPropertyValues("approval.observability.workflow.interval-ms=" + interval)
                .run(context -> assertNotNull(context.getStartupFailure()));
        }
    }

    @Test
    void metersUseCachedValuesKeepCommonTagsAndInvalidateTogether() {
        AtomicInteger reads = new AtomicInteger();
        var next = new AtomicReference<>(new Snapshot(Instant.now(), 5, 3, 2, 9, 4, 1));
        var registry = new SimpleMeterRegistry();
        try {
            registry.config().commonTags("application", "approval-platform");
            var monitor = new WorkflowPopulationMonitor(() -> { reads.incrementAndGet(); return next.get(); },
                Duration.ofSeconds(5), () -> { });
            try (var metrics = new WorkflowPopulationMetrics(monitor, registry)) {
                String[] names = {"approval.process.active", "approval.process.sla.covered", "approval.process.overdue",
                    "approval.task.active", "approval.task.sla.covered", "approval.task.overdue"};
                for (String name : names) assertTrue(Double.isNaN(registry.get(name).gauge().value()));
                assertEquals(0, reads.get()); metrics.refresh(); assertEquals(1, reads.get());
                double[] expected = {5, 3, 2, 9, 4, 1};
                for (int i = 0; i < names.length; i++) assertEquals(expected[i], registry.get(names[i]).gauge().value());
                assertEquals(1, reads.get(), "scrapes cannot query the database");
                registry.getMeters().forEach(meter -> {
                    assertEquals(1, meter.getId().getTags().size());
                    assertEquals("approval-platform", meter.getId().getTag("application"));
                });
                next.set(null); metrics.refresh();
                for (String name : names) assertTrue(Double.isNaN(registry.get(name).gauge().value()));
                assertEquals(0, registry.get("approval.workflow.sample.up").gauge().value());
                assertEquals(1, registry.get("approval.workflow.sample.errors").functionCounter().count());
            }
            assertEquals(0, monitor.up());
        } finally {
            registry.close();
        }
    }

    private static ApplicationContextRunner runner(String url) {
        return new ApplicationContextRunner().withUserConfiguration(WorkflowPopulationConfiguration.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(DataSourceProperties.class, () -> {
                var database = new DataSourceProperties(); database.setUrl(url); database.setUsername("fixture"); return database;
            }).withPropertyValues("approval.observability.workflow.enabled=true");
    }
}
