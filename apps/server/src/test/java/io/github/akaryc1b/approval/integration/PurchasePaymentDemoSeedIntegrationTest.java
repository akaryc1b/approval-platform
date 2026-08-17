package io.github.akaryc1b.approval.integration;

import io.github.akaryc1b.approval.ApprovalPlatformApplication;
import io.github.akaryc1b.approval.demo.PurchasePaymentDemoScenario;
import io.github.akaryc1b.approval.demo.PurchasePaymentDemoSeedState;
import io.github.akaryc1b.approval.demo.PurchasePaymentDemoSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void startsRealBackendAppliesIdempotentSeedAndExposesHealth() throws Exception {
        PurchasePaymentDemoSeedState.SeedEvidence evidence = state.requireEvidence();

        assertEquals(scenario.tenantId(), evidence.tenantId());
        assertEquals(scenario.request().businessKey(), evidence.businessKey());
        assertEquals(1, evidence.taskIds().size());
        assertEquals(2, evidence.attachments().size());
        assertEquals(
            "3680e624-4ca6-54f9-8e1c-7736f5fc936d",
            evidence.attachments().getFirst().attachmentId().toString()
        );
        assertFalse(evidence.attachments().stream()
            .anyMatch(PurchasePaymentDemoSeedState.AttachmentEvidence::bound));

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> health = client.send(
            HttpRequest.newBuilder(uri("/actuator/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, health.statusCode());
        assertTrue(health.body().contains("\"status\":\"UP\""));

        HttpResponse<String> instance = client.send(
            approvalGet("/api/approval/instances/" + evidence.instanceId(), "demo-admin"),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, instance.statusCode());
        assertTrue(instance.body().contains(evidence.instanceId().toString()));
        assertTrue(instance.body().contains(evidence.businessKey()));

        String managerId = scenario.requireUser(
            scenario.assigneeRules().initiatorUserId().value()
        ).managerId();
        HttpResponse<String> pending = client.send(
            approvalGet("/api/approval/tasks/pending", managerId),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, pending.statusCode());
        assertTrue(pending.body().contains(evidence.taskIds().getFirst().toString()));
        assertTrue(pending.body().contains(evidence.businessKey()));

        PurchasePaymentDemoSeedState.SeedEvidence replay = seeder.apply();
        assertEquals(evidence.instanceId(), replay.instanceId());
        assertEquals(evidence.taskIds(), replay.taskIds());
        assertEquals(evidence.attachments(), replay.attachments());
        assertEquals(evidence.seededAt(), replay.seededAt());
    }

    private HttpRequest approvalGet(String path, String operatorId) {
        return HttpRequest.newBuilder(uri(path))
            .header("X-Tenant-Id", scenario.tenantId())
            .header("X-Operator-Id", operatorId)
            .header("X-Request-Id", "demo-seed-http-verification-v1")
            .header("X-Trace-Id", "demo-seed-http-verification-v1")
            .GET()
            .build();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
