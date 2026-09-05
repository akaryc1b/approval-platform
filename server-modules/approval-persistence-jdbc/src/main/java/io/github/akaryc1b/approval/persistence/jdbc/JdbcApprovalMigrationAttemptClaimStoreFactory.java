package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Trusted metadata-driven migration attempt claim-store selection. */
public final class JdbcApprovalMigrationAttemptClaimStoreFactory {

    private JdbcApprovalMigrationAttemptClaimStoreFactory() {
    }

    public static ApprovalMigrationAttemptClaimStore create(
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
            case POSTGRESQL -> new JdbcApprovalMigrationAttemptClaimStore(
                source,
                mapper,
                manager,
                audit,
                ids
            );
            case MYSQL -> new JdbcMySqlApprovalMigrationAttemptClaimStore(
                source,
                mapper,
                manager,
                audit,
                ids
            );
        };
    }
}
