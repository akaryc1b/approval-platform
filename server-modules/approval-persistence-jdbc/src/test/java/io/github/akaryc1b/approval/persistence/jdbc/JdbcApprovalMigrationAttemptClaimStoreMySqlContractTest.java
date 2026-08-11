package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationAttemptClaimStoreMySqlContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path JDBC_ROOT = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc"
    );

    @Test
    void mysqlClaimUsesSkipLockedPortableValuesAndStrictEvidenceWrites()
        throws IOException {
        String store = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalMigrationAttemptClaimStore.java")
        );
        String lower = store.toLowerCase(Locale.ROOT);

        assertTrue(store.contains("JdbcDatabaseValueAdapter"));
        assertTrue(store.contains("AuditHashCanonicalizer.canonicalInstant"));
        assertTrue(lower.contains("for update skip locked"));
        assertTrue(store.contains("JdbcMySqlApprovalInstanceCommandFence"));
        assertTrue(store.contains("acquireMigrationLock("));
        assertTrue(store.contains("insert into ap_process_migration_claim_batch ("));
        assertTrue(store.contains("insert into ap_approval_instance_command_fence ("));
        assertTrue(store.contains("insert into ap_process_migration_attempt_event ("));
        assertTrue(store.contains("insert into ap_process_migration_intent_event ("));
        assertTrue(store.contains("ap_process_migration_plan_consumption"));

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
            "on conflict (",
            "on conflict do ",
            "on conflict on constraint "
        )) {
            assertFalse(
                lower.contains(forbidden),
                () -> "MySQL H3 claim store contains forbidden token: " + forbidden
            );
        }
    }

    @Test
    void trustedFactoryOwnsServerClaimVendorSelectionAndMysqlMigrationLock()
        throws IOException {
        String configuration = Files.readString(ROOT.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalMigrationExecutionConfiguration.java"
        ));
        String factory = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalMigrationAttemptClaimStoreFactory.java")
        );
        String commandFence = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalInstanceCommandFence.java")
        );

        assertTrue(configuration.contains(
            "JdbcApprovalMigrationAttemptClaimStoreFactory.create("
        ));
        assertFalse(configuration.contains(
            "new JdbcApprovalMigrationAttemptClaimStore("
        ));
        assertTrue(factory.contains(
            "case POSTGRESQL -> new JdbcApprovalMigrationAttemptClaimStore("
        ));
        assertTrue(factory.contains(
            "case MYSQL -> new JdbcMySqlApprovalMigrationAttemptClaimStore("
        ));
        assertTrue(commandFence.contains("void acquireMigrationLock("));
        assertTrue(commandFence.contains("locks.acquire(lockScope(tenant, instanceId));"));
        assertTrue(commandFence.contains("approval-instance-command:v1:"));
    }

    @Test
    void applicationBoundaryAndPermanentScopeRemainDatabaseNeutralAndHonest()
        throws IOException {
        for (String relative : List.of(
            "server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/"
                + "application/port/ApprovalMigrationAttemptClaimStore.java",
            "server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/"
                + "application/ApprovalMigrationAttemptClaimService.java"
        )) {
            String source = Files.readString(ROOT.resolve(relative))
                .toLowerCase(Locale.ROOT);
            assertFalse(source.contains("mysql"));
            assertFalse(source.contains("postgresql"));
        }

        String contract = Files.readString(ROOT.resolve(
            "docs/database/MYSQL_8_4_P3_H3_MIGRATION_ATTEMPT_CLAIM_CONTRACT.md"
        ));
        String upper = contract.toUpperCase(Locale.ROOT);
        for (String required : List.of(
            "MYSQL_P3_H3_MIGRATION_ATTEMPT_CLAIM_",
            "APPROVALMIGRATIONATTEMPTCLAIMSTORE",
            "FOR UPDATE SKIP LOCKED",
            "CLAIMED -> CLAIMED",
            "MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED",
            "PR_92_REMAINS_OPEN_DRAFT",
            "ISSUE_91_REMAINS_OPEN"
        )) {
            assertTrue(
                upper.contains(required),
                () -> "P3-H3 contract missing required marker: " + required
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
