package io.github.akaryc1b.approval.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.ApprovalPlatformApplication;
import io.github.akaryc1b.approval.demo.PurchasePaymentDemoScenario;
import io.github.akaryc1b.approval.demo.PurchasePaymentDemoSeedState;
import io.github.akaryc1b.approval.demo.PurchasePaymentDemoSeeder;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("local")
@SpringBootTest(
    classes = ApprovalPlatformApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "approval.demo.purchase-payment.enabled=true",
        "approval.connector.generic.enabled=false",
        "flowable.async-executor-activate=false",
        "management.endpoint.health.show-details=always"
    }
)
class PurchasePaymentDemoSeedIntegrationTest {

    private static final String MANAGER_APPROVE_REQUEST =
        "demo-manager-approve-request-v1";
    private static final String FINANCE_REVIEW_APPROVE_REQUEST =
        "demo-finance-review-approve-request-v1";
    private static final String FINANCE_APPROVER_A_REQUEST =
        "demo-finance-approver-a-request-v1";
    private static final String FINANCE_APPROVER_B_REQUEST =
        "demo-finance-approver-b-request-v1";
    private static final String PAYMENT_CONFIRMATION_REQUEST =
        "demo-payment-confirmation-request-v1";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_demo_seed_test")
        .withUsername("approval")
        .withPassword("approval");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    PurchasePaymentDemoSeedState state;

    @Autowired
    PurchasePaymentDemoSeeder seeder;

    @Autowired
    PurchasePaymentDemoScenario scenario;

    @Autowired
    @Qualifier("approvalPersistenceObjectMapper")
    ObjectMapper objectMapper;

    @Autowired
    DataSource dataSource;

