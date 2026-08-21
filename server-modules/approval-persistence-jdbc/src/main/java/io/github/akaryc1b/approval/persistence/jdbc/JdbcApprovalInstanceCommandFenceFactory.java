package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalInstanceCommandFence;

import javax.sql.DataSource;
import java.util.Objects;

/** Trusted metadata-driven Approval Instance command-fence selection. */
public final class JdbcApprovalInstanceCommandFenceFactory {

    private JdbcApprovalInstanceCommandFenceFactory() {
    }

    public static ApprovalInstanceCommandFence create(DataSource dataSource) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        return switch (new ApprovalDatabaseVendorResolver().resolve(source).vendor()) {
            case POSTGRESQL -> new JdbcApprovalInstanceCommandFence(source);
            case MYSQL -> new JdbcMySqlApprovalInstanceCommandFence(source);
        };
    }
}
