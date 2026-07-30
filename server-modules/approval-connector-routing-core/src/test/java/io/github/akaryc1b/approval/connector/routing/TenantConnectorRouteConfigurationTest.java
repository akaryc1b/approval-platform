package io.github.akaryc1b.approval.connector.routing;

import io.github.akaryc1b.approval.connector.ConnectorProvider;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingDescriptor;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialType;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.ProviderApiFamily;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteDefinition;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteIntent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.github.akaryc1b.approval.connector.routing.TenantConnectorRoutingFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantConnectorRouteConfigurationTest {

    @Test
    void acceptsOneLegalDingTalkOrganizationRoute() {
        RouteDefinition route = TenantConnectorRoutingFixtures.organizationRoute(
            "tenant-a",
            "credential-a",
            ProviderApiFamily.OPEN_API_V1,
            true
        );
        TenantConnectorRouteSnapshot snapshot = TenantConnectorRouteSnapshot.create(
            "snapshot-v1",
            List.of(route)
        );

        assertTrue(route.hashMatches());
        assertTrue(route.supportedByP4());
        assertTrue(snapshot.configurationValid());
        assertEquals(1, snapshot.exactCandidates(
            "tenant-a",
            ConnectorProvider.Capability.ORGANIZATION,
            RouteIntent.ORGANIZATION_READ_USER_BY_ID
        ).size());
    }

    @Test
    void canonicalOrderingProducesStableSnapshotHash() {
        RouteDefinition first = TenantConnectorRoutingFixtures.organizationRoute(
            "tenant-b",
            "credential-b",
            ProviderApiFamily.LEGACY_OAPI,
            true
        );
        RouteDefinition second = TenantConnectorRoutingFixtures.identityRoute(
            "tenant-a",
            "credential-a"
        );

        TenantConnectorRouteSnapshot left = TenantConnectorRouteSnapshot.create(
            "snapshot-v1",
            List.of(first, second)
        );
        TenantConnectorRouteSnapshot right = TenantConnectorRouteSnapshot.create(
            "snapshot-v1",
            List.of(second, first)
        );

        assertEquals(left.snapshotHash(), right.snapshotHash());
        assertEquals(left.definitions(), right.definitions());
    }

    @Test
    void equivalentDefinitionsHaveStableHashes() {
        RouteDefinition left = TenantConnectorRoutingFixtures.organizationRoute(
            "tenant-a",
            "credential-a",
            ProviderApiFamily.LEGACY_OAPI,
            true
        );
        RouteDefinition right = TenantConnectorRoutingFixtures.organizationRoute(
            "tenant-a",
            "credential-a",
            ProviderApiFamily.LEGACY_OAPI,
            true
        );

        assertEquals(left.definitionHash(), right.definitionHash());
    }

    @Test
    void duplicateDefinitionAndDuplicateKeyAreRejectedAtStartup() {
        RouteDefinition route = TenantConnectorRoutingFixtures.organizationRoute(
            "tenant-a",
            "credential-a",
            ProviderApiFamily.OPEN_API_V1,
            true
        );
        TenantConnectorRouteSnapshot snapshot = TenantConnectorRouteSnapshot.create(
            "snapshot-v1",
            List.of(route, route)
        );

        assertTrue(snapshot.integrityValid());
        assertTrue(snapshot.configurationIssues().contains("duplicate_definition"));
        assertTrue(snapshot.configurationIssues().contains(
            "duplicate_tenant_capability_operation"
        ));
        assertThrows(IllegalArgumentException.class, snapshot::requireValidConfiguration);
    }

    @Test
    void tamperedDefinitionAndSnapshotHashesFailClosed() {
        RouteDefinition valid = TenantConnectorRoutingFixtures.organizationRoute(
            "tenant-a",
            "credential-a",
            ProviderApiFamily.OPEN_API_V1,
            true
        );
        RouteDefinition tampered = new RouteDefinition(
            valid.tenantId(),
            valid.providerKey(),
            valid.capability(),
            valid.intent(),
            valid.apiFamily(),
            valid.transportProfile(),
            valid.credentialReference(),
            valid.credentialMaterialType(),
            valid.routeVersion(),
            valid.routePolicyVersion(),
            valid.credentialPolicyVersion(),
            valid.credentialDescriptorFingerprint(),
            valid.enabled(),
            valid.validFrom(),
            valid.validUntil(),
            "0".repeat(64)
        );
        TenantConnectorRouteSnapshot snapshot = new TenantConnectorRouteSnapshot(
            "snapshot-v1",
            List.of(tampered),
            "1".repeat(64)
        );

        assertFalse(snapshot.integrityValid());
        assertTrue(snapshot.integrityIssues().contains("definition_hash_mismatch"));
        assertTrue(snapshot.integrityIssues().contains("snapshot_hash_mismatch"));
    }

    @Test
    void wildcardAndCatchAllIdentifiersAreRejected() {
        CredentialBindingDescriptor descriptor = TenantConnectorRoutingFixtures.descriptor(
            "tenant-a",
            "credential-a",
            ConnectorOperation.ORGANIZATION_READ
        );

        assertThrows(IllegalArgumentException.class, () ->
            TenantConnectorRoutingFixtures.route(
                "*",
                "dingtalk",
                ConnectorProvider.Capability.ORGANIZATION,
                RouteIntent.ORGANIZATION_READ_USER_BY_ID,
                ProviderApiFamily.OPEN_API_V1,
                descriptor,
                true,
                "route-v1",
                NOW,
                NOW.plusSeconds(10)
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            TenantConnectorRoutingFixtures.route(
                "default",
                "dingtalk",
                ConnectorProvider.Capability.ORGANIZATION,
                RouteIntent.ORGANIZATION_READ_USER_BY_ID,
                ProviderApiFamily.OPEN_API_V1,
                descriptor,
                true,
                "route-v1",
                NOW,
                NOW.plusSeconds(10)
            )
        );
    }

    @Test
    void missingCredentialMalformedVersionHashAndValidityAreRejected() {
        CredentialBindingDescriptor descriptor = TenantConnectorRoutingFixtures.descriptor(
            "tenant-a",
            "credential-a",
            ConnectorOperation.ORGANIZATION_READ
        );
        assertThrows(IllegalArgumentException.class, () ->
            new io.github.akaryc1b.approval.connector.contract.CredentialReference(
                "dingtalk",
                " "
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            TenantConnectorRoutingFixtures.route(
                "tenant-a",
                "dingtalk",
                ConnectorProvider.Capability.ORGANIZATION,
                RouteIntent.ORGANIZATION_READ_USER_BY_ID,
                ProviderApiFamily.OPEN_API_V1,
                descriptor,
                true,
                "route version with spaces",
                NOW,
                NOW.plusSeconds(10)
            )
        );
        RouteDefinition valid = TenantConnectorRoutingFixtures.organizationRoute(
            "tenant-a",
            "credential-a",
            ProviderApiFamily.OPEN_API_V1,
            true
        );
        assertThrows(IllegalArgumentException.class, () -> new RouteDefinition(
            valid.tenantId(),
            valid.providerKey(),
            valid.capability(),
            valid.intent(),
            valid.apiFamily(),
            valid.transportProfile(),
            valid.credentialReference(),
            valid.credentialMaterialType(),
            valid.routeVersion(),
            valid.routePolicyVersion(),
            valid.credentialPolicyVersion(),
            "not-a-hash",
            true,
            NOW,
            NOW.plusSeconds(10),
            valid.definitionHash()
        ));
        assertThrows(IllegalArgumentException.class, () ->
            TenantConnectorRoutingFixtures.route(
                "tenant-a",
                "dingtalk",
                ConnectorProvider.Capability.ORGANIZATION,
                RouteIntent.ORGANIZATION_READ_USER_BY_ID,
                ProviderApiFamily.OPEN_API_V1,
                descriptor,
                true,
                "route-v1",
                NOW,
                NOW
            )
        );
    }

    @Test
    void unsupportedProviderMaterialAndApiMatrixAreInvalidConfiguration() {
        CredentialBindingDescriptor descriptor = TenantConnectorRoutingFixtures.descriptor(
            "tenant-a",
            "credential-a",
            ConnectorOperation.IDENTITY_RESOLVE
        );
        RouteDefinition wrongProvider = TenantConnectorRoutingFixtures.route(
            "tenant-a",
            "feishu",
            ConnectorProvider.Capability.AUTHENTICATION,
            RouteIntent.IDENTITY_RESOLVE_DINGTALK_USERID,
            ProviderApiFamily.LEGACY_OAPI,
            new CredentialBindingDescriptor(
                new io.github.akaryc1b.approval.connector.contract.CredentialReference(
                    "feishu",
                    "credential-a"
                ),
                "tenant-a",
                "feishu",
                CredentialMaterialType.ACCESS_TOKEN,
                descriptor.keyId(),
                descriptor.versionId(),
                descriptor.state(),
                descriptor.notBefore(),
                descriptor.expiresAt(),
                descriptor.allowedOperations(),
                descriptor.policyVersion(),
                descriptor.metadata()
            ),
            true,
            "route-v1",
            NOW.minusSeconds(1),
            NOW.plusSeconds(10)
        );
        RouteDefinition wrongFamily = TenantConnectorRoutingFixtures.route(
            "tenant-b",
            "dingtalk",
            ConnectorProvider.Capability.AUTHENTICATION,
            RouteIntent.IDENTITY_RESOLVE_DINGTALK_USERID,
            ProviderApiFamily.OPEN_API_V1,
            TenantConnectorRoutingFixtures.descriptor(
                "tenant-b",
                "credential-b",
                ConnectorOperation.IDENTITY_RESOLVE
            ),
            true,
            "route-v1",
            NOW.minusSeconds(1),
            NOW.plusSeconds(10)
        );

        TenantConnectorRouteSnapshot snapshot = TenantConnectorRouteSnapshot.create(
            "snapshot-v1",
            List.of(wrongProvider, wrongFamily)
        );
        assertTrue(snapshot.configurationIssues().contains("unsupported_route"));
    }

    @Test
    void closedOperationAndApiEnumsRejectUserSearchAndUnknownFamilies() {
        assertThrows(IllegalArgumentException.class, () -> RouteIntent.valueOf("USER_SEARCH"));
        assertThrows(
            IllegalArgumentException.class,
            () -> ProviderApiFamily.valueOf("ARBITRARY_API")
        );
    }

    @Test
    void excessiveRouteCountAndCanonicalSizeAreBounded() {
        List<RouteDefinition> routes = new ArrayList<>();
        for (int index = 0; index <= TenantConnectorRouteSnapshot.MAX_ROUTES; index++) {
            routes.add(TenantConnectorRoutingFixtures.organizationRoute(
                "tenant-" + index,
                "credential-" + index,
                ProviderApiFamily.OPEN_API_V1,
                true
            ));
        }
        TenantConnectorRouteSnapshot snapshot = TenantConnectorRouteSnapshot.create(
            "snapshot-v1",
            routes
        );

        assertTrue(snapshot.integrityIssues().contains("route_count_exceeded"));
        assertTrue(snapshot.integrityIssues().contains("canonical_size_exceeded"));
    }

    @Test
    void perTenantRouteCountIsBounded() {
        RouteDefinition route = TenantConnectorRoutingFixtures.organizationRoute(
            "tenant-a",
            "credential-a",
            ProviderApiFamily.OPEN_API_V1,
            true
        );
        List<RouteDefinition> routes = Collections.nCopies(
            TenantConnectorRouteSnapshot.MAX_ROUTES_PER_TENANT + 1,
            route
        );
        TenantConnectorRouteSnapshot snapshot = TenantConnectorRouteSnapshot.create(
            "snapshot-v1",
            routes
        );

        assertTrue(snapshot.configurationIssues().contains("tenant_route_count_exceeded"));
    }

    @Test
    void exactIndexScalesAcrossOneThousandTenantsWithoutFallback() {
        List<RouteDefinition> routes = new ArrayList<>(2_000);
        for (int index = 0; index < 1_000; index++) {
            String tenant = "tenant-" + index;
            routes.add(TenantConnectorRoutingFixtures.organizationRoute(
                tenant,
                "org-credential-" + index,
                ProviderApiFamily.OPEN_API_V1,
                true
            ));
            routes.add(TenantConnectorRoutingFixtures.identityRoute(
                tenant,
                "identity-credential-" + index
            ));
        }
        TenantConnectorRouteSnapshot snapshot = TenantConnectorRouteSnapshot.create(
            "snapshot-v1",
            routes
        );

        assertTrue(snapshot.configurationValid());
        assertEquals(1, snapshot.exactCandidates(
            "tenant-731",
            ConnectorProvider.Capability.ORGANIZATION,
            RouteIntent.ORGANIZATION_READ_USER_BY_ID
        ).size());
        assertTrue(snapshot.exactCandidates(
            "tenant-missing",
            ConnectorProvider.Capability.ORGANIZATION,
            RouteIntent.ORGANIZATION_READ_USER_BY_ID
        ).isEmpty());
    }
}
