package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalFormPackageStore;

import javax.sql.DataSource;
import java.util.Objects;

/** Trusted metadata-driven Form Package Store selection. */
public final class JdbcApprovalFormPackageStoreFactory {

    private JdbcApprovalFormPackageStoreFactory() {
    }

    public static ApprovalFormPackageStore create(DataSource dataSource) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        return switch (new ApprovalDatabaseVendorResolver().resolve(source).vendor()) {
            case POSTGRESQL -> new JdbcApprovalFormPackageStore(source);
            case MYSQL -> new JdbcMySqlApprovalFormPackageStore(source);
        };
    }
}
