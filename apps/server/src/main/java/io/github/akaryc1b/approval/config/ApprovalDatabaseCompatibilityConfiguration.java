package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.persistence.jdbc.ApprovalDatabaseRuntimeBaselineValidator;
import io.github.akaryc1b.approval.persistence.jdbc.ApprovalDatabaseVendorResolver;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApprovalDatabaseCompatibilityProperties.class)
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

    @Bean
    ApprovalDatabaseAuthorityBoundary approvalDatabaseAuthorityBoundary(
        ApprovalDatabaseVendorResolver.DatabaseIdentity identity,
        ApprovalDatabaseCompatibilityProperties properties
    ) {
        return new ApprovalDatabaseAuthorityBoundary(
            identity.vendor(),
            properties.getRuntimeIdentity(),
            properties.getMigrationIdentity()
        );
    }

    @Bean
    ApprovalDatabaseRuntimeBaselineValidator approvalDatabaseRuntimeBaselineValidator() {
        return new ApprovalDatabaseRuntimeBaselineValidator();
    }

    @Bean
    ApprovalDatabaseRuntimeBaselineValidator.DatabaseRuntimeBaseline
        approvalDatabaseRuntimeBaseline(
            DataSource dataSource,
            ApprovalDatabaseVendorResolver.DatabaseIdentity identity,
            ApprovalDatabaseRuntimeBaselineValidator validator
        ) {
        return validator.validate(dataSource, identity);
    }
}
