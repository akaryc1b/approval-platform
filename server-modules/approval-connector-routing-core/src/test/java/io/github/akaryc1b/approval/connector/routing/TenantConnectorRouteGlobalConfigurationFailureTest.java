package io.github.akaryc1b.approval.connector.routing;

import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingDescriptor;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.ProviderApiFamily;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.ResolutionStatus;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RevalidationStatus;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteDefinition;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RoutePlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static io.github.akaryc1b.approval.connector.routing.TenantConnectorRoutingFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TenantConnectorRouteGlobalConfigurationFailureTest {

    @Test
    void duplicateConfigurationAnywhereBlocksOtherwiseExactResolution() {
        CredentialBindingDescriptor descriptor = TenantConnectorRoutingFixtures.descriptor(
            "tenant-a",
            "credential-a",
            ConnectorOperation.ORGANIZATION_READ
        );
        RouteDefinition tenantA = TenantConnectorRoutingFixtures.organizationRoute(
            "tenant-a",
            "credential-a",
            ProviderApiFamily.OPEN_API_V1,
            true
        );
        RouteDefinition tenantB = TenantConnectorRoutingFixtures.organizationRoute(
            "tenant-b",
            "credential-b",
            ProviderApiFamily.OPEN_API_V1,
            true
        );
        TenantConnectorRouteSnapshot invalid = TenantConnectorRouteSnapshot.create(
            "snapshot-v1",
            List.of(tenantA, tenantB, tenantB)
        );
        TenantConnectorRouteResolver resolver = new TenantConnectorRouteResolver(
            () -> invalid,
            reference -> Optional.of(descriptor)
        );

        var result = resolver.resolve(
            "tenant-a",
            TenantConnectorRoutingFixtures.organizationRequest("request-a"),
            NOW
        );

        assertEquals(ResolutionStatus.INVALID_CONFIGURATION, result.status());
        assertFalse(result.executablePlanPresent());
    }

    @Test
    void duplicateConfigurationAnywhereInvalidatesExistingPlan() {
        CredentialBindingDescriptor descriptor = TenantConnectorRoutingFixtures.descriptor(
            "tenant-a",
            "credential-a",
            ConnectorOperation.ORGANIZATION_READ
        );
        RouteDefinition tenantA = TenantConnectorRoutingFixtures.organizationRoute(
            "tenant-a",
            "credential-a",
            ProviderApiFamily.OPEN_API_V1,
            true
        );
        TenantConnectorRouteSnapshot valid = TenantConnectorRouteSnapshot.create(
            "snapshot-v1",
            List.of(tenantA)
        );
        RoutePlan plan = new TenantConnectorRouteResolver(
            () -> valid,
            reference -> Optional.of(descriptor)
        ).resolve(
            "tenant-a",
            TenantConnectorRoutingFixtures.organizationRequest("request-a"),
            NOW
        ).plan().orElseThrow();

        RouteDefinition tenantB = TenantConnectorRoutingFixtures.organizationRoute(
            "tenant-b",
            "credential-b",
            ProviderApiFamily.OPEN_API_V1,
            true
        );
        TenantConnectorRouteSnapshot invalid = TenantConnectorRouteSnapshot.create(
            "snapshot-v2",
            List.of(tenantA, tenantB, tenantB)
        );
        TenantConnectorRouteRevalidator revalidator = new TenantConnectorRouteRevalidator(
            () -> invalid,
            reference -> Optional.of(descriptor)
        );

        var result = revalidator.revalidate("tenant-a", plan, NOW.plusSeconds(1));

        assertEquals(RevalidationStatus.INVALID_CONFIGURATION, result.status());
        assertFalse(result.validForDispatch());
    }
}
