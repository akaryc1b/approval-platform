package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalEffectiveReleaseStore;

import javax.sql.DataSource;
import java.util.Objects;

/** Trusted metadata-driven Effective Release store selection. */
public final class JdbcApprovalEffectiveReleaseStoreFactory {

    private JdbcApprovalEffectiveReleaseStoreFactory() {
    }

    public static ApprovalEffectiveReleaseStore create(DataSource dataSource) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        return switch (new ApprovalDatabaseVendorResolver().resolve(source).vendor()) {
            case POSTGRESQL -> new JdbcApprovalEffectiveReleaseStore(source);
            case MYSQL -> new JdbcMySqlApprovalEffectiveReleaseStore(source);
        };
    }
}
