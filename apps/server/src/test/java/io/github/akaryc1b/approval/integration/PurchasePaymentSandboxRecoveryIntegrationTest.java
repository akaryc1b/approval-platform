package io.github.akaryc1b.approval.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.ApprovalPlatformApplication;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService.ApproveCommand;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService.ApproveResult;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.connector.generic.GenericRestBusinessCallbackConnector;
import io.github.akaryc1b.approval.connector.generic.GenericWebhookEndpoint;
import io.github.akaryc1b.approval.demo.PurchasePaymentDemoPaymentSandbox;
import io.github.akaryc1b.approval.demo.PurchasePaymentDemoScenario;
import io.github.akaryc1b.approval.demo.PurchasePaymentDemoSeedState;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import io.github.akaryc1b.approval.integration.outbox.OutboxDispatcher;
import io.github.akaryc1b.approval.integration.outbox.OutboxRepository;
import io.github.akaryc1b.approval.integration.retry.ExponentialBackoffRetryPolicy;
import io.github.akaryc1b.approval.integration.webhook.HmacSha256WebhookSigner;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalBusinessEventOutbox;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("local")
@SpringBootTest(
    classes = ApprovalPlatformApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "approval.demo.purchase-payment.enabled=true",
        "approval.demo.purchase-payment.sandbox.enabled=true",
        "approval.demo.purchase-payment.sandbox.port=0",
        "approval.connector.generic.enabled=false",
        "approval.connector.generic.key-id=local-payment-sandbox-key-v1",
        "approval.connector.generic.secret="
            + "local-payment-sandbox-secret-material-not-for-production",
        "flowable.async-executor-activate=false"
    }
)
class PurchasePaymentSandboxRecoveryIntegrationTest {

    private static final String FINAL_APPROVAL_REQUEST =
        "demo-sandbox-payment-confirmation-request-v1";
    private static final byte[] SANDBOX_SECRET =
        "local-payment-sandbox-secret-material-not-for-production"
            .getBytes(StandardCharsets.UTF_8);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_payment_sandbox_test")
        .withUsername("approval")
        .withPassword("approval");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    PurchasePaymentDemoSeedState seedState;

    @Autowired
    PurchasePaymentDemoScenario scenario;

    @Autowired
    PurchasePaymentDemoPaymentSandbox sandbox;

    @Autowired
    PurchasePaymentApplicationService purchasePaymentService;

    @Autowired
    OutboxRepository outboxRepository;

    @Autowired
    DataSource dataSource;

    @Autowired
    @Qualifier("approvalPersistenceObjectMapper")
    ObjectMapper objectMapper;

    @Test
    void completesPaymentRecoversSignedSandboxDeliveryAndAvoidsDuplicateSideEffect() {
        sandbox.resetUnavailable();
        PurchasePaymentDemoSeedState.SeedEvidence evidence = seedState.requireEvidence();
        completePurchaseToPayment(evidence);

        OutboxSnapshot pending = requireCompletionOutbox();
        assertEquals(JdbcApprovalBusinessEventOutbox.COMPLETED_EVENT_TYPE, pending.eventType());
        assertEquals(evidence.instanceId().toString(), pending.aggregateId());
        assertEquals("PENDING", pending.status());
        assertEquals(0, pending.attempts());
        assertNull(pending.providerRequestId());
        assertNull(pending.responseCode());
        assertNull(pending.deliveredAt());
        assertNull(pending.lastError());

        MutableClock dispatchClock = new MutableClock(
            pending.availableAt().plusMillis(1),
            ZoneOffset.UTC
        );
        GenericRestBusinessCallbackConnector connector =
            new GenericRestBusinessCallbackConnector(
                HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build(),
                context -> new GenericWebhookEndpoint(
                    sandbox.endpoint(),
                    "local-payment-sandbox-key-v1",
                    SANDBOX_SECRET,
                    Duration.ofSeconds(2),
                    Map.of("X-Approval-Sandbox", "local-product-readiness")
                ),
                new HmacSha256WebhookSigner(),
                dispatchClock,
                () -> "local-payment-sandbox-nonce-v1"
            );
        OutboxDispatcher dispatcher = new OutboxDispatcher(
            outboxRepository,
            connectorKey -> {
                assertEquals(scenario.assigneeRules().connectorKey(), connectorKey);
                return connector;
            },
            new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                3,
                0
            ),
            dispatchClock,
            Duration.ofSeconds(30)
        );

        OutboxDispatcher.DispatchReport unavailable = dispatcher.dispatchBatch(
            10,
            "payment-sandbox-worker"
        );
        assertEquals(new OutboxDispatcher.DispatchReport(1, 0, 1, 0, 0), unavailable);

