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
@TestExecutionListeners(listeners = ApprovalReleaseLifecycleHttpPostgresTest.DatabaseCleanup.class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Import(ApprovalReleaseLifecycleHttpPostgresTest.IdentityFixture.class)
@SpringBootTest(classes = ApprovalPlatformApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "approval.security.identity.mode=principal",
        "approval.demo.purchase-payment.enabled=true",
        "approval.demo.purchase-payment.sandbox.enabled=false",
        "approval.connector.generic.enabled=false",
        "flowable.async-executor-activate=false"
    })
class ApprovalReleaseLifecycleHttpPostgresTest {
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

    @Test
    void publishesDeploysActivatesAndRollsBackWithGovernedReplayAndIsolation() throws Exception {
        String key = "isolated-release-lifecycle";
        JsonNode firstPackage = publish(key, 1);
        JsonNode secondPackage = publish(key, 2);
        deploy(key, 1); deploy(key, 2);
        String activation = "/version-management/" + key + "/releases/1/activate";
        Map<String, Object> initial = Map.of("expectedRevision", 0);
        command(activation, initial, 400, null, "missing-reason");
        command(activation, initial, 400, "isolated activation", null);
        testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.PARTICIPANT));
        post(activation, initial, 403);
        testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.PROCESS_PUBLISHER));
        String idempotency = UUID.randomUUID().toString();
        JsonNode first = json(command(activation, initial, 200, "isolated activation", idempotency));
        assertEquals(first, json(command(activation, initial, 200, "isolated activation", idempotency)));
        assertEquals(firstPackage.path("packageHash"), first.path("effectiveRelease").path("releasePackageHash"));
        long firstRevision = requiredLong(first.path("effectiveRelease"), "revision");
        String secondActivation = "/version-management/" + key + "/releases/2/activate";
        post(secondActivation, Map.of("expectedRevision", 0), 409);
        JsonNode second = post(secondActivation, Map.of("expectedRevision", firstRevision), 200).path("effectiveRelease");
        assertEquals(secondPackage.path("packageHash"), second.path("releasePackageHash"));
        String rollback = "/version-management/" + key + "/releases/1/rollback";
        String rollbackKey = UUID.randomUUID().toString();
        Map<String, Object> rollbackBody = Map.of("expectedRevision", requiredLong(second, "revision"));
        JsonNode rolled = json(command(rollback, rollbackBody, 200, "isolated rollback", rollbackKey));
        assertEquals(rolled, json(command(rollback, rollbackBody, 200, "isolated rollback", rollbackKey)));
        assertEquals(1, rolled.path("effectiveRelease").path("effectiveReleaseVersion").asInt());
        assertEquals(firstPackage.path("packageHash"), rolled.path("effectiveRelease").path("releasePackageHash"));
        assertEquals(rolled.path("effectiveRelease"), json(get("/version-management/" + key + "/effective", 200)));
        JsonNode history = json(get("/version-management/" + key + "/effective/history?limit=10", 200));
        assertEquals(3, requiredLong(history, "total")); assertEquals(3, history.path("items").size());
        testPrincipal.set(principal("other-isolated-tenant", ApprovalEnterpriseRole.PROCESS_PUBLISHER));
        get("/version-management/" + key + "/effective", 404);
        get("/release-packages/" + key + "/1", 404);
    }

    @Test
    void exportsAndImportsImmutableDefinitionsWithHashReplayAndTenantBoundaries() throws Exception {
        String key = "isolated-transfer";
        JsonNode published = publish(key, 1);
        testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.DEPARTMENT_APPROVAL_ADMIN));
        get("/artifact-transfer/definition-exports/" + key + "/1", 400);
        JsonNode definition = json(governedGet("/artifact-transfer/definition-exports/" + key + "/1", 200));
        JsonNode release = json(governedGet("/artifact-transfer/release-exports/" + key + "/1", 200));
        assertTrue(definition.path("envelopeHash").asText().matches("[a-f0-9]{64}"), definition.toString());
        assertTrue(release.path("envelopeHash").asText().matches("[a-f0-9]{64}"), release.toString());
        Map<String, Object> body = Map.of("envelope", definition, "targetDefinitionKey", key,
            "targetDefinitionVersion", 2, "targetFormPackageVersion", 1, "targetName", "Imported isolated draft");
        String idem = UUID.randomUUID().toString();
        JsonNode imported = json(command("/artifact-transfer/imports", body, 201, "isolated import", idem));
        UUID.fromString(imported.path("draftId").asText());
        assertEquals(imported, json(command("/artifact-transfer/imports", body, 201, "isolated import", idem)));
        JsonNode draft = json(get("/process-design-drafts/" + imported.path("draftId").asText(), 200));
        assertEquals("DRAFT", draft.path("status").asText());
        assertEquals(2, draft.path("definition").path("version").asInt());
        var changed = new java.util.HashMap<>(body); changed.put("targetName", "changed idem");
        command("/artifact-transfer/imports", changed, 409, "isolated import", idem);
        var tampered = definition.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) tampered).put("envelopeHash", "0".repeat(64));
        changed = new java.util.HashMap<>(body); changed.put("envelope", tampered);
        post("/artifact-transfer/imports", changed, 400);
        assertEquals(published.path("packageHash"), json(get("/release-packages/" + key + "/1", 200)).path("packageHash"));
        get("/release-packages/" + key + "/2", 404);
        // Governed template preview/import uses the same real exported immutable envelope.
        com.fasterxml.jackson.databind.node.ObjectNode template;
        try (var input = getClass().getResourceAsStream("/templates/m6c/valid-template-package.json")) {
            template = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(java.util.Objects.requireNonNull(input));
        }
        var dependencies = template.putObject("dependencyManifest").putArray("dependencies");
        dependencies.addObject().put("kind", "FORM_FIELD").put("key", "amount").putNull("minimumVersion").put("required", true);
        template.putArray("componentDescriptors");
        template.putArray("artifacts").addObject().put("kind", "APPROVAL_DSL")
            .put("resourceName", "approval/isolated.dsl.json").put("contentHash", definition.path("envelopeHash").asText());
        var contract = mapper.treeToValue(template, io.github.akaryc1b.approval.application.ProcessTemplateContracts.TemplatePackage.class);
        template.put("contentHash", new io.github.akaryc1b.approval.application.ProcessTemplateCanonicalHasher().packageHash(contract));
        var previewBody = new java.util.HashMap<String, Object>();
        previewBody.put("templatePackage", template); previewBody.put("targetDefinitionKey", key);
        previewBody.put("targetDefinitionVersion", 3); previewBody.put("targetDraftName", "Governed isolated template");
        previewBody.put("bindings", java.util.List.of(Map.of("kind", "FORM_PACKAGE", "sourceKey", "source-form",
            "targetResourceKey", key, "targetVersion", 1)));
        testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.PROCESS_DESIGNER));
        JsonNode preview = post("/management/process-template-imports/previews", previewBody, 200);
        assertTrue(preview.path("plan").path("importable").asBoolean(), preview.toString());
        assertTrue(preview.path("governedPreviewHash").asText().matches("[a-f0-9]{64}"));
        testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.DEPARTMENT_APPROVAL_ADMIN));
        var create = new java.util.HashMap<>(previewBody); create.put("artifactEnvelope", definition);
        create.put("expectedGovernedPreviewHash", "0".repeat(64));
        post("/management/process-template-imports/drafts", create, 409);
        create.put("expectedGovernedPreviewHash", preview.path("governedPreviewHash").asText());
        String templateKey = UUID.randomUUID().toString();
        JsonNode created = json(command("/management/process-template-imports/drafts", create, 201, "isolated template import", templateKey));
        assertEquals(created, json(command("/management/process-template-imports/drafts", create, 201, "isolated template import", templateKey)));
        String templateDraftId = created.path("draft").path("draft").path("draftId").asText();
        UUID.fromString(templateDraftId);
        JsonNode governedDraft = json(get("/process-design-drafts/" + templateDraftId, 200));
        assertEquals("DRAFT", governedDraft.path("status").asText());
        assertEquals(3, governedDraft.path("definition").path("version").asInt());
        get("/release-packages/" + key + "/3", 404);
        testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.PROCESS_DESIGNER));
        var copyBody = Map.of("definitionKey", key, "sourceDefinitionVersion", 1, "targetDefinitionVersion", 4,
            "formPackageVersion", 1, "name", "Copied isolated definition");
        String copyKey = UUID.randomUUID().toString();
        JsonNode copied = json(command("/process-design-drafts/from-published", copyBody, 200, "isolated copy", copyKey));
        assertEquals(copied, json(command("/process-design-drafts/from-published", copyBody, 200, "isolated copy", copyKey)));
        assertEquals(4, copied.path("definition").path("version").asInt());
        assertEquals("DRAFT", copied.path("status").asText());
        testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.PARTICIPANT));
        governedGet("/artifact-transfer/definition-exports/" + key + "/1", 403);
        testPrincipal.set(principal("other-isolated-tenant", ApprovalEnterpriseRole.DEPARTMENT_APPROVAL_ADMIN));
        governedGet("/artifact-transfer/definition-exports/" + key + "/1", 404);
    }

    @Test
    void batchSimulationCoversLowHighBlockedAndBoundedRevisionLoopWithoutMutation() throws Exception {
        JsonNode draft = create("isolated-simulation", 1);
        String path = "/process-design-drafts/" + draft.path("draftId").asText();
        var approve = Map.of("managerApproval", "APPROVE", "financeReview", "APPROVE", "financeCountersign", "APPROVE", "paymentConfirmation", "APPROVE");
        var scenarios = java.util.List.of(scenario("low", 1200, approve, "COMPLETED", 100),
            scenario("high", 10000, approve, "COMPLETED", 100), scenario("blocked", 1200, Map.of(), "BLOCKED", 100),
            scenario("revision", 1200, Map.of("managerApproval", "REJECT"), "TRANSITION_LIMIT_REACHED", 10));
        JsonNode report = post(path + "/batch-simulate", Map.of("expectedRevision", requiredLong(draft, "revision"),
            "scenarios", scenarios, "formValueDisclosure", "FIELD_NAMES_ONLY"), 200);
        assertEquals(4, requiredLong(report, "scenarioCount"));
        assertTrue(report.path("scenarioResults").isArray()); assertEquals(4, report.path("scenarioResults").size());
        var byId = new java.util.HashMap<String, JsonNode>();
        for (JsonNode result : report.path("scenarioResults")) {
            byId.put(result.path("scenarioId").asText(), result);
            String expectedRun = switch (result.path("scenarioId").asText()) {
                case "blocked" -> "BLOCKED";
                case "revision" -> "TRANSITION_LIMIT_REACHED";
                default -> "PASSED";
            };
            assertEquals(expectedRun, result.path("runStatus").asText(), result.toString());
            assertTrue(result.path("expectationFailures").isArray() && result.path("expectationFailures").isEmpty());
            assertTrue(result.path("formValues").isObject() && result.path("formValues").isEmpty());
        }
        assertEquals(Set.of("low", "high", "blocked", "revision"), byId.keySet());
        assertTrue(byId.get("low").path("skippedNodeIds").toString().contains("financeReview"));
        assertTrue(byId.get("high").path("visitedNodeIds").toString().contains("financeReview"));
        assertEquals("BLOCKED", byId.get("blocked").path("simulationStatus").asText());
        assertEquals("TRANSITION_LIMIT_REACHED", byId.get("revision").path("simulationStatus").asText());
        assertEquals(draft, json(get(path, 200)));
        post(path + "/batch-simulate", Map.of("expectedRevision", 999, "scenarios", scenarios), 409);
        JsonNode archived = post(path + "/archive", Map.of("expectedRevision", requiredLong(draft, "revision")), 200);
        assertEquals("ARCHIVED", archived.path("status").asText());
    }

    private Map<String, Object> scenario(String id, int amount, Map<String, String> decisions, String status, int limit) {
        return Map.of("scenarioId", id, "name", id, "formValues", Map.of("amount", amount), "approvalDecisions", decisions,
            "identitySnapshots", Map.of(), "expectedTerminalStatus", status, "expectedVisitedNodeIds", java.util.List.of(),
            "expectedSkippedNodeIds", java.util.List.of(), "maxTransitions", limit);
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
        System.out.println("LIFECYCLE_HTTP " + request.method() + " " + request.uri().getPath() + " " + response.statusCode());
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
