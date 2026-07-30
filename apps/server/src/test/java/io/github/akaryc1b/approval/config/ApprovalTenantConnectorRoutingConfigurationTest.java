package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.TenantConnectorRouteResolutionService;
import io.github.akaryc1b.approval.connector.ConnectorProvider;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingCatalog;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingDescriptor;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingState;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialType;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.ProviderApiFamily;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteDefinition;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteIntent;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.TransportProfile;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalTenantConnectorRoutingConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(
            ApprovalTenantConnectorRoutingConfiguration.class,
            CredentialCatalogConfiguration.class
        );

    @Test
    void routingIsDisabledByDefaultAndConstructsNoExecutionChain() {
        runner.run(context -> {
            assertFalse(context.containsBean("tenantConnectorRouteSnapshot"));
            assertFalse(context.containsBean("tenantConnectorRouteResolver"));
            assertFalse(context.containsBean("tenantConnectorRouteRevalidator"));
            assertFalse(context.containsBean("tenantConnectorRouteResolutionService"));
            assertEquals(0, context.getBeansOfType(
                TenantConnectorRouteResolutionService.class
            ).size());
        });
    }

    @Test
    void enabledRoutingWithMissingConfigurationFailsClosed() {
        runner.withPropertyValues(
            "approval.connector.tenant-routing.enabled=true"
        ).run(context -> assertTrue(context.getStartupFailure() != null));
    }

    @Test
    void malformedHashAndUnknownPropertyFailClosed() {
        Fixture fixture = fixture();
        runner.withPropertyValues(properties(fixture))
            .withPropertyValues(
                "approval.connector.tenant-routing.snapshot-hash=" + "0".repeat(64)
            )
            .run(context -> assertTrue(context.getStartupFailure() != null));

        runner.withPropertyValues(properties(fixture))
            .withPropertyValues(
                "approval.connector.tenant-routing.routes[0].endpoint=https://example.invalid"
            )
            .run(context -> assertTrue(context.getStartupFailure() != null));
    }

    @Test
    void validConfigurationConstructsResolutionOnlyBeans() {
        Fixture fixture = fixture();
        runner.withPropertyValues(properties(fixture)).run(context -> {
            assertTrue(context.getStartupFailure() == null);
            assertTrue(context.containsBean("tenantConnectorRouteSnapshot"));
            assertTrue(context.containsBean("tenantConnectorRouteResolver"));
            assertTrue(context.containsBean("tenantConnectorRouteRevalidator"));
            assertTrue(context.containsBean("tenantConnectorRouteResolutionService"));
            assertTrue(context.getBean(TenantConnectorRouteSnapshot.class).configurationValid());
            assertEquals(1, context.getBeansOfType(
                TenantConnectorRouteResolutionService.class
            ).size());
            assertEquals(0, context.getBeanNamesForAnnotation(
                org.springframework.web.bind.annotation.RestController.class
            ).length);
        });
    }

    @Test
    void enabledRoutingRequiresServerOwnedCredentialCatalog() {
        Fixture fixture = fixture();
        new ApplicationContextRunner()
            .withUserConfiguration(ApprovalTenantConnectorRoutingConfiguration.class)
            .withPropertyValues(properties(fixture))
            .run(context -> assertTrue(context.getStartupFailure() != null));
    }

    private static String[] properties(Fixture fixture) {
        RouteDefinition route = fixture.route();
        return new String[] {
            "approval.connector.tenant-routing.enabled=true",
            "approval.connector.tenant-routing.configuration-version=snapshot-v1",
            "approval.connector.tenant-routing.snapshot-hash="
                + fixture.snapshot().snapshotHash(),
            "approval.connector.tenant-routing.routes[0].tenant-id=" + route.tenantId(),
            "approval.connector.tenant-routing.routes[0].provider-key="
                + route.providerKey(),
            "approval.connector.tenant-routing.routes[0].capability="
                + route.capability().name(),
            "approval.connector.tenant-routing.routes[0].intent=" + route.intent().name(),
            "approval.connector.tenant-routing.routes[0].api-family="
                + route.apiFamily().name(),
            "approval.connector.tenant-routing.routes[0].transport-profile="
                + route.transportProfile().name(),
            "approval.connector.tenant-routing.routes[0].credential-reference="
                + route.credentialReference().referenceId(),
            "approval.connector.tenant-routing.routes[0].credential-material-type="
                + route.credentialMaterialType().name(),
            "approval.connector.tenant-routing.routes[0].route-version="
                + route.routeVersion(),
            "approval.connector.tenant-routing.routes[0].route-policy-version="
                + route.routePolicyVersion(),
            "approval.connector.tenant-routing.routes[0].credential-policy-version="
                + route.credentialPolicyVersion(),
            "approval.connector.tenant-routing.routes[0].credential-descriptor-fingerprint="
                + route.credentialDescriptorFingerprint(),
            "approval.connector.tenant-routing.routes[0].enabled=true",
            "approval.connector.tenant-routing.routes[0].valid-from=" + route.validFrom(),
            "approval.connector.tenant-routing.routes[0].valid-until=" + route.validUntil(),
            "approval.connector.tenant-routing.routes[0].definition-hash="
                + route.definitionHash()
        };
    }

    private static Fixture fixture() {
        Instant now = Instant.parse("2026-07-26T00:00:00Z");
        CredentialBindingDescriptor descriptor = CredentialCatalogConfiguration.DESCRIPTOR;
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
            now.plusSeconds(600)
        );
        TenantConnectorRouteSnapshot snapshot = TenantConnectorRouteSnapshot.create(
            "snapshot-v1",
            List.of(route)
        );
        return new Fixture(route, snapshot);
    }

    private record Fixture(
        RouteDefinition route,
        TenantConnectorRouteSnapshot snapshot
    ) {
    }

    @Configuration(proxyBeanMethods = false)
    static class CredentialCatalogConfiguration {

        private static final CredentialBindingDescriptor DESCRIPTOR = descriptor();

        @Bean
        CredentialBindingCatalog credentialBindingCatalog() {
            return reference -> Optional.of(DESCRIPTOR);
        }

        private static CredentialBindingDescriptor descriptor() {
            Instant now = Instant.parse("2026-07-26T00:00:00Z");
            return new CredentialBindingDescriptor(
                new CredentialReference("dingtalk", "credential-a"),
                "tenant-a",
                "dingtalk",
                CredentialMaterialType.ACCESS_TOKEN,
                "key-1",
                "version-1",
                CredentialBindingState.ACTIVE,
                now.minusSeconds(60),
                now.plusSeconds(3_600),
                Set.of(ConnectorOperation.ORGANIZATION_READ),
                "credential-policy-v1",
                Map.of()
            );
        }
    }
}
