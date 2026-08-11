package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationRuntimeBindingCasStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Trusted metadata-driven D5 Runtime Binding CAS selection. */
public final class JdbcApprovalMigrationRuntimeBindingCasStoreFactory {

    private JdbcApprovalMigrationRuntimeBindingCasStoreFactory() {
    }

    public static ApprovalMigrationRuntimeBindingCasStore create(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEvents,
        Supplier<UUID> identifiers
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
        AuditEventSink audit = Objects.requireNonNull(
            auditEvents,
            "auditEvents must not be null"
        );
        Supplier<UUID> ids = Objects.requireNonNull(
            identifiers,
            "identifiers must not be null"
        );
        return switch (new ApprovalDatabaseVendorResolver().resolve(source).vendor()) {
            case POSTGRESQL -> new PostgresSerializedApprovalMigrationRuntimeBindingCasStore(
                source,
                new JdbcApprovalMigrationRuntimeBindingCasStore(
                    source,
                    mapper,
                    manager,
                    audit,
                    ids
                )
            );
            case MYSQL -> new JdbcMySqlApprovalMigrationRuntimeBindingCasStore(
                source,
                mapper,
                manager,
                audit,
                ids
            );
        };
    }
}
