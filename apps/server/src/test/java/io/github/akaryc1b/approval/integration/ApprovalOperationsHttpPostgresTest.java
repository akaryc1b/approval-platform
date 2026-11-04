package io.github.akaryc1b.approval.integration;

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
import org.junit.jupiter.api.BeforeEach;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real HTTP/PG with a JVM-owned test authentication adapter, NOT browser SSO acceptance. */
@EnabledIf("databaseAvailable")
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = ApprovalOperationsHttpPostgresTest.DatabaseCleanup.class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Import(ApprovalOperationsHttpPostgresTest.IdentityFixture.class)
@SpringBootTest(classes = ApprovalPlatformApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "approval.security.identity.mode=principal",
        "approval.demo.purchase-payment.enabled=true",
        "approval.demo.purchase-payment.sandbox.enabled=false",
        "approval.connector.generic.enabled=false",
        "flowable.async-executor-activate=false",
        "approval.notifications.delivery-delay-ms=3600000",
        "approval.connector.operations-diagnostics.enabled=true"
    })
class ApprovalOperationsHttpPostgresTest {
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

    @BeforeEach void auditor() { testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.OPERATIONS)); }
    @AfterEach void clearIdentity() { testPrincipal.set(null); }

    @Autowired io.github.akaryc1b.approval.application.port.ApprovalNotificationStore notifications;
    @Autowired io.github.akaryc1b.approval.connector.operations.BoundedConnectorOperationsDiagnosticsStore diagnostics;
    private static final String FAILURES = "/management/operational-failures";
    private static final String CATEGORY = "/NOTIFICATION_DELIVERY/";

    @Test
    void detectsConsistencyWithIdempotentReadbackAndRoleTenantBoundaries() throws Exception {
        String path = "/management/consistency/checks";
        command(path, Map.of(), 400, null, "missing-reason");
        String key = UUID.randomUUID().toString();
        JsonNode check = json(command(path, Map.of(), 200, "isolated consistency inspection", key));
        assertEquals(check, json(command(path, Map.of(), 200, "isolated consistency inspection", key)));
        assertEquals("COMPLETED", check.path("status").asText());
        UUID.fromString(check.path("checkId").asText());
        JsonNode page = json(get(path + "?limit=1", 200));
        assertEquals(1, requiredLong(page, "total")); assertEquals(1, page.path("items").size());
        assertEquals(check, page.path("items").get(0));
        JsonNode findings = json(get(path + "/" + check.path("checkId").asText() + "/findings?limit=1", 200));
        assertEquals(requiredLong(check, "findingCount"), requiredLong(findings, "total"));
        assertTrue(findings.path("items").isArray());
        get(path + "?limit=0", 400);
        testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.PARTICIPANT));
        get(path, 403); command(path, Map.of(), 403, "isolated forbidden check", UUID.randomUUID().toString());
        testPrincipal.set(principal("other-ops-tenant", ApprovalEnterpriseRole.OPERATIONS));
        assertEquals(0, requiredLong(json(get(path, 200)), "total"));
        get(path + "/" + check.path("checkId").asText() + "/findings", 404);
    }

    @Test
    void diagnosticObservationPagesAreTenantScopedAndDoNotClaimDispatchOrDurability() throws Exception {
        String tenant = "isolated-diagnostic-observations";
        testPrincipal.set(principal(tenant, ApprovalEnterpriseRole.OPERATIONS));
        String path = "/management/connector-operations/diagnostics";
        assertEquals(0, json(get(path, 200)).path("items").size());
        for (int i = 0; i < 2; i++) {
            String hash = io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash.sha256Utf8("synthetic-" + i);
            diagnostics.record(new io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts.InvocationEvidence(
                io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts.tenantHash(tenant),
                hash, null, null, null, null, null, null, null, null, null, null,
                io.github.akaryc1b.approval.connector.contract.ConnectorOperation.ORGANIZATION_READ, "USER_BY_ID",
                io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts.GateResult.NOT_EVALUATED,
                false, 0,
                io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts.CompletionClassification.REJECTED_BEFORE_DISPATCH,
                io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts.DurationBucket.LT_10_MS,
                io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts.StableFailureCode.ROUTE_MISSING,
                hash), Instant.now());
        }
        var response = get(path + "?pageSize=1", 200);
        assertTrue(response.headers().firstValue("cache-control").orElseThrow().contains("no-store"));
        JsonNode first = json(response); assertEquals(1, first.path("items").size());
        assertTrue(first.path("processLocal").asBoolean());
        for (String field : new String[] { "persistent", "auditSystem", "recoveryMechanism", "productionExecutionAuthorized", "approvalStateMutationAuthorized" }) {
            assertTrue(first.hasNonNull(field)); assertEquals(false, first.path(field).asBoolean());
        }
        assertTrue(first.hasNonNull("nextPageToken") && !first.path("nextPageToken").asText().isBlank());
        JsonNode second = json(get(path + "?pageSize=1&pageToken=" + encode(first.path("nextPageToken").asText()), 200));
        assertEquals(1, second.path("items").size()); assertNotEquals(first.path("items").get(0), second.path("items").get(0));
        for (JsonNode entry : java.util.List.of(first.path("items").get(0), second.path("items").get(0))) {
            assertTrue(entry.hasNonNull("dispatchAttempted")); assertEquals(false, entry.path("dispatchAttempted").asBoolean());
            assertEquals(0, requiredLong(entry, "dispatchCount"));
            assertEquals("ROUTE_MISSING", entry.path("stableFailureCode").asText());
            assertTrue(entry.path("evidenceHash").asText().matches("[a-f0-9]{64}"));
        }
        get(path + "/summary", 200); get(path + "?pageSize=0", 400);
        testPrincipal.set(principal("other-ops-tenant", ApprovalEnterpriseRole.OPERATIONS));
        assertEquals(0, json(get(path, 200)).path("items").size());
        testPrincipal.set(principal(tenant, ApprovalEnterpriseRole.PARTICIPANT)); get(path, 403);
    }

    @Test
    void operationalDeadLetterReplayResetsOnlyLocalQueueAndPreservesAttempts() throws Exception {
        UUID first = deadNotification();
        String path = FAILURES + CATEGORY + first + "/replay";
        get(FAILURES + "?limit=0", 400); get(FAILURES + "?category=UNKNOWN", 400);
        testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.PARTICIPANT));
        command(path, Map.of(), 403, "isolated queue recovery", "forbidden");
        send(HttpRequest.newBuilder(uri(FAILURES)).timeout(Duration.ofSeconds(15))
            .header("X-Approval-Trusted-Permissions", "approval.management.operational-failure.read").GET().build(), 403);
        testPrincipal.set(principal("other-ops-tenant", ApprovalEnterpriseRole.OPERATIONS));
        command(path, Map.of(), 404, "isolated queue recovery", "wrong-tenant");
        testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.OPERATIONS));
        command(path, Map.of(), 400, null, "missing-reason"); command(path, Map.of(), 400, "isolated queue recovery", null);
        assertEquals("DEAD_LETTER", stored(first).status().name());
        String key = UUID.randomUUID().toString();
        JsonNode replay = json(command(path, Map.of(), 200, "isolated queue recovery", key));
        assertEquals("REPLAYED", replay.path("outcome").asText()); assertEquals(first.toString(), replay.path("sourceId").asText());
        assertEquals(replay, json(command(path, Map.of(), 200, "isolated queue recovery", key)));
        assertPendingWithRetainedAttempt(first);
        assertEquals(1, json(get(FAILURES + CATEGORY + first + "/attempts", 200)).size());
        command(path, Map.of(), 404, "isolated repeated recovery", UUID.randomUUID().toString());
        UUID second = deadNotification(); UUID third = deadNotification(); UUID absent = UUID.randomUUID();
        var body = Map.of("items", java.util.List.of(item(second), item(third), item(absent)));
        String batchKey = UUID.randomUUID().toString();
        JsonNode batch = json(command(FAILURES + "/replay-batch", body, 200, "isolated batch recovery", batchKey));
        assertEquals(2, requiredLong(batch, "replayed")); assertEquals(1, requiredLong(batch, "rejected"));
        assertEquals(3, batch.path("items").size());
        for (int i = 0; i < 3; i++) {
            assertEquals(java.util.List.of(second, third, absent).get(i).toString(), batch.path("items").get(i).path("sourceId").asText());
            assertEquals(i < 2 ? "REPLAYED" : "REJECTED", batch.path("items").get(i).path("outcome").asText());
        }
        assertEquals(batch, json(command(FAILURES + "/replay-batch", body, 200, "isolated batch recovery", batchKey)));
        command(FAILURES + "/replay-batch", Map.of("items", java.util.List.of(item(second))), 409, "isolated changed batch", batchKey);
        assertPendingWithRetainedAttempt(second); assertPendingWithRetainedAttempt(third);
        testPrincipal.set(null); get(FAILURES, 401);
    }

    private Map<String, Object> item(UUID id) { return Map.of("category", "NOTIFICATION_DELIVERY", "sourceId", id); }
    private void assertPendingWithRetainedAttempt(UUID id) {
        assertEquals("PENDING", stored(id).status().name()); assertEquals(0, stored(id).attemptCount());
        assertEquals(1, notifications.findAttempts(TENANT, "isolated-recipient", id).size());
    }
    private io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationIntent stored(UUID id) {
        return notifications.findHistory(new io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationHistoryCriteria(
            TENANT, "isolated-recipient", false, 100, 0)).items().stream().filter(value -> value.intentId().equals(id)).findFirst().orElseThrow();
    }
    private UUID deadNotification() {
        UUID id = UUID.randomUUID(); Instant at = Instant.parse("2020-01-01T00:00:00Z");
        var intent = new io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationIntent(
            id, TENANT, io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationEventType.TASK_ASSIGNED,
            io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationChannel.IN_APP,
            "isolated-recipient", "approval-platform", null, null, "APPROVAL_INSTANCE", "synthetic-instance",
            "task-assigned", 1, "synthetic recovery", "no provider invocation", Map.of(), "synthetic-" + id, false,
            io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationStatus.PENDING,
            0, 5, at, null, null, null, null, null, null, at, at, 1);
        assertEquals(1, notifications.enqueue(java.util.List.of(intent)));
        var claimed = notifications.claimDue(at, 100, "isolated-fixture", at.plusSeconds(30)).stream()
            .filter(value -> value.intentId().equals(id)).findFirst().orElseThrow();
        assertEquals("DEAD_LETTER", notifications.markFailed(TENANT, id, claimed.version(), UUID.randomUUID(), at,
            at.plusSeconds(1), false, at.plusSeconds(1), "SYNTHETIC_FAILURE", "isolated test failure").status().name());
        return id;
    }

    private static long requiredLong(JsonNode object, String field) {
        assertTrue(object.hasNonNull(field) && object.get(field).isIntegralNumber(), field + " must be numeric");
        return object.get(field).longValue();
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/api/approval" + path); }
    private JsonNode json(HttpResponse<String> response) throws Exception { return mapper.readTree(response.body()); }
    private HttpResponse<String> get(String path, int expected) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15)).GET().build(), expected);
    }
    private HttpResponse<String> command(String path, Object body, int expected, String reason, String key) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
            .header("X-Request-Id", UUID.randomUUID().toString());
        if (reason != null) builder.header("X-Approval-Operation-Reason", reason);
        if (key != null) builder.header("Idempotency-Key", key);
        return send(builder.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), expected);
    }
    private HttpResponse<String> send(HttpRequest request, int expected) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("OPERATIONS_HTTP " + request.method() + " " + request.uri().getPath() + " " + response.statusCode());
        assertEquals(expected, response.statusCode(), request.method() + " " + request.uri() + " " + response.body());
        return response;
    }
    private static ApprovalPrincipal principal(String tenant, ApprovalEnterpriseRole role) {
        var responsibility = new ApprovalResponsibilityAssignment(role, ApprovalResponsibilitySourceType.ROLE,
            "isolated-http-test-role", ApprovalResourceScope.tenant());
        return ApprovalPrincipal.active(tenant, "isolated-auditor", Set.of(), Set.of(responsibility), null);
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
