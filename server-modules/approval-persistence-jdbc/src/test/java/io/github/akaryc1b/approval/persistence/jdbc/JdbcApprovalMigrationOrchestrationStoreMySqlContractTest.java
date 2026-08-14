package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationOrchestrationStoreMySqlContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path JDBC_ROOT = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc"
    );

    @Test
    void mysqlD7UsesTransactionLocksPortableValuesAndCompleteEvidence()
        throws IOException {
        String d7 = Files.readString(JDBC_ROOT.resolve(
            "JdbcMySqlApprovalMigrationOrchestrationStore.java"
        ));
        String lower = d7.toLowerCase(Locale.ROOT);

        assertTrue(d7.contains("approval-migration-orchestration:v1:"));
        assertTrue(d7.contains("JdbcMySqlTransactionLockManager"));
        assertTrue(d7.contains("locks.acquire("));
        assertTrue(d7.contains("values.bindUuid("));
        assertTrue(d7.contains("values.bindInstant("));
        assertTrue(d7.contains("insert into ap_process_migration_canary_selection"));
        assertTrue(d7.contains("insert into ap_process_migration_orchestration_run"));
        assertTrue(d7.contains("insert into ap_process_migration_orchestration_event"));
        assertTrue(d7.contains("insert into ap_process_migration_kill_switch_observation"));
        assertTrue(d7.contains("insert into ap_process_migration_orchestration_batch"));
        assertTrue(d7.contains("m5-d7-orchestration-request-v1"));
        assertTrue(d7.contains("m5-d7-orchestration-run-v1"));
        assertTrue(d7.contains("m5-d7-canary-selection-v1"));
        assertTrue(d7.contains("m5-d7-kill-switch-observation-v1"));
        assertTrue(d7.contains("m5-d7-dispatch-observation-request-v1"));
        assertTrue(d7.contains("m5-d7-bounded-batch-v1"));
        assertTrue(d7.contains("m5-d7-orchestration-event-v1"));
        assertTrue(d7.contains("changed orchestration replay is forbidden"));
        assertTrue(d7.contains("changed kill-switch dispatch replay is forbidden"));
        assertTrue(d7.contains("exact current D2 claim and command fence"));

        for (String forbidden : List.of(
            "pg_advisory",
            "::text",
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
                () -> "MySQL D7 contains forbidden PostgreSQL/shortcut token: " + forbidden
            );
        }
    }

    @Test
    void trustedFactoryOwnsD7VendorSelectionAndServerWiring() throws IOException {
        String factory = Files.readString(JDBC_ROOT.resolve(
            "JdbcApprovalMigrationOrchestrationStoreFactory.java"
        ));
        String configuration = Files.readString(ROOT.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalMigrationExecutionConfiguration.java"
        ));

        assertTrue(factory.contains("new ApprovalDatabaseVendorResolver().resolve(source).vendor()"));
        assertTrue(factory.contains("case POSTGRESQL ->"));
        assertTrue(factory.contains("case MYSQL ->"));
        assertTrue(factory.contains("new JdbcApprovalMigrationOrchestrationStore("));
        assertTrue(factory.contains("new JdbcMySqlApprovalMigrationOrchestrationStore("));
        assertTrue(configuration.contains(
            "JdbcApprovalMigrationOrchestrationStoreFactory.create("
        ));
        assertFalse(configuration.contains("new JdbcApprovalMigrationOrchestrationStore("));
    }

    @Test
    void applicationD7BoundaryRemainsDatabaseNeutral() throws IOException {
        for (String relative : List.of(
            "server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/"
                + "application/port/ApprovalMigrationOrchestrationStore.java",
            "server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/"
                + "application/ApprovalMigrationBoundedOrchestrationService.java"
        )) {
            String source = Files.readString(ROOT.resolve(relative))
                .toLowerCase(Locale.ROOT);
            assertFalse(source.contains("mysql"));
            assertFalse(source.contains("postgresql"));
        }
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
