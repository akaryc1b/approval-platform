package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalFormDesignDraftStore;

import javax.sql.DataSource;
import java.util.Objects;

/** Trusted metadata-driven Form Design Draft Store selection. */
public final class JdbcApprovalFormDesignDraftStoreFactory {

    private JdbcApprovalFormDesignDraftStoreFactory() {
    }

    public static ApprovalFormDesignDraftStore create(
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
            case POSTGRESQL -> new JdbcApprovalFormDesignDraftStore(source, mapper);
            case MYSQL -> new JdbcMySqlApprovalFormDesignDraftStore(source, mapper);
        };
    }
}
