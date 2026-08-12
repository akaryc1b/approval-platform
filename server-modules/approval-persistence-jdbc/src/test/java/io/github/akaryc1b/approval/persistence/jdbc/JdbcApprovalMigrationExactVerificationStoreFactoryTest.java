package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExactVerificationStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcApprovalMigrationExactVerificationStoreFactoryTest {

    @Test
    void selectsExistingPostgreSqlVerificationAuthorityFromTrustedMetadata() {
        DataSource dataSource = dataSource("PostgreSQL", "16.6", 16, 6);
        ApprovalMigrationExactVerificationStore store =
            JdbcApprovalMigrationExactVerificationStoreFactory.create(
                dataSource,
                new ObjectMapper().findAndRegisterModules(),
                new JdbcTransactionManager(dataSource),
                event -> {
                },
                UUID::randomUUID
            );

        assertInstanceOf(JdbcApprovalMigrationExactVerificationStore.class, store);
    }

    @Test
    void selectsMySqlVerificationAuthorityFromTrustedMetadata() {
        DataSource dataSource = dataSource("MySQL", "8.4.4", 8, 4);
        ApprovalMigrationExactVerificationStore store =
            JdbcApprovalMigrationExactVerificationStoreFactory.create(
                dataSource,
                new ObjectMapper().findAndRegisterModules(),
                new JdbcTransactionManager(dataSource),
                event -> {
                },
                UUID::randomUUID
            );

        assertInstanceOf(JdbcMySqlApprovalMigrationExactVerificationStore.class, store);
    }

    @Test
    void rejectsUnsupportedMySqlVersionBeforeCreatingVerificationStore() {
        DataSource dataSource = dataSource("MySQL", "8.0.39", 8, 0);

        assertThrows(
            ApprovalDatabaseVendor.UnsupportedDatabaseVersionException.class,
            () -> JdbcApprovalMigrationExactVerificationStoreFactory.create(
                dataSource,
                new ObjectMapper().findAndRegisterModules(),
                new JdbcTransactionManager(dataSource),
                event -> {
                },
                UUID::randomUUID
            )
        );
    }

    @Test
    void rejectsUnsupportedDatabaseProductBeforeCreatingVerificationStore() {
        DataSource dataSource = dataSource("H2", "2.3.232", 2, 3);

        assertThrows(
            ApprovalDatabaseVendor.UnsupportedDatabaseVendorException.class,
            () -> JdbcApprovalMigrationExactVerificationStoreFactory.create(
                dataSource,
                new ObjectMapper().findAndRegisterModules(),
                new JdbcTransactionManager(dataSource),
                event -> {
                },
                UUID::randomUUID
            )
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