    @Test
    void completesGovernedPurchaseToPaymentChainAndRecordsOneFinalOutboxEvent()
        throws Exception {
        PurchasePaymentDemoSeedState.SeedEvidence evidence = state.requireEvidence();

        assertEquals(scenario.tenantId(), evidence.tenantId());
        assertEquals(scenario.request().businessKey(), evidence.businessKey());
        assertEquals(1, evidence.taskIds().size());
        assertEquals(2, evidence.attachments().size());
        assertEquals(
            "3680e624-4ca6-54f9-8e1c-7736f5fc936d",
            evidence.attachments().getFirst().attachmentId().toString()
        );
        assertTrue(evidence.attachments().stream()
            .allMatch(PurchasePaymentDemoSeedState.AttachmentEvidence::bound));

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> health = client.send(
            HttpRequest.newBuilder(uri("/actuator/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, health.statusCode(), health.body());
        assertTrue(health.body().contains("\"status\":\"UP\""));

        String initiatorId = scenario.assigneeRules().initiatorUserId().value();
        String managerId = scenario.requireUser(initiatorId).managerId();
        String financeReviewerId = requireSingleUserWithRole(
            scenario.assigneeRules().financeReviewerRoleCode()
        );
        Set<String> financeApproverIds = userIdsWithPosition(
            scenario.assigneeRules().financeApproverPositionCode()
        );
        assertEquals("demo-employee", initiatorId);
        assertEquals(2, financeApproverIds.size());

        JsonNode initialInstance = getJson(
            client,
            "/api/approval/instances/" + evidence.instanceId(),
            scenario.administratorId(),
            "demo-inspect-initial-instance-v1"
        );
        assertEquals(
            evidence.instanceId().toString(),
            initialInstance.path("instance").path("instanceId").asText()
        );
        assertEquals(
            scenario.request().businessKey(),
            initialInstance.path("instance").path("businessKey").asText()
        );
        assertEquals("RUNNING", initialInstance.path("instance").path("status").asText());

        JsonNode managerTask = requireSinglePendingTask(
            client,
            managerId,
            PurchasePaymentTemplate.MANAGER_APPROVAL_TASK_KEY,
            "demo-manager-pending-v1"
        );
        assertEquals(
            evidence.taskIds().getFirst().toString(),
            managerTask.path("taskId").asText()
        );

        PurchasePaymentDemoSeedState.SeedEvidence replay = seeder.apply();
        assertEquals(evidence.instanceId(), replay.instanceId());
        assertEquals(evidence.taskIds(), replay.taskIds());
        assertEquals(evidence.attachments(), replay.attachments());
        assertEquals(evidence.seededAt(), replay.seededAt());

        UUID managerTaskId = UUID.fromString(managerTask.path("taskId").asText());
        JsonNode managerApproved = approve(
            client,
            managerTaskId,
            managerId,
            MANAGER_APPROVE_REQUEST,
            "demo-manager-approve-v1",
            "Manager approved deterministic high-value request."
        );
        assertTransition(
            managerApproved,
            "RUNNING",
            Set.of(PurchasePaymentTemplate.FINANCE_REVIEW_TASK_KEY),
            Set.of(financeReviewerId)
        );

        JsonNode managerReplay = approve(
            client,
            managerTaskId,
            managerId,
            MANAGER_APPROVE_REQUEST,
            "demo-manager-approve-v1",
            "Manager approved deterministic high-value request."
        );
        assertEquals(managerApproved, managerReplay);

        JsonNode financeReviewTask = requireSinglePendingTask(
            client,
            financeReviewerId,
            PurchasePaymentTemplate.FINANCE_REVIEW_TASK_KEY,
            "demo-finance-review-pending-v1"
        );
        assertEquals(
            textValues(managerApproved.path("activeTasks"), "taskId"),
            Set.of(financeReviewTask.path("taskId").asText())
        );

        JsonNode financeReviewed = approve(
            client,
            UUID.fromString(financeReviewTask.path("taskId").asText()),
            financeReviewerId,
            FINANCE_REVIEW_APPROVE_REQUEST,
            "demo-finance-review-approve-v1",
            "Finance review approved."
        );
        assertTransition(
            financeReviewed,
            "RUNNING",
            Set.of(PurchasePaymentTemplate.FINANCE_COUNTERSIGN_TASK_KEY),
            financeApproverIds
        );

        String financeApproverA = "demo-finance-approver-a";
        String financeApproverB = "demo-finance-approver-b";
        JsonNode financeTaskA = requireSinglePendingTask(
            client,
            financeApproverA,
            PurchasePaymentTemplate.FINANCE_COUNTERSIGN_TASK_KEY,
            "demo-finance-approver-a-pending-v1"
        );
        JsonNode financeTaskB = requireSinglePendingTask(
            client,
            financeApproverB,
            PurchasePaymentTemplate.FINANCE_COUNTERSIGN_TASK_KEY,
            "demo-finance-approver-b-pending-v1"
        );
        String financeTaskAId = financeTaskA.path("taskId").asText();
        String financeTaskBId = financeTaskB.path("taskId").asText();
        assertNotEquals(financeTaskAId, financeTaskBId);
        assertEquals(
            textValues(financeReviewed.path("activeTasks"), "taskId"),
            Set.of(financeTaskAId, financeTaskBId)
        );

        JsonNode firstCountersign = approve(
            client,
            UUID.fromString(financeTaskAId),
            financeApproverA,
            FINANCE_APPROVER_A_REQUEST,
            "demo-finance-approver-a-v1",
            "Finance approver A countersigned."
        );
        assertTransition(
            firstCountersign,
            "RUNNING",
            Set.of(PurchasePaymentTemplate.FINANCE_COUNTERSIGN_TASK_KEY),
            Set.of(financeApproverB)
        );
        assertNoCompletionOutboxEvidence(evidence.instanceId());

        JsonNode finalCountersign = approve(
            client,
            UUID.fromString(financeTaskBId),
            financeApproverB,
            FINANCE_APPROVER_B_REQUEST,
            "demo-finance-approver-b-v1",
            "Finance approver B countersigned."
        );
        assertTransition(
            finalCountersign,
            "RUNNING",
            Set.of(PurchasePaymentTemplate.PAYMENT_CONFIRMATION_TASK_KEY),
            Set.of(initiatorId)
        );
        assertNoCompletionOutboxEvidence(evidence.instanceId());
        assertNoOutboxForRequest(FINANCE_APPROVER_B_REQUEST);

        JsonNode runningInstance = getJson(
            client,
            "/api/approval/instances/" + evidence.instanceId(),
            scenario.administratorId(),
            "demo-inspect-awaiting-payment-v1"
        );
        assertEquals("RUNNING", runningInstance.path("instance").path("status").asText());

        JsonNode paymentTask = requireSinglePendingTask(
            client,
            initiatorId,
            PurchasePaymentTemplate.PAYMENT_CONFIRMATION_TASK_KEY,
            "demo-payment-confirmation-pending-v1"
        );
        String paymentTaskId = paymentTask.path("taskId").asText();
        assertEquals(
            Set.of(paymentTaskId),
            textValues(finalCountersign.path("activeTasks"), "taskId")
        );
        assertNotEquals(financeTaskAId, paymentTaskId);
        assertNotEquals(financeTaskBId, paymentTaskId);

        JsonNode paymentConfirmed = approve(
            client,
            UUID.fromString(paymentTaskId),
            initiatorId,
            PAYMENT_CONFIRMATION_REQUEST,
            "demo-payment-confirmation-v1",
            "Payment side-effect handoff confirmed from the WeChat actor."
        );
        assertTransition(paymentConfirmed, "COMPLETED", Set.of(), Set.of());

        PurchasePaymentDemoSeedState.SeedEvidence completedReplay = seeder.apply();
        assertEquals(evidence.instanceId(), completedReplay.instanceId());
        assertEquals("COMPLETED", completedReplay.status().name());
        assertEquals(evidence.taskIds(), completedReplay.taskIds());
        assertEquals(evidence.attachments(), completedReplay.attachments());
        assertEquals(evidence.seededAt(), completedReplay.seededAt());

        JsonNode completedInstance = getJson(
            client,
            "/api/approval/instances/" + evidence.instanceId(),
            scenario.administratorId(),
            "demo-inspect-completed-instance-v1"
        );
        assertEquals(
            "COMPLETED",
            completedInstance.path("instance").path("status").asText()
        );

        assertNoPendingTasks(client, initiatorId, "demo-initiator-final-pending-v1");
        assertNoPendingTasks(client, managerId, "demo-manager-final-pending-v1");
        assertNoPendingTasks(
            client,
            financeReviewerId,
            "demo-finance-reviewer-final-pending-v1"
        );
        assertNoPendingTasks(
            client,
            financeApproverA,
            "demo-finance-approver-a-final-pending-v1"
        );
        assertNoPendingTasks(
            client,
            financeApproverB,
            "demo-finance-approver-b-final-pending-v1"
        );

        JsonNode timeline = getJson(
            client,
            "/api/approval/instances/" + evidence.instanceId() + "/timeline",
            initiatorId,
            "demo-completed-timeline-v1"
        );
        assertEquals(evidence.instanceId().toString(), timeline.path("instanceId").asText());
        Set<String> auditEventIds = Set.of(
            requireTimelineEvidence(
                timeline,
                "demo-seed-start-request-v2",
                initiatorId,
                "INSTANCE_STARTED"
            ),
            requireTimelineEvidence(
                timeline,
                MANAGER_APPROVE_REQUEST,
                managerId,
                "TASK_APPROVED"
            ),
            requireTimelineEvidence(
                timeline,
                FINANCE_REVIEW_APPROVE_REQUEST,
                financeReviewerId,
                "TASK_APPROVED"
            ),
            requireTimelineEvidence(
                timeline,
                FINANCE_APPROVER_A_REQUEST,
                financeApproverA,
                "TASK_APPROVED"
            ),
            requireTimelineEvidence(
                timeline,
                FINANCE_APPROVER_B_REQUEST,
                financeApproverB,
                "TASK_APPROVED"
            ),
            requireTimelineEvidence(
                timeline,
                PAYMENT_CONFIRMATION_REQUEST,
                initiatorId,
                "TASK_APPROVED"
            )
        );
        assertEquals(6, auditEventIds.size());

        assertCompletionOutboxEvidence(
            evidence.instanceId(),
            PAYMENT_CONFIRMATION_REQUEST
        );
    }

    private String requireSingleUserWithRole(String roleCode) {
        Set<String> matches = new HashSet<>();
        for (PurchasePaymentDemoScenario.DemoUser user : scenario.users()) {
            if (user.roleCodes().contains(roleCode)) {
                matches.add(user.id());
            }
        }
        assertEquals(1, matches.size(), "governed role fixture cardinality");
        return matches.iterator().next();
    }

    private Set<String> userIdsWithPosition(String positionCode) {
        Set<String> matches = new HashSet<>();
        for (PurchasePaymentDemoScenario.DemoUser user : scenario.users()) {
            if (user.positionCodes().contains(positionCode)) {
                matches.add(user.id());
            }
        }
        return Set.copyOf(matches);
    }

    private JsonNode requireSinglePendingTask(
        HttpClient client,
        String operatorId,
        String expectedTaskDefinitionKey,
        String requestId
    ) throws Exception {
        JsonNode page = getJson(
            client,
            "/api/approval/tasks/pending",
            operatorId,
            requestId
        );
        assertEquals(1, page.path("total").asLong());
        assertEquals(1, page.path("items").size());
        JsonNode task = page.path("items").get(0);
        assertEquals(
            expectedTaskDefinitionKey,
            task.path("taskDefinitionKey").asText()
        );
        assertEquals(
            scenario.request().businessKey(),
            task.path("businessKey").asText()
        );
        return task;
    }

    private void assertNoPendingTasks(
        HttpClient client,
        String operatorId,
        String requestId
    ) throws Exception {
        JsonNode page = getJson(
            client,
            "/api/approval/tasks/pending",
            operatorId,
            requestId
        );
        assertEquals(0, page.path("total").asLong());
        assertEquals(0, page.path("items").size());
    }

    private JsonNode approve(
        HttpClient client,
        UUID taskId,
        String operatorId,
        String requestId,
        String idempotencyKey,
        String comment
    ) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("comment", comment));
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(uri(
                    "/api/approval/tasks/" + taskId + "/approve"
                ))
                .header("X-Tenant-Id", scenario.tenantId())
                .header("X-Operator-Id", operatorId)
                .header("X-Request-Id", requestId)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Trace-Id", requestId)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, response.statusCode(), response.body());
        return objectMapper.readTree(response.body());
    }

    private JsonNode getJson(
        HttpClient client,
        String path,
        String operatorId,
        String requestId
    ) throws Exception {
        HttpResponse<String> response = client.send(
            approvalGet(path, operatorId, requestId),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, response.statusCode(), response.body());
        return objectMapper.readTree(response.body());
    }

    private void assertTransition(
        JsonNode result,
        String expectedStatus,
        Set<String> expectedTaskDefinitionKeys,
        Set<String> expectedAssigneeIds
    ) {
        assertEquals(expectedStatus, result.path("instanceStatus").asText());
        JsonNode activeTasks = result.path("activeTasks");
        assertEquals(expectedAssigneeIds.size(), activeTasks.size());
        assertEquals(
            expectedTaskDefinitionKeys,
            textValues(activeTasks, "taskDefinitionKey")
        );
        assertEquals(expectedAssigneeIds, textValues(activeTasks, "assigneeId"));
    }

    private String requireTimelineEvidence(
        JsonNode timeline,
        String requestId,
        String operatorId,
        String action
    ) {
        String eventId = null;
        int matches = 0;
        for (JsonNode item : timeline.path("items")) {
            if (requestId.equals(item.path("requestId").asText())) {
                matches++;
                eventId = item.path("eventId").asText();
                assertFalse(eventId.isBlank());
                assertEquals(operatorId, item.path("operatorId").asText());
                assertEquals(action, item.path("action").asText());
            }
        }
        assertEquals(1, matches, "timeline evidence count for " + requestId);
        return eventId;
    }

    private void assertNoCompletionOutboxEvidence(UUID instanceId) throws Exception {
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(
                """
                select count(*)
                from ap_outbox
                where tenant_id = ? and aggregate_id = ?
                """
            )
        ) {
            statement.setString(1, scenario.tenantId());
            statement.setString(2, instanceId.toString());
            try (ResultSet results = statement.executeQuery()) {
                assertTrue(results.next());
                assertEquals(0, results.getInt(1));
            }
        }
    }

