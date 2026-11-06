package io.github.akaryc1b.approval.integration;

import io.github.akaryc1b.approval.application.ApprovalFormDesignService;
import io.github.akaryc1b.approval.application.ApprovalDesignService;
import io.github.akaryc1b.approval.application.ApprovalDesignCommands;
import io.github.akaryc1b.approval.application.ApprovalReleasePreflightService;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseLifecycleService;
import io.github.akaryc1b.approval.application.ApprovalReleaseDeploymentService;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseActivationService;
import io.github.akaryc1b.approval.application.ApprovalEffectiveReleaseService;
import io.github.akaryc1b.approval.application.ApprovalAttachmentService;
import io.github.akaryc1b.approval.application.port.ApprovalEffectiveReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormSubmissionStore;
import io.github.akaryc1b.approval.application.port.FormSubmissionWorkflowStarter;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalEffectiveRelease;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import io.github.akaryc1b.approval.engine.ApprovalEngine;
import io.github.akaryc1b.approval.demo.PurchasePaymentDemoScenario;
import org.flowable.engine.RuntimeService;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.slf4j.MDC;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.ApprovalPlatformApplication;
import io.github.akaryc1b.approval.security.ApprovalPrincipal;
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
import java.nio.charset.StandardCharsets;
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
@TestExecutionListeners(listeners = ApprovalVersionedStartPrincipalHttpPostgresTest.DatabaseCleanup.class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Import(ApprovalVersionedStartPrincipalHttpPostgresTest.IdentityFixture.class)
@SpringBootTest(classes = ApprovalPlatformApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "approval.security.identity.mode=principal",
        "approval.demo.purchase-payment.enabled=true",
        "approval.demo.purchase-payment.sandbox.enabled=false",
        "approval.connector.generic.enabled=false",
        "flowable.async-executor-activate=false"
    })
class ApprovalVersionedStartPrincipalHttpPostgresTest {
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

    @Autowired ApprovalFormDesignService forms;
    @Autowired ApprovalDesignService designs;
    @Autowired ApprovalReleasePreflightService preflight;
    @Autowired ApprovalProcessReleaseLifecycleService releases;
    @Autowired ApprovalReleaseDeploymentService deployment;
    @Autowired ApprovalProcessReleaseActivationService activation;
    @Autowired ApprovalEffectiveReleaseStore effective;
    @Autowired ApprovalProjectionStore projections;
    @Autowired ApprovalFormSubmissionStore submissions;
    @Autowired ApprovalAttachmentService attachments;
    @Autowired PurchasePaymentDemoScenario scenario;
    @Autowired RuntimeService flowable;
    @MockitoSpyBean(name="formSubmissionWorkflowStarter") FormSubmissionWorkflowStarter starter;
    @MockitoSpyBean(name="approvalEngine") ApprovalEngine engine;

    @BeforeEach void employee() { testPrincipal.set(ApprovalPrincipal.active(TENANT, "demo-employee", Set.of(), null)); }
    @AfterEach void resetFixture() { reset(starter, engine); testPrincipal.set(null); }

