package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;

import javax.sql.DataSource;
import java.util.Objects;

/** Trusted metadata-driven projection-store selection for PostgreSQL 16 and MySQL 8.4. */
public final class JdbcApprovalProjectionStoreFactory {

    private JdbcApprovalProjectionStoreFactory() {
    }

    public static ApprovalProjectionStore create(
        DataSource dataSource,
        ObjectMapper objectMapper
    ) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        ObjectMapper mapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper must not be null"
        );
        return switch (new ApprovalDatabaseVendorResolver().resolve(source).vendor()) {
            case POSTGRESQL -> new JdbcApprovalProjectionStore(source, mapper);
            case MYSQL -> new JdbcMySqlApprovalProjectionStore(source, mapper);
        };
    }
}
