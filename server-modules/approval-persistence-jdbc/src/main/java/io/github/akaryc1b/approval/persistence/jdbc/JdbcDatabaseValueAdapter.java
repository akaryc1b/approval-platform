package io.github.akaryc1b.approval.persistence.jdbc;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded JDBC value conversion for governed PostgreSQL/MySQL compatibility work. */
final class JdbcDatabaseValueAdapter {

    private final ApprovalDatabaseVendor vendor;

    private JdbcDatabaseValueAdapter(ApprovalDatabaseVendor vendor) {
        this.vendor = Objects.requireNonNull(vendor, "vendor must not be null");
    }

    static JdbcDatabaseValueAdapter resolve(DataSource dataSource) {
        ApprovalDatabaseVendor vendor = new ApprovalDatabaseVendorResolver()
            .resolve(dataSource)
            .vendor();
        return new JdbcDatabaseValueAdapter(vendor);
    }

    ApprovalDatabaseVendor vendor() {
        return vendor;
    }

    Object bindUuid(UUID value) {
        UUID exact = Objects.requireNonNull(value, "UUID value must not be null");
        return vendor == ApprovalDatabaseVendor.MYSQL ? exact.toString() : exact;
    }

    Object bindNullableUuid(UUID value) {
        return value == null ? null : bindUuid(value);
    }

    List<Object> bindUuids(List<UUID> values) {
        Objects.requireNonNull(values, "UUID values must not be null");
        List<Object> bound = new ArrayList<>(values.size());
        for (UUID value : values) {
            bound.add(bindUuid(value));
        }
        return List.copyOf(bound);
    }

    Object bindInstant(Instant value) {
        Instant exact = Objects.requireNonNull(value, "instant value must not be null");
        return vendor == ApprovalDatabaseVendor.MYSQL
            ? LocalDateTime.ofInstant(exact, ZoneOffset.UTC)
            : exact.atOffset(ZoneOffset.UTC);
    }

    Object bindNullableInstant(Instant value) {
        return value == null ? null : bindInstant(value);
    }

    UUID uuid(ResultSet resultSet, String column) throws SQLException {
        UUID value = nullableUuid(resultSet, column);
        if (value == null) {
            throw new SQLException("required UUID column was null: " + column);
        }
        return value;
    }

    UUID nullableUuid(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof CharSequence text) {
            try {
                return UUID.fromString(text.toString());
            } catch (IllegalArgumentException exception) {
                throw invalidColumnValue(column, value, exception);
            }
        }
        if (value instanceof byte[] bytes && bytes.length == 16) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException exception) {
            throw invalidColumnValue(column, value, exception);
        }
    }

    Instant instant(ResultSet resultSet, String column) throws SQLException {
        Instant value = nullableInstant(resultSet, column);
        if (value == null) {
            throw new SQLException("required instant column was null: " + column);
        }
        return value;
    }

    Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toInstant(ZoneOffset.UTC);
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant();
        }
        if (value instanceof CharSequence text) {
            String exact = text.toString();
            try {
                return Instant.parse(exact);
            } catch (RuntimeException ignored) {
                try {
                    return OffsetDateTime.parse(exact).toInstant();
                } catch (RuntimeException alsoIgnored) {
                    try {
                        return LocalDateTime.parse(exact).toInstant(ZoneOffset.UTC);
                    } catch (RuntimeException exception) {
                        throw invalidColumnValue(column, value, exception);
                    }
                }
            }
        }
        throw invalidColumnValue(column, value, null);
    }

    private static SQLException invalidColumnValue(
        String column,
        Object value,
        Throwable cause
    ) {
        String type = value == null ? "null" : value.getClass().getName();
        return new SQLException(
            "unsupported JDBC value type for column " + column + ": " + type,
            cause
        );
    }
}
