package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
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

/** Stores the completed command result in the same transaction as its side effects. */
public final class JdbcIdempotencyGuard implements IdempotencyGuard {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final JdbcIdempotencyDialect dialect;

    public JdbcIdempotencyGuard(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        Clock clock
    ) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        this.dialect = JdbcIdempotencyDialect.resolve(source);
        this.jdbc = new NamedParameterJdbcTemplate(source);
        ObjectMapper copy = Objects.requireNonNull(
            objectMapper,
            "objectMapper must not be null"
        ).copy();
        this.objectMapper = ApprovalDefinitionJacksonSupport.configure(copy)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
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
        MapSqlParameterSource admission = new MapSqlParameterSource()
            .addValue("tenantId", context.tenantId())
            .addValue("operation", operation)
            .addValue("idempotencyKey", context.idempotencyKey())
            .addValue("requestHash", requestHash)
            .addValue("requestId", context.requestId())
            .addValue("traceId", context.traceId())
            .addValue("createdAt", offset(clock.instant()));
        int inserted;
        try {
            inserted = jdbc.update(dialect.admissionSql(), admission);
        } catch (RuntimeException exception) {
            if (!dialect.isExpectedDuplicateAdmission(exception)) {
                throw exception;
            }
            inserted = 0;
        }
        if (inserted == 0) {
            return replay(context, operation, requestHash, resultType);
        }
        if (inserted != 1) {
            throw new IllegalStateException("unexpected idempotency admission row count");
        }

        T result = Objects.requireNonNull(action.get(), "command action returned null");
        int completed = jdbc.update(
            dialect.completionSql(),
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
            dialect.replaySql(),
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
        String resultJson = Objects.toString(row.get("result_json"), null);
        if (resultJson == null || resultJson.isBlank()) {
            throw new IllegalStateException(
                "idempotency result payload is missing or has an unsupported encoding"
            );
        }
        return decode(resultJson, resultType);
    }

    private String encode(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "unable to encode idempotent command result",
                exception
            );
        }
    }

    private <T> T decode(String json, Class<T> resultType) {
        try {
            return objectMapper.readValue(json, resultType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "unable to decode idempotent command result",
                exception
            );
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
            throw new IllegalArgumentException(
                "requestHash must be a lowercase SHA-256 value"
            );
        }
    }
}
