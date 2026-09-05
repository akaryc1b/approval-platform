package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidenceStore;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Trusted metadata selection for PostgreSQL/MySQL P4 durable evidence authority. */
public final class JdbcApprovalAssistanceDurableEvidenceStoreFactory {

    private JdbcApprovalAssistanceDurableEvidenceStoreFactory() {
    }

    public static ApprovalAssistanceDurableEvidenceStore create(
        DataSource dataSource,
        PlatformTransactionManager transactionManager,
        Supplier<UUID> eventIdentifiers
    ) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        PlatformTransactionManager manager = Objects.requireNonNull(
            transactionManager,
            "transactionManager must not be null"
        );
        Supplier<UUID> identifiers = Objects.requireNonNull(
            eventIdentifiers,
            "eventIdentifiers must not be null"
        );
        return switch (new ApprovalDatabaseVendorResolver().resolve(source).vendor()) {
            case POSTGRESQL -> new JdbcApprovalAssistanceDurableEvidenceStore(
                source,
                manager,
                identifiers
            );
            case MYSQL -> new JdbcMySqlApprovalAssistanceDurableEvidenceStore(
                source,
                manager,
                identifiers
            );
        };
    }
}
