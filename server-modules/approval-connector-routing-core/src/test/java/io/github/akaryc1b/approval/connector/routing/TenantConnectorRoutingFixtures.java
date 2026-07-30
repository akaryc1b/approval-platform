package io.github.akaryc1b.approval.connector.routing;

import io.github.akaryc1b.approval.connector.ConnectorProvider;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingDescriptor;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingState;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialType;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.ProviderApiFamily;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteDefinition;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteIntent;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteRequest;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.TransportProfile;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

final class TenantConnectorRoutingFixtures {

    static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");

    private TenantConnectorRoutingFixtures() {
    }

    static CredentialBindingDescriptor descriptor(
        String tenantId,
        String referenceId,
        ConnectorOperation operation
    ) {
        return new CredentialBindingDescriptor(
            new CredentialReference("dingtalk", referenceId),
            tenantId,
            "dingtalk",
            CredentialMaterialType.ACCESS_TOKEN,
            "key-1",
            "version-1",
            CredentialBindingState.ACTIVE,
            NOW.minusSeconds(60),
            NOW.plusSeconds(3_600),
            Set.of(operation),
            "credential-policy-v1",
            Map.of()
        );
    }

    static RouteDefinition organizationRoute(
        String tenantId,
        String referenceId,
        ProviderApiFamily family,
        boolean enabled
    ) {
        CredentialBindingDescriptor descriptor = descriptor(
            tenantId,
            referenceId,
            ConnectorOperation.ORGANIZATION_READ
        );
        return route(
            tenantId,
            "dingtalk",
            ConnectorProvider.Capability.ORGANIZATION,
            RouteIntent.ORGANIZATION_READ_USER_BY_ID,
            family,
            descriptor,
            enabled,
            "route-v1",
            NOW.minusSeconds(30),
            NOW.plusSeconds(300)
        );
    }

    static RouteDefinition identityRoute(String tenantId, String referenceId) {
        CredentialBindingDescriptor descriptor = descriptor(
            tenantId,
            referenceId,
            ConnectorOperation.IDENTITY_RESOLVE
        );
        return route(
            tenantId,
            "dingtalk",
            ConnectorProvider.Capability.AUTHENTICATION,
            RouteIntent.IDENTITY_RESOLVE_DINGTALK_USERID,
            ProviderApiFamily.LEGACY_OAPI,
            descriptor,
            true,
            "route-v1",
            NOW.minusSeconds(30),
            NOW.plusSeconds(300)
        );
    }

    static RouteDefinition route(
        String tenantId,
        String providerKey,
        ConnectorProvider.Capability capability,
        RouteIntent intent,
        ProviderApiFamily family,
        CredentialBindingDescriptor descriptor,
        boolean enabled,
        String routeVersion,
        Instant validFrom,
        Instant validUntil
    ) {
        return RouteDefinition.create(
            tenantId,
            providerKey,
            capability,
            intent,
            family,
            TransportProfile.DINGTALK_JAVA21_FIXED_HTTPS_V1,
            descriptor.reference(),
            descriptor.credentialType(),
            routeVersion,
            "route-policy-v1",
            descriptor.policyVersion(),
            descriptor.fingerprint(),
            enabled,
            validFrom,
            validUntil
        );
    }

    static RouteRequest organizationRequest(String correlation) {
        return new RouteRequest(
            ConnectorProvider.Capability.ORGANIZATION,
            RouteIntent.ORGANIZATION_READ_USER_BY_ID,
            "business-1",
            correlation
        );
    }
}
