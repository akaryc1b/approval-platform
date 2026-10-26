package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.persistence.jdbc.ApprovalDefinitionJacksonSupport;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The Boot 4 HTTP mapper is Jackson 3, separate from Jackson 2 persistence. */
@Configuration(proxyBeanMethods = false)
public class ApprovalHttpJsonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer approvalDefinitionHttpDiscriminator() {
        return builder -> builder.addMixIn(
            ApprovalDefinition.ProcessNode.class,
            ApprovalDefinitionJacksonSupport.ProcessNodeMixin.class
        );
    }
}
