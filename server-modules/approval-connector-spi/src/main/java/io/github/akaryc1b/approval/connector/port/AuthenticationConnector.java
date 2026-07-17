package io.github.akaryc1b.approval.connector.port;

import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.model.TenantSnapshot;
import io.github.akaryc1b.approval.connector.model.UserSnapshot;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public interface AuthenticationConnector {

    AuthenticationResult authenticate(ConnectorContext context, AuthenticationRequest request);

    record AuthenticationRequest(
        String credentialType,
        String credential,
        Map<String, String> attributes
    ) {
        public AuthenticationRequest {
            credentialType = requireText(credentialType, "credentialType");
            credential = requireText(credential, "credential");
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    record AuthenticationResult(
        UserSnapshot principal,
        TenantSnapshot tenant,
        Set<String> permissions,
        Instant expiresAt,
        Map<String, String> attributes
    ) {
        public AuthenticationResult {
            principal = Objects.requireNonNull(principal, "principal must not be null");
            tenant = Objects.requireNonNull(tenant, "tenant must not be null");
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
