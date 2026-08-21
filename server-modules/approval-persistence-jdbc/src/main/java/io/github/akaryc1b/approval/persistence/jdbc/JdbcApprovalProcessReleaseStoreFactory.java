package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore;

import javax.sql.DataSource;
import java.util.Objects;

/** Trusted metadata-driven Process Release lifecycle store selection. */
public final class JdbcApprovalProcessReleaseStoreFactory {

    private JdbcApprovalProcessReleaseStoreFactory() {
    }

    public static ApprovalProcessReleaseStore create(DataSource dataSource) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        return switch (new ApprovalDatabaseVendorResolver().resolve(source).vendor()) {
            case POSTGRESQL -> new JdbcApprovalProcessReleaseStore(source);
            case MYSQL -> new JdbcMySqlApprovalProcessReleaseStore(source);
        };
    }
}
