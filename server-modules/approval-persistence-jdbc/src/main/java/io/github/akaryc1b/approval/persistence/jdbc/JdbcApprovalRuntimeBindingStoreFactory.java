package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalRuntimeBindingStore;

import javax.sql.DataSource;
import java.util.Objects;

/** Trusted metadata-driven immutable Runtime Binding Store selection. */
public final class JdbcApprovalRuntimeBindingStoreFactory {

    private JdbcApprovalRuntimeBindingStoreFactory() {
    }

    public static ApprovalRuntimeBindingStore create(DataSource dataSource) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        return switch (new ApprovalDatabaseVendorResolver().resolve(source).vendor()) {
            case POSTGRESQL -> new JdbcApprovalRuntimeBindingStore(source);
            case MYSQL -> new JdbcMySqlApprovalRuntimeBindingStore(source);
        };
    }
}
