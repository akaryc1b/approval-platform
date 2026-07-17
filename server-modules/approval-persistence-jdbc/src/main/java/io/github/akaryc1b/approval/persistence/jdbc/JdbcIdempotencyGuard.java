package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Stores the completed command result in the same transaction as the command side effects.
 */
public final class JdbcIdempotencyGuard implements IdempotencyGuard {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public JdbcIdempotencyGuard(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        Clock clock
    ) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.transactionTemplate = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public <T> T execute(
        RequestContext context,
        String operation,
        String requestHash,
        Class<T> resultType,
        Supplier<T> action
    ) {
        Objects.requireNonNull(context, "context must not be null");
        requireText(operation, "operation");
        requireHash(requestHash);
        Objects.requireNonNull(resultType, "resultType must not be null");
        Objects.requireNonNull(action, "action must not be null");

        T result = transactionTemplate.execute(status -> executeInTransaction(
            context,
            operation,
            requestHash,
            resultType,
            action
        ));
        return Objects.requireNonNull(result, "idempotent command result must not be null");
    }

    private <T> T executeInTransaction(
        RequestContext context,
        String operation,
        String requestHash,
        Class<T> resultType,
        Supplier<T> action
    ) {
        Instant now = clock.instant();
        int inserted = jdbc.update(
            """
            insert into ap_command_idempotency (
                tenant_id, operation, idempotency_key, request_hash,
                request_id, trace_id, status, created_at
            ) values (
                :tenantId, :operation, :idempotencyKey, :requestHash,
                :requestId, :traceId, 'IN_PROGRESS', :createdAt
            )
            on conflict (tenant_id, operation, idempotency_key) do nothing
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", context.tenantId())
                .addValue("operation", operation)
                .addValue("idempotencyKey", context.idempotencyKey())
                .addValue("requestHash", requestHash)
                .addValue("requestId", context.requestId())
                .addValue("traceId", context.traceId())
                .addValue("createdAt", offset(now))
        );
        if (inserted == 0) {
            return replay(context, operation, requestHash, resultType);
        }

        T result = Objects.requireNonNull(action.get(), "command action returned null");
        int completed = jdbc.update(
            """
            update ap_command_idempotency
            set result_type = :resultType,
                result_json = cast(:resultJson as jsonb),
                status = 'COMPLETED',
                completed_at = :completedAt
            where tenant_id = :tenantId
              and operation = :operation
              and idempotency_key = :idempotencyKey
              and status = 'IN_PROGRESS'
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", context.tenantId())
                .addValue("operation", operation)
                .addValue("idempotencyKey", context.idempotencyKey())
                .addValue("resultType", resultType.getName())
                .addValue("resultJson", encode(result))
                .addValue("completedAt", offset(clock.instant()))
        );
        if (completed != 1) {
            throw new IllegalStateException("idempotency result could not be completed");
        }
        return result;
    }

    private <T> T replay(
        RequestContext context,
        String operation,
        String requestHash,
        Class<T> resultType
    ) {
        Map<String, Object> row = jdbc.queryForMap(
            """
            select request_hash, result_type, result_json::text as result_json, status
            from ap_command_idempotency
            where tenant_id = :tenantId
              and operation = :operation
              and idempotency_key = :idempotencyKey
            for update
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", context.tenantId())
                .addValue("operation", operation)
                .addValue("idempotencyKey", context.idempotencyKey())
        );
        if (!requestHash.equals(row.get("request_hash"))) {
            throw new IdempotencyConflictException(
                "idempotency key was already used with a different request payload"
            );
        }
        if (!"COMPLETED".equals(row.get("status"))) {
            throw new IllegalStateException("idempotency record is not completed");
        }
        if (!resultType.getName().equals(row.get("result_type"))) {
            throw new IdempotencyConflictException(
                "idempotency key was already used for a different result type"
            );
        }
        return decode(Objects.toString(row.get("result_json")), resultType);
    }

    private String encode(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("unable to encode idempotent command result", exception);
        }
    }

    private <T> T decode(String json, Class<T> resultType) {
        try {
            return objectMapper.readValue(json, resultType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("unable to decode idempotent command result", exception);
        }
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireHash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash must be a lowercase SHA-256 value");
        }
    }
}
