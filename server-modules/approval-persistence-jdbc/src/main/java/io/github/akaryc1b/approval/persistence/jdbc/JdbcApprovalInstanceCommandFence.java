package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalInstanceCommandFence;
import io.github.akaryc1b.approval.domain.migration.ApprovalCommandOperation;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** PostgreSQL transaction advisory lock shared by business commands and migration claims. */
public final class JdbcApprovalInstanceCommandFence implements ApprovalInstanceCommandFence {

    private static final String LOCK_NAMESPACE = "approval-instance-command:v1:";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcApprovalInstanceCommandFence(DataSource dataSource) {
        jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
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
        Instant checkedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                "business command fencing requires an active platform transaction"
            );
        }
        acquireTransactionLock(tenant, instanceId);
        var active = jdbc.query("""
            select attempt_id,lease_owner,lease_until
            from ap_approval_instance_command_fence
            where tenant_id=:tenantId and approval_instance_id=:instanceId
              and status='ACTIVE' and lease_until>:checkedAt
            order by lease_until desc,fence_id
            limit 1
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenant)
                .addValue("instanceId", instanceId)
                .addValue("checkedAt", JdbcApprovalMigrationJson.offset(checkedAt)),
            (row, number) -> row.getObject("attempt_id", UUID.class)
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
        acquireTransactionLock(
            requireText(tenantId, "tenantId", 128),
            Objects.requireNonNull(approvalInstanceId, "approvalInstanceId must not be null")
        );
    }

    private void acquireTransactionLock(String tenantId, UUID approvalInstanceId) {
        String key = LOCK_NAMESPACE + tenantId + ':' + approvalInstanceId;
        jdbc.query(
            "select pg_advisory_xact_lock(hashtextextended(:lockKey,0))",
            new MapSqlParameterSource("lockKey", key),
            resultSet -> null
        );
    }

    private static String requireText(String value, String name, int maximum) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " is blank or exceeds maximum length " + maximum);
        }
        return normalized;
    }
}
