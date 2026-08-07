package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.persistence.jdbc.ApprovalDatabaseVendor;
import io.github.akaryc1b.approval.persistence.jdbc.ApprovalDatabaseVendorResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalDatabaseCompatibilityConfigurationTest {

    @Test
    void validatesPostgreSqlByDefault() {
        contextRunner(dataSource("PostgreSQL", "16.4", 16, 4)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(
                ApprovalDatabaseVendorResolver.DatabaseIdentity.class
            );
            var identity = context.getBean(
                ApprovalDatabaseVendorResolver.DatabaseIdentity.class
            );
            assertThat(identity.vendor()).isEqualTo(ApprovalDatabaseVendor.POSTGRESQL);
        });
    }

    @Test
    void validatesMySql84WhenExplicitlySelected() {
        contextRunner(dataSource("MySQL", "8.4.2", 8, 4))
            .withPropertyValues("approval.database.expected-vendor=MYSQL")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var identity = context.getBean(
                    ApprovalDatabaseVendorResolver.DatabaseIdentity.class
                );
                assertThat(identity.vendor()).isEqualTo(ApprovalDatabaseVendor.MYSQL);
            });
    }

    @Test
    void failsClosedWhenConfiguredVendorDoesNotMatchJdbcMetadata() {
        contextRunner(dataSource("PostgreSQL", "16.4", 16, 4))
            .withPropertyValues("approval.database.expected-vendor=MYSQL")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(
                        ApprovalDatabaseVendorResolver
                            .DatabaseVendorMismatchException.class
                    );
            });
    }

    @Test
    void rejectsUnsupportedVendorWithoutAValidationBypass() {
        contextRunner(dataSource("Unsupported", "1.0", 1, 0)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(
                    ApprovalDatabaseVendor.UnsupportedDatabaseVendorException.class
                );
        });
    }

    private static ApplicationContextRunner contextRunner(DataSource dataSource) {
        return new ApplicationContextRunner()
            .withUserConfiguration(ApprovalDatabaseCompatibilityConfiguration.class)
            .withBean(DataSource.class, () -> dataSource);
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
