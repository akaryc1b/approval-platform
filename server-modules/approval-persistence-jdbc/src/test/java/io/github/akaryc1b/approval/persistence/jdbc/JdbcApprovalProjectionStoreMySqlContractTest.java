package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalProjectionStoreMySqlContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path JDBC_ROOT = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc"
    );

    @Test
    void completeMySqlProjectionStoreDelegatesTheAcceptedTaskCasPrimitive()
        throws IOException {
        String mysqlStore = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalProjectionStore.java")
        );
        String definitionStore = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlDefinitionInstanceProjectionStore.java")
        );
        String lifecycleStore = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlTaskLifecycleProjectionStore.java")
        );
        String lower = (mysqlStore + definitionStore + lifecycleStore)
            .toLowerCase(Locale.ROOT);

        assertTrue(mysqlStore.contains(
            "private final JdbcMySqlApprovalTaskCasStore taskCas;"
        ));
        assertTrue(mysqlStore.contains("return taskCas.findTasks(tenantId, instanceId);"));
        assertTrue(mysqlStore.contains("return taskCas.findTask(tenantId, taskId);"));
        assertTrue(mysqlStore.contains("return taskCas.claimPendingTask("));
        assertTrue(mysqlStore.contains("return taskCas.claimPendingTaskForControl("));
        assertTrue(mysqlStore.contains("return taskCas.transferPendingTask("));
        assertTrue(mysqlStore.contains("JdbcMySqlProjectionCodec.canonicalInstant(claimedAt)"));
        assertTrue(mysqlStore.contains("JdbcMySqlProjectionCodec.canonicalInstant(transferredAt)"));

        for (String forbidden : List.of(
            "returning *",
            "jsonb",
            "pg_advisory",
            "on conflict",
            "insert ignore",
            "replace into",
            "on duplicate key update"
        )) {
            assertFalse(
                lower.contains(forbidden),
                () -> "complete MySQL projection store contains forbidden boundary: "
                    + forbidden
            );
        }
    }

    @Test
    void completionSerializationAndOwnershipRemainFailClosed() throws IOException {
        String definitionStore = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlDefinitionInstanceProjectionStore.java")
        );
        String lifecycleStore = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlTaskLifecycleProjectionStore.java")
        );
        String mysqlLocks = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlTransactionLockManager.java")
        );

        assertTrue(lifecycleStore.contains("status = 'COMPLETING'"));
        assertTrue(lifecycleStore.contains("version = :claimedVersion"));
        assertTrue(lifecycleStore.contains("version = version + 1"));
        assertTrue(lifecycleStore.contains("verifyActiveTaskOwner"));
        assertTrue(lifecycleStore.contains("findTaskIdentifierOwner"));
        assertTrue(lifecycleStore.contains(
            "engine task is already owned by another approval instance"
        ));
        assertTrue(lifecycleStore.contains(
            "active task identifier is already owned by another engine task"
        ));
        assertTrue(lifecycleStore.contains("requireTransaction("));
        assertTrue(definitionStore.contains("cast(:attachmentIdsJson as json)"));
        assertTrue(definitionStore.contains("cast(:assigneeSnapshotJson as json)"));

        assertTrue(mysqlLocks.contains("select get_lock(?, ?)"));
        assertTrue(mysqlLocks.contains("select release_lock(?)"));
        assertTrue(mysqlLocks.contains("connection.getCatalog()"));
        assertTrue(mysqlLocks.contains("afterCompletion"));
        assertTrue(mysqlLocks.contains("connection.abort(Runnable::run)"));
        assertTrue(mysqlLocks.contains("SHA-256"));
        assertFalse(mysqlLocks.toLowerCase(Locale.ROOT).contains("release_all_locks"));
    }

    @Test
    void trustedFactoryServerProfileAndPostgreSqlReferenceRemainExplicit()
        throws IOException {
        String factory = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalProjectionStoreFactory.java")
        );
        String postgreSqlStore = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalProjectionStore.java")
        ).toLowerCase(Locale.ROOT);
        String server = Files.readString(ROOT.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalPlatformConfiguration.java"
        ));
        String mySqlProfile = Files.readString(ROOT.resolve(
            "apps/server/src/main/resources/application-mysql.yml"
        ));

        assertTrue(factory.contains(
            "case POSTGRESQL -> new JdbcApprovalProjectionStore(source, mapper)"
        ));
        assertTrue(factory.contains(
            "case MYSQL -> new JdbcMySqlApprovalProjectionStore(source, mapper)"
        ));
        assertTrue(factory.contains("ApprovalDatabaseVendorResolver"));

        assertTrue(postgreSqlStore.contains("pg_advisory_xact_lock"));
        assertTrue(postgreSqlStore.contains("returning *"));
        assertTrue(postgreSqlStore.contains("cast(:attachmentidsjson as jsonb)"));
        assertTrue(postgreSqlStore.contains("on conflict (tenant_id, engine_task_id)"));

        assertTrue(server.contains("JdbcApprovalProjectionStoreFactory.create("));
        assertFalse(server.contains("new JdbcApprovalProjectionStore("));
        assertTrue(mySqlProfile.contains("useAffectedRows: false"));
    }

    @Test
    void permanentContractRetainsHonestStatusAndNonClaims() throws IOException {
        String contract = Files.readString(ROOT.resolve(
            "docs/database/MYSQL_8_4_P3_E_COMPLETE_PROJECTION_STORE_CONTRACT.md"
        ));

        for (String required : List.of(
            "POSTGRESQL_PROJECTION_STORE_UNCHANGED",
            "P3_D_TASK_CAS_DELEGATED_NOT_DUPLICATED",
            "MYSQL_NAMED_LOCKS_TRANSACTION_BOUND",
            "DATABASE_SCOPED_LOCK_NAMES",
            "STRICT_ACTIVE_TASK_OWNERSHIP",
            "GLOBAL_TASK_IDENTIFIER_FENCED",
            "NO_AUTOMATIC_RETRY",
            "MYSQL_P3_E_COMPLETE_PROJECTION_STORE_STAGED",
            "MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED",
            "PR_92_REMAINS_OPEN_DRAFT",
            "ISSUE_91_REMAINS_OPEN"
        )) {
            assertTrue(
                contract.contains(required),
                () -> "P3-E contract is missing required marker: " + required
            );
        }
        assertFalse(contract.contains("MYSQL_8_4_PRODUCTION_SUPPORTED"));
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
