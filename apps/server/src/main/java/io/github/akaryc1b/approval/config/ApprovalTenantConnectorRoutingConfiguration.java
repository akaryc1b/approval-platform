package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.TenantConnectorRouteResolutionService;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingCatalog;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteConfigurationSource;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteResolver;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteRevalidator;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default-disabled wiring for route resolution only. No transport execution chain is constructed.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApprovalTenantConnectorRoutingProperties.class)
public class ApprovalTenantConnectorRoutingConfiguration {

    private static final String PREFIX = "approval.connector.tenant-routing";

    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = "enabled", havingValue = "true")
    TenantConnectorRouteSnapshot tenantConnectorRouteSnapshot(
        ApprovalTenantConnectorRoutingProperties properties
    ) {
        return properties.toSnapshot();
    }

    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = "enabled", havingValue = "true")
    TenantConnectorRouteConfigurationSource tenantConnectorRouteConfigurationSource(
        TenantConnectorRouteSnapshot snapshot
    ) {
        return () -> snapshot;
    }

    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = "enabled", havingValue = "true")
    TenantConnectorRouteResolver tenantConnectorRouteResolver(
        TenantConnectorRouteConfigurationSource source,
        CredentialBindingCatalog credentialBindingCatalog
    ) {
        return new TenantConnectorRouteResolver(source, credentialBindingCatalog);
    }

    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = "enabled", havingValue = "true")
    TenantConnectorRouteRevalidator tenantConnectorRouteRevalidator(
        TenantConnectorRouteConfigurationSource source,
        CredentialBindingCatalog credentialBindingCatalog
    ) {
        return new TenantConnectorRouteRevalidator(source, credentialBindingCatalog);
    }

    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = "enabled", havingValue = "true")
    TenantConnectorRouteResolutionService tenantConnectorRouteResolutionService(
        TenantConnectorRouteResolver resolver,
        TenantConnectorRouteRevalidator revalidator
    ) {
        return new TenantConnectorRouteResolutionService(resolver, revalidator);
    }
}
