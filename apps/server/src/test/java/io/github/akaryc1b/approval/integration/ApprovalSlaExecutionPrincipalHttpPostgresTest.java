package io.github.akaryc1b.approval.integration;

import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ActionType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.IntentStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaActionDispatcher;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.demo.PurchasePaymentDemoScenario;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.slf4j.MDC;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.ApprovalPlatformApplication;
import io.github.akaryc1b.approval.security.ApprovalEnterpriseRole;
import io.github.akaryc1b.approval.security.ApprovalPrincipal;
import io.github.akaryc1b.approval.security.ApprovalResourceScope;
import io.github.akaryc1b.approval.security.ApprovalResponsibilityAssignment;
import io.github.akaryc1b.approval.security.ApprovalResponsibilitySourceType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.web.filter.OncePerRequestFilter;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real HTTP/PG with a JVM-owned test authentication adapter, NOT browser SSO acceptance. */
@EnabledIf("databaseAvailable")
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = ApprovalSlaExecutionPrincipalHttpPostgresTest.DatabaseCleanup.class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Import(ApprovalSlaExecutionPrincipalHttpPostgresTest.IdentityFixture.class)
@SpringBootTest(classes = ApprovalPlatformApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "approval.security.identity.mode=principal",
        "approval.demo.purchase-payment.enabled=true",
        "approval.demo.purchase-payment.sandbox.enabled=false",
        "approval.connector.generic.enabled=false",
        "flowable.async-executor-activate=false",
        "approval.sla.execution.enabled=false",
        "approval.sla.operational-notifications.enabled=false",
        "approval.notifications.delivery-delay-ms=3600000"
    })
class ApprovalSlaExecutionPrincipalHttpPostgresTest {
    private static final String TENANT = "demo-purchase-payment";
    private static PostgreSQLContainer postgres;
    private static final String EXTERNAL_DB = System.getenv("APPROVAL_TEST_DB_URL");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @LocalServerPort int port;
    @Autowired AtomicReference<ApprovalPrincipal> testPrincipal;
    @Autowired @Qualifier("approvalPersistenceObjectMapper") ObjectMapper mapper;

    static boolean databaseAvailable() {
        return EXTERNAL_DB != null || DockerClientFactory.instance().isDockerAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        if (EXTERNAL_DB != null) {
            if (!EXTERNAL_DB.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/[a-zA-Z0-9_]+")) {
                throw new IllegalArgumentException("external test PostgreSQL must use explicit IPv4 loopback");
            }
            properties.add("spring.datasource.url", () -> EXTERNAL_DB);
            properties.add("spring.datasource.username", () -> System.getenv("APPROVAL_TEST_DB_USERNAME"));
            properties.add("spring.datasource.password", () -> System.getenv("APPROVAL_TEST_DB_PASSWORD"));
        } else {
            postgres = new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("approval_audit_http").withUsername("approval").withPassword("approval");
            postgres.start();
            properties.add("spring.datasource.url", postgres::getJdbcUrl);
            properties.add("spring.datasource.username", postgres::getUsername);
            properties.add("spring.datasource.password", postgres::getPassword);
        }
    }

    // afterTestClass callbacks run in reverse order: Spring dirties/closes at3000, then DB stops.
    public static class DatabaseCleanup extends AbstractTestExecutionListener {
        @Override public int getOrder() { return 2500; }
        @Override public void afterTestClass(TestContext ignored) {
            if (postgres != null) postgres.stop();
        }
    }

