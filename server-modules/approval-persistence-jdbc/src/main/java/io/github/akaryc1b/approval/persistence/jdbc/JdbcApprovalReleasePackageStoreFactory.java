package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;

import javax.sql.DataSource;
import java.util.Objects;

/** Trusted metadata-driven Approval Release Package Store selection. */
public final class JdbcApprovalReleasePackageStoreFactory {

    private JdbcApprovalReleasePackageStoreFactory() {
    }

    public static ApprovalReleasePackageStore create(DataSource dataSource) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        return switch (new ApprovalDatabaseVendorResolver().resolve(source).vendor()) {
            case POSTGRESQL -> new JdbcApprovalReleasePackageStore(source);
            case MYSQL -> new JdbcMySqlApprovalReleasePackageStore(source);
        };
    }
}
