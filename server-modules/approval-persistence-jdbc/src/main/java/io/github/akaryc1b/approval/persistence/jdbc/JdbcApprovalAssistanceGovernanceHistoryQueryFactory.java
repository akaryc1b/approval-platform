package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Objects;

/** Trusted database-vendor selection for the AI governance-history query. */
public final class JdbcApprovalAssistanceGovernanceHistoryQueryFactory {

    private JdbcApprovalAssistanceGovernanceHistoryQueryFactory() {
    }

    public static ApprovalAssistanceGovernanceHistoryQuery create(
        DataSource dataSource,
        PlatformTransactionManager transactionManager
    ) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        PlatformTransactionManager manager = Objects.requireNonNull(
            transactionManager,
            "transactionManager must not be null"
        );
        return switch (new ApprovalDatabaseVendorResolver().resolve(source).vendor()) {
            case POSTGRESQL -> new JdbcApprovalAssistanceGovernanceHistoryQuery(
                source,
                manager
            );
            case MYSQL -> new JdbcMySqlApprovalAssistanceGovernanceHistoryQuery(
                source,
                manager
            );
        };
    }
}
