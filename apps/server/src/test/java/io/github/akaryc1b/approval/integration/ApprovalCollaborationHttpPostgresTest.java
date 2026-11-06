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
@TestExecutionListeners(listeners = ApprovalCollaborationHttpPostgresTest.DatabaseCleanup.class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Import(ApprovalCollaborationHttpPostgresTest.IdentityFixture.class)
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
class ApprovalCollaborationHttpPostgresTest {
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


    private static final String MANAGER = "demo-manager";
    private static final String A = "demo-finance-approver-a";
    private static final String B = "demo-finance-approver-b";

    @Test
    void fourModesEnforceThresholdsAndOwnerCompletionWithIdempotentDecisions() throws Exception {
        for (String mode : java.util.List.of("ALL", "ANY", "VOTE", "WEIGHTED")) {
            JsonNode started = start();
            String task = started.path("activeTasks").get(0).path("taskId").asText();
            actor(MANAGER);
            var body = new java.util.HashMap<String, Object>();
            body.put("connectorKey", "demo-directory"); body.put("mode", mode);
            body.put("participants", java.util.List.of(candidate(A, mode.equals("WEIGHTED") ? 2 : 1), candidate(B, 1)));
            body.put("reason", "isolated collaboration threshold");
            if (mode.equals("VOTE")) body.put("approvalThreshold", 2);
            if (mode.equals("WEIGHTED")) body.put("approvalWeightThreshold", 2);
            String path = "/tasks/" + task + "/collaboration";
            String key = UUID.randomUUID().toString();
            JsonNode policy = json(command(path, body, 200, null, key));
            assertEquals(policy, json(command(path, body, 200, null, key)));
            assertEquals("ACTIVE", policy.path("status").asText());
            assertEquals(2, policy.path("participants").size());
            command("/tasks/" + task + "/approve", Map.of("comment", "not yet satisfied"), 409, null, UUID.randomUUID().toString());
            actor("demo-admin"); get(path, 404);
            command(path + "/participants", Map.of("connectorKey", "demo-directory",
                "participants", java.util.List.of(candidate("demo-finance-reviewer", 1)), "reason", "forbidden add sign"),
                403, null, UUID.randomUUID().toString());
            testPrincipal.set(principal("other-collaboration-tenant", ApprovalEnterpriseRole.PARTICIPANT));
            get(path, 404);
            actor(A);
            JsonNode pending = json(get("/collaboration/tasks/pending?limit=100", 200));
            assertTrue(java.util.stream.StreamSupport.stream(pending.spliterator(), false)
                .anyMatch(item -> item.path("taskId").asText().equals(task)));
            String participant = participant(policy, A);
            String decisionPath = "/collaboration/participants/" + participant + "/decide";
            String decisionKey = UUID.randomUUID().toString();
            JsonNode decided = json(command(decisionPath, Map.of("decision", "APPROVED", "comment", "approve threshold"),
                200, null, decisionKey));
            assertEquals(decided, json(command(decisionPath, Map.of("decision", "APPROVED", "comment", "approve threshold"),
                200, null, decisionKey)));
            command(decisionPath, Map.of("decision", "REJECTED", "comment", "changed decision"),
                409, null, decisionKey);
            boolean needsBoth = mode.equals("ALL") || mode.equals("VOTE");
            assertEquals(needsBoth ? "ACTIVE" : "SATISFIED", decided.path("status").asText());
            if (needsBoth) {
                actor(B);
                decided = json(command("/collaboration/participants/" + participant(policy, B) + "/decide",
                    Map.of("decision", "APPROVED", "comment", "second approval"), 200, null, UUID.randomUUID().toString()));
            }
            assertEquals("SATISFIED", decided.path("status").asText());
            assertEquals(needsBoth ? 2 : 1, requiredLong(decided.path("progress"), "approvedCount"));
            actor(MANAGER);
            assertEquals("SATISFIED", json(get(path, 200)).path("status").asText());
            command("/tasks/" + task + "/approve", Map.of("comment", "threshold satisfied"), 200, null, UUID.randomUUID().toString());
        }
    }

    @Test
    void weightedMinorityCannotSatisfyThresholdByItself() throws Exception {
        JsonNode started = start();
        String task = started.path("activeTasks").get(0).path("taskId").asText();
        actor(MANAGER);
        JsonNode policy = json(command("/tasks/" + task + "/collaboration",
            Map.of("connectorKey", "demo-directory", "mode", "WEIGHTED", "approvalWeightThreshold", 2,
                "participants", java.util.List.of(candidate(A, 2), candidate(B, 1)),
                "reason", "weighted minority boundary"), 200, null, UUID.randomUUID().toString()));
        actor(B);
        JsonNode minority = json(command("/collaboration/participants/" + participant(policy, B) + "/decide",
            Map.of("decision", "APPROVED", "comment", "minority weight approval"), 200, null, UUID.randomUUID().toString()));
        assertEquals("ACTIVE", minority.path("status").asText());
        assertEquals(1, requiredLong(minority.path("progress"), "approvedWeight"));
        actor(MANAGER);
        command("/tasks/" + task + "/approve", Map.of("comment", "insufficient weight"), 409, null, UUID.randomUUID().toString());
        actor(A);
        JsonNode satisfied = json(command("/collaboration/participants/" + participant(policy, A) + "/decide",
            Map.of("decision", "APPROVED", "comment", "majority weight approval"), 200, null, UUID.randomUUID().toString()));
        assertEquals("SATISFIED", satisfied.path("status").asText());
        assertEquals(3, requiredLong(satisfied.path("progress"), "approvedWeight"));
        actor(MANAGER);
        command("/tasks/" + task + "/approve", Map.of("comment", "sufficient weighted approval"), 200, null, UUID.randomUUID().toString());
    }

    @Test
    void addSignRemovalAndRejectedThresholdRemainGoverned() throws Exception {
        JsonNode started = start();
        String task = started.path("activeTasks").get(0).path("taskId").asText();
        actor(MANAGER);
        String path = "/tasks/" + task + "/collaboration";
        JsonNode policy = json(command(path, Map.of("connectorKey", "demo-directory", "mode", "ALL",
            "participants", java.util.List.of(candidate(A, 1)), "reason", "initial single signer"), 200, null, UUID.randomUUID().toString()));
        String key = UUID.randomUUID().toString();
        var addition = Map.of("connectorKey", "demo-directory", "participants", java.util.List.of(candidate(B, 1)),
            "reason", "add second reviewer");
        JsonNode added = json(command(path + "/participants", addition, 200, null, key));
        assertEquals(2, added.path("participants").size());
        assertEquals(added, json(command(path + "/participants", addition, 200, null, key)));
        String remove = "/collaboration/participants/" + participant(added, B) + "/remove";
        String removeKey = UUID.randomUUID().toString();
        JsonNode removed = json(command(remove, Map.of("reason", "remove second reviewer"), 200, null, removeKey));
        assertEquals(removed, json(command(remove, Map.of("reason", "remove second reviewer"), 200, null, removeKey)));
        assertEquals("REMOVED", person(removed, B).path("status").asText());
        actor(B);
        command("/collaboration/participants/" + participant(added, B) + "/decide",
            Map.of("decision", "APPROVED", "comment", "removed signer"), 409, null, UUID.randomUUID().toString());
        actor(A);
        JsonNode rejected = json(command("/collaboration/participants/" + participant(policy, A) + "/decide",
            Map.of("decision", "REJECTED", "comment", "reject required approval"), 200, null, UUID.randomUUID().toString()));
        assertEquals("REJECTED", rejected.path("status").asText());
        actor(MANAGER);
        command("/tasks/" + task + "/approve", Map.of("comment", "rejected threshold"), 409, null, UUID.randomUUID().toString());
        actor("demo-employee");
        command("/instances/" + started.path("instanceId").asText() + "/withdraw",
            Map.of("comment", "isolated rejected workflow cleanup"), 200, null, UUID.randomUUID().toString());
    }

    @Test
    void delegationPinsResponsibilityAndHandoverTransfersExistingTasks() throws Exception {
        actor(MANAGER);
        Instant now = Instant.now();
        var ruleBody = Map.of("connectorKey", "demo-directory", "delegateIdentity", identity("demo-finance-reviewer"),
            "scope", "DEFINITION", "definitionKey", "purchase-payment", "validFrom", now.minusSeconds(60).toString(),
            "validUntil", now.plusSeconds(3600).toString(), "reason", "isolated manager delegation");
        var self = new java.util.HashMap<String, Object>(ruleBody); self.put("delegateIdentity", identity(MANAGER));
        command("/delegations", self, 400, null, UUID.randomUUID().toString());
        var expired = new java.util.HashMap<String, Object>(ruleBody); expired.put("validUntil", now.minusSeconds(1).toString());
        command("/delegations", expired, 400, null, UUID.randomUUID().toString());
        String key = UUID.randomUUID().toString();
        JsonNode rule = json(command("/delegations", ruleBody, 200, null, key));
        assertEquals(rule, json(command("/delegations", ruleBody, 200, null, key)));
        command("/delegations", ruleBody, 409, null, UUID.randomUUID().toString());
        JsonNode started = start();
        JsonNode task = started.path("activeTasks").get(0);
        assertEquals("demo-finance-reviewer", task.path("assigneeId").asText());
        actor("demo-finance-reviewer");
        JsonNode assignment = json(get("/tasks/" + task.path("taskId").asText() + "/delegation", 200));
        assertEquals(MANAGER, assignment.path("principalAssigneeId").asText());
        assertEquals("demo-finance-reviewer", assignment.path("delegateAssigneeId").asText());
        assertEquals(rule.path("ruleId").asText(), assignment.path("delegationRuleId").asText());
        actor(A);
        command("/delegations/" + rule.path("ruleId").asText() + "/revoke",
            Map.of("reason", "wrong owner cannot revoke"), 409, null, UUID.randomUUID().toString());
        actor(MANAGER);
        String revoke = "/delegations/" + rule.path("ruleId").asText() + "/revoke";
        String revokeKey = UUID.randomUUID().toString();
        JsonNode revoked = json(command(revoke, Map.of("reason", "finish isolated delegation"), 200, null, revokeKey));
        assertEquals(revoked, json(command(revoke, Map.of("reason", "finish isolated delegation"), 200, null, revokeKey)));
        assertEquals("REVOKED", revoked.path("status").asText());
        actor("demo-finance-reviewer");
        command("/tasks/" + task.path("taskId").asText() + "/approve", Map.of("comment", "delegated responsibility"),
            200, null, UUID.randomUUID().toString());
        JsonNode completed = json(get("/tasks/" + task.path("taskId").asText() + "/delegation", 200));
        assertEquals("COMPLETED", completed.path("status").asText());
        assertEquals("demo-finance-reviewer", completed.path("completedBy").asText());

        JsonNode fresh = start();
        String freshTask = fresh.path("activeTasks").get(0).path("taskId").asText();
        assertEquals(MANAGER, fresh.path("activeTasks").get(0).path("assigneeId").asText());
        var handover = Map.of("connectorKey", "demo-directory", "principalIdentity", identity(MANAGER),
            "successorIdentity", identity("demo-finance-reviewer"), "reason", "isolated responsibility handover");
        actor(MANAGER);
        command("/handovers", handover, 403, "isolated responsibility handover", UUID.randomUUID().toString());
        testPrincipal.set(principal(TENANT, ApprovalEnterpriseRole.DEPARTMENT_APPROVAL_ADMIN));
        String handoverKey = UUID.randomUUID().toString();
        JsonNode transferred = json(command("/handovers", handover, 200, "isolated responsibility handover", handoverKey));
        assertEquals(transferred, json(command("/handovers", handover, 200, "isolated responsibility handover", handoverKey)));
        assertTrue(requiredLong(transferred, "transferredTaskCount") >= 1);
        assertTrue(java.util.stream.StreamSupport.stream(transferred.path("transferredTaskIds").spliterator(), false)
            .anyMatch(item -> item.asText().equals(freshTask)));
        assertEquals(MANAGER, transferred.path("handover").path("principalId").asText());
        assertEquals("demo-finance-reviewer", transferred.path("handover").path("successorId").asText());
        String handoverId = transferred.path("handover").path("handoverId").asText();
        UUID.fromString(handoverId);
        command("/handovers/" + handoverId + "/revoke", Map.of("reason", "finish isolated handover"),
            200, "finish isolated handover", UUID.randomUUID().toString());
        actor("demo-finance-reviewer");
        command("/tasks/" + freshTask + "/approve", Map.of("comment", "handover successor"),
            200, null, UUID.randomUUID().toString());
    }

    @Test
    void manualTransferKeepsTaskIdentityAndRestrictsOriginalAssignee() throws Exception {
        JsonNode started = start();
        String task = started.path("activeTasks").get(0).path("taskId").asText();
        String path = "/tasks/" + task + "/transfer";
        var body = Map.of("targetUserId", "demo-finance-reviewer", "comment", "isolated manual transfer");
        actor("demo-employee");
        command(path, body, 409, null, UUID.randomUUID().toString());
        actor(MANAGER);
        command(path, Map.of("targetUserId", "unknown-person", "comment", "not in immutable snapshot"),
            409, null, UUID.randomUUID().toString());
        String key = UUID.randomUUID().toString();
        JsonNode result = json(command(path, body, 200, null, key));
        assertEquals(result, json(command(path, body, 200, null, key)));
        assertEquals(task, result.path("taskId").asText());
        assertEquals(MANAGER, result.path("previousAssigneeId").asText());
        assertEquals("demo-finance-reviewer", result.path("targetAssigneeId").asText());
        command(path, Map.of("targetUserId", A, "comment", "changed transfer"), 409, null, key);
        command("/tasks/" + task + "/approve", Map.of("comment", "former owner cannot approve"),
            409, null, UUID.randomUUID().toString());
        actor("demo-finance-reviewer");
        get("/tasks/pending/" + task, 200);
        JsonNode approved = json(command("/tasks/" + task + "/approve", Map.of("comment", "new owner approves"),
            200, null, UUID.randomUUID().toString()));
        assertEquals(task, approved.path("completedTaskId").asText());
    }

    @Test
    void originalAssigneeRetrievesAndReapprovesWithNonemptyProcessedPagination() throws Exception {
        String business = "retrieval-" + UUID.randomUUID();
        JsonNode started = start(10000, business);
        String instance = started.path("instanceId").asText();
        String task = started.path("activeTasks").get(0).path("taskId").asText();
        actor(MANAGER);
        JsonNode approved = json(command("/tasks/" + task + "/approve", Map.of("comment", "initial manager approval"),
            200, null, UUID.randomUUID().toString()));
        assertEquals(1, approved.path("activeTasks").size());
        JsonNode downstream = approved.path("activeTasks").get(0);
        assertEquals("financeReview", downstream.path("taskDefinitionKey").asText());
        String processedPath = "/tasks/processed?keyword=" + encode(business) + "&limit=1";
        JsonNode before = json(get(processedPath, 200));
        assertEquals(1, requiredLong(before, "total")); assertEquals(1, before.path("items").size());
        assertEquals(task, before.path("items").get(0).path("taskId").asText());
        assertTrue(before.path("items").get(0).path("retrievable").asBoolean());
        actor("demo-employee");
        assertEquals(0, requiredLong(json(get(processedPath, 200)), "total"));
        command("/tasks/" + task + "/retrieve", Map.of("comment", "initiator is not original approver"),
            409, null, UUID.randomUUID().toString());
        testPrincipal.set(principal("other-collaboration-tenant", ApprovalEnterpriseRole.PARTICIPANT));
        assertEquals(0, requiredLong(json(get(processedPath, 200)), "total"));
        actor(MANAGER);
        String key = UUID.randomUUID().toString();
        JsonNode retrieved = json(command("/tasks/" + task + "/retrieve", Map.of("comment", "reconsider manager approval"),
            200, null, key));
        assertEquals(retrieved, json(command("/tasks/" + task + "/retrieve", Map.of("comment", "reconsider manager approval"),
            200, null, key)));
        command("/tasks/" + task + "/retrieve", Map.of("comment", "changed retrieval payload"), 409, null, key);
        assertEquals(task, retrieved.path("completedTaskId").asText());
        assertEquals(instance, retrieved.path("instanceId").asText());
        assertEquals(1, retrieved.path("activeTasks").size());
        JsonNode restored = retrieved.path("activeTasks").get(0);
        assertEquals(MANAGER, restored.path("assigneeId").asText());
        assertEquals("managerApproval", restored.path("taskDefinitionKey").asText());
        assertTrue(!task.equals(restored.path("taskId").asText()));
        JsonNode tasks = json(get("/tasks?instanceId=" + instance, 200));
        assertEquals("CANCELED", java.util.stream.StreamSupport.stream(tasks.spliterator(), false)
            .filter(item -> item.path("taskId").asText().equals(downstream.path("taskId").asText()))
            .findFirst().orElseThrow().path("status").asText());
        JsonNode reapproved = json(command("/tasks/" + restored.path("taskId").asText() + "/approve",
            Map.of("comment", "reapprove retrieved manager task"), 200, null, UUID.randomUUID().toString()));
        assertEquals(1, reapproved.path("activeTasks").size());
        assertEquals("financeReview", reapproved.path("activeTasks").get(0).path("taskDefinitionKey").asText());
        JsonNode first = json(get(processedPath, 200));
        JsonNode second = json(get(processedPath + "&offset=1", 200));
        assertEquals(2, requiredLong(first, "total")); assertEquals(2, requiredLong(second, "total"));
        assertEquals(1, first.path("items").size()); assertEquals(1, second.path("items").size());
        assertTrue(first.path("hasMore").asBoolean()); assertEquals(false, second.path("hasMore").asBoolean());
        assertEquals(Set.of(task, restored.path("taskId").asText()),
            Set.of(first.path("items").get(0).path("taskId").asText(), second.path("items").get(0).path("taskId").asText()));
        actor("demo-employee");
        command("/instances/" + instance + "/withdraw", Map.of("comment", "isolated retrieval cleanup"),
            200, null, UUID.randomUUID().toString());
    }

    private void actor(String actor) {
        testPrincipal.set(ApprovalPrincipal.active(TENANT, actor, Set.of(), Set.of(new ApprovalResponsibilityAssignment(
            ApprovalEnterpriseRole.PARTICIPANT, ApprovalResponsibilitySourceType.ROLE,
            "isolated-participant", ApprovalResourceScope.tenant())), null));
    }
    private Map<String, Object> identity(String user) {
        return Map.of("source", "demo-directory", "objectType", "user", "value", user);
    }
    private Map<String, Object> candidate(String user, int weight) {
        var result = new java.util.HashMap<String, Object>(identity(user)); result.put("weight", weight); return result;
    }
    private JsonNode person(JsonNode policy, String user) {
        return java.util.stream.StreamSupport.stream(policy.path("participants").spliterator(), false)
            .filter(item -> item.path("participantUserId").asText().equals(user)).findFirst().orElseThrow();
    }
    private String participant(JsonNode policy, String user) { return person(policy, user).path("participantId").asText(); }
    private JsonNode start() throws Exception { return start(100, "collaboration-" + UUID.randomUUID()); }
    private JsonNode start(int amount, String business) throws Exception {
        actor("demo-employee");
        String key = UUID.randomUUID().toString();
        String boundary = "isolated-" + key;
        String multipart = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"evidence.txt\"\r\n"
            + "Content-Type: text/plain\r\n\r\nisolated purchase evidence\r\n--" + boundary + "--\r\n";
        JsonNode upload = json(send(HttpRequest.newBuilder(uri("/attachments")).timeout(Duration.ofSeconds(15))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary).header("X-Request-Id", key)
            .header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString(multipart)).build(), 200));
        JsonNode result = json(command("/instances/purchase-payment", Map.of("businessKey", business,
            "amount", amount, "supplier", "synthetic supplier", "purchaseOrderReference", "SYNTHETIC",
            "attachmentIds", java.util.List.of(upload.path("attachmentId").asText()),
            "assigneeRules", Map.of("connectorKey", "demo-directory", "initiatorUserId", identity("demo-employee"),
                "financeReviewerRoleCode", "FINANCE_REVIEWER", "financeApproverPositionCode", "FINANCE_APPROVER",
                "maximumFinanceApprovers", 10)), 200, null, key + "-start"));
        UUID.fromString(result.path("instanceId").asText());
        assertEquals(1, result.path("activeTasks").size());
        return result;
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
        System.out.println("COLLABORATION_HTTP " + request.method() + " " + request.uri().getPath() + " " + response.statusCode());
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
