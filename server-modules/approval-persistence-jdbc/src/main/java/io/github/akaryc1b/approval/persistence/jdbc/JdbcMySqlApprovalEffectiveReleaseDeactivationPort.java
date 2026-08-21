package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalEffectiveReleaseDeactivationPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.util.Objects;

/** MySQL 8.4 CAS removal of only the mutable current-effective release projection. */
public final class JdbcMySqlApprovalEffectiveReleaseDeactivationPort
    implements ApprovalEffectiveReleaseDeactivationPort {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcMySqlApprovalEffectiveReleaseDeactivationPort(DataSource dataSource) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        if (JdbcDatabaseValueAdapter.resolve(source).vendor() != ApprovalDatabaseVendor.MYSQL) {
            throw new IllegalArgumentException(
                "JdbcMySqlApprovalEffectiveReleaseDeactivationPort requires MySQL 8.4"
            );
        }
        this.jdbc = new NamedParameterJdbcTemplate(source);
    }

    @Override
    public boolean clear(String tenantId, String definitionKey, long expectedRevision) {
        if (expectedRevision < 1) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }
        int deleted = jdbc.update(
            """
            delete from ap_approval_effective_release
            where tenant_id = :tenantId
              and definition_key = :definitionKey
              and revision = :expectedRevision
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue("definitionKey", requireText(definitionKey, "definitionKey"))
                .addValue("expectedRevision", expectedRevision)
        );
        if (deleted == 0) {
            return false;
        }
        if (deleted != 1) {
            throw new IllegalStateException("effective release deactivation was not singular");
        }
        return true;
    }

    private static String requireText(String value, String name) {
        String exact = Objects.requireNonNull(value, name + " must not be null");
        if (exact.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return exact;
    }
}
