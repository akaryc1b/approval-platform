package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Pre-migrates the closed Run-B failure set through the privileged test authority. */
public final class MySqlSeparatedMigrationExtension implements BeforeAllCallback {

    private static final Set<String> TARGET_CLASSES = Set.of(
        "io.github.akaryc1b.approval.persistence.jdbc."
            + "JdbcApprovalFormDesignDraftStoreMySqlIntegrationTest",
        "io.github.akaryc1b.approval.persistence.jdbc."
            + "JdbcApprovalFormPackageStoreMySqlIntegrationTest",
        "io.github.akaryc1b.approval.persistence.jdbc."
            + "JdbcApprovalFormStoreMySqlIntegrationTest",
        "io.github.akaryc1b.approval.persistence.jdbc."
            + "JdbcApprovalUiSchemaStoreMySqlIntegrationTest",
        "io.github.akaryc1b.approval.persistence.jdbc."
            + "JdbcAuditEventSinkMySqlIntegrationTest",
        "io.github.akaryc1b.approval.persistence.jdbc."
            + "JdbcIdempotencyGuardMySqlContractIntegrationTest",
        "io.github.akaryc1b.approval.persistence.jdbc."
            + "JdbcIdempotencyGuardMySqlIntegrationTest",
        "io.github.akaryc1b.approval.persistence.jdbc."
            + "JdbcMySqlApprovalTaskCasStoreIntegrationTest"
    );

    @Override
    public void beforeAll(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        if (!TARGET_CLASSES.contains(testClass.getName())) {
            return;
        }
        MySQLContainer mysql = requireContainer(testClass);
        if (!mysql.isRunning()) {
            mysql.start();
        }
        DataSource runtimeDataSource = new DriverManagerDataSource(
            configuredJdbcUrl(mysql),
            mysql.getUsername(),
            mysql.getPassword()
        );
        MySqlTestDatabaseAuthority.flyway(mysql, runtimeDataSource)
            .locations("classpath:db/mysqlmigration")
            .failOnMissingLocations(true)
            .validateMigrationNaming(true)
            .load()
            .migrate();
    }

    static Set<String> targetClasses() {
        return TARGET_CLASSES;
    }

    private static MySQLContainer requireContainer(Class<?> testClass) {
        List<MySQLContainer> containers = new ArrayList<>();
        for (Class<?> type = testClass; type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                    || !MySQLContainer.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                if (!field.trySetAccessible()) {
                    throw new IllegalStateException(
                        "cannot access MySQL container for " + testClass.getName()
                    );
                }
                try {
                    containers.add((MySQLContainer) field.get(null));
                } catch (IllegalAccessException exception) {
                    throw new IllegalStateException(
                        "cannot read MySQL container for " + testClass.getName(),
                        exception
                    );
                }
            }
        }
        if (containers.size() != 1) {
            throw new IllegalStateException(
                "expected one MySQL container for "
                    + testClass.getName()
                    + " but found "
                    + containers.size()
            );
        }
        return containers.getFirst();
    }

    private static String configuredJdbcUrl(MySQLContainer mysql) {
        String base = mysql.getJdbcUrl();
        String separator = base.contains("?") ? "&" : "?";
        return base + separator
            + "characterEncoding=UTF-8"
            + "&connectionCollation=utf8mb4_0900_as_cs"
            + "&connectionTimeZone=UTC"
            + "&forceConnectionTimeZoneToSession=true"
            + "&preserveInstants=true"
            + "&useAffectedRows=false";
    }
}
