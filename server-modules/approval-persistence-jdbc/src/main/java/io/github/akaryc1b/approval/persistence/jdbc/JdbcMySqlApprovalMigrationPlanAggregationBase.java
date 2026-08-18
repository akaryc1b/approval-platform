package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore.AggregationConflictException;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PauseReason;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

abstract class JdbcMySqlApprovalMigrationPlanAggregationBase {

    protected static final String LOCK_NAMESPACE =
        "approval-migration-plan-aggregation:v1:";
    protected static final String ZERO_HASH = "0".repeat(64);

    protected final NamedParameterJdbcTemplate jdbc;
    protected final JdbcApprovalMigrationJson json;
    protected final JdbcDatabaseValueAdapter values;
    protected final JdbcMySqlTransactionLockManager locks;
    protected final TransactionTemplate transactions;
    protected final AuditEventSink auditEvents;
    protected final Supplier<UUID> identifiers;

    JdbcMySqlApprovalMigrationPlanAggregationBase(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEvents,
        Supplier<UUID> identifiers
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
        values = JdbcDatabaseValueAdapter.resolve(source);
        if (values.vendor() != ApprovalDatabaseVendor.MYSQL) {
            throw new IllegalArgumentException(
                "MySQL plan aggregation store requires MySQL metadata"
            );
        }
        jdbc = new NamedParameterJdbcTemplate(source);
        json = new JdbcApprovalMigrationJson(mapper);
        locks = new JdbcMySqlTransactionLockManager(source);
        transactions = new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.auditEvents = Objects.requireNonNull(
            auditEvents,
            "auditEvents must not be null"
        );
        this.identifiers = Objects.requireNonNull(
            identifiers,
            "identifiers must not be null"
        );
    }

    protected <T> Optional<T> queryOne(
        String sql,
        MapSqlParameterSource parameters,
        RowMapper<T> mapper,
        String duplicateMessage
    ) {
        List<T> rows = jdbc.query(sql, parameters, mapper);
        if (rows.size() > 1) {
            throw conflict(duplicateMessage);
        }
        return rows.stream().findFirst();
    }

    protected <T> Optional<T> queryLatest(
        String sql,
        MapSqlParameterSource parameters,
        RowMapper<T> mapper
    ) {
        return jdbc.query(sql, parameters, mapper).stream().findFirst();
    }

    protected <T> T execute(String message, Supplier<T> operation) {
        try {
            T value = transactions.execute(status -> operation.get());
            return Objects.requireNonNull(value, "transaction returned null");
        } catch (AggregationConflictException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw conflict(message, exception);
        }
    }

    protected UUID nextIdentifier(String name) {
        return Objects.requireNonNull(
            identifiers.get(),
            name + " supplier returned null"
        );
    }

    protected MapSqlParameterSource params(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("parameter pairs must be even");
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        for (int index = 0; index < pairs.length; index += 2) {
            parameters.addValue((String) pairs[index], bind(pairs[index + 1]));
        }
        return parameters;
    }

    protected Object bind(Object value) {
        if (value instanceof UUID uuid) {
            return values.bindUuid(uuid);
        }
        if (value instanceof Instant instant) {
            return values.bindInstant(instant);
        }
        return value;
    }

    protected static boolean manual(String status) {
        return "MANUAL_REVIEW_REQUIRED".equals(status)
            || "UNRESOLVED".equals(status);
    }

    protected static PauseReason pauseReason(String value) {
        if (value == null || "NONE".equals(value)) {
            return PauseReason.NONE;
        }
        return switch (value) {
            case "KILL_SWITCH_ACTIVE" -> PauseReason.KILL_SWITCH;
            case "CANARY_UNKNOWN" -> PauseReason.UNKNOWN;
            case "CANARY_RECONCILIATION" -> PauseReason.RECONCILIATION;
            case "CANARY_MANUAL_REVIEW" -> PauseReason.MANUAL_REVIEW;
            case "CANARY_BINDING_CONFLICT" -> PauseReason.BINDING_CONFLICT;
            case "TERMINAL_FAILURE" -> PauseReason.TERMINAL_FAILURE;
            case "CANARY_IN_FLIGHT" -> PauseReason.CANARY_IN_FLIGHT;
            case "EMPTY_BATCH" -> PauseReason.EMPTY_BATCH;
            case "CANARY_NOT_EXACT_TARGET", "STALE_KILL_SWITCH_REVISION",
                 "STALE_ORCHESTRATION_REVISION", "STALE_WORKER", "STALE_LEASE" ->
                PauseReason.STALE_AUTHORITY;
            case "MISSING_OR_INCOMPLETE_EVIDENCE" ->
                PauseReason.INCOMPLETE_EVIDENCE;
            default -> PauseReason.INCOMPLETE_EVIDENCE;
        };
    }

    protected static Integer nullableInteger(ResultSet row, String column)
        throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }

    protected static Long nullableLong(ResultSet row, String column)
        throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    protected static Boolean nullableBoolean(ResultSet row, String column)
        throws SQLException {
        boolean value = row.getBoolean(column);
        return row.wasNull() ? null : value;
    }

    protected static String planLockScope(String tenantId, UUID planId) {
        return LOCK_NAMESPACE + tenantId + ':' + planId;
    }

    protected static String hashValues(Object... inputs) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : inputs) {
                if (value == null) {
                    digest.update("-1:".getBytes(StandardCharsets.UTF_8));
                } else {
                    byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
                    digest.update(
                        Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8)
                    );
                    digest.update((byte) ':');
                    digest.update(bytes);
                    digest.update((byte) '|');
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    protected static AggregationConflictException conflict(String message) {
        return new AggregationConflictException(message);
    }

    protected static AggregationConflictException conflict(
        String message,
        Throwable cause
    ) {
        return new AggregationConflictException(message, cause);
    }

    protected record PlanContext(
        String tenantId,
        UUID planId,
        String planHash,
        int selectedCount,
        UUID intentId,
        String intentEvidenceHash
    ) {
    }

    protected record CanarySignal(UUID selectionId, String selectionHash) {
    }

    protected record RunSignal(UUID runId, String phase, String runHash) {
    }

    protected record EventSignal(
        String eventType,
        String pauseReason,
        String eventHash
    ) {
    }

    protected record ObservationSignal(
        Boolean switchEnabled,
        Boolean dispatchAllowed,
        String reasonCode,
        String observationHash
    ) {
    }

    protected record FactRow(
        int sequenceNo,
        UUID approvalInstanceId,
        boolean canary,
        String instanceEvidenceHash,
        int attemptCount,
        UUID attemptId,
        Integer attemptNumber,
        String attemptStatus,
        Long attemptRevision,
        String engineOutcome,
        String expectedBindingEvidenceHash,
        String engineRequestHash,
        String engineRequestEvidenceHash,
        String engineOutcomeDisposition,
        String outcomeHash,
        String verificationClassification,
        Boolean verificationTruncated,
        String verificationEvidenceHash,
        int completionCount,
        UUID completionAttemptId,
        Long bindingRevision,
        String targetBindingEvidenceHash,
        String completionHash,
        int conflictCount,
        String conflictHash,
        String reconciliationStatus,
        String reconciliationHash,
        String observationClassification,
        String observationDisposition,
        String observationHash
    ) {
    }

    protected record SignalRow(
        UUID selectionId,
        String selectionHash,
        UUID runId,
        String phase,
        String runHash,
        String eventType,
        String pauseReason,
        String eventHash,
        String batchHash,
        Boolean switchEnabled,
        Boolean dispatchAllowed,
        String killReasonCode,
        String observationHash
    ) {
    }
}
