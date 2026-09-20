package io.github.akaryc1b.approval.integration;

import io.github.akaryc1b.approval.ApprovalPlatformApplication;
import io.github.akaryc1b.approval.engine.EngineJobPopulationReader.Snapshot;
import io.github.akaryc1b.approval.engine.flowable.FlowableJobPopulationReader;
import io.github.akaryc1b.approval.observability.EngineJobMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.testcontainers.postgresql.PostgreSQLContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Actual Flowable/PostgreSQL job transitions and the existing private HTTP Prometheus endpoint. */
@Testcontainers
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = ApprovalPlatformApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "approval.demo.purchase-payment.enabled=true", "approval.connector.generic.enabled=false",
        "approval.observability.engine-jobs.enabled=true", "approval.observability.workflow.enabled=false",
        "approval.observability.outbox.enabled=false", "flowable.async-executor-activate=false",
        "management.server.address=127.0.0.1", "management.server.port=0",
        "management.endpoints.web.exposure.include=health,prometheus"
    })
public class ApprovalEngineJobMetricsPostgresTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("engine_job_observation_test").withUsername("approval")
        .withPassword(UUID.randomUUID().toString());
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired ManagementService management;
    @Autowired RepositoryService repository;
    @Autowired RuntimeService runtime;
    @Autowired EngineJobMetrics metrics;
    @Autowired MeterRegistry registry;
    @LocalManagementPort int managementPort;

    @Test
    void publicEngineTransitionsAndPrivateHttpExportAgreeWithoutObserverSideEffects() throws Exception {
        var reader = new FlowableJobPopulationReader(management, Clock.systemUTC());
        Snapshot base = reader.read();
        NoopTask.calls.set(0);
        String a = repository.createDeployment().tenantId("job-monitor-tenant-a")
            .addString("job-monitor.bpmn20.xml", bpmn()).deploy().getId();
        String b = repository.createDeployment().tenantId("job-monitor-tenant-b")
            .addString("job-monitor.bpmn20.xml", bpmn()).deploy().getId();
        try {
            String first = runtime.startProcessInstanceByKeyAndTenantId("monitorAsync", "job-monitor-tenant-a").getId();
            String second = runtime.startProcessInstanceByKeyAndTenantId("monitorAsync", "job-monitor-tenant-b").getId();
            String timed = runtime.startProcessInstanceByKeyAndTenantId("monitorTimer", "job-monitor-tenant-a").getId();
            sample(base, 2, 1, 0, 0);
            assertEquals(0, NoopTask.calls.get(), "sampling must not execute a job");
            runtime.suspendProcessInstanceById(second);
            sample(base, 1, 1, 1, 0);
            var firstJob = management.createJobQuery().processInstanceId(first).singleResult();
            assertNotNull(firstJob);
            // Test-only public engine operation: observe durable dead-letter state, not
            // an automatic retry-exhaustion claim or a new product recovery endpoint.
            String deadId = management.moveJobToDeadLetterJob(firstJob.getId()).getId();
            sample(base, 0, 1, 1, 1);
            assertNotNull(runtime.createProcessInstanceQuery().processInstanceId(first).singleResult(),
                "one dead job is not a terminal approval process");
            for (int i = 0; i < 5; i++) reader.read();
            assertEquals(1, management.createDeadLetterJobQuery().jobId(deadId).count());
            assertEquals(0, NoopTask.calls.get());
            assertHttp(base, 0, 1, 1, 1);

            // Reconstruct the reader: counts must survive reader recreation rather than
            // depend on local lifecycle counters.
            assertEquals(base.deadLetter() + 1,
                new FlowableJobPopulationReader(management, Clock.systemUTC()).read().deadLetter());
            String resumedJob = management.moveDeadLetterJobToExecutableJob(deadId, 3).getId();
            management.executeJob(resumedJob);
            sample(base, 0, 1, 1, 0);
            runtime.activateProcessInstanceById(second);
            management.executeJob(management.createJobQuery().processInstanceId(second).singleResult().getId());
            String timerId = management.createTimerJobQuery().processInstanceId(timed).singleResult().getId();
            management.executeJob(management.moveTimerToExecutableJob(timerId).getId());
            sample(base, 0, 0, 0, 0);
            assertEquals(2, NoopTask.calls.get());
            assertHttp(base, 0, 0, 0, 0);
            registry.getMeters().stream().filter(meter -> meter.getId().getName().startsWith("approval.engine.jobs"))
                .forEach(meter -> meter.getId().getTags().forEach(tag ->
                    assertTrue(List.of("application", "queue").contains(tag.getKey()), "bounded labels only")));
        } finally {
            repository.deleteDeployment(a, true);
            repository.deleteDeployment(b, true);
        }
    }

    private void sample(Snapshot base, long executable, long timer, long suspended, long dead) throws Exception {
        double[] expected = {base.executable() + executable, base.timer() + timer,
            base.suspended() + suspended, base.deadLetter() + dead};
        List<String> queues = List.of("executable", "timer", "suspended", "dead_letter");
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        do {
            metrics.refresh();
            boolean matches = registry.get("approval.engine.jobs.sample.up").gauge().value() == 1;
            for (int i = 0; i < queues.size(); i++) {
                matches &= registry.get("approval.engine.jobs").tags("queue", queues.get(i)).gauge().value() == expected[i];
            }
            if (matches) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        for (int i = 0; i < queues.size(); i++) {
            assertEquals(expected[i], registry.get("approval.engine.jobs").tags("queue", queues.get(i)).gauge().value());
        }
        assertEquals(1.0d, registry.get("approval.engine.jobs.sample.up").gauge().value());
    }

    private void assertHttp(Snapshot base, long executable, long timer, long suspended, long dead) throws Exception {
        double[] expected = {base.executable() + executable, base.timer() + timer,
            base.suspended() + suspended, base.deadLetter() + dead};
        List<String> queues = List.of("executable", "timer", "suspended", "dead_letter");
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var response = client.send(HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + managementPort + "/actuator/prometheus"))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            for (int i = 0; i < queues.size(); i++) {
                var match = Pattern.compile("(?m)^approval_engine_jobs\\{[^}]*queue=\"" + queues.get(i)
                    + "\"[^}]*} ([^ \\r\\n]+)").matcher(response.body());
                assertTrue(match.find(), "queue exported: " + queues.get(i));
                assertEquals(expected[i], Double.parseDouble(match.group(1)));
            }
        }
    }

    public static final class NoopTask implements JavaDelegate {
        static final AtomicInteger calls = new AtomicInteger();
        @Override
        public void execute(DelegateExecution execution) { calls.incrementAndGet(); }
    }
    private static String bpmn() {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
              xmlns:flowable="http://flowable.org/bpmn" targetNamespace="engine-job-monitor-test">
              <process id="monitorAsync" isExecutable="true">
                <startEvent id="start"/><sequenceFlow id="one" sourceRef="start" targetRef="work"/>
                <serviceTask id="work" flowable:async="true" flowable:class="%s"/>
                <sequenceFlow id="two" sourceRef="work" targetRef="end"/><endEvent id="end"/>
              </process>
              <process id="monitorTimer" isExecutable="true">
                <startEvent id="timerStart"/><sequenceFlow id="timerOne" sourceRef="timerStart" targetRef="timerWait"/>
                <intermediateCatchEvent id="timerWait"><timerEventDefinition><timeDuration>PT1H</timeDuration>
                  </timerEventDefinition></intermediateCatchEvent>
                <sequenceFlow id="timerTwo" sourceRef="timerWait" targetRef="timerEnd"/><endEvent id="timerEnd"/>
              </process>
            </definitions>
            """.formatted(NoopTask.class.getName());
    }
}