        OutboxSnapshot rescheduled = requireCompletionOutbox();
        assertEquals(pending.id(), rescheduled.id());
        assertEquals("PENDING", rescheduled.status());
        assertEquals(1, rescheduled.attempts());
        assertTrue(rescheduled.availableAt().isAfter(pending.availableAt()));
        assertTrue(rescheduled.lastError().startsWith("HTTP 503"));
        assertNull(rescheduled.providerRequestId());
        assertNull(rescheduled.deliveredAt());
        assertEquals(1, sandbox.deliveryAttempts());
        assertEquals(0, sandbox.acceptedPaymentResults());
        assertNull(sandbox.failure());

        sandbox.restore();
        dispatchClock.set(rescheduled.availableAt().plusMillis(1));
        OutboxDispatcher.DispatchReport recovered = dispatcher.dispatchBatch(
            10,
            "payment-sandbox-worker"
        );
        assertEquals(new OutboxDispatcher.DispatchReport(1, 1, 0, 0, 0), recovered);

        OutboxSnapshot delivered = requireCompletionOutbox();
        assertEquals(pending.id(), delivered.id());
        assertEquals("DELIVERED", delivered.status());
        assertEquals(1, delivered.attempts());
        assertEquals(200, delivered.responseCode());
        assertEquals(
            "local-payment-sandbox-" + pending.eventId(),
            delivered.providerRequestId()
        );
        assertNotNull(delivered.deliveredAt());
        assertNull(delivered.lastError());
        assertEquals(2, sandbox.deliveryAttempts());
        assertEquals(1, sandbox.acceptedPaymentResults());
        assertEquals(pending.eventId(), sandbox.acceptedEventId());
        assertEquals(pending.idempotencyKey(), sandbox.acceptedIdempotencyKey());
        assertEquals(FINAL_APPROVAL_REQUEST, sandbox.lastRequestId());
        assertNull(sandbox.failure());

        dispatchClock.set(delivered.deliveredAt().plusSeconds(30));
        OutboxDispatcher.DispatchReport noDuplicate = dispatcher.dispatchBatch(
            10,
            "payment-sandbox-worker"
        );
        assertEquals(new OutboxDispatcher.DispatchReport(0, 0, 0, 0, 0), noDuplicate);
        assertEquals(2, sandbox.deliveryAttempts());
        assertEquals(1, sandbox.acceptedPaymentResults());

