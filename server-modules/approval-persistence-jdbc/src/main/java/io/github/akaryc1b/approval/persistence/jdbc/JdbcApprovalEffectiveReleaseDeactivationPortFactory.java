package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalEffectiveReleaseDeactivationPort;

import javax.sql.DataSource;
import java.util.Objects;

/** Trusted metadata-driven Effective Release deactivation selection. */
public final class JdbcApprovalEffectiveReleaseDeactivationPortFactory {

    private JdbcApprovalEffectiveReleaseDeactivationPortFactory() {
    }

    public static ApprovalEffectiveReleaseDeactivationPort create(DataSource dataSource) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        return switch (new ApprovalDatabaseVendorResolver().resolve(source).vendor()) {
            case POSTGRESQL -> new JdbcApprovalEffectiveReleaseDeactivationPort(source);
            case MYSQL -> new JdbcMySqlApprovalEffectiveReleaseDeactivationPort(source);
        };
    }
}
