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
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class JdbcOutboxRepository implements OutboxRepository {

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final JdbcIntegrationDatabaseDialect database;
    private final JdbcOutboxDialect dialect;
    private final TransactionTemplate transactionTemplate;

    public JdbcOutboxRepository(DataSource dataSource, ObjectMapper objectMapper) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        this.jdbc = new NamedParameterJdbcTemplate(source);
        this.objectMapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper must not be null"
        );
        this.database = JdbcIntegrationDatabaseDialect.resolve(source);
        this.dialect = JdbcOutboxDialect.forDatabase(database);
        this.transactionTemplate = new TransactionTemplate(
            new JdbcTransactionManager(source)
        );
    }

    @Override
    public AppendResult append(OutboxMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", database.bindUuid(message.id()))
            .addValue("tenantId", message.context().tenantId())
            .addValue("connectorKey", message.context().connectorKey())
            .addValue("requestId", message.context().requestId())
            .addValue("traceId", message.context().traceId())
            .addValue("eventId", database.bindUuid(message.event().eventId()))
            .addValue("eventType", message.event().eventType())
            .addValue("aggregateType", message.event().aggregateType())
            .addValue("aggregateId", message.event().aggregateId())
            .addValue("occurredAt", database.bindInstant(message.event().occurredAt()))
            .addValue("idempotencyKey", message.event().idempotencyKey())
            .addValue("payloadJson", encodePayload(message.event().payload()))
            .addValue("availableAt", database.bindInstant(message.availableAt()))
            .addValue("createdAt", database.bindInstant(message.createdAt()));
        try {
            int inserted = jdbc.update(dialect.appendSql(), parameters);
            if (inserted == 1) {
                return AppendResult.INSERTED;
            }
            if (dialect.isExpectedNoOpDuplicate(inserted)) {
                return AppendResult.DUPLICATE;
            }
            throw new IllegalStateException("unexpected Outbox append row count");
        } catch (DuplicateKeyException exception) {
            if (!dialect.requiresBusinessKeyVerification(exception)
                || !businessKeyExists(parameters)) {
                throw exception;
            }
            return AppendResult.DUPLICATE;
        }
    }

    private boolean businessKeyExists(MapSqlParameterSource parameters) {
        List<UUID> ids = jdbc.query(
            """
            select id
            from ap_outbox
            where tenant_id = :tenantId
              and connector_key = :connectorKey
              and idempotency_key = :idempotencyKey
            """,
            parameters,
            (resultSet, rowNumber) -> database.uuid(resultSet, "id")
        );
        if (ids.size() > 1) {
            throw new IllegalStateException("Outbox business key resolved multiple rows");
        }
        return ids.size() == 1;
    }

    @Override
    public List<ClaimedMessage> claimDue(
        Instant now,
        int limit,
        String workerId,
        Duration leaseDuration
    ) {
        Instant exactNow = Objects.requireNonNull(now, "now must not be null");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        String exactWorker = requireText(workerId, "workerId");
        requirePositive(leaseDuration, "leaseDuration");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("now", database.bindInstant(exactNow))
            .addValue("limit", limit)
            .addValue("workerId", exactWorker)
            .addValue(
                "lockedUntil",
                database.bindInstant(exactNow.plus(leaseDuration))
            );
        if (database == JdbcIntegrationDatabaseDialect.POSTGRESQL) {
            return jdbc.query(
                dialect.postgreSqlClaimSql(),
                parameters,
                claimedMessageMapper()
            );
        }
        return Objects.requireNonNull(
            transactionTemplate.execute(status -> claimMySql(parameters)),
            "MySQL Outbox claim result must not be null"
        );
    }

    private List<ClaimedMessage> claimMySql(MapSqlParameterSource parameters) {
        List<UUID> ids = jdbc.query(
            dialect.mySqlSelectDueSql(),
            parameters,
            (resultSet, rowNumber) -> database.uuid(resultSet, "id")
        );
        if (ids.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource claimed = new MapSqlParameterSource(parameters.getValues())
            .addValue("ids", ids.stream().map(database::bindUuid).toList());
        int updated = jdbc.update(dialect.mySqlClaimSql(), claimed);
        if (updated != ids.size()) {
            throw new IllegalStateException(
                "MySQL Outbox claim lost selected row ownership"
            );
        }
        List<ClaimedMessage> messages = jdbc.query(
            dialect.mySqlReadClaimsSql(),
            claimed,
            claimedMessageMapper()
        );
        if (messages.size() != ids.size()) {
            throw new IllegalStateException(
                "MySQL Outbox claimed row readback was incomplete"
            );
        }
        return List.copyOf(messages);
    }

    @Override
    public boolean markDelivered(
        UUID messageId,
        String workerId,
        String providerRequestId,
        int responseCode,
        Instant deliveredAt
    ) {
        UUID exactMessageId = Objects.requireNonNull(messageId, "messageId must not be null");
        Instant exactDeliveredAt = Objects.requireNonNull(
            deliveredAt,
            "deliveredAt must not be null"
        );
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
            where id = :id
              and status = 'IN_FLIGHT'
              and locked_by = :workerId
              and locked_until > :deliveredAt
            """;
        return jdbc.update(sql, new MapSqlParameterSource()
            .addValue("id", database.bindUuid(exactMessageId))
            .addValue("workerId", requireText(workerId, "workerId"))
            .addValue("providerRequestId", providerRequestId)
            .addValue("responseCode", responseCode)
            .addValue("deliveredAt", database.bindInstant(exactDeliveredAt))) == 1;
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
        UUID exactMessageId = Objects.requireNonNull(
            messageId,
            "messageId must not be null"
        );
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
        Instant exactAvailableAt = Objects.requireNonNull(
            availableAt,
            "availableAt must not be null"
        );
        Instant exactUpdatedAt = Objects.requireNonNull(
            updatedAt,
            "updatedAt must not be null"
        );
        String sql = """
            update ap_outbox
            set status = 'PENDING',
                attempts = :attempts,
                available_at = :availableAt,
                last_error = :lastError,
                updated_at = :updatedAt,
                locked_by = null,
                locked_until = null
            where id = :id
              and status = 'IN_FLIGHT'
              and locked_by = :workerId
              and locked_until > :updatedAt
            """;
        return jdbc.update(sql, new MapSqlParameterSource()
            .addValue("id", database.bindUuid(exactMessageId))
            .addValue("workerId", requireText(workerId, "workerId"))
            .addValue("attempts", attempts)
            .addValue("availableAt", database.bindInstant(exactAvailableAt))
            .addValue("lastError", errorMessage)
            .addValue("updatedAt", database.bindInstant(exactUpdatedAt))) == 1;
    }

    @Override
    public boolean markDead(
        UUID messageId,
        String workerId,
        int attempts,
        String errorMessage,
        Instant deadAt
    ) {
        UUID exactMessageId = Objects.requireNonNull(
            messageId,
            "messageId must not be null"
        );
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
        Instant exactDeadAt = Objects.requireNonNull(
            deadAt,
            "deadAt must not be null"
        );
        String sql = """
            update ap_outbox
            set status = 'DEAD',
                attempts = :attempts,
                last_error = :lastError,
                dead_at = :deadAt,
                updated_at = :deadAt,
                locked_by = null,
                locked_until = null
            where id = :id
              and status = 'IN_FLIGHT'
              and locked_by = :workerId
              and locked_until > :deadAt
            """;
        return jdbc.update(sql, new MapSqlParameterSource()
            .addValue("id", database.bindUuid(exactMessageId))
            .addValue("workerId", requireText(workerId, "workerId"))
            .addValue("attempts", attempts)
            .addValue("lastError", errorMessage)
            .addValue("deadAt", database.bindInstant(exactDeadAt))) == 1;
    }

    private RowMapper<ClaimedMessage> claimedMessageMapper() {
        return (resultSet, rowNumber) -> {
            Instant createdAt = database.instant(resultSet, "created_at");
            ConnectorContext context = new ConnectorContext(
                resultSet.getString("connector_key"),
                resultSet.getString("tenant_id"),
                resultSet.getString("request_id"),
                resultSet.getString("trace_id"),
                createdAt
            );
            BusinessEvent event = new BusinessEvent(
                database.uuid(resultSet, "event_id"),
                resultSet.getString("event_type"),
                resultSet.getString("aggregate_type"),
                resultSet.getString("aggregate_id"),
                database.instant(resultSet, "occurred_at"),
                resultSet.getString("idempotency_key"),
                decodePayload(payloadJson(resultSet))
            );
            OutboxMessage message = new OutboxMessage(
                database.uuid(resultSet, "id"),
                context,
                event,
                database.instant(resultSet, "available_at"),
                createdAt
            );
            return new ClaimedMessage(
                message,
                resultSet.getInt("attempts"),
                resultSet.getString("locked_by"),
                database.instant(resultSet, "locked_until")
            );
        };
    }

    private String payloadJson(ResultSet resultSet) throws SQLException {
        String json = database == JdbcIntegrationDatabaseDialect.MYSQL
            ? resultSet.getString("payload_json_text")
            : resultSet.getString("payload_json");
        if (json == null) {
            throw new SQLException("Outbox payload JSON envelope is missing or invalid");
        }
        return json;
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

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
