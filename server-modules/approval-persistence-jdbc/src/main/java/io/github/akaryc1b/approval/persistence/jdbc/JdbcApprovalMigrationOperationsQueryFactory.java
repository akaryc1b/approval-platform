package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationOperationsQuery;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Objects;

/** Trusted JDBC-metadata selection for the M5-E1 migration operations query. */
public final class JdbcApprovalMigrationOperationsQueryFactory {

    private JdbcApprovalMigrationOperationsQueryFactory() {
    }

    public static ApprovalMigrationOperationsQuery create(
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
            case POSTGRESQL -> new JdbcApprovalMigrationOperationsQuery(
                source,
                exactClock
            );
            case MYSQL -> new JdbcMySqlApprovalMigrationOperationsQuery(
                source,
                manager,
                exactClock
            );
        };
    }
}
