package io.github.akaryc1b.approval.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.ApprovalPlatformApplication;
import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker;
import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker.WorkerMetrics;
import io.github.akaryc1b.approval.application.port.ApprovalSlaActionDispatcher.DispatchResult;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.IntentStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarIdentity;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.PolicyStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaPolicyIdentity;
import io.github.akaryc1b.approval.integration.jdbc.JdbcOutboxRepository;
import io.github.akaryc1b.approval.observability.SlaCompletionLagMetrics;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaActionStateRecorder;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaTimeoutEventRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static io.github.akaryc1b.approval.observability.SlaLagFixtures.CALENDAR;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.DEADLINE;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.FINISH;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.INSTANCE;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.POLICY;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.SLA;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.START;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.TASK;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.TENANT;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.calendar;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.id;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.intent;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.policy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Actual application management HTTP export after real SLA/Outbox writes, not a fabricated scrape response. */
@Testcontainers
@ActiveProfiles("local")
@Import(CollectorRehearsalSchemaConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = ApprovalPlatformApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "approval.demo.purchase-payment.enabled=false",
        "approval.connector.generic.enabled=false",
        "approval.sla.execution.enabled=false",
        "approval.observability.sla-lag.enabled=true",
        "flowable.async-executor-activate=false",
        "management.server.address=127.0.0.1",
        "management.server.port=0",
        "management.endpoints.web.exposure.include=health,prometheus"
    })
class ApprovalSlaLagPrometheusPostgresTest {
    private static final String METRIC = "approval_sla_overdue_completion_lag_seconds";
    private static final String DROPPED = "approval_sla_overdue_completion_observations_dropped_total";
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("sla_lag_http_test").withUsername("approval").withPassword(UUID.randomUUID().toString());
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager manager;
    @Autowired ApprovalSlaStore store;
    @Autowired ApprovalSlaExecutionStore executions;
    @Autowired WorkerMetrics metrics;
    @LocalManagementPort int managementPort;
    @LocalServerPort int applicationPort;

