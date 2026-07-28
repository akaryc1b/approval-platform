package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationOperationsQuery;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalMigrationOperationsQuery;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;

/** M5-E1 read-only operations visibility wiring. */
@Configuration(proxyBeanMethods = false)
public class ApprovalMigrationOperationsConfiguration {

    @Bean
    ApprovalMigrationOperationsQuery approvalMigrationOperationsQuery(DataSource dataSource) {
        return new JdbcApprovalMigrationOperationsQuery(dataSource, Clock.systemUTC());
    }
}
