package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Objects;

/** Trusted JDBC-metadata selection for the M5-E2 migration diagnostics query. */
public final class JdbcApprovalMigrationDiagnosticsQueryFactory {

    private JdbcApprovalMigrationDiagnosticsQueryFactory() {
    }

    public static ApprovalMigrationDiagnosticsQuery create(
        DataSource dataSource,
        PlatformTransactionManager transactionManager,
        Clock clock
    ) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        PlatformTransactionManager manager = Objects.requireNonNull(
            transactionManager,
            "transactionManager must not be null"
        );
        Clock exactClock = Objects.requireNonNull(clock, "clock must not be null");

        return switch (new ApprovalDatabaseVendorResolver().resolve(source).vendor()) {
            case POSTGRESQL -> new JdbcApprovalMigrationDiagnosticsQuery(
                source,
                exactClock
            );
            case MYSQL -> new JdbcMySqlApprovalMigrationDiagnosticsQuery(
                source,
                manager,
                exactClock
            );
        };
    }
}
