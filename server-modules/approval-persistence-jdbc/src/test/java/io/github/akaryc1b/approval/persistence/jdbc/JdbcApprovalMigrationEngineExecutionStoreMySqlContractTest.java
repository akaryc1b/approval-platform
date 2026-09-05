package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationEngineExecutionStoreMySqlContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path JDBC_ROOT = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc"
    );

    @Test
    void mysqlExecutionUsesPortableValuesStrictEvidenceAndExactAttemptCas()
        throws IOException {
        String store = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalMigrationEngineExecutionStore.java")
        );
        String lower = store.toLowerCase(Locale.ROOT);

        assertTrue(store.contains("JdbcDatabaseValueAdapter"));
        assertTrue(store.contains("AuditHashCanonicalizer.canonicalInstant"));
        assertTrue(store.contains("JdbcMySqlApprovalInstanceCommandFence"));
        assertTrue(store.contains("acquireMigrationLock("));
        assertTrue(store.contains(
            "insert into ap_process_migration_engine_request ("
        ));
        assertTrue(store.contains(
            "insert into ap_process_migration_engine_outcome ("
        ));
        assertTrue(store.contains(
            "insert into ap_process_migration_attempt_event ("
        ));
        assertTrue(store.contains("revision=:expectedRevision"));
        assertTrue(store.contains("status=:expectedStatus"));
        assertTrue(store.contains("ap_process_runtime_binding"));
        assertTrue(store.contains("ap_process_migration_plan_consumption"));
        assertTrue(store.contains("m5-engine-request-v1"));
        assertTrue(store.contains("m5-engine-request-evidence-v1"));
        assertTrue(store.contains("m5-engine-outcome-v1"));

        for (String forbidden : List.of(
            "::text",
            "::jsonb",
            " as jsonb",
            "for update of ",
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
                () -> "MySQL H4 execution store contains forbidden SQL: " + forbidden
            );
        }
    }

    @Test
    void trustedFactoryOwnsServerExecutionVendorSelection() throws IOException {
        String configuration = Files.readString(ROOT.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalMigrationExecutionConfiguration.java"
        ));
        String factory = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalMigrationEngineExecutionStoreFactory.java")
        );
        String commandFence = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalInstanceCommandFence.java")
        );

        assertTrue(configuration.contains(
            "JdbcApprovalMigrationEngineExecutionStoreFactory.create("
        ));
        assertFalse(configuration.contains(
            "new JdbcApprovalMigrationEngineExecutionStore("
        ));
        assertTrue(factory.contains(
            "case POSTGRESQL -> new JdbcApprovalMigrationEngineExecutionStore("
        ));
        assertTrue(factory.contains(
            "case MYSQL -> new JdbcMySqlApprovalMigrationEngineExecutionStore("
        ));
        assertTrue(commandFence.contains("void acquireMigrationLock("));
        assertTrue(commandFence.contains("approval-instance-command:v1:"));
    }

    @Test
    void applicationEngineBoundaryAndPermanentScopeRemainHonest() throws IOException {
        for (String relative : List.of(
            "server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/"
                + "application/port/ApprovalMigrationEngineExecutionStore.java",
            "server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/"
                + "application/ApprovalMigrationSingleInstanceExecutor.java"
        )) {
            String source = Files.readString(ROOT.resolve(relative))
                .toLowerCase(Locale.ROOT);
            assertFalse(source.contains("mysql"));
            assertFalse(source.contains("postgresql"));
        }

        String contract = Files.readString(ROOT.resolve(
            "docs/database/MYSQL_8_4_P3_H4_MIGRATION_ENGINE_EXECUTION_CONTRACT.md"
        ));
        String upper = contract.toUpperCase(Locale.ROOT);
        for (String required : List.of(
            "MYSQL_P3_H4_MIGRATION_ENGINE_EXECUTION_",
            "APPROVALMIGRATIONENGINEEXECUTIONSTORE",
            "CLAIMED -> ENGINE_REQUESTED",
            "AMBIGUOUS_UNKNOWN",
            "REAL FLOWABLE MIGRATION EXECUTION ON MYSQL 8.4",
            "MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED",
            "PR_92_REMAINS_OPEN_DRAFT",
            "ISSUE_91_REMAINS_OPEN"
        )) {
            assertTrue(
                upper.contains(required),
                () -> "P3-H4 contract missing required marker: " + required
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
