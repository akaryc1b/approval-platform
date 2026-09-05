package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;

import javax.sql.DataSource;
import java.util.Objects;

/** Trusted metadata-driven Approval Release Deployment Store selection. */
public final class JdbcApprovalReleaseDeploymentStoreFactory {

    private JdbcApprovalReleaseDeploymentStoreFactory() {
    }

    public static ApprovalReleaseDeploymentStore create(DataSource dataSource) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        return switch (new ApprovalDatabaseVendorResolver().resolve(source).vendor()) {
            case POSTGRESQL -> new JdbcApprovalReleaseDeploymentStore(source);
            case MYSQL -> new JdbcMySqlApprovalReleaseDeploymentStore(source);
        };
    }
}
