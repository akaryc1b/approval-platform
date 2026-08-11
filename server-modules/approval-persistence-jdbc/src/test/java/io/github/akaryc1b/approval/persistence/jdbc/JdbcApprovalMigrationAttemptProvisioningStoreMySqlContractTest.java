package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationAttemptProvisioningStoreMySqlContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path JDBC_ROOT = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc"
    );

    @Test
    void mysqlProvisioningUsesRelationalAuthorityPortableValuesAndStrictInserts()
        throws IOException {
        String store = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalMigrationAttemptProvisioningStore.java")
        );
        String lower = store.toLowerCase(Locale.ROOT);

        assertTrue(store.contains("JdbcDatabaseValueAdapter"));
        assertTrue(store.contains("AuditHashCanonicalizer.canonicalInstant"));
        assertTrue(store.contains("for update"));
        assertTrue(store.contains("insert into ap_process_migration_attempt ("));
        assertTrue(store.contains("insert into ap_process_migration_attempt_event ("));
        assertTrue(store.contains("ap_process_migration_plan_consumption"));
        assertTrue(store.contains("ap_process_migration_plan_instance"));
        assertTrue(store.contains("ap_process_runtime_binding"));
        assertTrue(store.contains("ap_approval_instance"));

        for (String forbidden : List.of(
            "::text",
            "::jsonb",
            "as jsonb",
            "for share",
            "pg_advisory",
            "foreign_key_checks",
            "insert ignore",
            "replace into",
            "on duplicate key update",
            "skip locked"
        )) {
            assertFalse(
                lower.contains(forbidden),
                () -> "MySQL H2 provisioning contains forbidden token: " + forbidden
            );
        }
    }

    @Test
    void trustedFactoryOwnsServerProvisioningVendorSelection() throws IOException {
        String configuration = Files.readString(ROOT.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalMigrationExecutionConfiguration.java"
        ));
        String factory = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalMigrationAttemptProvisioningStoreFactory.java")
        );

        assertTrue(configuration.contains(
            "JdbcApprovalMigrationAttemptProvisioningStoreFactory.create("
        ));
        assertFalse(configuration.contains(
            "new JdbcApprovalMigrationAttemptProvisioningStore("
        ));
        assertTrue(factory.contains(
            "case POSTGRESQL -> new JdbcApprovalMigrationAttemptProvisioningStore("
        ));
        assertTrue(factory.contains(
            "case MYSQL -> new JdbcMySqlApprovalMigrationAttemptProvisioningStore("
        ));
    }

    @Test
    void applicationBoundaryAndPermanentScopeRemainDatabaseNeutralAndHonest()
        throws IOException {
        for (String relative : List.of(
            "server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/"
                + "application/port/ApprovalMigrationAttemptProvisioningStore.java",
            "server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/"
                + "application/ApprovalMigrationAttemptClaimService.java"
        )) {
            String source = Files.readString(ROOT.resolve(relative))
                .toLowerCase(Locale.ROOT);
            assertFalse(source.contains("mysql"));
            assertFalse(source.contains("postgresql"));
        }

        String contract = Files.readString(ROOT.resolve(
            "docs/database/MYSQL_8_4_P3_H2_MIGRATION_ATTEMPT_PROVISIONING_CONTRACT.md"
        ));
        String upper = contract.toUpperCase(Locale.ROOT);
        for (String required : List.of(
            "MYSQL_P3_H2_MIGRATION_ATTEMPT_PROVISIONING_",
            "APPROVALMIGRATIONATTEMPTPROVISIONINGSTORE",
            "FOR UPDATE",
            "CREATEDCOUNT=0",
            "MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED",
            "PR_92_REMAINS_OPEN_DRAFT",
            "ISSUE_91_REMAINS_OPEN"
        )) {
            assertTrue(
                upper.contains(required),
                () -> "P3-H2 contract missing required marker: " + required
            );
        }
        assertFalse(hasStandaloneStatusLine(
            contract,
            "MYSQL_8_4_PRODUCTION_SUPPORTED"
        ));
    }

    private static boolean hasStandaloneStatusLine(String text, String marker) {
        return text.lines().map(String::trim).anyMatch(marker::equals);
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
