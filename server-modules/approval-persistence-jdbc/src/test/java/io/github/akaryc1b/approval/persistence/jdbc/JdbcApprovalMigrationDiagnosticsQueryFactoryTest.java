package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcApprovalMigrationDiagnosticsQueryFactoryTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-08-18T09:00:00Z"),
        ZoneOffset.UTC
    );

    @Test
    void selectsTheAcceptedPostgreSqlImplementationFromMetadata() {
        assertInstanceOf(
            JdbcApprovalMigrationDiagnosticsQuery.class,
            JdbcApprovalMigrationDiagnosticsQueryFactory.create(
                dataSource("PostgreSQL", "16.6", 16, 6),
                transactionManager(),
                CLOCK
            )
        );
    }

    @Test
    void selectsTheMySqlImplementationFromMetadata() {
        assertInstanceOf(
            JdbcMySqlApprovalMigrationDiagnosticsQuery.class,
            JdbcApprovalMigrationDiagnosticsQueryFactory.create(
                dataSource("MySQL", "8.4.4", 8, 4),
                transactionManager(),
                CLOCK
            )
        );
    }

    @Test
    void rejectsUnsupportedOrUnacceptedDatabaseIdentity() {
        assertThrows(
            ApprovalDatabaseVendor.UnsupportedDatabaseVendorException.class,
            () -> JdbcApprovalMigrationDiagnosticsQueryFactory.create(
                dataSource("H2", "2.3.232", 2, 3),
                transactionManager(),
                CLOCK
            )
        );
        assertThrows(
            ApprovalDatabaseVendor.UnsupportedDatabaseVersionException.class,
            () -> JdbcApprovalMigrationDiagnosticsQueryFactory.create(
                dataSource("MySQL", "8.0.39", 8, 0),
                transactionManager(),
                CLOCK
            )
        );
    }

    private static PlatformTransactionManager transactionManager() {
        return proxy(
            PlatformTransactionManager.class,
            (ignored, method, arguments) -> defaultValue(method.getReturnType())
        );
    }

    private static DataSource dataSource(
        String productName,
        String productVersion,
        int majorVersion,
        int minorVersion
    ) {
        DatabaseMetaData metadata = proxy(
            DatabaseMetaData.class,
            (ignored, method, arguments) -> switch (method.getName()) {
                case "getDatabaseProductName" -> productName;
                case "getDatabaseProductVersion" -> productVersion;
                case "getDatabaseMajorVersion" -> majorVersion;
                case "getDatabaseMinorVersion" -> minorVersion;
                default -> defaultValue(method.getReturnType());
            }
        );
        Connection connection = proxy(
            Connection.class,
            (ignored, method, arguments) -> switch (method.getName()) {
                case "getMetaData" -> metadata;
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
