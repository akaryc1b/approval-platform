package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationRuntimeBindingCasMySqlContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path JDBC_ROOT = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc"
    );

    @Test
    void mysqlD5UsesTransactionLocksPortableValuesAndExplicitEvidence() throws IOException {
        String d5 = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalMigrationRuntimeBindingCasStore.java")
        );
        String fence = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalInstanceCommandFence.java")
        );
        String lower = d5.toLowerCase(Locale.ROOT);

        assertTrue(d5.contains("approval-migration-binding-cas:v1:"));
        assertTrue(d5.contains("JdbcMySqlApprovalInstanceCommandFence.lockScope"));
        assertTrue(d5.contains("values.bindUuid("));
        assertTrue(d5.contains("values.bindInstant("));
        assertTrue(d5.contains("insert into ap_process_runtime_binding_evidence"));
        assertTrue(d5.contains("insert into ap_process_migration_instance_completion"));
        assertTrue(d5.contains("insert into ap_process_migration_binding_cas_conflict"));
        assertTrue(d5.contains("binding_revision=binding_revision+1"));
        assertTrue(d5.contains("m5-runtime-binding-v44"));
        assertTrue(d5.contains("m5-runtime-binding-history-v44"));
        assertTrue(d5.contains("m5-instance-completion-v44"));
        assertTrue(d5.contains("m5-binding-cas-conflict-v44"));
        assertTrue(fence.contains("approval-instance-command:v1:"));

        for (String forbidden : List.of(
            "pg_advisory",
            "::text",
            "::jsonb",
            "as jsonb",
            "convert_to(",
            "encode(sha256",
            "for update of ",
            "foreign_key_checks",
            "insert ignore",
            "replace into",
            "on duplicate key update"
        )) {
            assertFalse(
                lower.contains(forbidden),
                () -> "MySQL D5 contains forbidden PostgreSQL/shortcut token: " + forbidden
            );
        }
    }

    @Test
    void trustedFactoriesOwnServerVendorSelection() throws IOException {
        String runtimeConfig = Files.readString(ROOT.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalRuntimeBindingEvidenceConfiguration.java"
        ));
        String migrationConfig = Files.readString(ROOT.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalMigrationExecutionConfiguration.java"
        ));
        String fenceFactory = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalInstanceCommandFenceFactory.java")
        );
        String casFactory = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalMigrationRuntimeBindingCasStoreFactory.java")
        );

        assertTrue(runtimeConfig.contains(
            "JdbcApprovalInstanceCommandFenceFactory.create(dataSource)"
        ));
        assertFalse(runtimeConfig.contains(
            "new JdbcApprovalInstanceCommandFence(dataSource)"
        ));
        assertTrue(migrationConfig.contains(
            "JdbcApprovalMigrationRuntimeBindingCasStoreFactory.create("
        ));
        assertFalse(migrationConfig.contains(
            "new PostgresSerializedApprovalMigrationRuntimeBindingCasStore("
        ));
        assertTrue(fenceFactory.contains(
            "case POSTGRESQL -> new JdbcApprovalInstanceCommandFence(source)"
        ));
        assertTrue(fenceFactory.contains(
            "case MYSQL -> new JdbcMySqlApprovalInstanceCommandFence(source)"
        ));
        assertTrue(casFactory.contains("case POSTGRESQL ->"));
        assertTrue(casFactory.contains("case MYSQL ->"));
    }

    @Test
    void applicationBoundaryAndPermanentScopeRemainDatabaseNeutralAndHonest()
        throws IOException {
        for (String relative : List.of(
            "server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/"
                + "application/port/ApprovalMigrationRuntimeBindingCasStore.java",
            "server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/"
                + "application/port/ApprovalInstanceCommandFence.java",
            "server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/"
                + "application/ApprovalMigrationRuntimeBindingCasService.java",
            "server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/"
                + "application/ApprovalMigrationAttemptPipelineService.java"
        )) {
            String source = Files.readString(ROOT.resolve(relative))
                .toLowerCase(Locale.ROOT);
            assertFalse(source.contains("mysql"));
            assertFalse(source.contains("postgresql"));
        }

        String contract = Files.readString(ROOT.resolve(
            "docs/database/MYSQL_8_4_P3_H1_MIGRATION_BINDING_CAS_CONTRACT.md"
        ));
        String upper = contract.toUpperCase(Locale.ROOT);
        for (String required : List.of(
            "MYSQL_P3_H1_MIGRATION_BINDING_CAS_",
            "APPROVAL-MIGRATION-BINDING-CAS:V1:",
            "APPROVAL-INSTANCE-COMMAND:V1:",
            "REPLAYED_COMPLETION",
            "REPLAYED_CONFLICT",
            "M5-RUNTIME-BINDING-V44",
            "M5-RUNTIME-BINDING-HISTORY-V44",
            "M5-INSTANCE-COMPLETION-V44",
            "M5-BINDING-CAS-CONFLICT-V44",
            "MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED",
            "PR_92_REMAINS_OPEN_DRAFT",
            "ISSUE_91_REMAINS_OPEN"
        )) {
            assertTrue(
                upper.contains(required),
                () -> "P3-H1 contract missing required marker: " + required
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
