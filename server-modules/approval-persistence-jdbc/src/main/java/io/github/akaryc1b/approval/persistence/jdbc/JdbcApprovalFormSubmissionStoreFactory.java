package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalFormSubmissionStore;

import javax.sql.DataSource;
import java.util.Objects;

/** Selects the Form Submission Store from trusted JDBC database metadata. */
public final class JdbcApprovalFormSubmissionStoreFactory {

    private JdbcApprovalFormSubmissionStoreFactory() {
    }

    public static ApprovalFormSubmissionStore create(
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
        ApprovalDatabaseVendor vendor = new ApprovalDatabaseVendorResolver()
            .resolve(source)
            .vendor();
        return switch (vendor) {
            case POSTGRESQL -> new JdbcApprovalFormSubmissionStore(source, mapper);
            case MYSQL -> new JdbcMySqlApprovalFormSubmissionStore(source, mapper);
        };
    }
}
