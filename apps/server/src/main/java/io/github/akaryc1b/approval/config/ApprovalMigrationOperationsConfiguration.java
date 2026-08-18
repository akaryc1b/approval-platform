package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOperationsQuery;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalMigrationDiagnosticsQuery;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalMigrationOperationsQueryFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;

/** M5-E1/E2 read-only operations visibility wiring. */
@Configuration(proxyBeanMethods = false)
public class ApprovalMigrationOperationsConfiguration {

    @Bean
    ApprovalMigrationOperationsQuery approvalMigrationOperationsQuery(
        DataSource dataSource,
        PlatformTransactionManager transactionManager
    ) {
        return JdbcApprovalMigrationOperationsQueryFactory.create(
            dataSource,
            transactionManager,
            Clock.systemUTC()
        );
    }

    @Bean
    ApprovalMigrationDiagnosticsQuery approvalMigrationDiagnosticsQuery(DataSource dataSource) {
        return new JdbcApprovalMigrationDiagnosticsQuery(dataSource, Clock.systemUTC());
    }
}
