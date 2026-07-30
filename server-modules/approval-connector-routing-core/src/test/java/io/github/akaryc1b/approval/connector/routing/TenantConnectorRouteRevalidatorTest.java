package io.github.akaryc1b.approval.connector.routing;

import io.github.akaryc1b.approval.connector.ConnectorProvider;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingDescriptor;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.ProviderApiFamily;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RevalidationStatus;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteDefinition;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RoutePlan;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteRevalidation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static io.github.akaryc1b.approval.connector.routing.TenantConnectorRoutingFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantConnectorRouteRevalidatorTest {

    @Test
    void exactUnchangedPlanRevalidatesWithoutDispatch() {
        Fixture fixture = fixture();
        RouteRevalidation result = fixture.revalidator().revalidate(
            "tenant-a",
            fixture.plan(),
            NOW.plusSeconds(1)
        );

        assertEquals(RevalidationStatus.VALID, result.status());
        assertTrue(result.validForDispatch());
    }

    @Test
    void crossTenantPlanRevalidationFails() {
        Fixture fixture = fixture();
        RouteRevalidation result = fixture.revalidator().revalidate(
            "tenant-b",
            fixture.plan(),
            NOW.plusSeconds(1)
        );

        assertEquals(RevalidationStatus.TENANT_MISMATCH, result.status());
        assertFalse(result.validForDispatch());
    }

    @Test
    void changedSnapshotIsStaleAndNeverSelectsReplacement() {
        Fixture fixture = fixture();
        RouteDefinition changed = TenantConnectorRoutingFixtures.route(
            "tenant-a",
            "dingtalk",
            ConnectorProvider.Capability.ORGANIZATION,
            TenantConnectorRouteContracts.RouteIntent.ORGANIZATION_READ_USER_BY_ID,
            ProviderApiFamily.OPEN_API_V1,
            fixture.descriptor(),
            true,
            "route-v2",
            NOW.minusSeconds(1),
            NOW.plusSeconds(60)
        );
        TenantConnectorRouteSnapshot changedSnapshot = TenantConnectorRouteSnapshot.create(
            "snapshot-v2",
            List.of(changed)
        );
        TenantConnectorRouteRevalidator revalidator = new TenantConnectorRouteRevalidator(
            () -> changedSnapshot,
            reference -> Optional.of(fixture.descriptor())
        );

        RouteRevalidation result = revalidator.revalidate(
            "tenant-a",
            fixture.plan(),
            NOW.plusSeconds(1)
        );

        assertEquals(RevalidationStatus.STALE, result.status());
        assertFalse(result.validForDispatch());
    }

    @Test
    void tamperedPlanHashIsRejectedBeforeRouteLookup() {
        Fixture fixture = fixture();
        RoutePlan plan = fixture.plan();
        RoutePlan tampered = new RoutePlan(
            plan.tenantEvidenceHash(),
            plan.providerKey(),
            plan.capability(),
            plan.intent(),
            plan.connectorOperation(),
            plan.apiFamily(),
            plan.transportProfile(),
            plan.credentialReferenceHash(),
            plan.credentialMaterialType(),
            plan.routeVersion(),
            plan.routePolicyVersion(),
            plan.credentialPolicyVersion(),
            plan.configurationSnapshotHash(),
            plan.routeDefinitionHash(),
            plan.credentialDescriptorFingerprint(),
            plan.requestEvidenceHash(),
            plan.businessReferenceHash(),
            plan.correlationEvidenceHash(),
            plan.createdAtEvidence(),
            "0".repeat(64)
        );

        RouteRevalidation result = fixture.revalidator().revalidate(
            "tenant-a",
            tampered,
            NOW.plusSeconds(1)
        );

        assertEquals(RevalidationStatus.INVALID_PLAN, result.status());
    }

    @Test
    void expiredRouteAndCredentialDriftRejectDispatch() {
        Fixture fixture = fixture();
        assertEquals(
            RevalidationStatus.EXPIRED,
            fixture.revalidator().revalidate(
                "tenant-a",
                fixture.plan(),
                NOW.plusSeconds(301)
            ).status()
        );

        TenantConnectorRouteRevalidator missingCredential = new TenantConnectorRouteRevalidator(
            () -> fixture.snapshot(),
            reference -> Optional.empty()
        );
        assertEquals(
            RevalidationStatus.INCOMPATIBLE,
            missingCredential.revalidate(
                "tenant-a",
                fixture.plan(),
                NOW.plusSeconds(1)
            ).status()
        );
    }

    @Test
    void sourceFailureReturnsNoDispatchAuthority() {
        Fixture fixture = fixture();
        TenantConnectorRouteRevalidator revalidator = new TenantConnectorRouteRevalidator(
            () -> {
                throw new IllegalStateException("unavailable");
            },
            reference -> Optional.of(fixture.descriptor())
        );

        RouteRevalidation result = revalidator.revalidate(
            "tenant-a",
            fixture.plan(),
            NOW
        );

        assertEquals(RevalidationStatus.SOURCE_UNAVAILABLE, result.status());
        assertFalse(result.validForDispatch());
    }

    private static Fixture fixture() {
        RouteDefinition route = TenantConnectorRoutingFixtures.organizationRoute(
            "tenant-a",
            "credential-a",
            ProviderApiFamily.OPEN_API_V1,
            true
        );
        CredentialBindingDescriptor descriptor = TenantConnectorRoutingFixtures.descriptor(
            "tenant-a",
            "credential-a",
            ConnectorOperation.ORGANIZATION_READ
        );
        TenantConnectorRouteSnapshot snapshot = TenantConnectorRouteSnapshot.create(
            "snapshot-v1",
            List.of(route)
        );
        TenantConnectorRouteResolver resolver = new TenantConnectorRouteResolver(
            () -> snapshot,
            reference -> Optional.of(descriptor)
        );
        RoutePlan plan = resolver.resolve(
            "tenant-a",
            TenantConnectorRoutingFixtures.organizationRequest("request-a"),
            NOW
        ).plan().orElseThrow();
        return new Fixture(
            snapshot,
            descriptor,
            plan,
            new TenantConnectorRouteRevalidator(
                () -> snapshot,
                reference -> Optional.of(descriptor)
            )
        );
    }

    private record Fixture(
        TenantConnectorRouteSnapshot snapshot,
        CredentialBindingDescriptor descriptor,
        RoutePlan plan,
        TenantConnectorRouteRevalidator revalidator
    ) {
    }
}
