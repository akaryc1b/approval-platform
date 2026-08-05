package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcControlledAutomationLineageStore;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.UUID;

/** Server composition-root wiring for non-executing M6-F P4 lineage persistence. */
@Configuration(proxyBeanMethods = false)
public class ControlledAutomationLineageConfiguration {

    @Bean
    FlywayConfigurationCustomizer controlledAutomationFlywayLocations() {
        return configuration -> configuration.locations(
            "classpath:db/migration",
            "classpath:m6f/db/migration"
        );
    }

    @Bean
    ControlledAutomationLineageStore controlledAutomationLineageStore(
        DataSource dataSource,
        PlatformTransactionManager transactionManager
    ) {
        return new JdbcControlledAutomationLineageStore(
            dataSource,
            transactionManager,
            UUID::randomUUID
        );
    }
}
