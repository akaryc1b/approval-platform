package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationReconciliationExecutionStoreMySqlContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path JDBC_ROOT = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc"
    );

    @Test
    void mysqlD6UsesBoundedTransactionLocksPortableValuesAndExplicitEvidence()
        throws IOException {
        String d6 = Files.readString(JDBC_ROOT.resolve(
            "JdbcMySqlApprovalMigrationReconciliationExecutionStore.java"
        ));
        String lower = d6.toLowerCase(Locale.ROOT);

        assertTrue(d6.contains("approval-migration-reconciliation:v1:"));
        assertTrue(d6.contains("JdbcMySqlTransactionLockManager"));
        assertTrue(d6.contains("locks.acquire("));
        assertTrue(d6.contains("values.bindUuid("));
        assertTrue(d6.contains("values.bindInstant("));
        assertTrue(d6.contains("insert into ap_process_migration_reconciliation"));
        assertTrue(d6.contains("insert into ap_process_migration_reconciliation_lease"));
        assertTrue(d6.contains("insert into ap_process_migration_reconciliation_lease_event"));
        assertTrue(d6.contains("insert into ap_process_migration_reconciliation_observation"));
        assertTrue(d6.contains("insert into ap_process_migration_attempt_event"));
        assertTrue(d6.contains("m5-reconciliation-open-v45"));
        assertTrue(d6.contains("m5-reconciliation-result-v45"));
        assertTrue(d6.contains("m5-reconciliation-lease-v45"));
        assertTrue(d6.contains("m5-reconciliation-lease-event-v45"));
        assertTrue(d6.contains("m5-reconciliation-observation-v45"));
        assertTrue(d6.contains("changed-payload migration reconciliation replay is forbidden"));
        assertTrue(d6.contains("separate governed D5 binding CAS is required"));
        assertTrue(d6.contains("migration redispatch is forbidden"));

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
                () -> "MySQL D6 contains forbidden PostgreSQL/shortcut token: " + forbidden
            );
        }
    }

    @Test
    void trustedFactoryOwnsD6VendorSelectionAndServerWiring() throws IOException {
        String factory = Files.readString(JDBC_ROOT.resolve(
            "JdbcApprovalMigrationReconciliationExecutionStoreFactory.java"
        ));
        String configuration = Files.readString(ROOT.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalMigrationExecutionConfiguration.java"
        ));

        assertTrue(factory.contains("new ApprovalDatabaseVendorResolver().resolve(source).vendor()"));
        assertTrue(factory.contains("case POSTGRESQL ->"));
        assertTrue(factory.contains("case MYSQL ->"));
        assertTrue(factory.contains("new JdbcApprovalMigrationReconciliationExecutionStore("));
        assertTrue(factory.contains("new JdbcMySqlApprovalMigrationReconciliationExecutionStore("));
        assertTrue(configuration.contains(
            "JdbcApprovalMigrationReconciliationExecutionStoreFactory.create("
        ));
        assertFalse(configuration.contains(
            "new JdbcApprovalMigrationReconciliationExecutionStore("
        ));
    }

    @Test
    void applicationD6BoundaryRemainsDatabaseNeutral() throws IOException {
        for (String relative : List.of(
            "server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/"
                + "application/port/ApprovalMigrationReconciliationStore.java",
            "server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/"
                + "application/ApprovalMigrationReconciliationService.java"
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