    @Test void exactReleaseBindingSurvivesActivationRaceReplayConcurrencyAndEngineRollback() throws Exception {
        Map<String,Object> firstBody = body("versioned-original");
        JsonNode first = post(1, firstBody, "first", 200);
        UUID firstId = UUID.fromString(first.path("instanceId").asText());
        ApprovalEffectiveRelease v1 = effective.find(TENANT, "purchase-payment").orElseThrow();
        publishAndActivate(2);
        ApprovalEffectiveRelease v2 = effective.find(TENANT, "purchase-payment").orElseThrow();
        assertEquals(2, v2.formSchemaVersion());
        assertEquals(2, get("/forms/purchase-payment/versions/2/runtime", 200).path("definition").path("version").asInt());
        post(1, body("old-version-new-business"), "old-version", 400);
        assertEquals(first.path("instanceId"), post(1, firstBody, "first", 200).path("instanceId"));
        assertBound(firstId, v1);
        assertTaskRuntime(firstId, v1);
        testPrincipal.set(ApprovalPrincipal.active("missing-tenant", "demo-employee", Set.of(), null));
        post(2, body("missing-tenant-business"), "missing-tenant", 400);
        employee();
        assertTrue(projections.findDefinition(TENANT, "purchase-payment", v2.definitionVersion()).isEmpty());
        Map<String,Object> leftBody = body("parallel-left"), rightBody = body("parallel-right");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var left = pool.submit(() -> post(2, leftBody, "parallel-left", 200));
            var right = pool.submit(() -> post(2, rightBody, "parallel-right", 200));
            assertBound(UUID.fromString(left.get(25, TimeUnit.SECONDS).path("instanceId").asText()), v2);
            assertBound(UUID.fromString(right.get(25, TimeUnit.SECONDS).path("instanceId").asText()), v2);
        }
        var materialized = projections.findDefinition(TENANT, "purchase-payment", v2.definitionVersion()).orElseThrow();
        var immutable = designs.findRelease(TENANT, "purchase-payment", v2.effectiveReleaseVersion()).orElseThrow();
        assertEquals(immutable.publishedBy(), materialized.publishedBy());
        assertEquals(immutable.publishedAt(), materialized.publishedAt());
        CountDownLatch captured = new CountDownLatch(1), proceed = new CountDownLatch(1);
        doAnswer(invocation -> { Object binding = invocation.callRealMethod(); captured.countDown();
            assertTrue(proceed.await(20, TimeUnit.SECONDS)); return binding;
        }).when(starter).prepareRelease(any(), any(), anyInt());
        Map<String,Object> raceBody = body("activation-race");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = pool.submit(() -> post(2, raceBody, "race", 200));
            assertTrue(captured.await(10, TimeUnit.SECONDS));
            try { publishAndActivate(3); } finally { proceed.countDown(); }
            UUID raced = UUID.fromString(pending.get(25, TimeUnit.SECONDS).path("instanceId").asText());
            assertBound(raced, v2);
            assertTaskRuntime(raced, v2);
        }
        reset(starter);
        assertEquals(3, effective.find(TENANT, "purchase-payment").orElseThrow().formSchemaVersion());
        assertEquals(first.path("instanceId"), post(1, firstBody, "first", 200).path("instanceId"));
        ApprovalEffectiveRelease v3 = effective.find(TENANT, "purchase-payment").orElseThrow();
        assertTrue(projections.findDefinition(TENANT, "purchase-payment", v3.definitionVersion()).isEmpty());
        AtomicBoolean fail = new AtomicBoolean(true);
        doAnswer(invocation -> { Object result = invocation.callRealMethod();
            if (fail.getAndSet(false)) throw new RuntimeException("injected after real Flowable start"); return result;
        }).when(engine).startExact(any());
        Map<String,Object> retryBody = body("rollback-retry");
        post(3, retryBody, "retry-key", 500);
        assertTrue(projections.findDefinition(TENANT, "purchase-payment", v3.definitionVersion()).isEmpty());
        assertTrue(projections.findByBusinessKey(TENANT, "rollback-retry").isEmpty());
        assertTrue(submissions.findByBusinessKey(TENANT, "rollback-retry").isEmpty());
        assertEquals(0, flowable.createProcessInstanceQuery().processInstanceBusinessKey("rollback-retry").count());
        JsonNode retried = post(3, retryBody, "retry-key", 200);
        assertEquals(retried.path("instanceId"), post(3, retryBody, "retry-key", 200).path("instanceId"));
        assertEquals(1, flowable.createProcessInstanceQuery().processInstanceBusinessKey("rollback-retry").count());
        assertBound(UUID.fromString(retried.path("instanceId").asText()), v3);
        var changed = new java.util.LinkedHashMap<>(retryBody); changed.put("values", Map.of("amount", 99,
            "supplier", "different", "purchaseOrderReference", "ORDER", "attachments", ((Map<?,?>)retryBody.get("values")).get("attachments")));
        post(3, changed, "retry-key", 409);
        var customCreate = context("custom-create");
        var custom = evidence(customCreate, () -> forms.createFromPurchasePaymentTemplate(
            new ApprovalFormDesignService.CreateCommand(customCreate, "custom-purchase-copy", "custom", 1, 1)));
        var customPublish = context("custom-publish");
        evidence(customPublish, () -> forms.publish(new ApprovalFormDesignService.PublishCommand(
            customPublish, custom.draftId(), custom.revision(), 1)));
        JsonNode rejected = send("POST", "/forms/custom-purchase-copy/versions/1/submissions",
            body("custom-unsupported"), "custom-unsupported", 422);
        assertEquals("FORM_SUBMISSION_UNSUPPORTED", rejected.path("code").asText());
        assertTrue(submissions.findByBusinessKey(TENANT, "custom-unsupported").isEmpty());
    }

    private void assertTaskRuntime(UUID id, ApprovalEffectiveRelease bound) throws Exception {
        var task = projections.findTasks(TENANT, id).stream().filter(value -> value.status() == ApprovalProjectionStore.TaskStatus.PENDING)
            .findFirst().orElseThrow();
        testPrincipal.set(ApprovalPrincipal.active(TENANT, task.assigneeId(), Set.of(), null));
        try {
            JsonNode runtime = get("/tasks/" + task.taskId() + "/form-runtime", 200);
            assertEquals(bound.formSchemaVersion(), runtime.path("definition").path("version").asInt());
            assertEquals(bound.uiSchemaHash(), runtime.path("uiSchemaHash").asText());
        } finally { employee(); }
    }

    private void assertBound(UUID id, ApprovalEffectiveRelease bound) {
        var instance = projections.findInstance(TENANT, id).orElseThrow();
        var snapshot = submissions.findByInstance(TENANT, id).orElseThrow();
        assertEquals(bound.effectiveReleaseVersion(), instance.releaseVersion());
        assertEquals(bound.releasePackageHash(), instance.releasePackageHash());
        assertEquals(bound.formSchemaVersion(), snapshot.formVersion());
        assertEquals(bound.formSchemaHash(), snapshot.schemaHash());
        assertEquals(bound.uiSchemaVersion(), snapshot.uiSchemaVersion());
        assertEquals(bound.uiSchemaHash(), snapshot.uiSchemaHash());
        assertEquals(bound.engineDefinitionId(), flowable.createProcessInstanceQuery()
            .processInstanceId(instance.engineInstanceId()).singleResult().getProcessDefinitionId());
    }
    private void publishAndActivate(int version) {
        var create = context("copy-form-" + version);
        var form = evidence(create, () -> forms.createFromPublished(new ApprovalFormDesignService.CopyPublishedCommand(
            create, "purchase-payment", 1, 1, version, version, "versioned form " + version)));
        var publishForm = context("publish-form-" + version);
        evidence(publishForm, () -> forms.publish(new ApprovalFormDesignService.PublishCommand(
            publishForm, form.draftId(), form.revision(), version)));
        var createProcess = context("create-process-" + version);
        int definitionVersion = PurchasePaymentTemplate.PROCESS_VERSION + version - 1;
        var draft = evidence(createProcess, () -> designs.createFromPurchasePaymentTemplate(new ApprovalDesignCommands.Create(
            createProcess, "purchase-payment", "versioned process " + version, definitionVersion, version)));
        var publication = preflight.preflightPublication(new ApprovalReleasePreflightService.PublicationRequest(
            TENANT, draft.draftId(), draft.revision(), "purchase-payment", definitionVersion, version, "default", null));
        assertTrue(publication.publishable(), publication.toString());
        var publish = context("publish-process-" + version);
        evidence(publish, () -> releases.publish(new ApprovalProcessReleaseLifecycleService.PublishCommand(
            new ApprovalDesignCommands.Publish(publish, draft.draftId(), draft.revision(), definitionVersion, version,
                "default", publication.preflightHash(), publication.warningCodes(), null), "Isolated exact binding publication")));
        var report = preflight.preflightDeployment(new ApprovalReleasePreflightService.DeploymentRequest(TENANT,
            "purchase-payment", version, "default"));
        assertTrue(report.deployable(), report.toString());
        var deploy = context("deploy-" + version);
        evidence(deploy, () -> deployment.deploy(new ApprovalReleaseDeploymentService.DeployCommand(deploy,
            "purchase-payment", version, "default", report.preflightHash(), report.warningCodes())));
        var activate = context("activate-" + version);
        Long expected = effective.find(TENANT, "purchase-payment").map(ApprovalEffectiveRelease::revision).orElse(null);
        evidence(activate, () -> activation.activate(new ApprovalEffectiveReleaseService.ActivationCommand(activate,
            "purchase-payment", version, expected, "Isolated exact binding activation")));
    }
    private RequestContext context(String key) { return new RequestContext(TENANT, "demo-admin", key, key, key); }
    private <T> T evidence(RequestContext context, Supplier<T> action) {
        Map<String,String> prior = MDC.getCopyOfContextMap();
        try { MDC.put("operatorId", context.operatorId()); MDC.put("requestId", context.requestId());
            MDC.put("traceId", context.traceId()); return action.get();
        } finally { MDC.clear(); if (prior != null) MDC.setContextMap(prior); }
    }
    private Map<String,Object> body(String business) {
        var ctx = new RequestContext(TENANT, "demo-employee", business + "-upload", business + "-upload", null);
        var attachment = evidence(ctx, () -> attachments.upload(new ApprovalAttachmentService.UploadCommand(ctx,
            "receipt.txt", "text/plain", business.getBytes(StandardCharsets.UTF_8))));
        return Map.of("businessKey", business, "values", Map.of("amount", 1200, "supplier", "Supplier",
            "purchaseOrderReference", "ORDER", "attachments", List.of(attachment.attachmentId().toString())),
            "startParameters", mapper.convertValue(scenario.assigneeRules(), Map.class));
    }
    private JsonNode post(int version, Map<String,Object> body, String key, int expected) throws Exception {
        return send("POST", "/forms/purchase-payment/versions/" + version + "/submissions", body, key, expected);
    }
    private JsonNode get(String path, int expected) throws Exception { return send("GET", path, null, null, expected); }
    private JsonNode send(String method, String path, Object body, String key, int expected) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/approval" + path))
            .timeout(Duration.ofSeconds(30)).header("X-Tenant-Id", testPrincipal.get().tenantId())
            .header("X-Request-Id", UUID.randomUUID().toString()).header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        System.out.println("VERSIONED_HTTP " + method + " " + path + " " + response.statusCode());
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
