package db.mysqlmigration;

import org.flywaydb.core.api.FlywayException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Objects;

/** Fail-closed server prerequisite for installing the governed MySQL V50 D7 triggers. */
final class MySqlV50TriggerInstallationAuthority {

    private static final String GLOBAL_VARIABLE_QUERY = """
        select
          @@global.log_bin as log_bin,
          @@global.log_bin_trust_function_creators as log_bin_trust_function_creators
        """.strip();
    private static final String REQUIREMENT =
        "MySQL 8.4 D7 trigger installation requires "
            + "@@GLOBAL.log_bin_trust_function_creators=ON when "
            + "@@GLOBAL.log_bin=ON; configure the server before Flyway. "
            + "The migration will not change global variables or disable binary logging";

    private MySqlV50TriggerInstallationAuthority() {
    }

    static void require(Connection connection) {
        Connection exact = Objects.requireNonNull(
            connection,
            "connection must not be null"
        );
        try (Statement statement = exact.createStatement();
             ResultSet result = statement.executeQuery(GLOBAL_VARIABLE_QUERY)) {
            if (!result.next()) {
                throw new FlywayException(
                    "MySQL 8.4 trigger installation authority query returned no row"
                );
            }
            boolean binaryLogging = booleanValue(
                result.getObject("log_bin"),
                "@@GLOBAL.log_bin"
            );
            boolean trustedCreators = booleanValue(
                result.getObject("log_bin_trust_function_creators"),
                "@@GLOBAL.log_bin_trust_function_creators"
            );
            if (result.next()) {
                throw new FlywayException(
                    "MySQL 8.4 trigger installation authority query returned multiple rows"
                );
            }
            require(binaryLogging, trustedCreators);
        } catch (SQLException exception) {
            throw new FlywayException(
                "Unable to verify MySQL 8.4 trigger installation authority",
                exception
            );
        }
    }

    static void require(boolean binaryLogging, boolean trustedCreators) {
        if (binaryLogging && !trustedCreators) {
            throw new FlywayException(REQUIREMENT);
        }
    }

    static boolean booleanValue(Object value, String name) {
        if (value instanceof Boolean exact) {
            return exact;
        }
        if (value instanceof Number number) {
            long exact = number.longValue();
            if ((exact == 0 || exact == 1) && number.doubleValue() == exact) {
                return exact == 1;
            }
        }
        if (value instanceof CharSequence text) {
            return switch (text.toString().trim().toUpperCase(Locale.ROOT)) {
                case "1", "ON", "TRUE" -> true;
                case "0", "OFF", "FALSE" -> false;
                default -> throw invalidValue(name, value);
            };
        }
        throw invalidValue(name, value);
    }

    static String globalVariableQuery() {
        return GLOBAL_VARIABLE_QUERY;
    }

    static String requirement() {
        return REQUIREMENT;
    }

    private static FlywayException invalidValue(String name, Object value) {
        String type = value == null ? "null" : value.getClass().getName();
        return new FlywayException(
            "Unsupported MySQL global variable value for " + name + ": " + type
        );
    }
}
