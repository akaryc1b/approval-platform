package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.persistence.jdbc.ApprovalDatabaseRuntimeBaselineValidator;
import io.github.akaryc1b.approval.persistence.jdbc.ApprovalDatabaseVendor;
import io.github.akaryc1b.approval.persistence.jdbc.ApprovalDatabaseVendorResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalDatabaseCompatibilityConfigurationTest {

    @Test
    void validatesPostgreSqlByDefault() {
        contextRunner(postgreSqlDataSource()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(
                ApprovalDatabaseVendorResolver.DatabaseIdentity.class
            );
            assertThat(context).hasSingleBean(
                ApprovalDatabaseRuntimeBaselineValidator.DatabaseRuntimeBaseline.class
            );
            assertThat(context).hasSingleBean(ApprovalDatabaseAuthorityBoundary.class);
            var identity = context.getBean(
                ApprovalDatabaseVendorResolver.DatabaseIdentity.class
            );
            assertThat(identity.vendor()).isEqualTo(ApprovalDatabaseVendor.POSTGRESQL);
        });
    }

    @Test
    void validatesMySql84WhenExplicitlySelected() {
        contextRunner(mySqlDataSource())
            .withPropertyValues(
                "approval.database.expected-vendor=MYSQL",
                "approval.database.runtime-identity=approval_runtime",
                "approval.database.migration-identity=approval_migrator"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasBean("approvalDatabaseAuthorityBoundary");
                var identity = context.getBean(
                    ApprovalDatabaseVendorResolver.DatabaseIdentity.class
                );
                var baseline = context.getBean(
                    ApprovalDatabaseRuntimeBaselineValidator.DatabaseRuntimeBaseline.class
                );
                var authority = context.getBean(
                    ApprovalDatabaseAuthorityBoundary.class
                );
                assertThat(identity.vendor()).isEqualTo(ApprovalDatabaseVendor.MYSQL);
                assertThat(baseline.settings())
                    .containsEntry("transactionIsolation", "READ-COMMITTED");
                assertThat(authority.runtimeIdentity()).isEqualTo("approval_runtime");
                assertThat(authority.migrationIdentity()).isEqualTo("approval_migrator");
                assertThat(authority.separated()).isTrue();
            });
    }

    @Test
    void rejectsMatchingMySqlRuntimeAndMigrationIdentities() {
        contextRunner(mySqlDataSource("approval@%"))
            .withPropertyValues(
                "approval.database.expected-vendor=MYSQL",
                "approval.database.runtime-identity=approval",
                "approval.database.migration-identity=APPROVAL"
            )
            .run(context -> assertStartupFailure(
                context.getStartupFailure(),
                "MySQL runtime and migration identities must be distinct"
            ));
    }

    @Test
    void rejectsMissingMySqlMigrationIdentity() {
        contextRunner(mySqlDataSource())
            .withPropertyValues(
                "approval.database.expected-vendor=MYSQL",
                "approval.database.runtime-identity=approval_runtime"
            )
            .run(context -> assertStartupFailure(
                context.getStartupFailure(),
                "MySQL migration identity must not be blank"
            ));
    }

    @Test
    void rejectsConfiguredRuntimeIdentityThatDoesNotMatchJdbcSession() {
        contextRunner(mySqlDataSource("actual_runtime@%"))
            .withPropertyValues(
                "approval.database.expected-vendor=MYSQL",
                "approval.database.runtime-identity=configured_runtime",
                "approval.database.migration-identity=approval_migrator"
            )
            .run(context -> assertStartupFailure(
                context.getStartupFailure(),
                "configured MySQL runtime identity does not match the JDBC session"
            ));
    }

    @Test
    void failsClosedWhenConfiguredVendorDoesNotMatchJdbcMetadata() {
        contextRunner(postgreSqlDataSource())
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
        contextRunner(dataSource(
            "Unsupported",
            "1.0",
            1,
            0,
            "unsupported",
            new Object[]{"UTC", "read committed"}
        )).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(
                    ApprovalDatabaseVendor.UnsupportedDatabaseVendorException.class
                );
        });
    }

    private static void assertStartupFailure(
        Throwable failure,
        String expectedMessage
    ) {
        assertThat(failure).isNotNull();
        assertThat(failure).hasRootCauseInstanceOf(
            ApprovalDatabaseAuthorityBoundary
                .InvalidDatabaseAuthorityBoundaryException.class
        );
        assertThat(rootCause(failure)).hasMessageContaining(expectedMessage);
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static ApplicationContextRunner contextRunner(DataSource dataSource) {
        return new ApplicationContextRunner()
            .withUserConfiguration(ApprovalDatabaseCompatibilityConfiguration.class)
            .withBean(DataSource.class, () -> dataSource);
    }

    private static DataSource postgreSqlDataSource() {
        return dataSource(
            "PostgreSQL",
            "16.4",
            16,
            4,
            "approval_postgresql",
            new Object[]{"UTC", "read committed"}
        );
    }

    private static DataSource mySqlDataSource() {
        return mySqlDataSource("approval_runtime@%");
    }

    private static DataSource mySqlDataSource(String userName) {
        return dataSource(
            "MySQL",
            "8.4.2",
            8,
            4,
            userName,
            new Object[]{
                "utf8mb4",
                "utf8mb4_0900_as_cs",
                "utf8mb4",
                "utf8mb4_0900_as_cs",
                "+00:00",
                "STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION",
                "InnoDB",
                "READ-COMMITTED",
                true
            }
        );
    }

    private static DataSource dataSource(
        String productName,
        String productVersion,
        int majorVersion,
        int minorVersion,
        String userName,
        Object[] runtimeRow
    ) {
        DatabaseMetaData metadata = proxy(
            DatabaseMetaData.class,
            (ignored, method, arguments) -> switch (method.getName()) {
                case "getDatabaseProductName" -> productName;
                case "getDatabaseProductVersion" -> productVersion;
                case "getDatabaseMajorVersion" -> majorVersion;
                case "getDatabaseMinorVersion" -> minorVersion;
                case "getUserName" -> userName;
                default -> defaultValue(method.getReturnType());
            }
        );
        ResultSet resultSet = resultSet(runtimeRow);
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
                case "getMetaData" -> metadata;
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
