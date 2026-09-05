package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.persistence.jdbc.ApprovalDatabaseVendorResolver.DatabaseIdentity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Validates database session and storage settings required by product semantics. */
public final class ApprovalDatabaseRuntimeBaselineValidator {

    private static final Set<String> REQUIRED_MYSQL_SQL_MODES = Set.of(
        "STRICT_TRANS_TABLES",
        "ERROR_FOR_DIVISION_BY_ZERO",
        "NO_ENGINE_SUBSTITUTION"
    );

    public DatabaseRuntimeBaseline validate(
        DataSource dataSource,
        DatabaseIdentity identity
    ) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        DatabaseIdentity exactIdentity = Objects.requireNonNull(
            identity,
            "identity must not be null"
        );
        try (Connection connection = source.getConnection()) {
            return switch (exactIdentity.vendor()) {
                case POSTGRESQL -> validatePostgreSql(connection);
                case MYSQL -> validateMySql(connection);
            };
        } catch (SQLException exception) {
            throw new DatabaseRuntimeBaselineValidationException(exception);
        }
    }

    private static DatabaseRuntimeBaseline validatePostgreSql(Connection connection)
        throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                 select
                     current_setting('TimeZone'),
                     current_setting('transaction_isolation')
                 """)) {
            requireFirstRow(result, ApprovalDatabaseVendor.POSTGRESQL);
            String timeZone = requireText(result.getString(1), "PostgreSQL TimeZone");
            String isolation = requireText(
                result.getString(2),
                "PostgreSQL transaction isolation"
            );
            if (!Set.of("UTC", "Etc/UTC").contains(timeZone)) {
                throw new InvalidDatabaseRuntimeBaselineException(
                    "PostgreSQL TimeZone must be UTC"
                );
            }
            if (!"read committed".equals(isolation.toLowerCase(Locale.ROOT))) {
                throw new InvalidDatabaseRuntimeBaselineException(
                    "PostgreSQL transaction isolation must be read committed"
                );
            }
            requireNoAdditionalRows(result, ApprovalDatabaseVendor.POSTGRESQL);
            return new DatabaseRuntimeBaseline(
                ApprovalDatabaseVendor.POSTGRESQL,
                Map.of(
                    "timeZone", timeZone,
                    "transactionIsolation", isolation
                )
            );
        }
    }

    private static DatabaseRuntimeBaseline validateMySql(Connection connection)
        throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                 select
                     @@character_set_server,
                     @@collation_server,
                     @@character_set_connection,
                     @@collation_connection,
                     @@session.time_zone,
                     @@session.sql_mode,
                     @@default_storage_engine,
                     @@session.transaction_isolation,
                     @@innodb_strict_mode
                 """)) {
            requireFirstRow(result, ApprovalDatabaseVendor.MYSQL);
            String serverCharacterSet = requireText(
                result.getString(1),
                "MySQL server character set"
            );
            String serverCollation = requireText(
                result.getString(2),
                "MySQL server collation"
            );
            String connectionCharacterSet = requireText(
                result.getString(3),
                "MySQL connection character set"
            );
            String connectionCollation = requireText(
                result.getString(4),
                "MySQL connection collation"
            );
            String timeZone = requireText(result.getString(5), "MySQL session time zone");
            String sqlMode = requireText(result.getString(6), "MySQL SQL mode");
            String storageEngine = requireText(
                result.getString(7),
                "MySQL default storage engine"
            );
            String isolation = requireText(
                result.getString(8),
                "MySQL transaction isolation"
            );
            boolean innodbStrictMode = result.getBoolean(9);

            requireExact("utf8mb4", serverCharacterSet, "MySQL server character set");
            requireExact(
                "utf8mb4_0900_as_cs",
                serverCollation,
                "MySQL server collation"
            );
            requireExact(
                "utf8mb4",
                connectionCharacterSet,
                "MySQL connection character set"
            );
            requireExact(
                "utf8mb4_0900_as_cs",
                connectionCollation,
                "MySQL connection collation"
            );
            requireExact("+00:00", timeZone, "MySQL session time zone");
            if (!"InnoDB".equalsIgnoreCase(storageEngine)) {
                throw new InvalidDatabaseRuntimeBaselineException(
                    "MySQL default storage engine must be InnoDB"
                );
            }
            requireExact(
                "READ-COMMITTED",
                isolation.toUpperCase(Locale.ROOT),
                "MySQL transaction isolation"
            );
            if (!innodbStrictMode) {
                throw new InvalidDatabaseRuntimeBaselineException(
                    "MySQL InnoDB strict mode must be enabled"
                );
            }
            Set<String> actualModes = Arrays.stream(sqlMode.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
            if (!actualModes.containsAll(REQUIRED_MYSQL_SQL_MODES)) {
                throw new InvalidDatabaseRuntimeBaselineException(
                    "MySQL SQL mode is missing required strict modes"
                );
            }

            Map<String, String> settings = new LinkedHashMap<>();
            settings.put("serverCharacterSet", serverCharacterSet);
            settings.put("serverCollation", serverCollation);
            settings.put("connectionCharacterSet", connectionCharacterSet);
            settings.put("connectionCollation", connectionCollation);
            settings.put("timeZone", timeZone);
            settings.put("sqlMode", sqlMode);
            settings.put("storageEngine", storageEngine);
            settings.put("transactionIsolation", isolation);
            settings.put("innodbStrictMode", Boolean.toString(innodbStrictMode));
            requireNoAdditionalRows(result, ApprovalDatabaseVendor.MYSQL);
            return new DatabaseRuntimeBaseline(ApprovalDatabaseVendor.MYSQL, settings);
        }
    }

    private static void requireFirstRow(
        ResultSet result,
        ApprovalDatabaseVendor vendor
    ) throws SQLException {
        if (!result.next()) {
            throw new InvalidDatabaseRuntimeBaselineException(
                vendor + " runtime baseline query returned no row"
            );
        }
    }

    private static void requireNoAdditionalRows(
        ResultSet result,
        ApprovalDatabaseVendor vendor
    ) throws SQLException {
        if (result.next()) {
            throw new InvalidDatabaseRuntimeBaselineException(
                vendor + " runtime baseline query returned multiple rows"
            );
        }
    }

    private static void requireExact(String expected, String actual, String name) {
        if (!expected.equals(actual)) {
            throw new InvalidDatabaseRuntimeBaselineException(
                name + " does not match the required production baseline"
            );
        }
    }

    private static String requireText(String value, String name) {
        String exact = Objects.requireNonNull(value, name + " must not be null").strip();
        if (exact.isEmpty()) {
            throw new InvalidDatabaseRuntimeBaselineException(name + " must not be blank");
        }
        return exact;
    }

    public record DatabaseRuntimeBaseline(
        ApprovalDatabaseVendor vendor,
        Map<String, String> settings
    ) {
        public DatabaseRuntimeBaseline {
            vendor = Objects.requireNonNull(vendor, "vendor must not be null");
            settings = Map.copyOf(
                Objects.requireNonNull(settings, "settings must not be null")
            );
        }
    }

    public static final class InvalidDatabaseRuntimeBaselineException
        extends IllegalStateException {

        public InvalidDatabaseRuntimeBaselineException(String message) {
            super(message);
        }
    }

    public static final class DatabaseRuntimeBaselineValidationException
        extends IllegalStateException {

        public DatabaseRuntimeBaselineValidationException(SQLException cause) {
            super("database runtime baseline validation failed", cause);
        }
    }
}
