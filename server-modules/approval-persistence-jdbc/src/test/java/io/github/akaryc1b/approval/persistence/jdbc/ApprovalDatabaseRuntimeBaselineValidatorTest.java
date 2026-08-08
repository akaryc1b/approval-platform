package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.persistence.jdbc.ApprovalDatabaseRuntimeBaselineValidator.InvalidDatabaseRuntimeBaselineException;
import io.github.akaryc1b.approval.persistence.jdbc.ApprovalDatabaseVendorResolver.DatabaseIdentity;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalDatabaseRuntimeBaselineValidatorTest {

    private final ApprovalDatabaseRuntimeBaselineValidator validator =
        new ApprovalDatabaseRuntimeBaselineValidator();

    @Test
    void acceptsPostgreSqlUtcReadCommittedBaseline() {
        var baseline = validator.validate(
            dataSource(row("UTC", "read committed")),
            identity(ApprovalDatabaseVendor.POSTGRESQL, 16, 4)
        );

        assertEquals(ApprovalDatabaseVendor.POSTGRESQL, baseline.vendor());
        assertEquals("UTC", baseline.settings().get("timeZone"));
    }

    @Test
    void rejectsPostgreSqlNonUtcSession() {
        assertThrows(
            InvalidDatabaseRuntimeBaselineException.class,
            () -> validator.validate(
                dataSource(row("America/New_York", "read committed")),
                identity(ApprovalDatabaseVendor.POSTGRESQL, 16, 4)
            )
        );
    }

    @Test
    void acceptsExactMySqlProductionBaseline() {
        var baseline = validator.validate(
            dataSource(mySqlRow(
                "utf8mb4_0900_as_cs",
                "STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION"
            )),
            identity(ApprovalDatabaseVendor.MYSQL, 8, 4)
        );

        assertEquals(ApprovalDatabaseVendor.MYSQL, baseline.vendor());
        assertEquals("READ-COMMITTED", baseline.settings().get("transactionIsolation"));
    }

    @Test
    void rejectsCaseInsensitiveMySqlCollation() {
        assertThrows(
            InvalidDatabaseRuntimeBaselineException.class,
            () -> validator.validate(
                dataSource(mySqlRow(
                    "utf8mb4_0900_ai_ci",
                    "STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION"
                )),
                identity(ApprovalDatabaseVendor.MYSQL, 8, 4)
            )
        );
    }

    @Test
    void rejectsMySqlWithoutAllStrictModes() {
        assertThrows(
            InvalidDatabaseRuntimeBaselineException.class,
            () -> validator.validate(
                dataSource(mySqlRow(
                    "utf8mb4_0900_as_cs",
                    "STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION"
                )),
                identity(ApprovalDatabaseVendor.MYSQL, 8, 4)
            )
        );
    }

    @Test
    void wrapsSqlFailureWithoutLeakingConnectionDetails() {
        DataSource failing = proxy(DataSource.class, (ignored, method, arguments) -> {
            if ("getConnection".equals(method.getName())) {
                throw new SQLException("jdbc:mysql://secret-host/approval?password=secret");
            }
            return defaultValue(method.getReturnType());
        });

        var exception = assertThrows(
            ApprovalDatabaseRuntimeBaselineValidator
                .DatabaseRuntimeBaselineValidationException.class,
            () -> validator.validate(
                failing,
                identity(ApprovalDatabaseVendor.MYSQL, 8, 4)
            )
        );

        assertEquals("database runtime baseline validation failed", exception.getMessage());
    }

    private static DatabaseIdentity identity(
        ApprovalDatabaseVendor vendor,
        int majorVersion,
        int minorVersion
    ) {
        return new DatabaseIdentity(
            vendor,
            vendor.productName(),
            majorVersion + "." + minorVersion + ".0",
            majorVersion,
            minorVersion
        );
    }

    private static Object[] mySqlRow(String collation, String sqlMode) {
        return row(
            "utf8mb4",
            collation,
            "utf8mb4",
            collation,
            "+00:00",
            sqlMode,
            "InnoDB",
            "READ-COMMITTED",
            true
        );
    }

    private static Object[] row(Object... values) {
        return values;
    }

    private static DataSource dataSource(Object[] values) {
        ResultSet resultSet = resultSet(values);
        Statement statement = proxy(
            Statement.class,
            (ignored, method, arguments) -> switch (method.getName()) {
                case "executeQuery" -> resultSet;
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            }
        );
        Connection connection = proxy(
            Connection.class,
            (ignored, method, arguments) -> switch (method.getName()) {
                case "createStatement" -> statement;
                case "close" -> null;
                case "isClosed" -> false;
                default -> defaultValue(method.getReturnType());
            }
        );
        return proxy(
            DataSource.class,
            (ignored, method, arguments) -> switch (method.getName()) {
                case "getConnection" -> connection;
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static ResultSet resultSet(Object[] values) {
        int[] cursor = {-1};
        return proxy(
            ResultSet.class,
            (ignored, method, arguments) -> switch (method.getName()) {
                case "next" -> ++cursor[0] == 0;
                case "getString" -> String.valueOf(
                    values[((Integer) arguments[0]) - 1]
                );
                case "getBoolean" -> values[((Integer) arguments[0]) - 1];
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[]{type},
            handler
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        if (type == double.class) {
            return 0.0D;
        }
        throw new IllegalArgumentException("unknown primitive type: " + type);
    }
}
