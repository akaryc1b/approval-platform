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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real HTTP/PG with a JVM-owned test authentication adapter, NOT browser SSO acceptance. */
@EnabledIf("databaseAvailable")
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = ApprovalGovernanceReadHttpPostgresTest.DatabaseCleanup.class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Import(ApprovalGovernanceReadHttpPostgresTest.IdentityFixture.class)
@SpringBootTest(classes = ApprovalPlatformApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "approval.security.identity.mode=principal",
        "approval.demo.purchase-payment.enabled=true",
        "approval.demo.purchase-payment.sandbox.enabled=false",
        "approval.connector.generic.enabled=false",
        "flowable.async-executor-activate=false"
    })
class ApprovalGovernanceReadHttpPostgresTest {
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
    void disabledProviderReadViewsAndPlansStayNonExecutable() throws Exception {
        String root = "/management/ai-governance";
        String range = "?from=" + encode(Instant.now().minusSeconds(3600).toString())
            + "&to=" + encode(Instant.now().minusSeconds(1).toString());
        for (String endpoint : new String[] {"/snapshot", "/usage", "/control-health", "/history" + range, "/incident-readiness" + range}) {
            HttpResponse<String> response = get(root + endpoint, 200);
            assertTrue(response.headers().firstValue("cache-control").orElseThrow().contains("no-store"));
            JsonNode body = json(response);
            assertTrue(body.isObject());
            if (endpoint.equals("/snapshot")) {
                assertEquals("NOT_CONFIGURED", body.path("runtimeState").asText());
                for (String flag : new String[] {"providerMutationAvailable", "canaryMutationAvailable", "rollbackMutationAvailable",
                    "commandExecutionAuthorized", "automaticRetryAuthorized", "rawSecretExposed"}) {
                    assertTrue(body.hasNonNull(flag) && body.get(flag).isBoolean());
                    assertEquals(false, body.get(flag).asBoolean());
                }
                assertTrue(body.path("evidenceHash").asText().matches("[a-f0-9]{64}"));
            }
            if (endpoint.startsWith("/history")) assertEquals(0, requiredLong(body, "providerInvocationCount"));
        }
        for (String operation : new String[] {"CANARY", "ROLLOUT", "ROLLBACK"}) {
            JsonNode plan = json(get(root + "/change-plan?operation=" + operation, 200));
            assertEquals(operation, plan.path("operation").asText());
            assertEquals("NON_EXECUTABLE_REVIEW_ONLY", plan.path("mode").asText());
            for (String flag : new String[] {"providerInvocationAuthorized", "secretResolutionAuthorized", "trafficMutationAuthorized",
                "configurationMutationAuthorized", "deploymentAuthorized", "applyAuthorized", "commandExecutionAuthorized"}) {
                assertTrue(plan.hasNonNull(flag) && plan.get(flag).isBoolean());
                assertEquals(false, plan.get(flag).asBoolean());
            }
        }
        assertEquals("NOT_CONFIGURED", json(get(root + "/snapshot", 200)).path("runtimeState").asText());
    }

    @Test
    void readOnlyBoundaryRejectsInvalidInputWrongRoleAndIdentity() throws Exception {
        String root = "/management/ai-governance";
        assertEquals("AI_GOVERNANCE_TENANT_INVALID", json(send(HttpRequest.newBuilder(uri(root + "/snapshot"))
            .timeout(Duration.ofSeconds(15)).GET().build(), 400)).path("code").asText());
        get(root + "/change-plan?operation=UNKNOWN", 400);
        get(root + "/history?from=invalid&to=invalid", 400);
        get(root + "/snapshot?unexpected=true", 400);
        send(HttpRequest.newBuilder(uri(root + "/snapshot")).timeout(Duration.ofSeconds(15))
            .header("X-Tenant-Id", TENANT).header("X-HTTP-Method-Override", "POST").GET().build(), 400);
        command(root + "/snapshot", Map.of(), 405, "not an executable command", UUID.randomUUID().toString());
        testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.PARTICIPANT));
        get(root + "/snapshot", 403);
        testPrincipal.set(principal("isolated-other-tenant", ApprovalEnterpriseRole.AUDITOR));
        send(HttpRequest.newBuilder(uri(root + "/snapshot")).timeout(Duration.ofSeconds(15))
            .header("X-Tenant-Id", TENANT).GET().build(), 404);
        get(root + "/usage", 200);
        testPrincipal.set(null);
        get(root + "/snapshot", 401);
    }

    private static long requiredLong(JsonNode object, String field) {
        assertTrue(object.hasNonNull(field) && object.get(field).isIntegralNumber(), field + " must be numeric");
        return object.get(field).longValue();
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/api/approval" + path); }
    private JsonNode json(HttpResponse<String> response) throws Exception { return mapper.readTree(response.body()); }
    private HttpResponse<String> get(String path, int expected) throws Exception {
        ApprovalPrincipal selected = testPrincipal.get();
        return send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15))
            .header("X-Tenant-Id", selected == null ? TENANT : selected.tenantId()).GET().build(), expected);
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
        System.out.println("GOVERNANCE_HTTP " + request.method() + " " + request.uri().getPath() + " " + response.statusCode());
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
