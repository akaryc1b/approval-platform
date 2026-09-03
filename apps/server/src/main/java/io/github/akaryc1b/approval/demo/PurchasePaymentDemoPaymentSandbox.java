package io.github.akaryc1b.approval.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.integration.webhook.SignedWebhookVerifier;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalBusinessEventOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local-only signed callback sandbox used by the purchase-to-payment runtime.
 *
 * <p>The HTTP transport is owned by Spring MVC. This class retains only the
 * governed request validation, deterministic outage/recovery state and
 * idempotent payment-side-effect evidence.</p>
 */
public final class PurchasePaymentDemoPaymentSandbox implements AutoCloseable {

    public static final String CALLBACK_PATH = "/payment-sandbox/v1/events";

    private static final Logger LOGGER = LoggerFactory.getLogger(
        PurchasePaymentDemoPaymentSandbox.class
    );

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final byte[] secret;
    private final String keyId;
    private final PurchasePaymentDemoScenario scenario;
    private final URI endpoint;
    private final Path controlFile;
    private final Path statusFile;
    private final String businessKeyPrefix;
    private final String purchaseOrderReferencePrefix;
    private final SignedWebhookVerifier verifier;
    private final AtomicBoolean available = new AtomicBoolean(false);
    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicInteger accepted = new AtomicInteger();
    private final AtomicReference<UUID> acceptedEventId = new AtomicReference<>();
    private final AtomicReference<String> acceptedIdempotencyKey = new AtomicReference<>();
    private final AtomicReference<String> lastRequestId = new AtomicReference<>();
    private final AtomicReference<Integer> lastHttpStatus = new AtomicReference<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Map<String, String> paymentResults = new ConcurrentHashMap<>();

    public PurchasePaymentDemoPaymentSandbox(
        ObjectMapper objectMapper,
        Clock clock,
        byte[] secret,
        String keyId,
        PurchasePaymentDemoScenario scenario,
        URI endpoint,
        Path controlFile,
        Path statusFile,
        Duration maximumClockSkew
    ) {
        this(
            objectMapper,
            clock,
            secret,
            keyId,
            scenario,
            endpoint,
            controlFile,
            statusFile,
            null,
            null,
            maximumClockSkew
        );
    }

    public PurchasePaymentDemoPaymentSandbox(
        ObjectMapper objectMapper,
        Clock clock,
        byte[] secret,
        String keyId,
        PurchasePaymentDemoScenario scenario,
        URI endpoint,
        Path controlFile,
        Path statusFile,
        String businessKeyPrefix,
        String purchaseOrderReferencePrefix,
        Duration maximumClockSkew
    ) {
        this.objectMapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.secret = Objects.requireNonNull(secret, "secret must not be null").clone();
        if (this.secret.length < 32) {
            Arrays.fill(this.secret, (byte) 0);
            throw new IllegalArgumentException("sandbox secret must contain at least 32 bytes");
        }
        this.keyId = requireText(keyId, "keyId");
        this.scenario = Objects.requireNonNull(scenario, "scenario must not be null");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        this.controlFile = normalize(controlFile);
        this.statusFile = normalize(statusFile);
        this.businessKeyPrefix = normalizeOptional(businessKeyPrefix);
        this.purchaseOrderReferencePrefix = normalizeOptional(
            purchaseOrderReferencePrefix
        );
        if ((this.businessKeyPrefix == null)
            != (this.purchaseOrderReferencePrefix == null)) {
            throw new IllegalArgumentException(
                "sandbox volume prefixes must be configured together"
            );
        }
        this.verifier = new SignedWebhookVerifier(maximumClockSkew);
    }

    public synchronized void initialize() throws IOException {
        deleteControlFile();
        available.set(false);
        writeStatus();
        LOGGER.info(
            "PURCHASE_PAYMENT_LOCAL_SANDBOX_STARTED endpoint={}",
            endpoint
        );
    }

    public URI endpoint() {
        return endpoint;
    }

    public void resetUnavailable() {
        deleteControlFile();
        available.set(false);
        attempts.set(0);
        accepted.set(0);
        acceptedEventId.set(null);
        acceptedIdempotencyKey.set(null);
        lastRequestId.set(null);
        lastHttpStatus.set(null);
        failure.set(null);
        paymentResults.clear();
        writeStatusUnchecked();
    }

    public void restore() {
        available.set(true);
        writeStatusUnchecked();
    }

    public int deliveryAttempts() {
        return attempts.get();
    }

    public int acceptedPaymentResults() {
        return accepted.get();
    }

