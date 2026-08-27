package io.github.akaryc1b.approval.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.integration.webhook.HmacSha256WebhookSigner;
import io.github.akaryc1b.approval.integration.webhook.SignedWebhookVerifier;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalBusinessEventOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local-profile-only signed payment callback sandbox shared by runtime E2E and tests.
 */
public final class PurchasePaymentDemoPaymentSandbox implements AutoCloseable {

    public static final String CALLBACK_PATH = "/payment-sandbox/v1/events";
    public static final String EVIDENCE_KIND = "PURCHASE_PAYMENT_LOCAL_SANDBOX_V1";

    private static final Logger LOGGER =
        LoggerFactory.getLogger(PurchasePaymentDemoPaymentSandbox.class);
    private static final int MAX_HEADER_LINE_BYTES = 16_384;
    private static final int MAX_BODY_BYTES = 1_048_576;
    private static final int SOCKET_TIMEOUT_MILLIS = 5_000;

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final byte[] secret;
    private final String keyId;
    private final PurchasePaymentDemoScenario scenario;
    private final int configuredPort;
    private final Path controlFile;
    private final Path statusFile;
    private final SignedWebhookVerifier verifier;
    private final AtomicBoolean available = new AtomicBoolean(false);
    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicInteger accepted = new AtomicInteger();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final ConcurrentHashMap<String, String> paymentResults =
        new ConcurrentHashMap<>();
    private final AtomicReference<UUID> acceptedEventId = new AtomicReference<>();
    private final AtomicReference<String> acceptedIdempotencyKey = new AtomicReference<>();
    private final AtomicReference<String> lastRequestId = new AtomicReference<>();
    private final AtomicReference<Integer> lastHttpStatus = new AtomicReference<>();

    private ServerSocket server;
    private ExecutorService executor;

