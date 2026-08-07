package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.persistence.jdbc.ApprovalDatabaseVendorResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApprovalDatabaseCompatibilityProperties.class)
@ConditionalOnProperty(
    prefix = "approval.database",
    name = "validation-enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class ApprovalDatabaseCompatibilityConfiguration {

    @Bean
    ApprovalDatabaseVendorResolver approvalDatabaseVendorResolver() {
        return new ApprovalDatabaseVendorResolver();
    }

    @Bean
    ApprovalDatabaseVendorResolver.DatabaseIdentity approvalDatabaseIdentity(
        DataSource dataSource,
        ApprovalDatabaseCompatibilityProperties properties,
        ApprovalDatabaseVendorResolver resolver
    ) {
        return resolver.resolve(dataSource, properties.getExpectedVendor());
    }
}
