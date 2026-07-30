package io.github.akaryc1b.approval.connector.routing;

import io.github.akaryc1b.approval.connector.ConnectorProvider;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingCatalog;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingDescriptor;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingState;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialSource;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialType;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.ProviderApiFamily;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.ResolutionStatus;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteDefinition;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RoutePlan;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteRequest;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteResolution;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.akaryc1b.approval.connector.routing.TenantConnectorRoutingFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantConnectorRouteResolverTest {

    @Test
    void resolvesOrganizationReadAcrossBothCapturedApiFamilies() {
        for (ProviderApiFamily family : ProviderApiFamily.values()) {
            RouteDefinition route = TenantConnectorRoutingFixtures.organizationRoute(
                "tenant-a",
                "credential-a",
                family,
                true
            );
            CredentialBindingDescriptor descriptor = TenantConnectorRoutingFixtures.descriptor(
                "tenant-a",
                "credential-a",
                ConnectorOperation.ORGANIZATION_READ
            );
            RouteResolution result = resolver(route, descriptor).resolve(
                "tenant-a",
                TenantConnectorRoutingFixtures.organizationRequest("request-a"),
                NOW
            );

            assertEquals(ResolutionStatus.RESOLVED, result.status());
            assertTrue(result.executablePlanPresent());
            assertEquals(family, result.plan().orElseThrow().apiFamily());
        }
    }

    @Test
    void resolvesIdentityOnlyThroughLegacyOapi() {
        RouteDefinition route = TenantConnectorRoutingFixtures.identityRoute(
            "tenant-a",
            "identity-a"
        );
        CredentialBindingDescriptor descriptor = TenantConnectorRoutingFixtures.descriptor(
            "tenant-a",
            "identity-a",
            ConnectorOperation.IDENTITY_RESOLVE
        );
        RouteRequest request = new RouteRequest(
            ConnectorProvider.Capability.AUTHENTICATION,
            TenantConnectorRouteContracts.RouteIntent.IDENTITY_RESOLVE_DINGTALK_USERID,
            null,
            "request-a"
        );

        RouteResolution result = resolver(route, descriptor).resolve("tenant-a", request, NOW);

        assertEquals(ResolutionStatus.RESOLVED, result.status());
        assertEquals(
            ProviderApiFamily.LEGACY_OAPI,
            result.plan().orElseThrow().apiFamily()
        );
    }

    @Test
    void tenantIsolationNeverFallsBackOrLeaksAnotherTenant() {
        RouteDefinition route = TenantConnectorRoutingFixtures.organizationRoute(
            "tenant-b",
            "credential-b",
            ProviderApiFamily.OPEN_API_V1,
            true
        );
        CredentialBindingDescriptor descriptor = TenantConnectorRoutingFixtures.descriptor(
            "tenant-b",
            "credential-b",
            ConnectorOperation.ORGANIZATION_READ
        );
        TenantConnectorRouteResolver resolver = resolver(route, descriptor);

        RouteResolution first = resolver.resolve(
            "tenant-a",
            TenantConnectorRoutingFixtures.organizationRequest("request-a"),
            NOW
        );
        RouteResolution second = resolver.resolve(
            "tenant-c",
            TenantConnectorRoutingFixtures.organizationRequest("request-a"),
            NOW
        );

        assertEquals(ResolutionStatus.MISSING, first.status());
        assertEquals(ResolutionStatus.MISSING, second.status());
        assertFalse(first.executablePlanPresent());
        assertFalse(first.toString().contains("tenant-b"));
        assertFalse(first.toString().contains("credential-b"));
    }

    @Test
    void disabledExpiredAndNotYetValidRoutesFailClosed() {
        CredentialBindingDescriptor descriptor = TenantConnectorRoutingFixtures.descriptor(
            "tenant-a",
            "credential-a",
            ConnectorOperation.ORGANIZATION_READ
        );
        RouteDefinition disabled = TenantConnectorRoutingFixtures.organizationRoute(
            "tenant-a",
            "credential-a",
            ProviderApiFamily.OPEN_API_V1,
            false
        );
        RouteDefinition expired = TenantConnectorRoutingFixtures.route(
            "tenant-b",
            "dingtalk",
            ConnectorProvider.Capability.ORGANIZATION,
            TenantConnectorRouteContracts.RouteIntent.ORGANIZATION_READ_USER_BY_ID,
            ProviderApiFamily.OPEN_API_V1,
            TenantConnectorRoutingFixtures.descriptor(
                "tenant-b",
                "credential-b",
                ConnectorOperation.ORGANIZATION_READ
            ),
            true,
            "route-v1",
            NOW.minusSeconds(20),
            NOW
        );
        RouteDefinition future = TenantConnectorRoutingFixtures.route(
            "tenant-c",
            "dingtalk",
            ConnectorProvider.Capability.ORGANIZATION,
            TenantConnectorRouteContracts.RouteIntent.ORGANIZATION_READ_USER_BY_ID,
            ProviderApiFamily.OPEN_API_V1,
            TenantConnectorRoutingFixtures.descriptor(
                "tenant-c",
                "credential-c",
                ConnectorOperation.ORGANIZATION_READ
            ),
            true,
            "route-v1",
            NOW.plusSeconds(1),
            NOW.plusSeconds(20)
        );

        assertEquals(
            ResolutionStatus.DISABLED,
            resolver(disabled, descriptor).resolve(
                "tenant-a",
                TenantConnectorRoutingFixtures.organizationRequest("request-a"),
                NOW
            ).status()
        );
        assertEquals(
            ResolutionStatus.EXPIRED,
            resolver(expired, TenantConnectorRoutingFixtures.descriptor(
                "tenant-b",
                "credential-b",
                ConnectorOperation.ORGANIZATION_READ
            )).resolve(
                "tenant-b",
                TenantConnectorRoutingFixtures.organizationRequest("request-b"),
                NOW
            ).status()
        );
        assertEquals(
            ResolutionStatus.NOT_YET_VALID,
            resolver(future, TenantConnectorRoutingFixtures.descriptor(
                "tenant-c",
                "credential-c",
                ConnectorOperation.ORGANIZATION_READ
            )).resolve(
                "tenant-c",
                TenantConnectorRoutingFixtures.organizationRequest("request-c"),
                NOW
            ).status()
        );
    }

    @Test
    void ambiguousRoutesNeverProduceAPlan() {
        RouteDefinition first = TenantConnectorRoutingFixtures.organizationRoute(
            "tenant-a",
            "credential-a",
            ProviderApiFamily.OPEN_API_V1,
            true
        );
        RouteDefinition second = TenantConnectorRoutingFixtures.organizationRoute(
            "tenant-a",
            "credential-b",
            ProviderApiFamily.LEGACY_OAPI,
            true
        );
        TenantConnectorRouteSnapshot snapshot = TenantConnectorRouteSnapshot.create(
            "snapshot-v1",
            List.of(first, second)
        );
        TenantConnectorRouteResolver resolver = new TenantConnectorRouteResolver(
            () -> snapshot,
            reference -> Optional.empty()
        );

        RouteResolution result = resolver.resolve(
            "tenant-a",
            TenantConnectorRoutingFixtures.organizationRequest("request-a"),
            NOW
        );

        assertEquals(ResolutionStatus.AMBIGUOUS, result.status());
        assertFalse(result.executablePlanPresent());
    }

    @Test
    void sourceSnapshotAndEvidenceFailuresDoNotCachePartialPlans() {
        RouteRequest request = TenantConnectorRoutingFixtures.organizationRequest("request-a");
        TenantConnectorRouteResolver unavailable = new TenantConnectorRouteResolver(
            () -> {
                throw new IllegalStateException("source exploded");
            },
            reference -> Optional.empty()
        );
        assertEquals(
            ResolutionStatus.SOURCE_UNAVAILABLE,
            unavailable.resolve("tenant-a", request, NOW).status()
        );

        RouteDefinition route = TenantConnectorRoutingFixtures.organizationRoute(
            "tenant-a",
            "credential-a",
            ProviderApiFamily.OPEN_API_V1,
            true
        );
        TenantConnectorRouteSnapshot invalid = new TenantConnectorRouteSnapshot(
            "snapshot-v1",
            List.of(route),
            "0".repeat(64)
        );
        assertEquals(
            ResolutionStatus.INVALID_CONFIGURATION,
            new TenantConnectorRouteResolver(
                () -> invalid,
                reference -> Optional.empty()
            ).resolve("tenant-a", request, NOW).status()
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
        TenantConnectorRouteResolver evidenceFailure = new TenantConnectorRouteResolver(
            () -> snapshot,
            reference -> Optional.of(descriptor),
            (status, tenant, requestHash, snapshotHash, definitionHash, planHash) -> {
                throw new IllegalStateException("evidence failure");
            }
        );

        RouteResolution result = evidenceFailure.resolve("tenant-a", request, NOW);
        assertEquals(ResolutionStatus.INVALID_CONFIGURATION, result.status());
        assertFalse(result.executablePlanPresent());
    }

    @Test
    void credentialProviderTenantOperationMaterialPolicyAndFingerprintMustMatch() {
        RouteDefinition route = TenantConnectorRoutingFixtures.organizationRoute(
            "tenant-a",
            "credential-a",
            ProviderApiFamily.OPEN_API_V1,
            true
        );
        CredentialBindingDescriptor valid = TenantConnectorRoutingFixtures.descriptor(
            "tenant-a",
            "credential-a",
            ConnectorOperation.ORGANIZATION_READ
        );
        assertEquals(
            ResolutionStatus.INCOMPATIBLE,
            resolver(route, null).resolve(
                "tenant-a",
                TenantConnectorRoutingFixtures.organizationRequest("request-a"),
                NOW
            ).status()
        );
        List<CredentialBindingDescriptor> invalid = List.of(
            descriptorLike(valid, "tenant-b", "dingtalk", CredentialMaterialType.ACCESS_TOKEN,
                Set.of(ConnectorOperation.ORGANIZATION_READ), "credential-policy-v1"),
            descriptorLike(valid, "tenant-a", "dingtalk", CredentialMaterialType.ACCESS_TOKEN,
                Set.of(ConnectorOperation.IDENTITY_RESOLVE), "credential-policy-v1"),
            descriptorLike(valid, "tenant-a", "dingtalk", CredentialMaterialType.APP_KEY_SECRET,
                Set.of(ConnectorOperation.ORGANIZATION_READ), "credential-policy-v1"),
            descriptorLike(valid, "tenant-a", "dingtalk", CredentialMaterialType.ACCESS_TOKEN,
                Set.of(ConnectorOperation.ORGANIZATION_READ), "credential-policy-v2")
        );

        for (CredentialBindingDescriptor descriptor : invalid) {
            RouteResolution result = resolver(route, descriptor).resolve(
                "tenant-a",
                TenantConnectorRoutingFixtures.organizationRequest("request-a"),
                NOW
            );
            assertEquals(ResolutionStatus.INCOMPATIBLE, result.status());
            assertFalse(result.executablePlanPresent());
        }
    }

    @Test
    void routeResolutionNeverOpensSecretMaterialOrCallsAnHttpSender() {
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
        CountingCatalogAndMaterialSource boundary = new CountingCatalogAndMaterialSource(descriptor);
        AtomicInteger tokenAcquisitions = new AtomicInteger();
        AtomicInteger tokenRefreshes = new AtomicInteger();
        AtomicInteger httpSenderInvocations = new AtomicInteger();
        TenantConnectorRouteResolver resolver = new TenantConnectorRouteResolver(
            () -> TenantConnectorRouteSnapshot.create("snapshot-v1", List.of(route)),
            boundary
        );

        RouteResolution result = resolver.resolve(
            "tenant-a",
            TenantConnectorRoutingFixtures.organizationRequest("request-a"),
            NOW
        );

        assertEquals(ResolutionStatus.RESOLVED, result.status());
        assertEquals(0, boundary.materialOpenCount.get());
        assertEquals(0, tokenAcquisitions.get());
        assertEquals(0, tokenRefreshes.get());
        assertEquals(0, httpSenderInvocations.get());
        for (Constructor<?> constructor : TenantConnectorRouteResolver.class.getConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertFalse(parameter.getName().toLowerCase().contains("http"));
                assertFalse(parameter.getName().contains("CredentialMaterialSource"));
            }
        }
    }

    @Test
    void routePlanIsImmutableSecretFreeAndContainsNoEndpointOrRawReference() {
        RoutePlan plan = resolvedPlan("route-v1", "credential-policy-v1", "credential-a");
        String rendered = plan.toString().toLowerCase();

        assertFalse(rendered.contains("tenant-a"));
        assertFalse(rendered.contains("credential-a"));
        assertFalse(rendered.contains("api.dingtalk.com"));
        assertFalse(rendered.contains("oapi.dingtalk.com"));
        assertFalse(rendered.contains("/topapi"));
        assertFalse(rendered.contains("super-secret"));
        assertTrue(plan.getClass().isRecord());
        assertTrue(java.util.Arrays.stream(plan.getClass().getDeclaredFields())
            .allMatch(field -> Modifier.isFinal(field.getModifiers())));
    }

    @Test
    void equivalentInputsHaveStablePlanHashAndGovernedChangesAlterIt() {
        RoutePlan first = resolvedPlan("route-v1", "credential-policy-v1", "credential-a");
        RoutePlan equivalent = resolvedPlan("route-v1", "credential-policy-v1", "credential-a");
        RoutePlan changedRoute = resolvedPlan("route-v2", "credential-policy-v1", "credential-a");
        RoutePlan changedPolicy = resolvedPlan("route-v1", "credential-policy-v2", "credential-a");
        RoutePlan changedCredential = resolvedPlan(
            "route-v1",
            "credential-policy-v1",
            "credential-b"
        );
        RoutePlan changedProvider = new RoutePlan(
            first.tenantEvidenceHash(),
            "dingtalk-alt",
            first.capability(),
            first.intent(),
            first.connectorOperation(),
            first.apiFamily(),
            first.transportProfile(),
            first.credentialReferenceHash(),
            first.credentialMaterialType(),
            first.routeVersion(),
            first.routePolicyVersion(),
            first.credentialPolicyVersion(),
            first.configurationSnapshotHash(),
            first.routeDefinitionHash(),
            first.credentialDescriptorFingerprint(),
            first.requestEvidenceHash(),
            first.businessReferenceHash(),
            first.correlationEvidenceHash(),
            first.createdAtEvidence(),
            "0".repeat(64)
        );

        assertEquals(first.planHash(), equivalent.planHash());
        assertNotEquals(first.planHash(), changedRoute.planHash());
        assertNotEquals(first.planHash(), changedPolicy.planHash());
        assertNotEquals(first.planHash(), changedCredential.planHash());
        assertNotEquals(first.computedPlanHash(), changedProvider.computedPlanHash());
    }

    @Test
    void requestCannotCarryTenantProviderApiFamilyOrCredentialOverrides() {
        Set<String> components = java.util.Arrays.stream(RouteRequest.class.getRecordComponents())
            .map(component -> component.getName().toLowerCase())
            .collect(java.util.stream.Collectors.toSet());

        for (String forbidden : List.of(
            "tenant", "provider", "host", "endpoint", "path", "method", "apifamily",
            "credential", "secret", "token", "appkey", "appsecret", "permission",
            "operator", "fallback"
        )) {
            assertTrue(
                components.stream().noneMatch(name -> name.contains(forbidden)),
                "request exposes forbidden authority " + forbidden
            );
        }
        assertThrows(IllegalArgumentException.class, () -> new RouteRequest(
            ConnectorProvider.Capability.ORGANIZATION,
            TenantConnectorRouteContracts.RouteIntent.IDENTITY_RESOLVE_DINGTALK_USERID,
            null,
            "request-a"
        ));
    }

    private static TenantConnectorRouteResolver resolver(
        RouteDefinition route,
        CredentialBindingDescriptor descriptor
    ) {
        return new TenantConnectorRouteResolver(
            () -> TenantConnectorRouteSnapshot.create("snapshot-v1", List.of(route)),
            reference -> Optional.ofNullable(descriptor)
        );
    }

    private static RoutePlan resolvedPlan(
        String routeVersion,
        String credentialPolicyVersion,
        String referenceId
    ) {
        CredentialBindingDescriptor descriptor = descriptorLike(
            TenantConnectorRoutingFixtures.descriptor(
                "tenant-a",
                referenceId,
                ConnectorOperation.ORGANIZATION_READ
            ),
            "tenant-a",
            "dingtalk",
            CredentialMaterialType.ACCESS_TOKEN,
            Set.of(ConnectorOperation.ORGANIZATION_READ),
            credentialPolicyVersion
        );
        RouteDefinition route = TenantConnectorRoutingFixtures.route(
            "tenant-a",
            "dingtalk",
            ConnectorProvider.Capability.ORGANIZATION,
            TenantConnectorRouteContracts.RouteIntent.ORGANIZATION_READ_USER_BY_ID,
            ProviderApiFamily.OPEN_API_V1,
            descriptor,
            true,
            routeVersion,
            NOW.minusSeconds(1),
            NOW.plusSeconds(60)
        );
        return resolver(route, descriptor).resolve(
            "tenant-a",
            TenantConnectorRoutingFixtures.organizationRequest("request-a"),
            NOW
        ).plan().orElseThrow();
    }

    private static CredentialBindingDescriptor descriptorLike(
        CredentialBindingDescriptor base,
        String tenantId,
        String providerKey,
        CredentialMaterialType materialType,
        Set<ConnectorOperation> operations,
        String policyVersion
    ) {
        CredentialReference reference = providerKey.equals(base.reference().providerKey())
            ? base.reference()
            : new CredentialReference(providerKey, base.reference().referenceId());
        return new CredentialBindingDescriptor(
            reference,
            tenantId,
            providerKey,
            materialType,
            base.keyId(),
            base.versionId(),
            CredentialBindingState.ACTIVE,
            base.notBefore(),
            base.expiresAt(),
            operations,
            policyVersion,
            Map.of()
        );
    }

    private static final class CountingCatalogAndMaterialSource
        implements CredentialBindingCatalog, CredentialMaterialSource {

        private final CredentialBindingDescriptor descriptor;
        private final AtomicInteger materialOpenCount = new AtomicInteger();

        private CountingCatalogAndMaterialSource(CredentialBindingDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public Optional<CredentialBindingDescriptor> find(CredentialReference reference) {
            return Optional.of(descriptor);
        }

        @Override
        public MaterialScope openMaterial(
            CredentialReference reference,
            String expectedKeyId,
            String expectedVersionId
        ) {
            materialOpenCount.incrementAndGet();
            throw new AssertionError("route resolution must never open credential material");
        }
    }
}
