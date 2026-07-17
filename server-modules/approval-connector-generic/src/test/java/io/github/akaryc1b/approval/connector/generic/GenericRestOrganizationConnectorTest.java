package io.github.akaryc1b.approval.connector.generic;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.model.ExternalId;
import io.github.akaryc1b.approval.connector.model.PageRequest;
import io.github.akaryc1b.approval.connector.port.OrganizationConnector.UserQuery;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericRestOrganizationConnectorTest {

    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef"
        .getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");

    @Test
    void searchesUsersAndMapsPage() {
        var httpClient = new StubHttpClient(200, userPageResponse());
        var connector = new GenericRestOrganizationConnector(transport(httpClient));

        var result = connector.searchUsers(
            context(),
            new UserQuery(
                "ali",
                new ExternalId("ruoyi5", "department", "2001"),
                "finance",
                null,
                true
            ),
            PageRequest.first(20)
        );

        assertEquals(1, result.items().size());
        assertEquals(1, result.total());
        assertEquals("cursor-2", result.nextCursor());
        assertEquals("Alice", result.items().getFirst().displayName());
        assertTrue(httpClient.capturedBody().contains("departmentId"));
        assertEquals(
            "organization.users.search.v1",
            httpClient.capturedRequest().headers()
                .firstValue("X-Approval-Operation")
                .orElseThrow()
        );
    }

    @Test
    void findReturnsEmptyForNotFound() {
        var connector = new GenericRestOrganizationConnector(
            transport(new StubHttpClient(404, "{\"error\":\"not found\"}"))
        );

        var result = connector.findUser(
            context(),
            new ExternalId("ruoyi5", "user", "missing")
        );

        assertFalse(result.isPresent());
    }

    @Test
    void serviceUnavailableIsRetryable() {
        var connector = new GenericRestOrganizationConnector(
            transport(new StubHttpClient(503, "{\"error\":\"unavailable\"}"))
        );

        var exception = assertThrows(
            GenericRestConnectorException.class,
            () -> connector.resolveRoleMembers(context(), "finance")
        );

        assertEquals(503, exception.statusCode());
        assertTrue(exception.retryable());
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

    private static String userPageResponse() {
        return """
            {
              "data": {
                "items": [
                  {
                    "id": {"source":"ruoyi5","objectType":"user","value":"10086"},
                    "username": "alice",
                    "displayName": "Alice",
                    "email": null,
                    "mobile": null,
                    "active": true,
                    "departmentIds": [
                      {"source":"ruoyi5","objectType":"department","value":"2001"}
                    ],
                    "roleCodes": ["finance"],
                    "positionCodes": [],
                    "managerId": null,
                    "attributes": {}
                  }
                ],
                "nextCursor": "cursor-2",
                "total": 1
              }
            }
            """;
    }
}
