package io.github.akaryc1b.approval.demo;

import io.github.akaryc1b.approval.config.OnlineEvaluationIdentityConfiguration;
import io.github.akaryc1b.approval.config.OnlineEvaluationWorkflowConfiguration;
import org.flowable.engine.ProcessEngine;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

/** Reuse the repository's migration authority; health alone does not create approval tables. */
@Configuration(proxyBeanMethods = false)
@Profile("online-demo")
public class OnlineEvaluationDatabaseConfiguration {
    @Bean
    static BeanFactoryPostProcessor onlineEvaluationConfigurationGuard(Environment environment) {
        return factory -> {
            String workflow = environment.getProperty("approval.evaluation.workflow-enabled", "false");
            if ("true".equals(workflow)) OnlineEvaluationWorkflowConfiguration.requireConfiguration(environment);
            else if ("false".equals(workflow)) OnlineEvaluationIdentityConfiguration.requireConfiguration(environment);
            else throw new IllegalStateException("INVALID_ONLINE_DEMO_MODE");
            String generation = environment.getRequiredProperty("approval.evaluation.generation");
            if (!generation.matches("[0-9a-f]{32}") || generation.matches("0+")) {
                throw new IllegalStateException("INVALID_ONLINE_DEMO_GENERATION");
            }
            try {
                String encoded = environment.getRequiredProperty("approval.evaluation.public-key");
                if (encoded.length() > 128) throw new IllegalArgumentException();
                var decoded = java.util.Base64.getDecoder().decode(encoded);
                var key = java.security.KeyFactory.getInstance("Ed25519")
                    .generatePublic(new java.security.spec.X509EncodedKeySpec(decoded));
                if (!java.util.Base64.getEncoder().encodeToString(key.getEncoded()).equals(encoded)) {
                    throw new IllegalArgumentException();
                }
            } catch (Exception failure) { throw new IllegalStateException("INVALID_ONLINE_DEMO_PUBLIC_KEY"); }
        };
    }

    @Bean(initMethod = "migrate")
    PurchasePaymentDemoMigrationConfiguration.PurchasePaymentDemoDatabaseMigration onlineEvaluationDatabaseMigration(
        DataSource dataSource, ProcessEngine processEngine
    ) {
        return new PurchasePaymentDemoMigrationConfiguration()
            .purchasePaymentDemoDatabaseMigration(dataSource, processEngine);
    }
}