    public PurchasePaymentDemoPaymentSandbox(
        ObjectMapper objectMapper,
        Clock clock,
        byte[] secret,
        String keyId,
        PurchasePaymentDemoScenario scenario,
        int configuredPort,
        Path controlFile,
        Path statusFile,
        Duration maximumClockSkew
    ) {
        this.objectMapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.secret = Objects.requireNonNull(secret, "secret must not be null").clone();
        this.keyId = requireText(keyId, "keyId");
        this.scenario = Objects.requireNonNull(scenario, "scenario must not be null");
        this.configuredPort = configuredPort;
        this.controlFile = normalize(controlFile);
        this.statusFile = normalize(statusFile);
        this.verifier = new SignedWebhookVerifier(
            new HmacSha256WebhookSigner(),
            Objects.requireNonNull(maximumClockSkew, "maximumClockSkew must not be null")
        );
        if (this.secret.length < 32) {
            Arrays.fill(this.secret, (byte) 0);
            throw new IllegalArgumentException("sandbox secret must contain at least 32 bytes");
        }
        if (configuredPort != 0 && (configuredPort < 1024 || configuredPort > 65_535)) {
            Arrays.fill(this.secret, (byte) 0);
            throw new IllegalArgumentException(
                "sandbox port must be 0 or between 1024 and 65535"
            );
        }
        if (maximumClockSkew.isZero() || maximumClockSkew.isNegative()) {
            Arrays.fill(this.secret, (byte) 0);
            throw new IllegalArgumentException("maximumClockSkew must be positive");
        }
        if (this.controlFile != null && this.controlFile.equals(this.statusFile)) {
            Arrays.fill(this.secret, (byte) 0);
            throw new IllegalArgumentException(
                "sandbox control and status files must be different"
            );
        }
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("payment sandbox is already started");
        }
        server = new ServerSocket();
        server.bind(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), configuredPort),
            16
        );
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "purchase-payment-demo-sandbox");
            thread.setDaemon(true);
            return thread;
        });
        executor.submit(this::acceptLoop);
        writeStatus();
        LOGGER.info(
            "PURCHASE_PAYMENT_LOCAL_SANDBOX_STARTED endpoint={} available={}",
            endpoint(),
            available.get()
        );
    }

    public URI endpoint() {
        ServerSocket active = requireServer();
        return URI.create(
            "http://127.0.0.1:" + active.getLocalPort() + CALLBACK_PATH
        );
    }

    public void resetUnavailable() {
        available.set(false);
        attempts.set(0);
        accepted.set(0);
        paymentResults.clear();
        acceptedEventId.set(null);
        acceptedIdempotencyKey.set(null);
        lastRequestId.set(null);
        lastHttpStatus.set(null);
        failure.set(null);
        deleteControlFile();
        writeStatusUnchecked();
    }

    public void restore() {
        available.set(true);
        writeStatusUnchecked();
        LOGGER.info("PURCHASE_PAYMENT_LOCAL_SANDBOX_RECOVERED endpoint={}", endpoint());
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

    public Throwable failure() {
        return failure.get();
    }

    public Snapshot snapshot() {
        return new Snapshot(
            1,
            EVIDENCE_KIND,
            endpoint().toString(),
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

    private void acceptLoop() {
        ServerSocket active = requireServer();
        while (!active.isClosed()) {
            try (Socket connection = active.accept()) {
                connection.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                handle(connection);
            } catch (SocketException exception) {
                if (!active.isClosed()) {
                    recordFailure(exception);
                }
            } catch (IOException | RuntimeException exception) {
                recordFailure(exception);
            }
        }
    }

    private void handle(Socket connection) throws IOException {
        attempts.incrementAndGet();
        int status = 500;
        try {
            SandboxRequest request = readRequest(connection);
            ValidatedEvent event = validateRequest(request);
            lastRequestId.set(event.requestId());
            if (!currentAvailability()) {
                status = 503;
                respond(connection, status, "payment sandbox unavailable", null);
                return;
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
            respond(
                connection,
                status,
                "payment sandbox accepted",
                "local-payment-sandbox-" + event.eventId()
            );
        } catch (IOException | RuntimeException exception) {
            recordFailure(exception);
            status = 400;
            respond(connection, status, "invalid sandbox request", null);
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
        require(
            scenario.request().businessKey().equals(payload.path("businessKey").asText()),
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
        require(
            scenario.request().purchaseOrderReference().equals(
                payload.path("purchaseOrderReference").asText()
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

    private static SandboxRequest readRequest(Socket connection) throws IOException {
        BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
        String requestLine = readHttpLine(input);
        String[] requestParts = requestLine.split(" ", 3);
        if (requestParts.length != 3) {
            throw new IOException("invalid HTTP request line");
        }

        Map<String, String> headers = new ConcurrentHashMap<>();
        while (true) {
            String line = readHttpLine(input);
            if (line.isEmpty()) {
                break;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new IOException("invalid HTTP header line");
            }
            headers.put(
                line.substring(0, separator).trim().toLowerCase(Locale.ROOT),
                line.substring(separator + 1).trim()
            );
        }

        String contentLengthValue = headers.get("content-length");
        if (contentLengthValue == null) {
            throw new IOException("missing Content-Length header");
        }
        int contentLength;
        try {
            contentLength = Integer.parseInt(contentLengthValue);
        } catch (NumberFormatException exception) {
            throw new IOException("invalid Content-Length header", exception);
        }
        if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
            throw new IOException("HTTP request body exceeds sandbox boundary");
        }
        byte[] bodyBytes = input.readNBytes(contentLength);
        if (bodyBytes.length != contentLength) {
            throw new EOFException("truncated HTTP request body");
        }
        return new SandboxRequest(
            requestParts[0],
            requestParts[1],
            Map.copyOf(headers),
            new String(bodyBytes, StandardCharsets.UTF_8)
        );
    }

    private static String readHttpLine(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() <= MAX_HEADER_LINE_BYTES) {
            int value = input.read();
            if (value < 0) {
                throw new EOFException("unexpected end of HTTP headers");
            }
            if (value == '\n') {
                byte[] bytes = line.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') {
                    length -= 1;
                }
                return new String(
                    bytes,
                    0,
                    length,
                    StandardCharsets.ISO_8859_1
                );
            }
            line.write(value);
        }
        throw new IOException("HTTP header line exceeds sandbox boundary");
    }

    private static String requireHeader(Map<String, String> headers, String name) {
        return requireText(headers.get(name.toLowerCase(Locale.ROOT)), name);
    }

    private static void respond(
        Socket connection,
        int status,
        String body,
        String requestId
    ) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String reason = switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 503 -> "Service Unavailable";
            default -> "Sandbox Response";
        };
        StringBuilder headers = new StringBuilder()
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
            headers.append("X-Request-Id: ").append(requestId).append("\r\n");
        }
        headers.append("\r\n");

        BufferedOutputStream output = new BufferedOutputStream(
            connection.getOutputStream()
        );
        output.write(headers.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.write(bodyBytes);
        output.flush();
    }

    private ServerSocket requireServer() {
        ServerSocket active = server;
        if (active == null) {
            throw new IllegalStateException("payment sandbox has not started");
        }
        return active;
    }

    private static Path normalize(Path value) {
        return value == null ? null : value.toAbsolutePath().normalize();
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
    public synchronized void close() throws Exception {
        ServerSocket active = server;
        if (active == null) {
            return;
        }
        try {
            active.close();
            ExecutorService activeExecutor = executor;
            if (activeExecutor != null) {
                activeExecutor.shutdownNow();
                if (!activeExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                        "payment sandbox executor did not stop"
                    );
                }
            }
        } finally {
            Arrays.fill(secret, (byte) 0);
            server = null;
            executor = null;
            LOGGER.info("PURCHASE_PAYMENT_LOCAL_SANDBOX_STOPPED");
        }
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
