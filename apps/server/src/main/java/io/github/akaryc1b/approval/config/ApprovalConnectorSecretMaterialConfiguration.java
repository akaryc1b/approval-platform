package io.github.akaryc1b.approval.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Records the default-disabled P5 gate without constructing a material source.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApprovalConnectorSecretMaterialProperties.class)
public class ApprovalConnectorSecretMaterialConfiguration {

    @Bean
    ApprovalConnectorSecretMaterialStatus approvalConnectorSecretMaterialStatus(
        ApprovalConnectorSecretMaterialProperties properties
    ) {
        properties.requireBlockedSelection();
        return new ApprovalConnectorSecretMaterialStatus(
            false,
            properties.getBackendSelection(),
            "backend_not_selected"
        );
    }
}
