package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.ApprovalIdentityService;
import io.github.akaryc1b.approval.application.ConnectorApprovalIdentityDirectory;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory;
import io.github.akaryc1b.approval.connector.port.OrganizationConnector;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class ApprovalIdentityConfiguration {

    @Bean
    ApprovalIdentityDirectory approvalIdentityDirectory(
        ObjectProvider<OrganizationConnector> organizationConnectors,
        Clock approvalClock
    ) {
        OrganizationConnector connector = organizationConnectors.getIfAvailable();
        if (connector == null) {
            return new ApprovalIdentityDirectory() {
                @Override
                public java.util.List<IdentityCandidate> search(IdentitySearch search) {
                    throw unavailable();
                }

                @Override
                public IdentityCandidate requireUser(IdentityLookup lookup) {
                    throw unavailable();
                }

                private IdentityResolutionException unavailable() {
                    return new IdentityResolutionException(
                        "IDENTITY_DIRECTORY_UNAVAILABLE",
                        "organization connector is not configured",
                        true
                    );
                }
            };
        }
        return new ConnectorApprovalIdentityDirectory(connector, approvalClock);
    }

    @Bean
    ApprovalIdentityService approvalIdentityService(
        ApprovalIdentityDirectory approvalIdentityDirectory
    ) {
        return new ApprovalIdentityService(approvalIdentityDirectory);
    }
}
