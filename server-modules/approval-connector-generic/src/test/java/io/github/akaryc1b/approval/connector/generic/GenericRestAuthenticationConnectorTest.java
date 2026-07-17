package io.github.akaryc1b.approval.connector.generic;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.port.AuthenticationConnector.AuthenticationRequest;
import io.github.akaryc1b.approval.integration.webhook.HmacSha256WebhookSigner;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericRestAuthenticationConnectorTest {

    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef"
        .getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");

    @Test
    void authenticatesAndMapsImmutableSnapshots() {
        var httpClient = new StubHttpClient(200, authenticationResponse());
        var connector = new GenericRestAuthenticationConnector(transport(httpClient));
        var context = context();

        var result = connector.authenticate(
            context,
            new AuthenticationRequest(
                "bearer",
                "host-token-value",
                Map.of("device", "web")
            )
        );

        assertEquals("ruoyi5:user:10086", result.principal().id().canonicalValue());
        assertEquals("Alice", result.principal().displayName());
        assertEquals("ruoyi5:tenant:000000", result.tenant().id().canonicalValue());
        assertEquals(Instant.parse("2026-07-17T13:00:00Z"), result.expiresAt());
        assertTrue(result.permissions().contains("approval:task:complete"));
        assertEquals(
            GenericRestAuthenticationConnector.PATH,
            httpClient.capturedRequest().uri().getPath()
        );
        assertEquals(
            GenericRestAuthenticationConnector.OPERATION,
            header(httpClient, "X-Approval-Operation")
        );
        assertTrue(httpClient.capturedBody().contains("host-token-value"));
        assertTrue(new HmacSha256WebhookSigner().verify(
            SECRET,
            Long.parseLong(header(httpClient, "X-Approval-Timestamp")),
            header(httpClient, "X-Approval-Nonce"),
            httpClient.capturedBody(),
            header(httpClient, "X-Approval-Signature")
        ));
    }

    private static GenericRestTransport transport(StubHttpClient httpClient) {
        var endpoint = new GenericRestHostEndpoint(
            URI.create("https://host.example.test"),
            "host-key",
            SECRET,
            Duration.ofSeconds(5),
            Map.of()
        );
        return new GenericRestTransport(
            httpClient,
            context -> endpoint,
            new HmacSha256WebhookSigner(),
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> "nonce-1"
        );
    }

    private static ConnectorContext context() {
        return new ConnectorContext("generic-rest", "tenant-a", "request-1", "trace-1", NOW);
    }

    private static String header(StubHttpClient httpClient, String name) {
        return httpClient.capturedRequest().headers().firstValue(name).orElseThrow();
    }

    private static String authenticationResponse() {
        return """
            {
              "data": {
                "principal": {
                  "id": {"source":"ruoyi5","objectType":"user","value":"10086"},
                  "username": "alice",
                  "displayName": "Alice",
                  "email": "alice@example.test",
                  "mobile": "13800000000",
                  "active": true,
                  "departmentIds": [
                    {"source":"ruoyi5","objectType":"department","value":"2001"}
                  ],
                  "roleCodes": ["finance"],
                  "positionCodes": ["accountant"],
                  "managerId": {"source":"ruoyi5","objectType":"user","value":"10001"},
                  "attributes": {"userType":"pc"}
                },
                "tenant": {
                  "id": {"source":"ruoyi5","objectType":"tenant","value":"000000"},
                  "name": "Default Tenant",
                  "active": true,
                  "attributes": {}
                },
                "permissions": ["approval:task:complete"],
                "expiresAt": "2026-07-17T13:00:00Z",
                "attributes": {"sessionId":"session-1"}
              }
            }
            """;
    }
}
