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
@TestExecutionListeners(listeners = ApprovalAuditPrincipalHttpPostgresTest.DatabaseCleanup.class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Import(ApprovalAuditPrincipalHttpPostgresTest.IdentityFixture.class)
@SpringBootTest(classes = ApprovalPlatformApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "approval.security.identity.mode=principal",
        "approval.demo.purchase-payment.enabled=true",
        "approval.demo.purchase-payment.sandbox.enabled=false",
        "approval.connector.generic.enabled=false",
        "flowable.async-executor-activate=false"
    })
class ApprovalAuditPrincipalHttpPostgresTest {
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

    @BeforeEach void auditor() { testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.AUDITOR)); }
    @AfterEach void clearIdentity() { testPrincipal.set(null); }

    @Test
    void auditorReadsFiltersPaginatesAndValidatesRealSeededAuditEvidence() throws Exception {
        String query = rangeQuery();
        JsonNode first = json(get("/management/audit?" + query + "&limit=1", 200));
        assertTrue(first.path("total").asLong() > 1);
        assertEquals(1, first.path("items").size());
        JsonNode event = first.path("items").get(0);
        assertEquals(TENANT, event.path("tenantId").asText());
        JsonNode second = json(get("/management/audit?" + query + "&limit=1&offset=1", 200));
        assertNotEquals(event.path("eventId"), second.path("items").get(0).path("eventId"));
        String request = event.path("requestId").asText();
        JsonNode filtered = json(get("/management/audit?" + query + "&requestId=" + encode(request), 200));
        assertTrue(filtered.path("items").size() > 0);
        for (JsonNode item : filtered.path("items")) assertEquals(request, item.path("requestId").asText());
        assertEquals(0, requiredLong(json(get("/management/audit?" + query + "&requestId=missing-" + UUID.randomUUID(), 200)), "total"));
        get("/management/audit?" + query + "&limit=0", 400);
        get("/management/audit?occurredFrom=2026-01-01T00:00:00Z&occurredTo=2026-03-01T00:00:00Z", 400);
    }

    @Test
    void governedSyntheticExportsAndIntegrityRequireReasonAndIdempotencyAndReplay() throws Exception {
        Map<String, Object> range = rangeBody();
        Map<String, Object> export = new java.util.HashMap<>(range);
        export.put("format", "JSON"); export.put("maxRecords", 1000);
        command("/management/audit/export", export, 400, null, UUID.randomUUID().toString());
        command("/management/audit/export", export, 400, "isolated synthetic export acceptance", null);
        testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.PARTICIPANT));
        command("/management/audit/export", export, 403, "isolated synthetic export acceptance", UUID.randomUUID().toString());
        testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.AUDITOR));
        String key = UUID.randomUUID().toString();
        HttpResponse<String> original = command("/management/audit/export", export, 200, "isolated synthetic export acceptance", key);
        assertTrue(original.headers().firstValue("content-type").orElseThrow().contains("application/json"));
        assertTrue(original.headers().firstValue("content-disposition").orElseThrow().contains("attachment"));
        JsonNode exported = json(original);
        assertTrue(exported.path("records").size() > 0);
        for (JsonNode record : exported.path("records")) assertEquals(TENANT, record.path("tenantId").asText());
        assertEquals(exported, json(command("/management/audit/export", export, 200, "isolated synthetic export acceptance", key)));
        Map<String, Object> csv = new java.util.HashMap<>(export); csv.put("format", "CSV");
        command("/management/audit/export", csv, 409, "isolated synthetic export acceptance", key);
        HttpResponse<String> csvResult = command("/management/audit/export", csv, 200, "isolated synthetic export acceptance", UUID.randomUUID().toString());
        assertTrue(csvResult.headers().firstValue("content-type").orElseThrow().contains("text/csv"));
        assertTrue(csvResult.body().startsWith("tenantSequence,eventId,tenantId,"));
        String verifyKey = UUID.randomUUID().toString();
        JsonNode verified = json(command("/management/audit/integrity/verify", range, 200, "isolated integrity verification", verifyKey));
        assertTrue(verified.path("valid").asBoolean());
        assertTrue(verified.path("checkedCount").asLong() > 0);
        assertEquals(verified, json(command("/management/audit/integrity/verify", range, 200, "isolated integrity verification", verifyKey)));
    }

    @Test
    void auditorReadsEmptyMigrationOperationsWithoutExecutingMigration() throws Exception {
        for (String root : new String[] { "/management/process-instance-operations", "/mobile/process-instance-operations" }) {
            assertEquals(0, requiredLong(json(get(root + "/summary", 200)), "totalPlans"));
            JsonNode plans = json(get(root + "/plans?limit=1&offset=0", 200));
            assertEquals(0, requiredLong(plans, "total"));
            assertTrue(plans.hasNonNull("items") && plans.get("items").isArray());
            assertTrue(plans.get("items").isEmpty());
            String missing = UUID.randomUUID().toString();
            get(root + "/plans/" + missing, 404);
            get(root + "/plans/" + missing + "/diagnostics", 404);
        }
    }

    @Test
    void trustedPrincipalCannotBeReplacedByRequestHeadersAndWrongRolesRemainDenied() throws Exception {
        String audit = "/management/audit?" + rangeQuery();
        testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.PARTICIPANT));
        get(audit, 403);
        get("/management/process-instance-operations/summary", 403);
        HttpRequest forged = HttpRequest.newBuilder(uri(audit)).timeout(Duration.ofSeconds(15))
            .header("X-Tenant-Id", TENANT).header("X-Operator-Id", "demo-admin")
            .header("X-Approval-Trusted-Permissions", "approval.management.audit.read,approval.management.audit.export").GET().build();
        assertEquals(403, http.send(forged, HttpResponse.BodyHandlers.ofString()).statusCode());
        testPrincipal.set(principal("other-isolated-tenant", ApprovalEnterpriseRole.AUDITOR));
        // With no tenant header, the server wrapper owns the other tenant and sees no seeded tenant events.
        assertEquals(0, requiredLong(json(get(audit, 200)), "total"));
        assertEquals(404, http.send(HttpRequest.newBuilder(uri(audit)).timeout(Duration.ofSeconds(15)).header("X-Tenant-Id", TENANT).GET().build(),
            HttpResponse.BodyHandlers.ofString()).statusCode());
        testPrincipal.set(null);
        get(audit, 401);
    }

    private static long requiredLong(JsonNode object, String field) {
        assertTrue(object.hasNonNull(field) && object.get(field).isIntegralNumber(), field + " must be numeric");
        return object.get(field).longValue();
    }

    private String rangeQuery() {
        return "occurredFrom=" + encode(Instant.now().minusSeconds(3600).toString())
            + "&occurredTo=" + encode(Instant.now().plusSeconds(60).toString());
    }
    private Map<String, Object> rangeBody() {
        return Map.of("occurredFrom", Instant.now().minusSeconds(3600).toString(),
            "occurredTo", Instant.now().plusSeconds(60).toString());
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
