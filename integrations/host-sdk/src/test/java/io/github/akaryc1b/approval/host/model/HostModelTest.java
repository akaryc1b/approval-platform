package io.github.akaryc1b.approval.host.model;

import io.github.akaryc1b.approval.host.web.HostErrorResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HostModelTest {

    @Test
    void authenticationResultCopiesCollections() {
        Set<String> permissions = new HashSet<>(Set.of("approval:task:complete"));
        Map<String, String> attributes = new HashMap<>(Map.of("departmentId", "100"));
        var result = new HostAuthenticationResult(
            "1",
            "tenant-a",
            "admin",
            "Administrator",
            permissions,
            Instant.parse("2026-07-17T13:00:00Z"),
            attributes
        );

        permissions.clear();
        attributes.clear();

        assertEquals(Set.of("approval:task:complete"), result.permissions());
        assertEquals(Map.of("departmentId", "100"), result.attributes());
        assertThrows(UnsupportedOperationException.class, () -> result.permissions().add("other"));
    }

    @Test
    void errorResponseKeepsStableCodeAndRetryMetadata() {
        Instant now = Instant.parse("2026-07-17T12:00:00Z");
        HostErrorResponse response = HostErrorResponse.from(
            ConnectorError.temporaryFailure("host unavailable"),
            "request-1",
            now
        );

        assertEquals("TEMPORARY_FAILURE", response.code());
        assertEquals("host unavailable", response.message());
        assertEquals(true, response.retryable());
        assertEquals("request-1", response.requestId());
        assertEquals(now, response.timestamp());
    }
}
