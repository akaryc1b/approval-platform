package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalDatabaseVendorResolverTest {

    private final ApprovalDatabaseVendorResolver resolver =
        new ApprovalDatabaseVendorResolver();

    @Test
    void acceptsPostgreSql16FromJdbcMetadata() {
        var identity = resolver.resolve(
            dataSource("PostgreSQL", "16.4", 16, 4),
            ApprovalDatabaseVendor.POSTGRESQL
        );

        assertEquals(ApprovalDatabaseVendor.POSTGRESQL, identity.vendor());
        assertEquals("PostgreSQL", identity.productName());
        assertEquals("16.4", identity.productVersion());
        assertEquals(16, identity.majorVersion());
        assertEquals(4, identity.minorVersion());
    }

    @Test
    void acceptsExactMySql84Baseline() {
        var identity = resolver.resolve(
            dataSource("MySQL", "8.4.2", 8, 4),
            ApprovalDatabaseVendor.MYSQL
        );

        assertEquals(ApprovalDatabaseVendor.MYSQL, identity.vendor());
        assertEquals("MySQL", identity.productName());
        assertEquals("8.4.2", identity.productVersion());
    }

    @Test
    void rejectsMySql80AsUnsupportedProductionBaseline() {
        var exception = assertThrows(
            ApprovalDatabaseVendor.UnsupportedDatabaseVersionException.class,
            () -> resolver.resolve(
                dataSource("MySQL", "8.0.43", 8, 0),
                ApprovalDatabaseVendor.MYSQL
            )
        );

        assertTrue(exception.getMessage().contains("required 8.4"));
    }

    @Test
    void rejectsMariaDbInsteadOfTreatingItAsMySql() {
        assertThrows(
            ApprovalDatabaseVendor.UnsupportedDatabaseVendorException.class,
            () -> resolver.resolve(
                dataSource("MariaDB", "11.8.2", 11, 8),
                ApprovalDatabaseVendor.MYSQL
            )
        );
    }

    @Test
    void rejectsConfiguredVendorMismatch() {
        assertThrows(
            ApprovalDatabaseVendorResolver.DatabaseVendorMismatchException.class,
            () -> resolver.resolve(
                dataSource("PostgreSQL", "16.4", 16, 4),
                ApprovalDatabaseVendor.MYSQL
            )
        );
    }

    @Test
    void wrapsMetadataAccessFailureWithoutIncludingConnectionDetails() {
        DataSource failing = proxy(DataSource.class, (ignored, method, arguments) -> {
            if ("getConnection".equals(method.getName())) {
                throw new SQLException("jdbc:mysql://secret-host/approval?password=secret");
            }
            return defaultValue(method.getReturnType());
        });

        var exception = assertThrows(
            ApprovalDatabaseVendorResolver.DatabaseVendorResolutionException.class,
            () -> resolver.resolve(failing, ApprovalDatabaseVendor.MYSQL)
        );

        assertEquals("database identity resolution failed", exception.getMessage());
    }

    @Test
    void parsesOnlySupportedExpectedVendorValues() {
        assertEquals(
            ApprovalDatabaseVendor.POSTGRESQL,
            ApprovalDatabaseVendor.parseExpected("postgresql")
        );
        assertEquals(
            ApprovalDatabaseVendor.MYSQL,
            ApprovalDatabaseVendor.parseExpected(" MYSQL ")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> ApprovalDatabaseVendor.parseExpected("mariadb")
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
