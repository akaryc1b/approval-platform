package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalInstanceCommandFence;
import io.github.akaryc1b.approval.domain.migration.ApprovalCommandOperation;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** MySQL transaction-bound command fence shared by business commands and migration completion. */
public final class JdbcMySqlApprovalInstanceCommandFence implements ApprovalInstanceCommandFence {

    private static final String LOCK_NAMESPACE = "approval-instance-command:v1:";

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcDatabaseValueAdapter values;
    private final JdbcMySqlTransactionLockManager locks;

    public JdbcMySqlApprovalInstanceCommandFence(DataSource dataSource) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        values = JdbcDatabaseValueAdapter.resolve(source);
        if (values.vendor() != ApprovalDatabaseVendor.MYSQL) {
            throw new IllegalArgumentException(
                "JdbcMySqlApprovalInstanceCommandFence requires MySQL 8.4"
            );
        }
        jdbc = new NamedParameterJdbcTemplate(source);
        locks = new JdbcMySqlTransactionLockManager(source);
    }

    @Override
    public void guardBusinessCommand(
        String tenantId,
        UUID approvalInstanceId,
        ApprovalCommandOperation operation,
        Instant happenedAt
    ) {
        ApprovalInstanceCommandFence.requireBusinessOperation(operation);
        String tenant = requireText(tenantId, "tenantId", 128);
        UUID instanceId = Objects.requireNonNull(
            approvalInstanceId,
            "approvalInstanceId must not be null"
        );
        Instant checkedAt = AuditHashCanonicalizer.canonicalInstant(
            Objects.requireNonNull(happenedAt, "happenedAt must not be null")
        );
        locks.acquire(lockScope(tenant, instanceId));
        var active = jdbc.query("""
            select attempt_id,lease_owner,lease_until
            from ap_approval_instance_command_fence
            where tenant_id=:tenantId and approval_instance_id=:instanceId
              and status='ACTIVE' and lease_until>:checkedAt
            order by lease_until desc,fence_id
            limit 1
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenant)
                .addValue("instanceId", values.bindUuid(instanceId))
                .addValue("checkedAt", values.bindInstant(checkedAt)),
            (row, number) -> row.getString("attempt_id")
                + "|" + row.getString("lease_owner")
                + "|" + row.getObject("lease_until"))
            .stream()
            .findFirst();
        if (active.isPresent()) {
            throw new InstanceCommandFencedException(
                "approval instance command " + operation
                    + " is fenced by an active migration lease " + active.get()
            );
        }
    }

    void acquireMigrationLock(String tenantId, UUID approvalInstanceId) {
        String tenant = requireText(tenantId, "tenantId", 128);
        UUID instanceId = Objects.requireNonNull(
            approvalInstanceId,
            "approvalInstanceId must not be null"
        );
        locks.acquire(lockScope(tenant, instanceId));
    }

    static String lockScope(String tenantId, UUID approvalInstanceId) {
        return LOCK_NAMESPACE + tenantId + ':' + approvalInstanceId;
    }

    private static String requireText(String value, String name, int maximum) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(
                name + " is blank or exceeds maximum length " + maximum
            );
        }
        return normalized;
    }
}
