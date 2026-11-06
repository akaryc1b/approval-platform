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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.Principal;
import java.time.Duration;
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
@TestExecutionListeners(listeners = ApprovalMigrationNonemptyHttpPostgresTest.DatabaseCleanup.class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Import(ApprovalMigrationNonemptyHttpPostgresTest.IdentityFixture.class)
@SpringBootTest(classes = ApprovalPlatformApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "approval.security.identity.mode=principal",
        "approval.demo.purchase-payment.enabled=true",
        "approval.demo.purchase-payment.sandbox.enabled=false",
        "approval.connector.generic.enabled=false",
        "flowable.async-executor-activate=false"
    })
class ApprovalMigrationNonemptyHttpPostgresTest {
    private static final String TENANT = "demo-purchase-payment";
    private static PostgreSQLContainer postgres;
    private static final String EXTERNAL_DB = System.getenv("APPROVAL_TEST_DB_URL");
    private final Set<String> preparedForms = new java.util.HashSet<>();
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

    @BeforeEach void auditor() { testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.PROCESS_PUBLISHER)); }
    @AfterEach void clearIdentity() { testPrincipal.set(null); }

    @Autowired javax.sql.DataSource dataSource;
    @Autowired io.github.akaryc1b.approval.application.port.ApprovalRuntimeBindingStore bindings;

    @Test
    void detectsWithoutMutationThenShowsRealProposedPlanAcrossReadOnlyAliases() throws Exception {
        String key = "purchase-payment";
        preparedForms.add(key); // Existing demo package is authoritative; never overwrite it.
        JsonNode before = json(get("/version-management/" + key + "/effective", 200));
        int sourceVersion = before.path("effectiveReleaseVersion").asInt();
        int targetVersion = before.path("definitionVersion").asInt() + 1;
        publish(key, targetVersion); deploy(key, targetVersion);
        String dryRun = "/version-management/" + key + "/releases/" + sourceVersion + "/migration-dry-run";
        Map<String, Object> body = Map.of("targetReleaseVersion", targetVersion, "limit", 100, "offset", 0);
        command(dryRun, body, 400, null, "no-reason");
        String idem = UUID.randomUUID().toString();
        JsonNode report = json(command(dryRun, body, 200, "isolated detect-only assessment", idem));
        assertEquals(report, json(command(dryRun, body, 200, "isolated detect-only assessment", idem)));
        assertTrue(report.path("detectOnly").asBoolean()); assertTrue(report.path("complete").asBoolean());
        assertTrue(requiredLong(report, "totalBindingCount") >= 1);
        assertTrue(requiredLong(report, "runningCount") >= 1);
        assertTrue(report.path("reportHash").asText().matches("[a-f0-9]{64}"));
        assertTrue(report.path("instances").isArray() && !report.path("instances").isEmpty());
        var ids = new java.util.ArrayList<UUID>();
        var originalBindings = new java.util.HashMap<UUID, Object>();
        for (JsonNode item : report.path("instances")) {
            UUID id = UUID.fromString(item.path("approvalInstanceId").asText());
            ids.add(id); originalBindings.put(id, bindings.find(TENANT, id).orElseThrow());
        }
        assertEquals(before, json(get("/version-management/" + key + "/effective", 200)));
        assertEquals("BLOCKED", report.path("status").asText());
        assertTrue(report.path("globalFindings").toString().contains("TARGET_RELEASE_NOT_ACTIVE"));
        JsonNode blockedReport = report;
        JsonNode activeBaseline = post("/version-management/" + key + "/releases/" + targetVersion + "/activate",
            Map.of("expectedRevision", requiredLong(before, "revision")), 200).path("effectiveRelease");
        String readyId = UUID.randomUUID().toString();
        report = json(command(dryRun, body, 200, "isolated ready detect-only assessment", readyId));
        assertEquals(report, json(command(dryRun, body, 200, "isolated ready detect-only assessment", readyId)));
        assertEquals("READY", report.path("status").asText(), report.toString());
        assertEquals(activeBaseline, json(get("/version-management/" + key + "/effective", 200)));
        // Plan creation is a Java/service-only fixture: no authorization or execution API is invented.
        var clock = java.time.Clock.systemUTC();
        var transactions = new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        var planStore = new io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalMigrationPlanStore(dataSource, mapper, transactions);
        var plans = new io.github.akaryc1b.approval.application.ApprovalMigrationPlanService(
            new io.github.akaryc1b.approval.persistence.jdbc.JdbcIdempotencyGuard(dataSource, mapper, transactions, clock),
            planStore, ignored -> { throw new AssertionError("authorization must never be invoked by this detect-only fixture"); },
            new io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalProcessReleaseStore(dataSource),
            new io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalReleasePackageStore(dataSource),
            new io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalReleaseDeploymentStore(dataSource),
            new io.github.akaryc1b.approval.persistence.jdbc.JdbcAuditEventSink(dataSource, mapper),
            new io.github.akaryc1b.approval.application.ApprovalReleasePackageHasher(), clock, UUID::randomUUID,
            Duration.ofMinutes(10), Duration.ofMinutes(5), Duration.ofMinutes(1));
        var blockedAssessment = mapper.treeToValue(blockedReport,
            io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.AssessmentResult.class);
        String rejectedId = UUID.randomUUID().toString();
        org.junit.jupiter.api.Assertions.assertThrows(
            io.github.akaryc1b.approval.application.ApprovalMigrationPlanService.StaleMigrationAssessmentException.class,
            () -> plans.createPlan(new io.github.akaryc1b.approval.application.ApprovalMigrationPlanService.CreatePlanCommand(
                new io.github.akaryc1b.approval.domain.context.RequestContext(TENANT, "isolated-auditor", rejectedId, rejectedId, null),
                blockedAssessment, ids, "blocked assessment must not create plan")));
        var assessment = mapper.treeToValue(report,
            io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.AssessmentResult.class);
        String request = UUID.randomUUID().toString();
        var command = new io.github.akaryc1b.approval.application.ApprovalMigrationPlanService.CreatePlanCommand(
            new io.github.akaryc1b.approval.domain.context.RequestContext(TENANT, "isolated-auditor", request, request, null),
            assessment, ids, "isolated proposed plan visibility");
        var plan = plans.createPlan(command).plan();
        assertEquals(plan, plans.createPlan(command).plan());
        assertEquals(io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanProtocol.PlanStatus.PROPOSED, plan.status());
        assertTrue(planStore.findAuthorizedPlan(TENANT, plan.planId(), plan.planHash(), clock.instant()).isEmpty());
        testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.AUDITOR));
        for (String root : new String[] { "/management/process-instance-operations", "/mobile/process-instance-operations" }) {
            assertEquals(1, requiredLong(json(get(root + "/summary", 200)), "totalPlans"));
            JsonNode page = json(get(root + "/plans?planStatus=PROPOSED&limit=1&offset=0", 200));
            assertEquals(1, requiredLong(page, "total")); assertEquals(1, page.path("items").size());
            JsonNode empty = json(get(root + "/plans?limit=1&offset=1", 200));
            assertTrue(empty.path("items").isArray() && empty.path("items").isEmpty());
            String path = root + "/plans/" + plan.planId();
            JsonNode detail = json(get(path, 200));
            assertEquals(plan.planId().toString(), detail.path("plan").path("planId").asText());
            assertEquals(plan.sourcePackageHash(), detail.path("sourcePackageHash").asText());
            JsonNode instances = json(get(path + "/instances?limit=1", 200));
            assertEquals(ids.size(), requiredLong(instances, "total")); assertEquals(1, instances.path("items").size());
            JsonNode diagnostics = json(get(path + "/diagnostics", 200));
            assertEquals(plan.planId().toString(), diagnostics.path("planId").asText());
            assertEquals(ids.size(), requiredLong(diagnostics, "selectedCount"));
            assertEquals(0, requiredLong(diagnostics, "provisionedAttemptCount"));
            JsonNode diagnosticPage = json(get(path + "/diagnostics/instances?page=1&pageSize=1", 200));
            assertEquals(ids.size(), requiredLong(diagnosticPage, "total"));
            assertEquals(1, diagnosticPage.path("items").size());
            get(root + "/plans?limit=0", 400);
            testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.PARTICIPANT)); get(path, 403);
            testPrincipal.set(principal("other-isolated-tenant", ApprovalEnterpriseRole.AUDITOR));
            get(path, 404); get(path + "/diagnostics", 404);
            assertEquals(0, requiredLong(json(get(root + "/summary", 200)), "totalPlans"));
            testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.AUDITOR));
        }
        testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.PROCESS_PUBLISHER));
        assertEquals(activeBaseline, json(get("/version-management/" + key + "/effective", 200)));
        for (UUID id : ids) assertEquals(originalBindings.get(id), bindings.find(TENANT, id).orElseThrow());
        assertEquals(plan, planStore.findPlan(TENANT, plan.planId()).orElseThrow());
    }

    private JsonNode create(String key, int version) throws Exception {
        if (!preparedForms.contains(key)) {
            testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.PROCESS_DESIGNER));
            JsonNode formDraft = post("/form-design-drafts", Map.of("source", "PURCHASE_PAYMENT_TEMPLATE",
                "formKey", key, "name", key, "formVersion", 1, "uiSchemaVersion", 1), 200);
            testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.PROCESS_PUBLISHER));
            post("/form-design-drafts/" + formDraft.path("draftId").asText() + "/publish",
                Map.of("expectedRevision", requiredLong(formDraft, "revision"), "packageVersion", 1), 200);
            preparedForms.add(key);
        }
        testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.PROCESS_DESIGNER));
        return post("/process-design-drafts", Map.of("source", "PURCHASE_PAYMENT_TEMPLATE", "definitionKey", key,
            "name", key, "definitionVersion", version, "formPackageVersion", 1), 200);
    }
    private JsonNode publish(String key, int version) throws Exception {
        JsonNode draft = create(key, version);
        testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.PROCESS_PUBLISHER));
        Map<String, Object> scenario = Map.of("decisions", Map.of(), "formValues", Map.of("amount", 1200), "maxTransitions", 100);
        JsonNode preflight = post("/preflight/publication", Map.of("draftId", draft.path("draftId").asText(),
            "expectedRevision", requiredLong(draft, "revision"), "definitionKey", key, "targetDefinitionVersion", version,
            "targetReleaseVersion", version, "deploymentTarget", "flowable-primary", "scenario", scenario), 200);
        assertTrue(preflight.path("publishable").asBoolean(), preflight.toString());
        JsonNode result = post("/process-design-drafts/" + draft.path("draftId").asText() + "/publish", Map.of(
            "expectedRevision", requiredLong(draft, "revision"), "definitionVersion", version, "releaseVersion", version,
            "deploymentTarget", "flowable-primary", "preflightHash", preflight.path("preflightHash").asText(),
            "acknowledgedWarningCodes", warnings(preflight), "preflightScenario", scenario), 200).path("releasePackage");
        assertTrue(result.path("packageHash").asText().matches("[a-f0-9]{64}"));
        return result;
    }
    private void deploy(String key, int version) throws Exception {
        JsonNode preflight = post("/preflight/deployment", Map.of("definitionKey", key, "releaseVersion", version,
            "deploymentTarget", "flowable-primary"), 200);
        Map<String, Object> body = Map.of("deploymentTarget", "flowable-primary", "preflightHash", preflight.path("preflightHash").asText(),
            "acknowledgedWarningCodes", warnings(preflight));
        String path = "/release-packages/" + key + "/" + version + "/deployment";
        var changed = new java.util.HashMap<>(body); changed.put("preflightHash", "0".repeat(64)); post(path, changed, 409);
        String idem = UUID.randomUUID().toString();
        JsonNode result = json(command(path, body, 200, "isolated deployment", idem));
        assertEquals(result, json(command(path, body, 200, "isolated deployment", idem)));
        assertEquals("DEPLOYED", result.path("deployment").path("status").asText());
        assertTrue(!result.path("deployment").path("engineDefinitionId").asText().isBlank());
        assertEquals(result.path("deployment"), json(get(path, 200)));
    }
    private java.util.List<String> warnings(JsonNode preflight) {
        assertTrue(preflight.path("warnings").isArray());
        java.util.List<String> codes = new java.util.ArrayList<>();
        preflight.path("warnings").forEach(warning -> codes.add(warning.path("code").asText()));
        return codes;
    }
    private JsonNode post(String path, Object body, int expected) throws Exception {
        return json(command(path, body, expected, "isolated lifecycle testing", UUID.randomUUID().toString()));
    }

    private static long requiredLong(JsonNode object, String field) {
        assertTrue(object.hasNonNull(field) && object.get(field).isIntegralNumber(), field + " must be numeric");
        return object.get(field).longValue();
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/api/approval" + path); }
    private JsonNode json(HttpResponse<String> response) throws Exception { return mapper.readTree(response.body()); }
    private HttpResponse<String> governedGet(String path, int expected) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15))
            .header("X-Approval-Operation-Reason", "isolated immutable export")
            .header("X-Request-Id", UUID.randomUUID().toString())
            .header("Idempotency-Key", UUID.randomUUID().toString()).GET().build(), expected);
    }
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
        System.out.println("MIGRATION_HTTP " + request.method() + " " + request.uri().getPath() + " " + response.statusCode());
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
