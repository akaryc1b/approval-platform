package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/** JSONB and timestamp mapping shared by M5-B JDBC stores. */
final class JdbcApprovalMigrationJson {

    private final ObjectMapper mapper;

    JdbcApprovalMigrationJson(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "objectMapper must not be null");
    }

    String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("migration evidence is not JSON serializable", exception);
        }
    }

    <T> T read(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("persisted migration evidence is invalid", exception);
        }
    }

    static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
