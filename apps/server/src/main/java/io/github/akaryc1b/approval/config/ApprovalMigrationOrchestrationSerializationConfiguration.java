package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore;
import io.github.akaryc1b.approval.persistence.jdbc.PostgresSerializedApprovalMigrationOrchestrationStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/** Serializes D7 exact replay before the existing short-transaction JDBC store. */
@Configuration(proxyBeanMethods = false)
public class ApprovalMigrationOrchestrationSerializationConfiguration {

    @Bean
    @Primary
    ApprovalMigrationOrchestrationStore serializedApprovalMigrationOrchestrationStore(
        DataSource dataSource,
        @Qualifier("approvalMigrationOrchestrationStore")
        ApprovalMigrationOrchestrationStore delegate
    ) {
        return new PostgresSerializedApprovalMigrationOrchestrationStore(
            dataSource,
            delegate
        );
    }
}