    @Test
    void committedRetryExportsExactPinnedCalendarDurationsAndOmissionsOnPrivateHttpOnly() throws Exception {
        assertInstanceOf(SlaCompletionLagMetrics.class, metrics);
        seed();
        assertEquals(1, executions.enqueue(List.of(intent(IntentStatus.READY, true))));
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            String initial = scrape(http);
            assertEquals(0.0d, only(samples(initial, DROPPED)).value());
            assertTrue(samples(initial, METRIC + "_count").isEmpty(), "unused timing must not invent zero samples");
            assertEquals(1, execute(FINISH, true).retryScheduled());
            assertEquals(1L, outboxCount());
            String retrying = scrape(http);
            assertTrue(samples(retrying, METRIC + "_count").isEmpty(), "retryable is not successful completion");
            assertEquals(1, execute(FINISH.plusSeconds(2), false).succeeded());
            assertEquals(IntentStatus.SUCCEEDED, executions.findIntent(TENANT, id(6)).orElseThrow().status());
            String completed = scrape(http);
            assertLag(completed, "natural_time", 237602.0d);
            assertLag(completed, "working_time", 7202.0d);
            assertEquals(2, samples(completed, METRIC + "_count").size());
            assertEquals(0, execute(FINISH.plusSeconds(3), false).claimed());
            String noReplay = scrape(http);
            assertLag(noReplay, "natural_time", 237602.0d);
            assertLag(noReplay, "working_time", 7202.0d);
            assertEquals(1L, outboxCount());
            // Exercise omission export, not a new business failure or a synthetic successful event.
            metrics.completed(null);
            String omitted = scrape(http);
            assertEquals(1.0d, only(samples(omitted, DROPPED)).value());
            assertLag(omitted, "working_time", 7202.0d);
            assertEquals(1L, outboxCount());
            var publicResponse = request(http, applicationPort);
            assertTrue(Set.of(401, 403, 404).contains(publicResponse.statusCode()), "no public metrics response");
            assertFalse(publicResponse.body().contains(METRIC));
        }
    }

    private ApprovalSlaExecutionWorker.WorkerReport execute(java.time.Instant now, boolean failAcknowledgement) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        var recorder = new JdbcApprovalSlaTimeoutEventRecorder(dataSource, manager, executions,
            new JdbcApprovalSlaActionStateRecorder(dataSource, manager), new JdbcOutboxRepository(dataSource, new ObjectMapper()),
            "generic-rest", clock);
        AtomicInteger calls = new AtomicInteger();
        var worker = new ApprovalSlaExecutionWorker(executions, value -> {
            calls.incrementAndGet(); recorder.record(value);
            return failAcknowledgement ? DispatchResult.retryableFailure("FIXTURE_ACK_LOST", "fixture") : DispatchResult.succeeded();
        }, metrics, clock, new ApprovalSlaExecutionWorker.Configuration(true, "http-fixture-worker", 10,
            Duration.ofSeconds(60), Duration.ofSeconds(1), Duration.ofSeconds(10)), UUID::randomUUID);
        var result = worker.processTenant(TENANT);
        assertEquals(result.claimed(), calls.get());
        return result;
    }
    private static HttpResponse<String> request(HttpClient http, int port) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/prometheus"))
            .timeout(Duration.ofSeconds(5)).header("Accept", "text/plain;version=0.0.4").GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private String scrape(HttpClient http) throws Exception {
        var response = request(http, managementPort);
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElse("").contains("text/plain"));
        String body = response.body();
        String timing = body.lines().filter(line -> line.startsWith("approval_sla_overdue_completion_")).reduce("", (a, b) -> a + b + "\n");
        for (String forbidden : List.of(TENANT, TASK.toString(), POLICY.toString(), "request-lag", "trace-lag", "calendar_id=", "tenant_id=")) {
            assertFalse(timing.contains(forbidden), "no identity in exported timing series");
        }
        return body;
    }
    private static void assertLag(String body, String basis, double seconds) {
        Map<String, String> tags = Map.of("application", "approval-platform", "target", "task", "source", "original", "time_basis", basis);
        Sample count = only(samples(body, METRIC + "_count").stream().filter(value -> basis.equals(value.tags().get("time_basis"))).toList());
        Sample sum = only(samples(body, METRIC + "_sum").stream().filter(value -> basis.equals(value.tags().get("time_basis"))).toList());
        assertEquals(tags, count.tags()); assertEquals(tags, sum.tags());
        assertEquals(1.0d, count.value()); assertEquals(seconds, sum.value(), 0.000001d);
        var buckets = samples(body, METRIC + "_bucket").stream().filter(value -> basis.equals(value.tags().get("time_basis"))).toList();
        assertTrue(buckets.size() > 2);
        Sample infinity = only(buckets.stream().filter(value -> "+Inf".equals(value.tags().get("le"))).toList());
        var expected = new HashMap<>(tags); expected.put("le", "+Inf");
        assertEquals(expected, infinity.tags()); assertEquals(1.0d, infinity.value());
    }
    private static List<Sample> samples(String body, String name) {
        Pattern sample = Pattern.compile("^" + Pattern.quote(name) + "(?:\\{([^}]*)\\})? ([^ ]+)$");
        Pattern label = Pattern.compile("([a-z_]+)=\"([^\"]*)\"");
        return body.lines().map(sample::matcher).filter(java.util.regex.Matcher::matches).map(match -> {
            Map<String, String> tags = new HashMap<>();
            var fields = label.matcher(match.group(1) == null ? "" : match.group(1));
            while (fields.find()) assertEquals(null, tags.put(fields.group(1), fields.group(2)), "unique labels");
            return new Sample(Map.copyOf(tags), Double.parseDouble(match.group(2)));
        }).toList();
    }
    private static Sample only(List<Sample> values) { assertEquals(1, values.size()); return values.getFirst(); }
    private record Sample(Map<String, String> tags, double value) { }
    private long outboxCount() { return new JdbcTemplate(dataSource).queryForObject("select count(*) from ap_outbox", Long.class); }

    private void seed() {
        var jdbc = new JdbcTemplate(dataSource); var start = Timestamp.from(START);
        jdbc.update("""
            insert into ap_definition_version (tenant_id, definition_key, definition_version, form_key, form_version,
            compiler_version, content_hash, deployment_id, engine_definition_id, engine_version, published_by, published_at)
            values (?, 'purchasePayment',1,'purchasePayment',1,'compiler-v1',repeat('a',64),'lag-http-deployment',
            'lag-http-definition',1,'publisher',?)
            """, TENANT, start);
        jdbc.update("""
            insert into ap_approval_instance (instance_id, tenant_id, business_key, engine_instance_id,
            definition_key, definition_version, form_key, form_version, compiler_version, content_hash,
            initiator_id, amount, supplier, purchase_order_reference, attachment_ids_json, assignee_snapshot_json,
            request_hash, status, version, created_at, updated_at)
            values (?,?,'LAG-HTTP-1','lag-http-engine','purchasePayment',1,'purchasePayment',1,'compiler-v1',
            repeat('a',64),'initiator',100,'fixture','PO-1','[]'::jsonb,'{}'::jsonb,repeat('b',64),'RUNNING',1,?,?)
            """, INSTANCE, TENANT, start, start);
        jdbc.update("""
            insert into ap_approval_task (task_id,instance_id,tenant_id,engine_task_id,task_definition_key,
            task_name,assignee_id,status,version,created_at,updated_at,completed_at)
            values (?,?,?,'lag-http-task','managerApproval','Approval','owner-a','PENDING',1,?,?,null)
            """, TASK, INSTANCE, TENANT, start, start);
        store.createCalendar(new CalendarIdentity(CALENDAR, TENANT, "lag-http-calendar", "Lag HTTP calendar", "Asia/Shanghai",
            CalendarStatus.DRAFT, null, "designer", START, START, 1));
        store.saveCalendarVersion(calendar(TENANT, 1, false), calendarRevision());
        store.publishCalendarVersion(TENANT, CALENDAR, 1, "publisher", START, calendarRevision());
        store.activateCalendarVersion(TENANT, CALENDAR, 1, "publisher", START, calendarRevision());
        store.createPolicy(new SlaPolicyIdentity(POLICY, TENANT, "lag-http-policy", "Lag HTTP policy", PolicyStatus.DRAFT,
            null, "designer", START, START, 1));
        store.savePolicyVersion(policy(true, TENANT, 1, false), policyRevision());
        store.publishPolicyVersion(TENANT, POLICY, 1, "publisher", START, policyRevision());
        store.activatePolicyVersion(TENANT, POLICY, 1, "publisher", START, policyRevision());
        jdbc.update("""
            insert into ap_sla_instance (sla_instance_id,tenant_id,approval_instance_id,task_id,collaboration_participant_id,
            definition_key,task_definition_key,target_type,policy_id,policy_version,calendar_id,calendar_version,time_zone,
            responsible_user_id,original_responsible_user_id,started_at,due_at,next_reminder_at,overdue_at,paused_at,
            pause_reason,accumulated_paused_millis,terminal_at,terminal_reason,status,last_action_sequence,
            request_id,trace_id,version,created_at,updated_at)
            values (?,?,?,?,null,'purchasePayment','managerApproval','TASK',?,1,?,1,'Asia/Shanghai','owner-a','owner-a',
            ?,?,null,?,null,null,0,null,null,'ACTIVE',0,'request-lag','trace-lag',1,?,?)
            """, SLA, TENANT, INSTANCE, TASK, POLICY, CALENDAR, start, Timestamp.from(DEADLINE), Timestamp.from(DEADLINE), start, start);
    }
    private long calendarRevision() { return store.findCalendar(TENANT, CALENDAR).orElseThrow().version(); }
    private long policyRevision() { return store.findPolicy(TENANT, POLICY).orElseThrow().version(); }
}
