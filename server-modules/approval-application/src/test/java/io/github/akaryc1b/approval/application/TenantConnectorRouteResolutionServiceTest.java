package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.connector.ConnectorProvider;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingDescriptor;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingState;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialType;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.ProviderApiFamily;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.ResolutionStatus;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteDefinition;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteIntent;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteRequest;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.TransportProfile;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteResolver;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteRevalidator;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteSnapshot;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TenantConnectorRouteResolutionServiceTest {

    @Test
    void trustedRequestContextIsTheOnlyTenantAuthority() {
        Instant now = Instant.parse("2026-07-26T00:00:00Z");
        CredentialBindingDescriptor descriptor = new CredentialBindingDescriptor(
            new CredentialReference("dingtalk", "credential-a"),
            "tenant-a",
            "dingtalk",
            CredentialMaterialType.ACCESS_TOKEN,
            "key-1",
            "version-1",
            CredentialBindingState.ACTIVE,
            now.minusSeconds(10),
            now.plusSeconds(60),
            Set.of(ConnectorOperation.ORGANIZATION_READ),
            "credential-policy-v1",
            Map.of()
        );
        RouteDefinition route = RouteDefinition.create(
            "tenant-a",
            "dingtalk",
            ConnectorProvider.Capability.ORGANIZATION,
            RouteIntent.ORGANIZATION_READ_USER_BY_ID,
            ProviderApiFamily.OPEN_API_V1,
            TransportProfile.DINGTALK_JAVA21_FIXED_HTTPS_V1,
            descriptor.reference(),
            descriptor.credentialType(),
            "route-v1",
            "route-policy-v1",
            descriptor.policyVersion(),
            descriptor.fingerprint(),
            true,
            now.minusSeconds(10),
            now.plusSeconds(60)
        );
        TenantConnectorRouteSnapshot snapshot = TenantConnectorRouteSnapshot.create(
            "snapshot-v1",
            List.of(route)
        );
        TenantConnectorRouteResolver resolver = new TenantConnectorRouteResolver(
            () -> snapshot,
            reference -> Optional.of(descriptor)
        );
        TenantConnectorRouteResolutionService service = new TenantConnectorRouteResolutionService(
            resolver,
            new TenantConnectorRouteRevalidator(
                () -> snapshot,
                reference -> Optional.of(descriptor)
            )
        );
        RouteRequest request = new RouteRequest(
            ConnectorProvider.Capability.ORGANIZATION,
            RouteIntent.ORGANIZATION_READ_USER_BY_ID,
            null,
            "request-a"
        );

        assertEquals(
            ResolutionStatus.RESOLVED,
            service.resolve(context("tenant-a"), request, now).status()
        );
        assertEquals(
            ResolutionStatus.MISSING,
            service.resolve(context("tenant-b"), request, now).status()
        );
    }

    private static RequestContext context(String tenantId) {
        return new RequestContext(
            tenantId,
            "operator-a",
            "request-a",
            "idempotency-a",
            "trace-a"
        );
    }
}
