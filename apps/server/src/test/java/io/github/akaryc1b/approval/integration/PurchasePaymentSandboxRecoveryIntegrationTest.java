package io.github.akaryc1b.approval.integration;

import com.fasterxml.jackson.databind.JsonNode;
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
import io.github.akaryc1b.approval.demo.PurchasePaymentDemoScenario;
import io.github.akaryc1b.approval.demo.PurchasePaymentDemoSeedState;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.integration.outbox.OutboxDispatcher;
import io.github.akaryc1b.approval.integration.outbox.OutboxRepository;
import io.github.akaryc1b.approval.integration.retry.ExponentialBackoffRetryPolicy;
import io.github.akaryc1b.approval.integration.webhook.HmacSha256WebhookSigner;
import io.github.akaryc1b.approval.integration.webhook.SignedWebhookVerifier;
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
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        "approval.connector.generic.enabled=false",
        "flowable.async-executor-activate=false"
    }
)
class PurchasePaymentSandboxRecoveryIntegrationTest {

    private static final String MANAGER_APPROVAL = "managerApproval";
    private static final String FINANCE_REVIEW = "financeReview";
    private static final String FINANCE_COUNTERSIGN = "financeCountersign";
    private static final String FINAL_APPROVAL_REQUEST =
        "demo-sandbox-finance-approver-b-request-v1";
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
    PurchasePaymentApplicationService purchasePaymentService;

    @Autowired
    OutboxRepository outboxRepository;

    @Autowired
    DataSource dataSource;

    @Autowired
    @Qualifier("approvalPersistenceObjectMapper")
    ObjectMapper objectMapper;

    @Test
    void completesApprovalRecoversSignedSandboxDeliveryAndAvoidsDuplicatePayment()
        throws Exception {
        PurchasePaymentDemoSeedState.SeedEvidence evidence = seedState.requireEvidence();
        completeApproval(evidence);

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
        try (PaymentSandbox sandbox = new PaymentSandbox(
            objectMapper,
            dispatchClock,
            SANDBOX_SECRET,
            scenario,
            evidence.instanceId(),
            FINAL_APPROVAL_REQUEST
        )) {
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
            assertNull(sandbox.failure());

            dispatchClock.set(delivered.deliveredAt().plusSeconds(30));
            OutboxDispatcher.DispatchReport noDuplicate = dispatcher.dispatchBatch(
                10,
                "payment-sandbox-worker"
            );
            assertEquals(new OutboxDispatcher.DispatchReport(0, 0, 0, 0, 0), noDuplicate);
            assertEquals(2, sandbox.deliveryAttempts());
            assertEquals(1, sandbox.acceptedPaymentResults());
        }

        var completed = purchasePaymentService.findInstance(
            scenario.tenantId(),
            evidence.instanceId()
        ).orElseThrow();
        assertEquals(InstanceStatus.COMPLETED, completed.instance().status());
    }

