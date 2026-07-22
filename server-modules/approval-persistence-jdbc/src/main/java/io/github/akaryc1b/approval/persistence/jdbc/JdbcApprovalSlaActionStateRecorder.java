package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalSlaActionStateRecorder;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** PostgreSQL optimistic recorder for SLA overdue action sequence evidence. */
public final class JdbcApprovalSlaActionStateRecorder implements ApprovalSlaActionStateRecorder {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcApprovalSlaActionStateRecorder(
        DataSource dataSource,
        PlatformTransactionManager transactionManager
    ) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
        this.transactions = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
    }

    @Override
    public RecordResult recordOverdue(
        String tenantId,
        UUID slaInstanceId,
        long actionSequence,
        String requestId,
        String traceId,
        Instant recordedAt
    ) {
        String tenant = requireText(tenantId, "tenantId", 128);
        UUID instanceId = Objects.requireNonNull(
            slaInstanceId,
            "slaInstanceId must not be null"
        );
        if (actionSequence < 1) {
            throw new IllegalArgumentException("actionSequence must be positive");
        }
        String request = requireText(requestId, "requestId", 128);
        String trace = normalizeOptional(traceId, 128);
        Instant occurredAt = Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        RecordResult result = transactions.execute(status -> {
            CurrentState current = requireState(tenant, instanceId);
            if (current.lastActionSequence() >= actionSequence) {
                return RecordResult.ALREADY_RECORDED;
            }
            if (!"ACTIVE".equals(current.status())) {
                throw new ActionStateException(
                    "APPROVAL_SLA_ACTION_STATE_CONFLICT",
                    false,
                    "only an active SLA can record overdue action state"
                );
            }
            int changed = jdbc.update(
                """
                update ap_sla_instance
                set last_action_sequence = :actionSequence,
                    request_id = :requestId,
                    trace_id = :traceId,
                    updated_at = :recordedAt,
                    version = version + 1
                where tenant_id = :tenantId
                  and sla_instance_id = :slaInstanceId
                  and status = 'ACTIVE'
                  and version = :expectedVersion
                  and last_action_sequence = :expectedActionSequence
                """,
                new MapSqlParameterSource()
                    .addValue("actionSequence", actionSequence)
                    .addValue("requestId", request)
                    .addValue("traceId", trace)
                    .addValue("recordedAt", offset(occurredAt))
                    .addValue("tenantId", tenant)
                    .addValue("slaInstanceId", instanceId)
                    .addValue("expectedVersion", current.version())
                    .addValue("expectedActionSequence", current.lastActionSequence())
            );
            if (changed == 1) {
                return RecordResult.RECORDED;
            }
            CurrentState latest = requireState(tenant, instanceId);
            if (latest.lastActionSequence() >= actionSequence) {
                return RecordResult.ALREADY_RECORDED;
            }
            if (!"ACTIVE".equals(latest.status())) {
                throw new ActionStateException(
                    "APPROVAL_SLA_ACTION_STATE_CONFLICT",
                    false,
                    "SLA state changed before overdue evidence was recorded"
                );
            }
            throw new ActionStateException(
                "APPROVAL_SLA_ACTION_VERSION_CONFLICT",
                true,
                "SLA action sequence changed concurrently"
            );
        });
        return Objects.requireNonNull(result, "record result must not be null");
    }

    private CurrentState requireState(String tenantId, UUID slaInstanceId) {
        List<CurrentState> rows = jdbc.query(
            """
            select status, last_action_sequence, version
            from ap_sla_instance
            where tenant_id = :tenantId and sla_instance_id = :slaInstanceId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("slaInstanceId", slaInstanceId),
            (resultSet, rowNumber) -> new CurrentState(
                resultSet.getString("status"),
                resultSet.getLong("last_action_sequence"),
                resultSet.getLong("version")
            )
        );
        return rows.stream().findFirst().orElseThrow(() -> new ActionStateException(
            "APPROVAL_SLA_INSTANCE_NOT_FOUND",
            false,
            "SLA instance was not found"
        ));
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " exceeds " + maximumLength + " characters");
        }
        return normalized;
    }

    private static String normalizeOptional(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                "optional text exceeds " + maximumLength + " characters"
            );
        }
        return normalized;
    }

    private record CurrentState(String status, long lastActionSequence, long version) {
    }
}
