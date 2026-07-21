package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalOperationalFailureStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL unified failure visibility while preserving each queue's own state machine. */
public final class JdbcApprovalOperationalFailureStore implements ApprovalOperationalFailureStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcApprovalOperationalFailureStore(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
    }

    @Override
    public OperationalFailurePage findFailures(OperationalFailureCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", criteria.tenantId())
            .addValue("limit", criteria.limit())
            .addValue("offset", criteria.offset());
        List<String> conditions = new ArrayList<>();
        conditions.add("failure.tenant_id = :tenantId");
        if (criteria.category() != null) {
            conditions.add("failure.category = :category");
            parameters.addValue("category", criteria.category().name());
        }
        if (criteria.failureKind() != null) {
            conditions.add("failure.failure_kind = :failureKind");
            parameters.addValue("failureKind", criteria.failureKind().name());
        }
        if (criteria.connectorKey() != null) {
            conditions.add("failure.connector_key = :connectorKey");
            parameters.addValue("connectorKey", criteria.connectorKey());
        }
        String where = String.join(" and ", conditions);
        String union = failureUnion();
        Long total = jdbc.queryForObject(
            "select count(*) from (" + union + ") failure where " + where,
            parameters,
            Long.class
        );
        long matched = total == null ? 0 : total;
        List<OperationalFailure> items = matched == 0
            ? List.of()
            : jdbc.query(
                """
                select failure.*
                from (%s) failure
                where %s
                order by failure.updated_at desc, failure.category, failure.source_id
                limit :limit offset :offset
                """.formatted(union, where),
                parameters,
                (resultSet, rowNumber) -> failure(resultSet)
            );
        return new OperationalFailurePage(items, matched, criteria.limit(), criteria.offset());
    }

    @Override
    public Optional<OperationalFailure> findFailure(
        String tenantId,
        FailureCategory category,
        UUID sourceId
    ) {
        return jdbc.query(
            """
            select failure.*
            from (%s) failure
            where failure.tenant_id = :tenantId
              and failure.category = :category
              and failure.source_id = :sourceId
            """.formatted(failureUnion()),
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue("category", Objects.requireNonNull(category, "category must not be null").name())
                .addValue("sourceId", Objects.requireNonNull(sourceId, "sourceId must not be null")),
            (resultSet, rowNumber) -> failure(resultSet)
        ).stream().findFirst();
    }

    @Override
    public List<OperationalFailureAttempt> findAttempts(
        String tenantId,
        FailureCategory category,
        UUID sourceId
    ) {
        requireText(tenantId, "tenantId");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        return switch (category) {
            case NOTIFICATION_DELIVERY -> notificationAttempts(tenantId, sourceId);
            case BUSINESS_OUTBOX -> outboxAttempts(tenantId, sourceId);
            case CONSISTENCY_CHECK -> consistencyAttempts(tenantId, sourceId);
        };
    }

    @Override
    public boolean replayOutboxDead(
        String tenantId,
        UUID sourceId,
        String replayedBy,
        String requestId,
        Instant availableAt
    ) {
        return jdbc.update(
            """
            update ap_outbox
            set status = 'PENDING',
                attempts = 0,
                available_at = :availableAt,
                updated_at = :availableAt,
                locked_by = null,
                locked_until = null,
                last_error = null,
                provider_request_id = null,
                response_code = null,
                delivered_at = null,
                dead_at = null,
                replay_count = replay_count + 1,
                last_replayed_at = :availableAt,
                last_replayed_by = :replayedBy,
                last_replay_request_id = :requestId
            where tenant_id = :tenantId
              and id = :sourceId
              and status = 'DEAD'
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue("sourceId", Objects.requireNonNull(sourceId, "sourceId must not be null"))
                .addValue("replayedBy", requireText(replayedBy, "replayedBy"))
                .addValue("requestId", requireText(requestId, "requestId"))
                .addValue("availableAt", offset(availableAt))
        ) == 1;
    }

    private List<OperationalFailureAttempt> notificationAttempts(
        String tenantId,
        UUID sourceId
    ) {
        return List.copyOf(jdbc.query(
            """
            select
                attempt.attempt_id,
                attempt.attempt_number,
                attempt.started_at,
                attempt.completed_at,
                attempt.successful,
                attempt.retryable,
                attempt.provider_message_id as provider_reference,
                null::integer as response_code,
                attempt.error_code,
                attempt.error_message,
                intent.next_attempt_at,
                null::varchar as worker_id
            from ap_notification_delivery_attempt attempt
            join ap_notification_intent intent
              on intent.tenant_id = attempt.tenant_id
             and intent.intent_id = attempt.intent_id
            where attempt.tenant_id = :tenantId
              and attempt.intent_id = :sourceId
            order by attempt.attempt_number, attempt.started_at
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId),
            (resultSet, rowNumber) -> attempt(resultSet)
        ));
    }

    private List<OperationalFailureAttempt> outboxAttempts(
        String tenantId,
        UUID sourceId
    ) {
        return List.copyOf(jdbc.query(
            """
            select
                attempt_id,
                attempt_number,
                started_at,
                completed_at,
                successful,
                retryable,
                provider_request_id as provider_reference,
                response_code,
                error_code,
                error_message,
                next_attempt_at,
                worker_id
            from ap_outbox_delivery_attempt
            where tenant_id = :tenantId
              and outbox_id = :sourceId
            order by attempt_number, started_at
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId),
            (resultSet, rowNumber) -> attempt(resultSet)
        ));
    }

    private List<OperationalFailureAttempt> consistencyAttempts(
        String tenantId,
        UUID sourceId
    ) {
        return jdbc.query(
            """
            select started_at, completed_at, error_code, error_message
            from ap_consistency_check
            where tenant_id = :tenantId
              and check_id = :sourceId
              and status = 'FAILED'
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId),
            (resultSet, rowNumber) -> new OperationalFailureAttempt(
                UUID.nameUUIDFromBytes(
                    ("consistency:" + tenantId + ':' + sourceId)
                        .getBytes(StandardCharsets.UTF_8)
                ),
                1,
                instant(resultSet, "started_at"),
                instant(resultSet, "completed_at"),
                false,
                true,
                null,
                null,
                resultSet.getString("error_code"),
                resultSet.getString("error_message"),
                null,
                null
            )
        );
    }

    private OperationalFailure failure(ResultSet resultSet) throws SQLException {
        return new OperationalFailure(
            FailureCategory.valueOf(resultSet.getString("category")),
            FailureKind.valueOf(resultSet.getString("failure_kind")),
            resultSet.getObject("source_id", UUID.class),
            resultSet.getString("responsibility"),
            resultSet.getString("status"),
            resultSet.getString("connector_key"),
            resultSet.getString("recipient_id"),
            resultSet.getString("aggregate_type"),
            resultSet.getString("aggregate_id"),
            resultSet.getInt("attempt_count"),
            nullableInteger(resultSet, "max_attempts"),
            nullableInstant(resultSet, "next_attempt_at"),
            resultSet.getString("last_error_code"),
            resultSet.getString("last_error_message"),
            instant(resultSet, "created_at"),
            instant(resultSet, "updated_at"),
            resultSet.getBoolean("replayable")
        );
    }

    private OperationalFailureAttempt attempt(ResultSet resultSet) throws SQLException {
        return new OperationalFailureAttempt(
            resultSet.getObject("attempt_id", UUID.class),
            resultSet.getInt("attempt_number"),
            instant(resultSet, "started_at"),
            instant(resultSet, "completed_at"),
            resultSet.getBoolean("successful"),
            resultSet.getBoolean("retryable"),
            resultSet.getString("provider_reference"),
            nullableInteger(resultSet, "response_code"),
            resultSet.getString("error_code"),
            resultSet.getString("error_message"),
            nullableInstant(resultSet, "next_attempt_at"),
            resultSet.getString("worker_id")
        );
    }

    private static String failureUnion() {
        return """
            select
                intent.tenant_id,
                'NOTIFICATION_DELIVERY'::varchar as category,
                case intent.channel
                    when 'CONNECTOR' then 'CONNECTOR'
                    when 'EMAIL' then 'EMAIL'
                    else 'INTERNAL'
                end::varchar as failure_kind,
                intent.intent_id as source_id,
                'NOTIFICATION_DELIVERY'::varchar as responsibility,
                intent.status,
                case when intent.channel = 'CONNECTOR'
                    then coalesce(intent.metadata_json ->> 'connectorKey', 'approval-connector')
                    else null
                end::varchar as connector_key,
                intent.recipient_id,
                intent.aggregate_type,
                intent.aggregate_id,
                intent.attempt_count,
                intent.max_attempts,
                intent.next_attempt_at,
                intent.last_error_code,
                intent.last_error_message,
                intent.created_at,
                intent.updated_at,
                true as replayable
            from ap_notification_intent intent
            where intent.status = 'DEAD_LETTER'

            union all

            select
                outbox.tenant_id,
                'BUSINESS_OUTBOX'::varchar as category,
                'BUSINESS_CALLBACK'::varchar as failure_kind,
                outbox.id as source_id,
                'BUSINESS_CALLBACK_OUTBOX'::varchar as responsibility,
                outbox.status,
                outbox.connector_key,
                null::varchar as recipient_id,
                outbox.aggregate_type,
                outbox.aggregate_id,
                outbox.attempts as attempt_count,
                null::integer as max_attempts,
                outbox.available_at as next_attempt_at,
                'OUTBOX_DEAD'::varchar as last_error_code,
                outbox.last_error as last_error_message,
                outbox.created_at,
                outbox.updated_at,
                true as replayable
            from ap_outbox outbox
            where outbox.status = 'DEAD'

            union all

            select
                consistency.tenant_id,
                'CONSISTENCY_CHECK'::varchar as category,
                'INTERNAL'::varchar as failure_kind,
                consistency.check_id as source_id,
                'CONSISTENCY_DETECTION'::varchar as responsibility,
                consistency.status,
                null::varchar as connector_key,
                consistency.requested_by as recipient_id,
                'CONSISTENCY_CHECK'::varchar as aggregate_type,
                consistency.check_id::varchar as aggregate_id,
                1 as attempt_count,
                null::integer as max_attempts,
                null::timestamptz as next_attempt_at,
                consistency.error_code as last_error_code,
                consistency.error_message as last_error_message,
                consistency.started_at as created_at,
                consistency.completed_at as updated_at,
                true as replayable
            from ap_consistency_check consistency
            where consistency.status = 'FAILED'
            """;
    }

    private static Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        if (value == null) {
            throw new SQLException(column + " must not be null");
        }
        return value.toInstant();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offset(Instant value) {
        return Objects.requireNonNull(value, "instant must not be null").atOffset(ZoneOffset.UTC);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
