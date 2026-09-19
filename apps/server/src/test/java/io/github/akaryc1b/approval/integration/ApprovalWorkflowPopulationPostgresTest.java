package io.github.akaryc1b.approval.integration;

import io.github.akaryc1b.approval.ApprovalPlatformApplication;
import io.github.akaryc1b.approval.application.PurchasePaymentTaskActionService;
import io.github.akaryc1b.approval.application.PurchasePaymentTaskActionService.TaskActionCommand;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.demo.PurchasePaymentDemoSeedState;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.observability.WorkflowPopulationMetrics;
import io.github.akaryc1b.approval.security.ApprovalIdentityContextFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static io.github.akaryc1b.approval.security.ApprovalIdentityContextFilter.OPERATOR_ID_HEADER;
import static io.github.akaryc1b.approval.security.ApprovalIdentityContextFilter.REQUEST_ID_HEADER;
import static io.github.akaryc1b.approval.security.ApprovalIdentityContextFilter.TENANT_ID_HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Real application/Flowable/PostgreSQL plus private HTTP export; approval calls are service integration. */
@Testcontainers
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = ApprovalPlatformApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "approval.demo.purchase-payment.enabled=true", "approval.connector.generic.enabled=false",
        "approval.observability.workflow.enabled=true", "approval.observability.outbox.enabled=false",
        "flowable.async-executor-activate=false", "management.server.address=127.0.0.1",
        "management.server.port=0", "management.endpoints.web.exposure.include=health,prometheus"
    })
class ApprovalWorkflowPopulationPostgresTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("workflow_population_application_test").withUsername("approval")
        .withPassword(UUID.randomUUID().toString());
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired PurchasePaymentDemoSeedState state;
    @Autowired ApprovalProjectionStore projections;
    @Autowired PurchasePaymentTaskActionService actions;
    @Autowired PlatformTransactionManager transactions;
    @Autowired WorkflowPopulationMetrics population;
    @Autowired MeterRegistry registry;
    @Autowired @Qualifier("approvalIdentityContextFilter")
    FilterRegistrationBean<ApprovalIdentityContextFilter> identity;
    @LocalManagementPort int managementPort;

    @Test
    void realApprovalPopulationsRollbackReplayAndHttpScrapeAgree() throws Exception {
        var seeded = state.requireEvidence(); String tenant = seeded.tenantId(); UUID instanceId = seeded.instanceId();
        sample(1, activeTasks(tenant, instanceId));
        TaskProjection first = pending(tenant, instanceId); TaskActionCommand firstCommand = command(tenant, first);
        var denied = new MockHttpServletResponse();
        identity.getFilter().doFilter(new MockHttpServletRequest("POST", "/api/approval/tasks/" + first.taskId() + "/approve"),
            denied, (request, response) -> fail("unidentified request reached the business service"));
        assertEquals(401, denied.getStatus());

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            approve(firstCommand);
            // The private reader must not join this uncommitted transaction.
            sample(1, 1);
            status.setRollbackOnly();
        });
        assertEquals(first.taskId(), pending(tenant, instanceId).taskId()); sample(1, 1);

        TaskActionCommand last = firstCommand; int decisions = 0;
        while (projections.findInstance(tenant, instanceId).orElseThrow().status() == InstanceStatus.RUNNING) {
            assertTrue(decisions < 8, "bounded deterministic approval scenario");
            last = decisions == 0 ? firstCommand : command(tenant, pending(tenant, instanceId));
            approve(last); decisions++;
            boolean running = projections.findInstance(tenant, instanceId).orElseThrow().status() == InstanceStatus.RUNNING;
            sample(running ? 1 : 0, activeTasks(tenant, instanceId));
        }
        assertEquals(5, decisions); approve(last); sample(0, 0);
        assertEquals(InstanceStatus.COMPLETED, projections.findInstance(tenant, instanceId).orElseThrow().status());

        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus"))
                .timeout(Duration.ofSeconds(10)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            for (String metric : List.of("approval_process_active", "approval_process_sla_covered", "approval_process_overdue",
                "approval_task_active", "approval_task_sla_covered", "approval_task_overdue")) {
                assertEquals(0.0d, exported(response.body(), metric));
            }
            assertEquals(1.0d, exported(response.body(), "approval_workflow_sample_up"));
            assertTrue(Double.isFinite(exported(response.body(), "approval_workflow_sample_timestamp_seconds")));
        }
        registry.getMeters().stream().filter(meter -> meter.getId().getName().startsWith("approval.workflow.")
            || List.of("approval.process.active", "approval.process.sla.covered", "approval.process.overdue",
                "approval.task.active", "approval.task.sla.covered", "approval.task.overdue").contains(meter.getId().getName()))
            .forEach(meter -> meter.getId().getTags().forEach(tag -> {
                assertEquals("application", tag.getKey()); assertEquals("approval-platform", tag.getValue());
            }));
    }

    private void sample(long processes, long tasks) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        do {
            population.refresh();
            if (gauge("approval.workflow.sample.up") == 1.0d && gauge("approval.process.active") == processes
                && gauge("approval.task.active") == tasks) return;
            try { Thread.sleep(20); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        } while (System.nanoTime() < deadline);
        assertEquals(1.0d, gauge("approval.workflow.sample.up"));
        assertEquals((double) processes, gauge("approval.process.active"));
        assertEquals((double) tasks, gauge("approval.task.active"));
    }
    private double gauge(String name) { return registry.get(name).gauge().value(); }
    private long activeTasks(String tenant, UUID instanceId) {
        return projections.findTasks(tenant, instanceId).stream()
            .filter(task -> task.status() == TaskStatus.PENDING || task.status() == TaskStatus.COMPLETING).count();
    }
    private TaskProjection pending(String tenant, UUID instanceId) {
        return projections.findTasks(tenant, instanceId).stream().filter(task -> task.status() == TaskStatus.PENDING)
            .findFirst().orElseThrow();
    }
    private TaskActionCommand command(String tenant, TaskProjection task) {
        String key = UUID.randomUUID().toString();
        return new TaskActionCommand(new RequestContext(tenant, task.assigneeId(), key, key, null), task.taskId(), "population integration");
    }
    private void approve(TaskActionCommand command) {
        var request = new MockHttpServletRequest("POST", "/api/approval/tasks/" + command.taskId() + "/approve");
        request.addHeader(TENANT_ID_HEADER, command.context().tenantId());
        request.addHeader(OPERATOR_ID_HEADER, command.context().operatorId());
        request.addHeader(REQUEST_ID_HEADER, command.context().requestId());
        var response = new MockHttpServletResponse(); var invoked = new AtomicBoolean();
        try {
            identity.getFilter().doFilter(request, response, (trusted, output) -> { invoked.set(true); actions.approve(command); });
        } catch (java.io.IOException | jakarta.servlet.ServletException failure) { throw new AssertionError(failure); }
        assertTrue(invoked.get()); assertEquals(200, response.getStatus());
    }
    private static double exported(String text, String name) {
        var matcher = Pattern.compile("^" + Pattern.quote(name) + "(?:\\{[^\\n]*\\})?\\s+([^\\s]+)", Pattern.MULTILINE).matcher(text);
        assertTrue(matcher.find(), "missing Prometheus series " + name); return Double.parseDouble(matcher.group(1));
    }
}
