package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalEffectiveReleaseDeactivationPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.util.Objects;

/** PostgreSQL CAS removal of only the mutable current-effective release projection. */
public final class JdbcApprovalEffectiveReleaseDeactivationPort
    implements ApprovalEffectiveReleaseDeactivationPort {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcApprovalEffectiveReleaseDeactivationPort(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
    }

    @Override
    public boolean clear(String tenantId, String definitionKey, long expectedRevision) {
        int deleted = jdbc.update(
            """
            delete from ap_approval_effective_release
            where tenant_id = :tenantId
              and definition_key = :definitionKey
              and revision = :expectedRevision
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("definitionKey", definitionKey)
                .addValue("expectedRevision", expectedRevision)
        );
        return deleted == 1;
    }
}
