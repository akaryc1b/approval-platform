package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalAuditStore;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Objects;

/** Selects the platform audit-store implementation from trusted JDBC metadata. */
public final class JdbcAuditEventStoreFactory {

    private JdbcAuditEventStoreFactory() {
    }

    public static ApprovalAuditStore create(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager
    ) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        ObjectMapper mapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper must not be null"
        );
        PlatformTransactionManager manager = Objects.requireNonNull(
            transactionManager,
            "transactionManager must not be null"
        );
        ApprovalDatabaseVendor vendor = new ApprovalDatabaseVendorResolver()
            .resolve(source)
            .vendor();
        return switch (vendor) {
            case POSTGRESQL -> new JdbcAuditEventSink(source, mapper, manager);
            case MYSQL -> new JdbcMySqlAuditEventSink(source, mapper, manager);
        };
    }
}
