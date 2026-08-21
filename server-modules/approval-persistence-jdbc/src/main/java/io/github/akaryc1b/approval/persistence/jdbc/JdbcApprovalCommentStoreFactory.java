package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Objects;

/** Trusted metadata-driven Comment Store selection for PostgreSQL 16 and MySQL 8.4. */
public final class JdbcApprovalCommentStoreFactory {

    private JdbcApprovalCommentStoreFactory() {
    }

    public static ApprovalCommentStore create(
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
        return switch (new ApprovalDatabaseVendorResolver()
            .resolve(source)
            .vendor()) {
            case POSTGRESQL -> new JdbcApprovalCommentStore(
                source,
                mapper
            );
            case MYSQL -> new JdbcMySqlApprovalCommentStore(
                source,
                mapper,
                manager
            );
        };
    }
}
