package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationBindingRevisionReader;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.UUID;

/** Exact tenant-scoped binding revision read used immediately before D5 CAS. */
public final class JdbcApprovalMigrationBindingRevisionReader
    implements ApprovalMigrationBindingRevisionReader {

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcDatabaseValueAdapter values;

    public JdbcApprovalMigrationBindingRevisionReader(DataSource dataSource) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        jdbc = new NamedParameterJdbcTemplate(source);
        values = JdbcDatabaseValueAdapter.resolve(source);
    }

    @Override
    public long currentRevision(String tenantId, UUID attemptId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Long revision = jdbc.query("""
            select binding.binding_revision
            from ap_process_migration_attempt attempt
            join ap_process_runtime_binding binding
              on binding.tenant_id=attempt.tenant_id
             and binding.approval_instance_id=attempt.approval_instance_id
            where attempt.tenant_id=:tenantId and attempt.attempt_id=:attemptId
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("attemptId", values.bindUuid(attemptId)),
            (row, number) -> row.getLong(1)).stream().findFirst().orElse(null);
        if (revision == null || revision < 1) {
            throw new IllegalStateException("exact runtime-binding revision was not found");
        }
        return revision;
    }
}
