package io.github.akaryc1b.approval.connector.generic;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector;
import io.github.akaryc1b.approval.integration.webhook.HmacSha256WebhookSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericRestBusinessCallbackConnectorTest {

    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef"
        .getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsCanonicalSignedRequest() {
        var requestBody = new AtomicReference<String>();
        var signature = new AtomicReference<String>();
        var timestamp = new AtomicReference<String>();
        var nonce = new AtomicReference<String>();
        server.createContext("/callback", exchange -> {
            capture(exchange, requestBody, signature, timestamp, nonce);
            exchange.getResponseHeaders().add("X-Request-Id", "provider-123");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        var connector = connector("/callback");
        var context = context();
        var event = event();

        var receipt = connector.deliver(context, event);

        assertEquals(BusinessCallbackConnector.DeliveryStatus.DELIVERED, receipt.status());
        assertEquals(204, receipt.responseCode());
        assertEquals("provider-123", receipt.providerRequestId());
        assertTrue(new HmacSha256WebhookSigner().verify(
            SECRET,
            Long.parseLong(timestamp.get()),
            nonce.get(),
            requestBody.get(),
            signature.get()
        ));
    }

    @Test
    void classifiesRateLimitAsRetryable() {
        server.createContext("/rate-limit", exchange -> {
            byte[] response = "slow down".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        var receipt = connector("/rate-limit").deliver(context(), event());

        assertEquals(BusinessCallbackConnector.DeliveryStatus.RETRYABLE_FAILURE, receipt.status());
        assertEquals(429, receipt.responseCode());
        assertEquals("slow down", receipt.errorMessage());
    }

    private GenericRestBusinessCallbackConnector connector(String path) {
        var endpoint = new GenericWebhookEndpoint(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path),
            "key-1",
            SECRET,
            Duration.ofSeconds(5),
            Map.of("X-Client", "approval-platform")
        );
        return new GenericRestBusinessCallbackConnector(
            HttpClient.newHttpClient(),
            context -> endpoint,
            new HmacSha256WebhookSigner(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> "nonce-1"
        );
    }

    private static ConnectorContext context() {
        return new ConnectorContext("generic", "tenant-a", "request-1", "trace-1", NOW);
    }

    private static BusinessCallbackConnector.BusinessEvent event() {
        return new BusinessCallbackConnector.BusinessEvent(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "PROCESS_APPROVED.v1",
            "PROCESS",
            "process-1",
            NOW,
            "process-1-approved",
            Map.of("amount", 1200, "currency", "CNY")
        );
    }

    private static void capture(
        HttpExchange exchange,
        AtomicReference<String> requestBody,
        AtomicReference<String> signature,
        AtomicReference<String> timestamp,
        AtomicReference<String> nonce
    ) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        signature.set(exchange.getRequestHeaders().getFirst("X-Approval-Signature"));
        timestamp.set(exchange.getRequestHeaders().getFirst("X-Approval-Timestamp"));
        nonce.set(exchange.getRequestHeaders().getFirst("X-Approval-Nonce"));
        assertEquals("tenant-a", exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
        assertEquals("process-1-approved", exchange.getRequestHeaders().getFirst("Idempotency-Key"));
        assertEquals("approval-platform", exchange.getRequestHeaders().getFirst("X-Client"));
    }
}
