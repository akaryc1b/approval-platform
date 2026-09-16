package io.github.akaryc1b.approval.integration;

import io.github.akaryc1b.approval.ApprovalPlatformApplication;
import io.github.akaryc1b.approval.application.PurchasePaymentTaskActionService;
import io.github.akaryc1b.approval.application.RuntimeBindingEnforcingProjectionStore;
import io.github.akaryc1b.approval.application.PurchasePaymentTaskActionService.TaskActionCommand;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.ProjectionConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.demo.PurchasePaymentDemoSeedState;
import io.github.akaryc1b.approval.demo.PurchasePaymentDemoSeeder;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.observability.ObservedApprovalProjectionStore;
import io.github.akaryc1b.approval.observability.ObservedIdempotencyGuard;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real PostgreSQL, Flowable, application services, transaction rollback and internal scrape. */
@Testcontainers
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = ApprovalPlatformApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "approval.demo.purchase-payment.enabled=true",
        "approval.connector.generic.enabled=false",
        "flowable.async-executor-activate=false",
        "management.server.address=127.0.0.1",
        "management.server.port=0",
        "management.endpoints.web.exposure.include=health,prometheus"
    }
)
class ApprovalBusinessMetricsPostgresTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_business_metrics_test")
        .withUsername("approval")
        .withPassword(UUID.randomUUID().toString());

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    PurchasePaymentDemoSeedState state;
    @Autowired
    PurchasePaymentDemoSeeder seeder;
    @Autowired
    ApprovalProjectionStore projections;
    @Autowired
    @Qualifier("approvalProjectionStore")
    ApprovalProjectionStore rawProjections;
    @Autowired
    PurchasePaymentTaskActionService actions;
    @Autowired
    IdempotencyGuard guard;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    MeterRegistry registry;
    @LocalManagementPort
    int managementPort;

    @Test
    void realWorkflowRollbackReplayAndPrometheusCountsAgree() throws Exception {
        assertInstanceOf(RuntimeBindingEnforcingProjectionStore.class, projections);
        assertInstanceOf(ObservedApprovalProjectionStore.class, rawProjections);
        assertInstanceOf(ObservedIdempotencyGuard.class, guard);
        var seeded = state.requireEvidence();
        String tenant = seeded.tenantId();
        UUID instanceId = seeded.instanceId();
        double starts = count("approval.process.started");
        double completions = count("approval.process.completed");
        double tasks = count("approval.task.completed");
        long commands = timed("committed");
        long rollbacks = timed("rolled_back");
        assertTrue(starts >= 1.0d, "the actual seeded start must be observed");
        seeder.apply();
        assertEquals(starts, count("approval.process.started"), "seed replay is not a new process");

        TaskProjection first = pending(tenant, instanceId);
        TaskActionCommand firstCommand = command(tenant, first);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            actions.approve(firstCommand);
            assertEquals(tasks, count("approval.task.completed"), "uncommitted writes must be invisible");
            status.setRollbackOnly();
        });
        assertEquals(first.taskId(), pending(tenant, instanceId).taskId());
        assertEquals(tasks, count("approval.task.completed"));
        assertEquals(completions, count("approval.process.completed"));
        assertEquals(rollbacks + 1, timed("rolled_back"));

        // Reuse the rolled-back idempotency key: its database transaction did not commit.
        actions.approve(firstCommand);
        int decisions = 1;
        TaskActionCommand last = firstCommand;
        while (projections.findInstance(tenant, instanceId).orElseThrow().status() != InstanceStatus.COMPLETED) {
            assertTrue(decisions < 8, "deterministic workflow must finish within eight decisions");
            last = command(tenant, pending(tenant, instanceId));
            actions.approve(last);
            decisions++;
        }
        assertEquals(5, decisions);
        assertEquals(tasks + 5, count("approval.task.completed"));
        assertEquals(completions + 1, count("approval.process.completed"));
        assertEquals(commands + 5, timed("committed"));

        actions.approve(last);
        assertEquals(tasks + 5, count("approval.task.completed"), "cached response is not another transition");
        assertEquals(commands + 5, timed("committed"), "cached response must not execute the timer callback");
        TaskActionCommand stale = new TaskActionCommand(
            new RequestContext(tenant, first.assigneeId(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), null),
            first.taskId(), "metrics integration"
        );
        assertThrows(ProjectionConflictException.class, () -> actions.approve(stale));
        assertEquals(rollbacks + 2, timed("rolled_back"));
        assertEquals(completions + 1, count("approval.process.completed"));

        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus")
            ).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("approval_process_started_total"));
            assertTrue(response.body().contains("approval_process_completed_total"));
            assertTrue(response.body().contains("approval_task_completed_total"));
            assertTrue(response.body().contains("approval_command_duration_seconds_count"));
        }
        registry.getMeters().stream().filter(meter -> meter.getId().getName().startsWith("approval.process.")
            || meter.getId().getName().equals("approval.task.completed")
            || meter.getId().getName().equals("approval.command.duration")).forEach(meter -> {
                for (var tag : meter.getId().getTags()) {
                    if (tag.getKey().equals("application")) {
                        assertEquals("approval-platform", tag.getValue());
                    } else {
                        assertTrue(tag.getKey().equals("operation") || tag.getKey().equals("outcome"));
                    }
                }
            });
    }

    private TaskProjection pending(String tenant, UUID instanceId) {
        return projections.findTasks(tenant, instanceId).stream()
            .filter(task -> task.status() == TaskStatus.PENDING).findFirst().orElseThrow();
    }

    private TaskActionCommand command(String tenant, TaskProjection task) {
        String key = UUID.randomUUID().toString();
        return new TaskActionCommand(new RequestContext(tenant, task.assigneeId(), key, key, null),
            task.taskId(), "metrics integration");
    }

    private double count(String name) {
        Counter counter = registry.find(name).counter();
        return counter == null ? 0.0d : counter.count();
    }

    private long timed(String outcome) {
        Timer timer = registry.find("approval.command.duration")
            .tags("operation", "approve", "outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }
}