    private void completeApproval(PurchasePaymentDemoSeedState.SeedEvidence evidence) {
        TaskProjection managerTask = requirePendingTask(
            purchasePaymentService.findTasks(scenario.tenantId(), evidence.instanceId()),
            MANAGER_APPROVAL,
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
            FINANCE_REVIEW,
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
            FINANCE_COUNTERSIGN,
            "demo-finance-approver-a"
        );
        ApproveResult firstCountersign = approve(
            financeA,
            "demo-finance-approver-a",
            "demo-sandbox-finance-approver-a-request-v1",
            "demo-sandbox-finance-approver-a-v1"
        );

        TaskProjection financeB = requirePendingTask(
            firstCountersign.activeTasks(),
            FINANCE_COUNTERSIGN,
            "demo-finance-approver-b"
        );
        ApproveResult completed = approve(
            financeB,
            "demo-finance-approver-b",
            FINAL_APPROVAL_REQUEST,
            "demo-sandbox-finance-approver-b-v1"
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

    private static final class PaymentSandbox implements AutoCloseable {

        private static final String CALLBACK_PATH = "/payment-sandbox/v1/events";
        private static final int MAX_REQUEST_LINE_LENGTH = 4096;
        private static final int MAX_HEADER_LINE_LENGTH = 8192;

        private final ObjectMapper objectMapper;
        private final Clock clock;
        private final byte[] secret;
        private final PurchasePaymentDemoScenario scenario;
        private final UUID instanceId;
        private final String expectedRequestId;
        private final SignedWebhookVerifier verifier;
        private final ServerSocket serverSocket;
        private final ExecutorService executor;
        private final AtomicBoolean available = new AtomicBoolean(false);
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger accepted = new AtomicInteger();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final ConcurrentHashMap<String, JsonNode> paymentResults =
            new ConcurrentHashMap<>();
        private final AtomicReference<UUID> acceptedEventId = new AtomicReference<>();
        private final AtomicReference<String> acceptedIdempotencyKey = new AtomicReference<>();

        private PaymentSandbox(
            ObjectMapper objectMapper,
            Clock clock,
            byte[] secret,
            PurchasePaymentDemoScenario scenario,
            UUID instanceId,
            String expectedRequestId
        ) throws IOException {
            this.objectMapper = Objects.requireNonNull(objectMapper);
            this.clock = Objects.requireNonNull(clock);
            this.secret = Objects.requireNonNull(secret).clone();
            this.scenario = Objects.requireNonNull(scenario);
            this.instanceId = Objects.requireNonNull(instanceId);
            this.expectedRequestId = Objects.requireNonNull(expectedRequestId);
            this.verifier = new SignedWebhookVerifier(
                new HmacSha256WebhookSigner(),
                Duration.ofMinutes(1)
            );
            this.serverSocket = new ServerSocket();
            this.serverSocket.bind(new InetSocketAddress(
                InetAddress.getLoopbackAddress(),
                0
            ));
            this.executor = Executors.newSingleThreadExecutor();
            this.executor.submit(this::serve);
        }

        URI endpoint() {
            try {
                return new URI(
                    "http",
                    null,
                    serverSocket.getInetAddress().getHostAddress(),
                    serverSocket.getLocalPort(),
                    CALLBACK_PATH,
                    null,
                    null
                );
            } catch (URISyntaxException exception) {
                throw new IllegalStateException("invalid local sandbox endpoint", exception);
            }
        }

        void restore() {
            available.set(true);
        }

        int deliveryAttempts() {
            return attempts.get();
        }

        int acceptedPaymentResults() {
            return accepted.get();
        }

        UUID acceptedEventId() {
            return acceptedEventId.get();
        }

        String acceptedIdempotencyKey() {
            return acceptedIdempotencyKey.get();
        }

        Throwable failure() {
            return failure.get();
        }

        private void serve() {
            while (!serverSocket.isClosed()) {
                try {
                    handle(serverSocket.accept());
                } catch (SocketException exception) {
                    if (!serverSocket.isClosed()) {
                        failure.compareAndSet(null, exception);
                    }
                } catch (IOException exception) {
                    failure.compareAndSet(null, exception);
                }
            }
        }

        private void handle(Socket socket) {
            try (
                Socket connection = socket;
                InputStream input = new BufferedInputStream(connection.getInputStream());
                OutputStream output = new BufferedOutputStream(connection.getOutputStream())
            ) {
                attempts.incrementAndGet();
                try {
                    SandboxRequest request = readRequest(input);
                    handleRequest(request, output);
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                    respond(output, 400, "invalid sandbox request", null);
                }
            } catch (IOException exception) {
                failure.compareAndSet(null, exception);
            }
        }

        private void handleRequest(SandboxRequest request, OutputStream output)
            throws IOException {
            assertEquals("POST", request.method());
            assertEquals(CALLBACK_PATH, request.target());
            assertTrue(request.version().startsWith("HTTP/1."));

            String timestamp = request.header("X-Approval-Timestamp");
            String nonce = request.header("X-Approval-Nonce");
            String signature = request.header("X-Approval-Signature");
            assertEquals(
                SignedWebhookVerifier.VerificationResult.VALID,
                verifier.verify(
                    secret,
                    timestamp,
                    nonce,
                    request.body(),
                    signature,
                    clock.instant()
                )
            );
            assertEquals(
                "local-payment-sandbox-key-v1",
                request.header("X-Approval-Key-Id")
            );
            assertEquals(scenario.tenantId(), request.header("X-Tenant-Id"));
            assertEquals(expectedRequestId, request.header("X-Request-Id"));
            assertEquals(
                "local-product-readiness",
                request.header("X-Approval-Sandbox")
            );

            JsonNode event = objectMapper.readTree(request.body());
            UUID eventId = UUID.fromString(event.path("eventId").asText());
            String idempotencyKey = event.path("idempotencyKey").asText();
            assertEquals(eventId.toString(), request.header("X-Approval-Event-Id"));
            assertEquals(idempotencyKey, request.header("Idempotency-Key"));
            assertEquals(
                JdbcApprovalBusinessEventOutbox.COMPLETED_EVENT_TYPE,
                event.path("eventType").asText()
            );
            assertEquals("APPROVAL_INSTANCE", event.path("aggregateType").asText());
            assertEquals(instanceId.toString(), event.path("aggregateId").asText());
            assertEquals(
                instanceId.toString(),
                event.path("payload").path("instanceId").asText()
            );
            assertEquals(
                scenario.request().businessKey(),
                event.path("payload").path("businessKey").asText()
            );
            assertEquals("COMPLETED", event.path("payload").path("status").asText());
            assertEquals(
                scenario.request().supplier(),
                event.path("payload").path("supplier").asText()
            );
            assertEquals(
                scenario.request().purchaseOrderReference(),
                event.path("payload").path("purchaseOrderReference").asText()
            );

            if (!available.get()) {
                respond(output, 503, "payment sandbox unavailable", null);
                return;
            }

            JsonNode previous = paymentResults.putIfAbsent(idempotencyKey, event);
            if (previous == null) {
                accepted.incrementAndGet();
                acceptedEventId.set(eventId);
                acceptedIdempotencyKey.set(idempotencyKey);
            } else {
                assertEquals(previous, event, "idempotent replay payload changed");
            }
            respond(
                output,
                200,
                "payment sandbox accepted",
                "local-payment-sandbox-" + eventId
            );
        }

        private static SandboxRequest readRequest(InputStream input) throws IOException {
            String requestLine = readAsciiLine(input, MAX_REQUEST_LINE_LENGTH);
            String[] requestParts = requestLine.split(" ", 3);
            if (requestParts.length != 3) {
                throw new IOException("malformed HTTP request line");
            }

            Map<String, String> headers = new LinkedHashMap<>();
            while (true) {
                String line = readAsciiLine(input, MAX_HEADER_LINE_LENGTH);
                if (line.isEmpty()) {
                    break;
                }
                int separator = line.indexOf(':');
                if (separator < 1) {
                    throw new IOException("malformed HTTP header");
                }
                String name = line.substring(0, separator)
                    .trim()
                    .toLowerCase(Locale.ROOT);
                String value = line.substring(separator + 1).trim();
                headers.put(name, value);
            }

            int contentLength;
            try {
                contentLength = Integer.parseInt(requireHeader(headers, "Content-Length"));
            } catch (NumberFormatException exception) {
                throw new IOException("invalid Content-Length", exception);
            }
            if (contentLength < 0 || contentLength > 1_048_576) {
                throw new IOException("Content-Length is outside sandbox bounds");
            }
            byte[] body = input.readNBytes(contentLength);
            if (body.length != contentLength) {
                throw new IOException("request body ended before Content-Length");
            }
            return new SandboxRequest(
                requestParts[0],
                requestParts[1],
                requestParts[2],
                Map.copyOf(headers),
                new String(body, StandardCharsets.UTF_8)
            );
        }

        private static String readAsciiLine(InputStream input, int maximumLength)
            throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int previous = -1;
            while (buffer.size() <= maximumLength) {
                int current = input.read();
                if (current < 0) {
                    throw new IOException("HTTP request ended before line terminator");
                }
                if (previous == '\r' && current == '\n') {
                    byte[] bytes = buffer.toByteArray();
                    return new String(
                        bytes,
                        0,
                        bytes.length - 1,
                        StandardCharsets.US_ASCII
                    );
                }
                buffer.write(current);
                previous = current;
            }
            throw new IOException("HTTP line exceeds sandbox limit");
        }

        private static String requireHeader(Map<String, String> headers, String name) {
            String value = headers.get(name.toLowerCase(Locale.ROOT));
            assertNotNull(value, "missing header " + name);
            assertFalse(value.isBlank(), "blank header " + name);
            return value;
        }

        private static void respond(
            OutputStream output,
            int status,
            String body,
            String requestId
        ) throws IOException {
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            String reason = switch (status) {
                case 200 -> "OK";
                case 400 -> "Bad Request";
                case 503 -> "Service Unavailable";
                default -> "Response";
            };
            StringBuilder responseHeaders = new StringBuilder()
                .append("HTTP/1.1 ")
                .append(status)
                .append(' ')
                .append(reason)
                .append("\r\n")
                .append("Content-Type: text/plain; charset=utf-8\r\n")
                .append("Content-Length: ")
                .append(bodyBytes.length)
                .append("\r\n")
                .append("Connection: close\r\n");
            if (requestId != null) {
                responseHeaders.append("X-Request-Id: ")
                    .append(requestId)
                    .append("\r\n");
            }
            responseHeaders.append("\r\n");
            output.write(responseHeaders.toString().getBytes(StandardCharsets.US_ASCII));
            output.write(bodyBytes);
            output.flush();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            executor.shutdownNow();
        }

        private record SandboxRequest(
            String method,
            String target,
            String version,
            Map<String, String> headers,
            String body
        ) {
            private String header(String name) {
                return requireHeader(headers, name);
            }
        }
    }
}