    private void assertNoOutboxForRequest(String requestId) throws Exception {
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(
                """
                select count(*)
                from ap_outbox
                where tenant_id = ? and request_id = ?
                """
            )
        ) {
            statement.setString(1, scenario.tenantId());
            statement.setString(2, requestId);
            try (ResultSet results = statement.executeQuery()) {
                assertTrue(results.next());
                assertEquals(0, results.getInt(1));
            }
        }
    }

    private void assertCompletionOutboxEvidence(
        UUID instanceId,
        String requestId
    ) throws Exception {
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(
                """
                select connector_key, aggregate_id
                from ap_outbox
                where tenant_id = ? and request_id = ?
                """
            )
        ) {
            statement.setString(1, scenario.tenantId());
            statement.setString(2, requestId);
            try (ResultSet results = statement.executeQuery()) {
                assertTrue(results.next(), "completion Outbox row is missing");
                assertEquals(
                    scenario.assigneeRules().connectorKey(),
                    results.getString("connector_key")
                );
                assertEquals(instanceId.toString(), results.getString("aggregate_id"));
                assertFalse(
                    results.next(),
                    "completion Outbox row must be unique for payment confirmation"
                );
            }
        }
    }

    private static Set<String> textValues(JsonNode array, String field) {
        Set<String> values = new HashSet<>();
        for (JsonNode item : array) {
            values.add(item.path(field).asText());
        }
        return Set.copyOf(values);
    }

    private HttpRequest approvalGet(
        String path,
        String operatorId,
        String requestId
    ) {
        return HttpRequest.newBuilder(uri(path))
            .header("X-Tenant-Id", scenario.tenantId())
            .header("X-Operator-Id", operatorId)
            .header("X-Request-Id", requestId)
            .header("X-Trace-Id", requestId)
            .GET()
            .build();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