        var completed = purchasePaymentService.findInstance(
            scenario.tenantId(),
            evidence.instanceId()
        ).orElseThrow();
        assertEquals(InstanceStatus.COMPLETED, completed.instance().status());
    }

    private void completePurchaseToPayment(
        PurchasePaymentDemoSeedState.SeedEvidence evidence
    ) {
        String initiatorId = scenario.assigneeRules().initiatorUserId().value();
        TaskProjection managerTask = requirePendingTask(
            purchasePaymentService.findTasks(scenario.tenantId(), evidence.instanceId()),
            PurchasePaymentTemplate.MANAGER_APPROVAL_TASK_KEY,
            "demo-manager"
        );
        ApproveResult managerApproved = approve(
            managerTask,
            "demo-manager",
            "demo-sandbox-manager-request-v1",
            "demo-sandbox-manager-v1"
        );

        TaskProjection financeReview = requirePendingTask(
            managerApproved.activeTasks(),
            PurchasePaymentTemplate.FINANCE_REVIEW_TASK_KEY,
            "demo-finance-reviewer"
        );
        ApproveResult financeReviewed = approve(
            financeReview,
            "demo-finance-reviewer",
            "demo-sandbox-finance-review-request-v1",
            "demo-sandbox-finance-review-v1"
        );

        TaskProjection financeA = requirePendingTask(
            financeReviewed.activeTasks(),
            PurchasePaymentTemplate.FINANCE_COUNTERSIGN_TASK_KEY,
            "demo-finance-approver-a"
        );
        TaskProjection financeBInitial = requirePendingTask(
            financeReviewed.activeTasks(),
            PurchasePaymentTemplate.FINANCE_COUNTERSIGN_TASK_KEY,
            "demo-finance-approver-b"
        );
        assertNotEquals(financeA.taskId(), financeBInitial.taskId());

        ApproveResult firstCountersign = approve(
            financeA,
            "demo-finance-approver-a",
            "demo-sandbox-finance-approver-a-request-v1",
            "demo-sandbox-finance-approver-a-v1"
        );
        TaskProjection financeB = requirePendingTask(
            firstCountersign.activeTasks(),
            PurchasePaymentTemplate.FINANCE_COUNTERSIGN_TASK_KEY,
            "demo-finance-approver-b"
        );
        ApproveResult awaitingPayment = approve(
            financeB,
            "demo-finance-approver-b",
            "demo-sandbox-finance-approver-b-request-v1",
            "demo-sandbox-finance-approver-b-v1"
        );
        assertEquals(InstanceStatus.RUNNING, awaitingPayment.instanceStatus());
        assertEquals(1, awaitingPayment.activeTasks().size());
        assertNoCompletionOutbox(evidence.instanceId());

        TaskProjection paymentConfirmation = requirePendingTask(
            awaitingPayment.activeTasks(),
            PurchasePaymentTemplate.PAYMENT_CONFIRMATION_TASK_KEY,
            initiatorId
        );
        assertNotEquals(financeA.taskId(), paymentConfirmation.taskId());
        assertNotEquals(financeB.taskId(), paymentConfirmation.taskId());

        ApproveResult completed = approve(
            paymentConfirmation,
            initiatorId,
            FINAL_APPROVAL_REQUEST,
            "demo-sandbox-payment-confirmation-v1"
        );
        assertEquals(InstanceStatus.COMPLETED, completed.instanceStatus());
        assertTrue(completed.activeTasks().isEmpty());
    }

    private ApproveResult approve(
        TaskProjection task,
        String operatorId,
        String requestId,
        String idempotencyKey
    ) {
        RequestContext context = new RequestContext(
            scenario.tenantId(),
            operatorId,
            requestId,
            idempotencyKey,
            requestId
        );
        return withRequestEvidence(
            context,
            () -> purchasePaymentService.approve(new ApproveCommand(
                context,
                task.taskId(),
                "Approved by deterministic local payment sandbox test."
            ))
        );
    }

    private static TaskProjection requirePendingTask(
        List<TaskProjection> tasks,
        String taskDefinitionKey,
        String assigneeId
    ) {
        return tasks.stream()
            .filter(task -> task.status() == TaskStatus.PENDING)
            .filter(task -> taskDefinitionKey.equals(task.taskDefinitionKey()))
            .filter(task -> assigneeId.equals(task.assigneeId()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "missing pending task " + taskDefinitionKey + " for " + assigneeId
            ));
    }

    private void assertNoCompletionOutbox(UUID instanceId) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer count = jdbc.queryForObject(
            """
            select count(*)
            from ap_outbox
            where tenant_id = ? and aggregate_id = ?
            """,
            Integer.class,
            scenario.tenantId(),
            instanceId.toString()
        );
        assertEquals(0, count);
    }

    private OutboxSnapshot requireCompletionOutbox() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<OutboxSnapshot> rows = jdbc.query(
            """
            select id, event_id, event_type, aggregate_id, idempotency_key,
                   status, attempts, available_at, provider_request_id,
                   response_code, delivered_at, last_error
            from ap_outbox
            where tenant_id = ? and request_id = ?
            """,
            (resultSet, rowNumber) -> outboxSnapshot(resultSet),
            scenario.tenantId(),
            FINAL_APPROVAL_REQUEST
        );
        assertEquals(1, rows.size(), "completion Outbox event must remain unique");
        return rows.getFirst();
    }

    private static OutboxSnapshot outboxSnapshot(ResultSet resultSet) throws SQLException {
        return new OutboxSnapshot(
            resultSet.getObject("id", UUID.class),
            resultSet.getObject("event_id", UUID.class),
            resultSet.getString("event_type"),
            resultSet.getString("aggregate_id"),
            resultSet.getString("idempotency_key"),
            resultSet.getString("status"),
            resultSet.getInt("attempts"),
            instant(resultSet, "available_at"),
            resultSet.getString("provider_request_id"),
            resultSet.getObject("response_code", Integer.class),
            instant(resultSet, "delivered_at"),
            resultSet.getString("last_error")
        );
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static <T> T withRequestEvidence(
        RequestContext context,
        Supplier<T> action
    ) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            MDC.put("operatorId", context.operatorId());
            MDC.put("requestId", context.requestId());
            MDC.put("traceId", context.traceId());
            return action.get();
        } finally {
            MDC.clear();
            if (previous != null && !previous.isEmpty()) {
                MDC.setContextMap(previous);
            }
        }
    }

    private record OutboxSnapshot(
        UUID id,
        UUID eventId,
        String eventType,
        String aggregateId,
        String idempotencyKey,
        String status,
        int attempts,
        Instant availableAt,
        String providerRequestId,
        Integer responseCode,
        Instant deliveredAt,
        String lastError
    ) {
    }

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;
        private final ZoneId zone;

        private MutableClock(Instant initial, ZoneId zone) {
            this(new AtomicReference<>(Objects.requireNonNull(initial)), zone);
        }

        private MutableClock(AtomicReference<Instant> current, ZoneId zone) {
            this.current = current;
            this.zone = Objects.requireNonNull(zone);
        }

        void set(Instant value) {
            current.set(Objects.requireNonNull(value));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            return new MutableClock(current, requestedZone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