    public UUID acceptedEventId() {
        return acceptedEventId.get();
    }

    public String acceptedIdempotencyKey() {
        return acceptedIdempotencyKey.get();
    }

    public String lastRequestId() {
        return lastRequestId.get();
    }

    public String failure() {
        return failureMessage();
    }

    public Snapshot snapshot() {
        return new Snapshot(
            1,
            "PURCHASE_PAYMENT_LOCAL_SANDBOX_V1",
            endpoint.toString(),
            currentAvailability(),
            attempts.get(),
            accepted.get(),
            acceptedEventId.get(),
            acceptedIdempotencyKey.get(),
            lastRequestId.get(),
            lastHttpStatus.get(),
            failureMessage(),
            clock.instant()
        );
    }

    public SandboxResponse handle(
        String method,
        String path,
        Map<String, String> headers,
        String body
    ) {
        attempts.incrementAndGet();
        int status = 500;
        try {
            SandboxRequest request = new SandboxRequest(
                requireText(method, "method"),
                requireText(path, "path"),
                normalizeHeaders(headers),
                Objects.requireNonNull(body, "body must not be null")
            );
            ValidatedEvent event = validateRequest(request);
            lastRequestId.set(event.requestId());
            if (!currentAvailability()) {
                status = 503;
                return new SandboxResponse(
                    status,
                    "payment sandbox unavailable",
                    null
                );
            }

            String canonicalPayload = objectMapper.writeValueAsString(event.payload());
            String previous = paymentResults.putIfAbsent(
                event.idempotencyKey(),
                canonicalPayload
            );
            if (previous == null) {
                accepted.incrementAndGet();
                acceptedEventId.set(event.eventId());
                acceptedIdempotencyKey.set(event.idempotencyKey());
            } else if (!previous.equals(canonicalPayload)) {
                throw new IllegalArgumentException(
                    "idempotent payment replay payload changed"
                );
            }
            status = 200;
            return new SandboxResponse(
                status,
                "payment sandbox accepted",
                "local-payment-sandbox-" + event.eventId()
            );
        } catch (IOException | RuntimeException exception) {
            recordFailure(exception);
            status = 400;
            return new SandboxResponse(status, "invalid sandbox request", null);
        } finally {
            lastHttpStatus.set(status);
            writeStatusUnchecked();
        }
    }

    private ValidatedEvent validateRequest(SandboxRequest request) throws IOException {
        require("POST".equals(request.method()), "sandbox requires POST");
        require(CALLBACK_PATH.equals(request.path()), "sandbox callback path is invalid");
        Map<String, String> headers = request.headers();
        String timestamp = requireHeader(headers, "X-Approval-Timestamp");
        String nonce = requireHeader(headers, "X-Approval-Nonce");
        String signature = requireHeader(headers, "X-Approval-Signature");
        require(
            verifier.verify(
                secret,
                timestamp,
                nonce,
                request.body(),
                signature,
                clock.instant()
            ) == SignedWebhookVerifier.VerificationResult.VALID,
            "sandbox signature is invalid"
        );
        require(
            keyId.equals(requireHeader(headers, "X-Approval-Key-Id")),
            "sandbox key ID is invalid"
        );
        require(
            scenario.tenantId().equals(requireHeader(headers, "X-Tenant-Id")),
            "sandbox tenant is invalid"
        );
        String requestId = requireHeader(headers, "X-Request-Id");

        JsonNode event = objectMapper.readTree(request.body());
        UUID eventId = UUID.fromString(
            requireText(event.path("eventId").asText(), "eventId")
        );
        String idempotencyKey = requireText(
            event.path("idempotencyKey").asText(),
            "idempotencyKey"
        );
        require(
            eventId.toString().equals(requireHeader(headers, "X-Approval-Event-Id")),
            "sandbox event header is invalid"
        );
        require(
            idempotencyKey.equals(requireHeader(headers, "Idempotency-Key")),
            "sandbox idempotency header is invalid"
        );
        require(
            JdbcApprovalBusinessEventOutbox.COMPLETED_EVENT_TYPE.equals(
                event.path("eventType").asText()
            ),
            "sandbox event type is invalid"
        );
        require(
            "APPROVAL_INSTANCE".equals(event.path("aggregateType").asText()),
            "sandbox aggregate type is invalid"
        );
        String aggregateId = requireText(
            event.path("aggregateId").asText(),
            "aggregateId"
        );
        UUID.fromString(aggregateId);
        JsonNode payload = event.path("payload");
        require(payload.isObject(), "sandbox payload must be an object");
        require(
            aggregateId.equals(payload.path("instanceId").asText()),
            "sandbox instance identity is invalid"
        );
        String businessKey = requireText(
            payload.path("businessKey").asText(),
            "businessKey"
        );
        require(
            matchesExpected(
                businessKey,
                scenario.request().businessKey(),
                businessKeyPrefix
            ),
            "sandbox business key is invalid"
        );
        require(
            "COMPLETED".equals(payload.path("status").asText()),
            "sandbox completion status is invalid"
        );
        require(
            scenario.request().supplier().equals(payload.path("supplier").asText()),
            "sandbox supplier is invalid"
        );
        String purchaseOrderReference = requireText(
            payload.path("purchaseOrderReference").asText(),
            "purchaseOrderReference"
        );
        require(
            matchesExpected(
                purchaseOrderReference,
                scenario.request().purchaseOrderReference(),
                purchaseOrderReferencePrefix
            ),
            "sandbox purchase order reference is invalid"
        );
        return new ValidatedEvent(
            eventId,
            idempotencyKey,
            requestId,
            event.deepCopy()
        );
    }

