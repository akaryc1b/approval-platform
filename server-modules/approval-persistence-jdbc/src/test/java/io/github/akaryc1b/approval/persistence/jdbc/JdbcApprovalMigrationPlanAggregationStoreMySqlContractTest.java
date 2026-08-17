package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationPlanAggregationStoreMySqlContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path JDBC_ROOT = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc"
    );

    @Test
    void mysqlD8UsesBoundedTransactionLockPortableValuesAndExactEvidence()
        throws IOException {
        String d8 = Files.readString(JDBC_ROOT.resolve(
            "JdbcMySqlApprovalMigrationPlanAggregationStore.java"
        ));
        String lower = d8.toLowerCase(Locale.ROOT);

        assertTrue(d8.contains("approval-migration-plan-aggregation:v1:"));
        assertTrue(d8.contains("JdbcMySqlTransactionLockManager"));
        assertTrue(d8.contains("locks.acquire("));
        assertTrue(d8.contains("values.bindUuid("));
        assertTrue(d8.contains("values.bindInstant("));
        assertTrue(d8.contains("values.uuid("));
        assertTrue(d8.contains("insert into ap_process_migration_plan_aggregate"));
        assertTrue(d8.contains("insert into ap_process_migration_plan_aggregate_event"));
        assertTrue(d8.contains("insert into ap_process_migration_plan_completion"));
        assertTrue(d8.contains("M5-D8-INSTANCE-FACT-V1"));
        assertTrue(d8.contains("M5-D8-PLAN-SIGNALS-V1"));
        assertTrue(d8.contains("M5-D8-INPUT-EVIDENCE-V1"));
        assertTrue(d8.contains("M5-D8-PLAN-AGGREGATE-V1"));
        assertTrue(d8.contains("M5-D8-PLAN-AGGREGATE-EVENT-V1"));
        assertTrue(d8.contains("M5-D8-PLAN-COMPLETION-V1"));
        assertTrue(d8.contains("M5-D8-PLAN-AGGREGATION-REQUEST-V1"));
        assertTrue(d8.contains("changed plan aggregation replay is forbidden"));
        assertTrue(d8.contains(
            "authoritative aggregation input is unchanged; exact replay must reuse"
        ));
        assertTrue(d8.contains("sealed plan selected count does not match canonical instances"));
        assertTrue(d8.contains("consumed migration plan was not found in tenant"));
        assertTrue(d8.contains("inserted != 1"));

        for (String forbidden : List.of(
            "pg_advisory",
            "left join lateral",
            "array_agg",
            "::text",
            "::integer",
            "::jsonb",
            "as jsonb",
            "cast(:payload as jsonb)",
            "for update of ",
            "foreign_key_checks",
            "insert ignore",
            "replace into",
            "on duplicate key update"
        )) {
            assertFalse(
                lower.contains(forbidden),
                () -> "forbidden MySQL D8 token: " + forbidden
            );
        }
    }

    @Test
    void trustedFactoryOwnsD8VendorSelectionAndServerWiring() throws IOException {
        String factory = Files.readString(JDBC_ROOT.resolve(
            "JdbcApprovalMigrationPlanAggregationStoreFactory.java"
        ));
        String canonical = Files.readString(JDBC_ROOT.resolve(
            "JdbcMySqlCanonicalApprovalMigrationPlanAggregationStore.java"
        ));
        String configuration = Files.readString(ROOT.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalMigrationExecutionConfiguration.java"
        ));

        assertTrue(factory.contains("new ApprovalDatabaseVendorResolver()"));
        assertTrue(factory.contains(".resolve(source)"));
        assertTrue(factory.contains(".vendor()"));
        assertTrue(factory.contains("case POSTGRESQL ->"));
        assertTrue(factory.contains("case MYSQL ->"));
        assertTrue(factory.contains(
            "new PostgresSerializedApprovalMigrationPlanAggregationStore("
        ));
        assertTrue(factory.contains(
            "new JdbcApprovalMigrationPlanAggregationStore("
        ));
        assertTrue(factory.contains(
            "new JdbcMySqlApprovalMigrationPlanAggregationStore("
        ));
        assertTrue(factory.contains(
            "new JdbcMySqlCanonicalApprovalMigrationPlanAggregationStore("
        ));
        assertTrue(canonical.contains("AuditHashCanonicalizer.canonicalInstant("));
        assertTrue(configuration.contains(
            "JdbcApprovalMigrationPlanAggregationStoreFactory.create("
        ));
        assertFalse(configuration.contains(
            "new JdbcApprovalMigrationPlanAggregationStore("
        ));
        assertFalse(configuration.contains(
            "new JdbcMySqlApprovalMigrationPlanAggregationStore("
        ));
    }

    @Test
    void applicationD8BoundaryRemainsDatabaseNeutralAndReadOnly() throws IOException {
        String source = Files.readString(ROOT.resolve(
            "server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/"
                + "application/port/ApprovalMigrationPlanAggregationStore.java"
        )).toLowerCase(Locale.ROOT);

        assertFalse(source.contains("mysql"));
        assertFalse(source.contains("postgresql"));
        assertFalse(source.contains("flowable"));
        assertFalse(source.contains("runtimebinding"));
    }

    private static Path repositoryRoot() {
        String configured = System.getProperty("maven.multiModuleProjectDirectory");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path current = Path.of(System.getProperty("user.dir"))
            .toAbsolutePath()
            .normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                && Files.isDirectory(current.resolve("apps/server"))
                && Files.isDirectory(current.resolve("server-modules"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root could not be resolved");
    }
}
