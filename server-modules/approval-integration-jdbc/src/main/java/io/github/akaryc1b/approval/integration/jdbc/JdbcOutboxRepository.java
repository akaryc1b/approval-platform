package io.github.akaryc1b.approval.integration.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector.BusinessEvent;
import io.github.akaryc1b.approval.integration.outbox.OutboxMessage;
import io.github.akaryc1b.approval.integration.outbox.OutboxRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class JdbcOutboxRepository implements OutboxRepository {

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcOutboxRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public AppendResult append(OutboxMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        String sql = """
            insert into ap_outbox (
                id, tenant_id, connector_key, request_id, trace_id,
                event_id, event_type, aggregate_type, aggregate_id,
                occurred_at, idempotency_key, payload_json, status,
                attempts, available_at, created_at, updated_at
            ) values (
                :id, :tenantId, :connectorKey, :requestId, :traceId,
                :eventId, :eventType, :aggregateType, :aggregateId,
                :occurredAt, :idempotencyKey, cast(:payloadJson as jsonb), 'PENDING',
                0, :availableAt, :createdAt, :createdAt
            )
            """;
        var parameters = new MapSqlParameterSource()
            .addValue("id", message.id())
            .addValue("tenantId", message.context().tenantId())
            .addValue("connectorKey", message.context().connectorKey())
            .addValue("requestId", message.context().requestId())
            .addValue("traceId", message.context().traceId())
            .addValue("eventId", message.event().eventId())
            .addValue("eventType", message.event().eventType())
            .addValue("aggregateType", message.event().aggregateType())
            .addValue("aggregateId", message.event().aggregateId())
            .addValue("occurredAt", offset(message.event().occurredAt()))
            .addValue("idempotencyKey", message.event().idempotencyKey())
            .addValue("payloadJson", encodePayload(message.event().payload()))
            .addValue("availableAt", offset(message.availableAt()))
            .addValue("createdAt", offset(message.createdAt()));
        try {
            jdbc.update(sql, parameters);
            return AppendResult.INSERTED;
        } catch (DuplicateKeyException exception) {
            return AppendResult.DUPLICATE;
        }
    }

    @Override
    public List<ClaimedMessage> claimDue(
        Instant now,
        int limit,
        String workerId,
        Duration leaseDuration
    ) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        String sql = """
            with due as (
                select id
                from ap_outbox
                where (
                    status = 'PENDING' and available_at <= :now
                ) or (
                    status = 'IN_FLIGHT' and locked_until <= :now
                )
                order by available_at, created_at
                for update skip locked
                limit :limit
            )
            update ap_outbox target
            set status = 'IN_FLIGHT',
                locked_by = :workerId,
                locked_until = :lockedUntil,
                updated_at = :now
            from due
            where target.id = due.id
            returning target.*
            """;
        var parameters = new MapSqlParameterSource()
            .addValue("now", offset(now))
            .addValue("limit", limit)
            .addValue("workerId", workerId)
            .addValue("lockedUntil", offset(now.plus(leaseDuration)));
        return jdbc.query(sql, parameters, claimedMessageMapper());
    }

    @Override
    public boolean markDelivered(
        UUID messageId,
        String workerId,
        String providerRequestId,
        int responseCode,
        Instant deliveredAt
    ) {
        String sql = """
            update ap_outbox
            set status = 'DELIVERED',
                provider_request_id = :providerRequestId,
                response_code = :responseCode,
                delivered_at = :deliveredAt,
                updated_at = :deliveredAt,
                locked_by = null,
                locked_until = null,
                last_error = null
            where id = :id and status = 'IN_FLIGHT' and locked_by = :workerId
            """;
        return jdbc.update(sql, new MapSqlParameterSource()
            .addValue("id", messageId)
            .addValue("workerId", workerId)
            .addValue("providerRequestId", providerRequestId)
            .addValue("responseCode", responseCode)
            .addValue("deliveredAt", offset(deliveredAt))) == 1;
    }

    @Override
    public boolean reschedule(
        UUID messageId,
        String workerId,
        int attempts,
        Instant availableAt,
        String errorMessage,
        Instant updatedAt
    ) {
        String sql = """
            update ap_outbox
            set status = 'PENDING',
                attempts = :attempts,
                available_at = :availableAt,
                last_error = :lastError,
                updated_at = :updatedAt,
                locked_by = null,
                locked_until = null
            where id = :id and status = 'IN_FLIGHT' and locked_by = :workerId
            """;
        return jdbc.update(sql, new MapSqlParameterSource()
            .addValue("id", messageId)
            .addValue("workerId", workerId)
            .addValue("attempts", attempts)
            .addValue("availableAt", offset(availableAt))
            .addValue("lastError", errorMessage)
            .addValue("updatedAt", offset(updatedAt))) == 1;
    }

    @Override
    public boolean markDead(
        UUID messageId,
        String workerId,
        int attempts,
        String errorMessage,
        Instant deadAt
    ) {
        String sql = """
            update ap_outbox
            set status = 'DEAD',
                attempts = :attempts,
                last_error = :lastError,
                dead_at = :deadAt,
                updated_at = :deadAt,
                locked_by = null,
                locked_until = null
            where id = :id and status = 'IN_FLIGHT' and locked_by = :workerId
            """;
        return jdbc.update(sql, new MapSqlParameterSource()
            .addValue("id", messageId)
            .addValue("workerId", workerId)
            .addValue("attempts", attempts)
            .addValue("lastError", errorMessage)
            .addValue("deadAt", offset(deadAt))) == 1;
    }

    private RowMapper<ClaimedMessage> claimedMessageMapper() {
        return (resultSet, rowNumber) -> {
            Instant createdAt = instant(resultSet, "created_at");
            var context = new ConnectorContext(
                resultSet.getString("connector_key"),
                resultSet.getString("tenant_id"),
                resultSet.getString("request_id"),
                resultSet.getString("trace_id"),
                createdAt
            );
            var event = new BusinessEvent(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getString("event_type"),
                resultSet.getString("aggregate_type"),
                resultSet.getString("aggregate_id"),
                instant(resultSet, "occurred_at"),
                resultSet.getString("idempotency_key"),
                decodePayload(resultSet.getString("payload_json"))
            );
            var message = new OutboxMessage(
                resultSet.getObject("id", UUID.class),
                context,
                event,
                instant(resultSet, "available_at"),
                createdAt
            );
            return new ClaimedMessage(
                message,
                resultSet.getInt("attempts"),
                resultSet.getString("locked_by"),
                instant(resultSet, "locked_until")
            );
        };
    }

    private String encodePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to encode Outbox payload", exception);
        }
    }

    private Map<String, Object> decodePayload(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, PAYLOAD_TYPE);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Unable to decode Outbox payload", exception);
        }
    }

    private static OffsetDateTime offset(Instant instant) {
        return Objects.requireNonNull(instant, "instant must not be null").atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
