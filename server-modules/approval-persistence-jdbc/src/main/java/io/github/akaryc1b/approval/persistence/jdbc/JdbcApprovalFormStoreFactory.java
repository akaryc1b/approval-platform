package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;

import javax.sql.DataSource;
import java.util.Objects;

/** Trusted metadata-driven form-store selection for PostgreSQL 16 and MySQL 8.4. */
public final class JdbcApprovalFormStoreFactory {

    private JdbcApprovalFormStoreFactory() {
    }

    public static ApprovalFormStore create(
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
            case POSTGRESQL -> new JdbcApprovalFormStore(source, mapper);
            case MYSQL -> new JdbcMySqlApprovalFormStore(source, mapper);
        };
    }
}