    private static boolean matchesExpected(
        String value,
        String expected,
        String configuredPrefix
    ) {
        return expected.equals(value)
            || configuredPrefix != null && value.startsWith(configuredPrefix);
    }

    private static Map<String, String> normalizeHeaders(Map<String, String> headers) {
        Objects.requireNonNull(headers, "headers must not be null");
        Map<String, String> normalized = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            String key = requireText(name, "header name").toLowerCase(Locale.ROOT);
            String candidate = requireText(value, "header value");
            String previous = normalized.putIfAbsent(key, candidate);
            if (previous != null && !previous.equals(candidate)) {
                throw new IllegalArgumentException("conflicting duplicate HTTP header");
            }
        });
        return Map.copyOf(normalized);
    }

    private boolean currentAvailability() {
        if (!available.get() && controlFile != null && Files.isRegularFile(controlFile)) {
            available.set(true);
        }
        return available.get();
    }

    private void deleteControlFile() {
        if (controlFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(controlFile);
        } catch (IOException exception) {
            throw new IllegalStateException(
                "unable to remove sandbox recovery control file",
                exception
            );
        }
    }

    private void recordFailure(Throwable throwable) {
        failure.compareAndSet(null, throwable);
        LOGGER.error("PURCHASE_PAYMENT_LOCAL_SANDBOX_FAILURE", throwable);
    }

    private String failureMessage() {
        Throwable value = failure.get();
        return value == null ? null : value.getClass().getName() + ": " + value.getMessage();
    }

    private synchronized void writeStatus() throws IOException {
        if (statusFile == null) {
            return;
        }
        Path parent = statusFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = statusFile.resolveSibling(statusFile.getFileName() + ".tmp");
        Files.writeString(
            temporary,
            objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(snapshot()) + System.lineSeparator(),
            StandardCharsets.UTF_8
        );
        try {
            Files.move(
                temporary,
                statusFile,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                temporary,
                statusFile,
                StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private void writeStatusUnchecked() {
        try {
            writeStatus();
        } catch (IOException exception) {
            recordFailure(exception);
            throw new IllegalStateException("unable to write sandbox status", exception);
        }
    }

    private static String requireHeader(Map<String, String> headers, String name) {
        return requireText(headers.get(name.toLowerCase(Locale.ROOT)), name);
    }

    private static Path normalize(Path value) {
        return value == null ? null : value.toAbsolutePath().normalize();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    @Override
    public synchronized void close() {
        Arrays.fill(secret, (byte) 0);
        LOGGER.info("PURCHASE_PAYMENT_LOCAL_SANDBOX_STOPPED");
    }

    public record Snapshot(
        int schemaVersion,
        String evidenceKind,
        String endpoint,
        boolean available,
        int deliveryAttempts,
        int acceptedPaymentResults,
        UUID acceptedEventId,
        String acceptedIdempotencyKey,
        String lastRequestId,
        Integer lastHttpStatus,
        String failure,
        Instant updatedAt
    ) {
    }

    public record SandboxResponse(
        int status,
        String body,
        String requestId
    ) {
    }

    private record SandboxRequest(
        String method,
        String path,
        Map<String, String> headers,
        String body
    ) {
    }

    private record ValidatedEvent(
        UUID eventId,
        String idempotencyKey,
        String requestId,
        JsonNode payload
    ) {
    }
}