    @Autowired ApprovalSlaStore slaStore;
    @Autowired ApprovalSlaExecutionStore executions;
    @Autowired ApprovalSlaActionDispatcher dispatcher;
    @Autowired PurchasePaymentApplicationService purchase;
    @Autowired PurchasePaymentDemoScenario scenario;
    @TestBean(name="purchasePaymentDemoClock", methodName="controlledClock") Clock approvalClock;
    static Clock controlledClock() { return new MutableClock(); }
    static final class MutableClock extends Clock {
        private volatile Instant current;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("UTC only"); return this; }
        @Override public Instant instant() { return current == null ? Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS) : current; }
        void advanceTo(Instant next) { if (current != null && next.isBefore(current)) throw new IllegalArgumentException("clock cannot regress"); current = next; }
    }
    @AfterEach void clearIdentity() { testPrincipal.set(null); }
    private void role(ApprovalEnterpriseRole role) {
        var assignment = new ApprovalResponsibilityAssignment(role, ApprovalResponsibilitySourceType.ROLE,
            "isolated-sla-role", ApprovalResourceScope.tenant());
        testPrincipal.set(ApprovalPrincipal.active(TENANT, "isolated-sla-admin", Set.of(), Set.of(assignment), null));
    }

    @Test void pauseResumeWorkerAndReplayUseRealDurableStateWithoutPretendingExternalDelivery() throws Exception {
        MutableClock clock = (MutableClock) approvalClock;
        role(ApprovalEnterpriseRole.PROCESS_DESIGNER);
        JsonNode created = request("POST", "/management/sla/policies",
            Map.of("policyKey", "isolated-sla-policy", "displayName", "Isolated SLA policy"), null, 200, false);
        String policy = "/management/sla/policies/" + created.path("policyId").asText();
        Map<String,Object> version = new LinkedHashMap<>();
        version.put("definitionKey", "purchase-payment"); version.put("taskDefinitionKey", "managerApproval");
        version.put("targetType", "TASK"); version.put("durationMode", "NATURAL_TIME"); version.put("duration", "PT1H");
        version.put("firstReminderOffset", "PT10M"); version.put("maximumReminderCount", 1);
        version.put("overdueOffset", "PT0S"); version.put("escalationTargetType", "USER"); version.put("escalationTarget", "demo-admin");
        version.put("automaticAction", "NONE"); version.put("naturalTimePauses", true); version.put("expectedIdentityVersion", 1);
        request("PUT", policy + "/versions/1", version, null, 200, false);
        role(ApprovalEnterpriseRole.PROCESS_PUBLISHER);
        request("POST", policy + "/versions/1/publish", Map.of("expectedIdentityVersion", 2), null, 200, false);
        request("POST", policy + "/versions/1/activate", Map.of("expectedIdentityVersion", 3), null, 200, false);
        var context = new RequestContext(TENANT, "demo-employee", "sla-runtime-start", "sla-runtime-start", "sla-runtime-trace");
        PurchasePaymentApplicationService.StartResult started;
        Map<String,String> previous = MDC.getCopyOfContextMap();
        try {
            MDC.put("operatorId", context.operatorId()); MDC.put("requestId", context.requestId()); MDC.put("traceId", context.traceId());
            started = purchase.start(new PurchasePaymentApplicationService.StartCommand(context, "sla-runtime-business",
                new java.math.BigDecimal("1200"), "Supplier", "ORDER", List.of("isolated-receipt-reference"), scenario.assigneeRules()));
        } finally { MDC.clear(); if (previous != null) MDC.setContextMap(previous); }
        var instance = slaStore.findActiveByApprovalInstance(TENANT, started.instanceId()).stream().findFirst().orElseThrow();
        // Flowable task timestamps use the engine clock; freeze only after actual creation.
        clock.advanceTo(Instant.now().plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertTrue(!clock.instant().isBefore(instance.startedAt()));
        assertEquals(3, intents().size()); assertEquals(3, count(IntentStatus.READY));
        String path = "/management/sla/instances/" + instance.slaInstanceId();
        role(ApprovalEnterpriseRole.PROCESS_DESIGNER);
        request("GET", path, null, null, 200, false);
        JsonNode paused = request("POST", path + "/pause", Map.of("expectedVersion", instance.version(),
            "reason", "Isolated pause acceptance"), null, 200, false);
        assertEquals("PAUSED", paused.path("status").asText());
        assertEquals(paused, request("POST", path + "/pause", Map.of("expectedVersion", instance.version(),
            "reason", "Isolated pause acceptance"), null, 200, false));
        assertEquals(3, intents().size()); assertEquals(3, count(IntentStatus.CANCELLED));
        clock.advanceTo(clock.instant().plus(Duration.ofMinutes(5)));
        JsonNode resumed = request("POST", path + "/resume", Map.of("expectedVersion", paused.path("version").asLong()), null, 200, false);
        assertEquals("ACTIVE", resumed.path("status").asText());
        assertEquals(instance.dueAt().plus(Duration.ofMinutes(5)), mapper.treeToValue(resumed.path("dueAt"), Instant.class));
        assertEquals(resumed, request("POST", path + "/resume", Map.of("expectedVersion", paused.path("version").asLong()), null, 200, false));
        assertEquals(6, intents().size()); assertEquals(3, count(IntentStatus.READY)); assertEquals(3, count(IntentStatus.CANCELLED));
        request("POST", path + "/pause", Map.of("expectedVersion", 1, "reason", "Stale pause must conflict"), null, 409, false);
        clock.advanceTo(mapper.treeToValue(resumed.path("overdueAt"), Instant.class).plusSeconds(1));
        var worker = new ApprovalSlaExecutionWorker(executions, dispatcher, ApprovalSlaExecutionWorker.WorkerMetrics.noop(),
            approvalClock, new ApprovalSlaExecutionWorker.Configuration(true, "isolated-manual-worker", 10,
                Duration.ofMinutes(1), Duration.ofSeconds(1), Duration.ofMinutes(1)), UUID::randomUUID);
        var report = worker.processTenant(TENANT);
        assertEquals(3, report.claimed()); assertEquals(1, report.succeeded()); assertEquals(2, report.dead());
        assertEquals(0, report.retryScheduled()); assertEquals(0, report.persistenceConflicts());
        assertEquals(1, count(IntentStatus.SUCCEEDED)); assertEquals(2, count(IntentStatus.DEAD));
        var overdue = intents().stream().filter(item -> item.actionType() == ActionType.OVERDUE && item.status() == IntentStatus.SUCCEEDED).findFirst().orElseThrow();
        assertEquals(1, overdue.attemptCount());
        assertEquals(overdue.actionSequence(), slaStore.findInstance(TENANT, instance.slaInstanceId()).orElseThrow().lastActionSequence());
        var reminder = intents().stream().filter(item -> item.actionType() == ActionType.REMINDER && item.status() == IntentStatus.DEAD).findFirst().orElseThrow();
        assertEquals("CONNECTOR_NOTIFICATION_UNSUPPORTED", reminder.lastErrorCode());
        var escalation = intents().stream().filter(item -> item.actionType() == ActionType.ESCALATION && item.status() == IntentStatus.DEAD).findFirst().orElseThrow();
        assertEquals("SLA_ACTION_NOT_CONFIGURED", escalation.lastErrorCode());
        assertEquals(0, worker.processTenant(TENANT).claimed());
        role(ApprovalEnterpriseRole.OPERATIONS);
        request("GET", "/management/sla/executions/summary", null, null, 200, false);
        request("GET", "/management/sla/executions?statuses=DEAD", null, null, 200, false);
        String replayPath = "/management/sla/executions/" + reminder.intentId() + "/replay";
        JsonNode replay = request("POST", replayPath, null, "sla-replay-once", 200, false);
        assertEquals(reminder.intentId().toString(), replay.path("intent").path("sourceIntentId").asText());
        JsonNode repeated = request("POST", replayPath, null, "sla-replay-once", 200, false);
        assertEquals(replay.path("intent").path("intentId"), repeated.path("intent").path("intentId"));
        assertTrue(repeated.path("replayedExistingRequest").asBoolean());
        assertEquals(7, intents().size());
        var replayReport = worker.processTenant(TENANT); assertEquals(1, replayReport.claimed()); assertEquals(1, replayReport.dead());
        assertEquals(1, count(IntentStatus.SUCCEEDED)); assertEquals(3, count(IntentStatus.DEAD));
        assertEquals(overdue.actionSequence(), slaStore.findInstance(TENANT, instance.slaInstanceId()).orElseThrow().lastActionSequence());
        assertEquals(0, worker.processTenant(TENANT).claimed());
        testPrincipal.set(ApprovalPrincipal.active(TENANT, "demo-employee", Set.of(), null));
        request("GET", "/management/sla/executions", null, null, 403, true);
        request("POST", replayPath, null, "forged-role-replay", 403, true);
        role(ApprovalEnterpriseRole.AUDITOR);
        request("POST", path + "/pause", Map.of("expectedVersion", 1, "reason", "Auditor cannot mutate"), null, 403, false);
        testPrincipal.set(null);
        request("GET", "/management/sla/executions", null, null, 401, false);
    }
    private List<ApprovalSlaExecutionStore.ExecutionIntent> intents() {
        return executions.findIntents(new ApprovalSlaExecutionStore.ExecutionIntentCriteria(TENANT,
            Set.of(), Set.of(), null, null, null, null, 200, 0)).items();
    }
    private long count(IntentStatus status) { return intents().stream().filter(item -> item.status() == status).count(); }
    private JsonNode request(String method, String path, Object body, String key, int expected, boolean forged) throws Exception {
        String id = UUID.randomUUID().toString();
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/approval" + path))
            .timeout(Duration.ofSeconds(15)).header("X-Tenant-Id", TENANT).header("Content-Type", "application/json")
            .header("X-Request-Id", id).header("X-Trace-Id", id)
            .header("X-Approval-Operation-Reason", "Isolated SLA lifecycle acceptance");
        if (!"GET".equals(method)) builder.header("Idempotency-Key", key == null ? id : key);
        if (forged) builder.header("X-Approval-Trusted-Permissions", "approval.management.sla.read,approval.management.operational-failure.replay");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        System.out.println("SLA_HTTP " + method + " " + path + " " + response.statusCode());
        assertEquals(expected, response.statusCode(), response.body()); return mapper.readTree(response.body());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class IdentityFixture {
        @Bean AtomicReference<ApprovalPrincipal> testPrincipal() { return new AtomicReference<>(); }
        @Bean FilterRegistrationBean<OncePerRequestFilter> testPrincipalFilter(AtomicReference<ApprovalPrincipal> selected) {
            var registration = new FilterRegistrationBean<OncePerRequestFilter>();
            registration.setFilter(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                    ApprovalPrincipal fixed = selected.get(); // JVM-only; no header/cookie/body can choose a role.
                    chain.doFilter(new HttpServletRequestWrapper(request) {
                        @Override public Principal getUserPrincipal() { return fixed; }
                    }, response);
                }
            });
            registration.setName("isolatedTestPrincipalFilter");
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            registration.addUrlPatterns("/api/approval/*");
            return registration;
        }
    }
}
