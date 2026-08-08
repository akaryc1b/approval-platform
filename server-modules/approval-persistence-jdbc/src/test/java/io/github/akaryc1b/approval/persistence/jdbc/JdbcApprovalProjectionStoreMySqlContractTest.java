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
    void mysqlProjectionDialectAndTransactionLockRemainBounded() throws IOException {
        String mysqlStore = lower(Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalProjectionStore.java")
        ));
        String mysqlLocks = lower(Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlTransactionLockManager.java")
        ));

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
                mysqlStore.contains(forbidden),
                () -> "MySQL projection store contains forbidden PostgreSQL or broad duplicate "
                    + "boundary: " + forbidden
            );
        }

        assertTrue(mysqlStore.contains("version = version + 1"));
        assertTrue(mysqlStore.contains("status = 'completing'"));
        assertTrue(mysqlStore.contains("version = :claimedversion"));
        assertTrue(mysqlStore.contains("requiretransaction("));
        assertTrue(mysqlStore.contains("verifyactivetaskowner"));
        assertTrue(mysqlStore.contains("requires a mysql 8.4 datasource"));

        assertTrue(mysqlLocks.contains("select get_lock(?, ?)"));
        assertTrue(mysqlLocks.contains("select release_lock(?)"));
        assertTrue(mysqlLocks.contains("aftercompletion"));
        assertTrue(mysqlLocks.contains("connection.abort(runnable::run)"));
        assertTrue(mysqlLocks.contains("sha-256"));
        assertFalse(mysqlLocks.contains("release_all_locks"));
    }

    @Test
    void factoryAndExecutableBindingPreservePostgreSqlAndTrustedSelection()
        throws IOException {
        String factory = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalProjectionStoreFactory.java")
        );
        String postgreSqlStore = lower(Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalProjectionStore.java")
        ));
        String server = Files.readString(ROOT.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalPlatformConfiguration.java"
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
    }

    @Test
    void permanentContractRetainsHonestStatusAndNonClaims() throws IOException {
        String contract = Files.readString(ROOT.resolve(
            "docs/database/MYSQL_8_4_P3_D_PROJECTION_TASK_CAS_CONTRACT.md"
        ));

        for (String required : List.of(
            "POSTGRESQL_PROJECTION_STORE_UNCHANGED",
            "MYSQL_NAMED_LOCKS_TRANSACTION_BOUND",
            "STRICT_ACTIVE_TASK_OWNERSHIP",
            "NO_INSERT_IGNORE",
            "NO_AUTOMATIC_RETRY",
            "MYSQL_P3_D_PROJECTION_TASK_CAS_SEMANTICS_STAGED",
            "MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED",
            "PR_92_REMAINS_OPEN_DRAFT",
            "ISSUE_91_REMAINS_OPEN"
        )) {
            assertTrue(
                contract.contains(required),
                () -> "P3-D contract is missing required marker: " + required
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

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
