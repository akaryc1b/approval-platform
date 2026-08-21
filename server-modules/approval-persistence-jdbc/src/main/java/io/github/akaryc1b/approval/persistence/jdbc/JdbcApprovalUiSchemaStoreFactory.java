package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;

import javax.sql.DataSource;
import java.util.Objects;

/** Trusted metadata-driven UI Schema Store selection for PostgreSQL 16 and MySQL 8.4. */
public final class JdbcApprovalUiSchemaStoreFactory {

    private JdbcApprovalUiSchemaStoreFactory() {
    }

    public static ApprovalUiSchemaStore create(
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
            case POSTGRESQL -> new JdbcApprovalUiSchemaStore(source, mapper);
            case MYSQL -> new JdbcMySqlApprovalUiSchemaStore(source, mapper);
        };
    }
}
