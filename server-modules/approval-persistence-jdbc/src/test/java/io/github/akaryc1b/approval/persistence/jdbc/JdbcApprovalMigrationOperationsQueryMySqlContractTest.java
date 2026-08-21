package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationOperationsQueryMySqlContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path MYSQL_QUERY = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc/"
            + "JdbcMySqlApprovalMigrationOperationsQuery.java"
    );
    private static final Path FACTORY = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc/"
            + "JdbcApprovalMigrationOperationsQueryFactory.java"
    );
    private static final Path CONFIGURATION = ROOT.resolve(
        "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
            + "ApprovalMigrationOperationsConfiguration.java"
    );
    private static final Path POSTGRES_QUERY = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc/"
            + "JdbcApprovalMigrationOperationsQuery.java"
    );

    @Test
    void mysqlAuthorityIsReadOnlyAndUsesExactLatestEvidenceDialect() throws IOException {
        String query = Files.readString(MYSQL_QUERY);
        String lower = query.toLowerCase();

        for (String required : List.of(
            "JdbcDatabaseValueAdapter.resolve(source)",
            "values.vendor() != ApprovalDatabaseVendor.MYSQL",
            "setReadOnly(true)",
            "TransactionDefinition.ISOLATION_REPEATABLE_READ",
            "row_number() over (",
            "partition by value.tenant_id,value.plan_id",
            "order by value.aggregate_revision desc,value.aggregate_id desc",
            "sum(case when plan.status='CONSUMED' then 1 else 0 end)",
            "values.bindUuid(planId)",
            "values.uuid(row, \"plan_id\")",
            "values.nullableUuid(row, \"intent_id\")",
            "values.nullableInstant(row, \"aggregated_at\")",
            "ap_process_migration_plan_aggregate",
            "ap_process_migration_exact_verification",
            "ap_process_migration_instance_completion",
            "ap_process_migration_binding_cas_conflict",
            "ap_process_migration_reconciliation",
            "ap_process_migration_reconciliation_observation"
        )) {
            assertTrue(query.contains(required), () -> "missing ME1 boundary: " + required);
        }

        for (String forbidden : List.of(
            "filter (where",
            "join lateral",
            "insert into",
            "update ",
            "delete from",
            "replace ",
            "on duplicate key update",
            "foreign_key_checks",
            "get_lock(",
            "lock tables",
            "set global",
            "set persist",
            "from act_",
            "join act_"
        )) {
            assertFalse(lower.contains(forbidden), () -> "forbidden ME1 SQL: " + forbidden);
        }
    }

    @Test
    void trustedFactoryWiresM5E1AndAllowsM5E2ToAdvanceIndependently()
        throws IOException {
        String factory = Files.readString(FACTORY);
        String configuration = Files.readString(CONFIGURATION);
        String postgres = Files.readString(POSTGRES_QUERY);

        assertTrue(factory.contains("ApprovalDatabaseVendorResolver"));
        assertTrue(factory.contains("case POSTGRESQL"));
        assertTrue(factory.contains("case MYSQL"));
        assertTrue(factory.contains("JdbcApprovalMigrationOperationsQuery"));
        assertTrue(factory.contains("JdbcMySqlApprovalMigrationOperationsQuery"));
        assertFalse(factory.contains("ApprovalMigrationDiagnosticsQuery"));

        assertTrue(configuration.contains(
            "JdbcApprovalMigrationOperationsQueryFactory.create("
        ));
        assertFalse(configuration.contains(
            "new JdbcApprovalMigrationOperationsQuery("
        ));
        assertTrue(configuration.contains(
            "JdbcApprovalMigrationDiagnosticsQueryFactory.create("
        ));
        assertFalse(configuration.contains(
            "new JdbcApprovalMigrationDiagnosticsQuery("
        ));

        assertTrue(postgres.contains("count(*) filter (where"));
        assertTrue(postgres.contains("left join lateral ("));
        assertFalse(postgres.contains("JdbcMySqlApprovalMigrationOperationsQuery"));
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
