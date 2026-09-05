package io.github.akaryc1b.approval.integration.jdbc;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

/** Bounded JDBC value and identity handling for integration persistence. */
enum JdbcIntegrationDatabaseDialect {
    POSTGRESQL,
    MYSQL;

    static JdbcIntegrationDatabaseDialect resolve(DataSource dataSource) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        try (Connection connection = source.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String productName = Objects.requireNonNull(
                metadata.getDatabaseProductName(),
                "database product name must not be null"
            ).strip();
            int majorVersion = metadata.getDatabaseMajorVersion();
            int minorVersion = metadata.getDatabaseMinorVersion();
            return switch (productName) {
                case "PostgreSQL" -> {
                    requireVersion(productName, majorVersion, minorVersion, 16, null);
                    yield POSTGRESQL;
                }
                case "MySQL" -> {
                    requireVersion(productName, majorVersion, minorVersion, 8, 4);
                    yield MYSQL;
                }
                default -> throw new UnsupportedIntegrationDatabaseException(
                    "unsupported integration database product: " + productName
                );
            };
        } catch (SQLException exception) {
            throw new UnsupportedIntegrationDatabaseException(
                "integration database identity resolution failed",
                exception
            );
        }
    }

    Object bindUuid(UUID value) {
        UUID exact = Objects.requireNonNull(value, "UUID value must not be null");
        return this == MYSQL ? exact.toString() : exact;
    }

    Object bindInstant(Instant value) {
        Instant exact = Objects.requireNonNull(value, "instant value must not be null");
        return this == MYSQL
            ? LocalDateTime.ofInstant(exact, ZoneOffset.UTC)
            : exact.atOffset(ZoneOffset.UTC);
    }

    UUID uuid(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
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
        if (value == null) {
            throw new SQLException("required UUID column was null: " + column);
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException exception) {
            throw invalidColumnValue(column, value, exception);
        }
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
        if (value instanceof Date date) {
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

    Instant instant(ResultSet resultSet, String column) throws SQLException {
        Instant value = nullableInstant(resultSet, column);
        if (value == null) {
            throw new SQLException("required instant column was null: " + column);
        }
        return value;
    }

    private static void requireVersion(
        String productName,
        int actualMajorVersion,
        int actualMinorVersion,
        int requiredMajorVersion,
        Integer requiredMinorVersion
    ) {
        boolean majorMismatch = actualMajorVersion != requiredMajorVersion;
        boolean minorMismatch = requiredMinorVersion != null
            && actualMinorVersion != requiredMinorVersion;
        if (majorMismatch || minorMismatch) {
            String required = requiredMinorVersion == null
                ? Integer.toString(requiredMajorVersion)
                : requiredMajorVersion + "." + requiredMinorVersion;
            throw new UnsupportedIntegrationDatabaseException(
                "unsupported " + productName + " integration database version "
                    + actualMajorVersion + "." + actualMinorVersion
                    + "; required " + required
            );
        }
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

    static final class UnsupportedIntegrationDatabaseException
        extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        UnsupportedIntegrationDatabaseException(String message) {
            super(message);
        }

        UnsupportedIntegrationDatabaseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
