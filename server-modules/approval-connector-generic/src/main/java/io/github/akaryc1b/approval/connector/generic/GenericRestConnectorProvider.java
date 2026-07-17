package io.github.akaryc1b.approval.connector.generic;

import io.github.akaryc1b.approval.connector.ConnectorProvider;
import io.github.akaryc1b.approval.connector.port.AuthenticationConnector;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector;
import io.github.akaryc1b.approval.connector.port.OrganizationConnector;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class GenericRestConnectorProvider implements ConnectorProvider {

    private final AuthenticationConnector authenticationConnector;
    private final OrganizationConnector organizationConnector;
    private final BusinessCallbackConnector businessCallbackConnector;
    private final ConnectorDescriptor descriptor;

    public GenericRestConnectorProvider(
        AuthenticationConnector authenticationConnector,
        OrganizationConnector organizationConnector,
        BusinessCallbackConnector businessCallbackConnector
    ) {
        this.authenticationConnector = Objects.requireNonNull(
            authenticationConnector,
            "authenticationConnector must not be null"
        );
        this.organizationConnector = Objects.requireNonNull(
            organizationConnector,
            "organizationConnector must not be null"
        );
        this.businessCallbackConnector = Objects.requireNonNull(
            businessCallbackConnector,
            "businessCallbackConnector must not be null"
        );
        this.descriptor = new ConnectorDescriptor(
            "generic-rest",
            "Generic REST Connector",
            "1.0",
            Set.of(
                Capability.AUTHENTICATION,
                Capability.ORGANIZATION,
                Capability.BUSINESS_CALLBACK
            ),
            Map.of(
                "transport", "signed-json",
                "signature", "hmac-sha256-v1"
            )
        );
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public Optional<AuthenticationConnector> authentication() {
        return Optional.of(authenticationConnector);
    }

    @Override
    public Optional<OrganizationConnector> organization() {
        return Optional.of(organizationConnector);
    }

    @Override
    public Optional<BusinessCallbackConnector> businessCallbacks() {
        return Optional.of(businessCallbackConnector);
    }
}
