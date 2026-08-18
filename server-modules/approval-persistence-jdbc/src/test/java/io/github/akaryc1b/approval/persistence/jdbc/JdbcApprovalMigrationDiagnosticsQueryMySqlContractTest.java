package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationDiagnosticsQueryMySqlContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path MYSQL_QUERY = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc/"
            + "JdbcMySqlApprovalMigrationDiagnosticsQuery.java"
    );
    private static final Path FACTORY = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc/"
            + "JdbcApprovalMigrationDiagnosticsQueryFactory.java"
    );
    private static final Path CONFIGURATION = ROOT.resolve(
        "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
            + "ApprovalMigrationOperationsConfiguration.java"
    );
    private static final Path POSTGRES_QUERY = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc/"
            + "JdbcApprovalMigrationDiagnosticsQuery.java"
    );

    @Test
    void mysqlAuthorityUsesExactReadOnlyLatestEvidenceDialect() throws IOException {
        String query = Files.readString(MYSQL_QUERY);
        String lower = query.toLowerCase();

        for (String required : List.of(
            "JdbcDatabaseValueAdapter.resolve(source)",
            "values.vendor() != ApprovalDatabaseVendor.MYSQL",
            "setReadOnly(true)",
            "TransactionDefinition.ISOLATION_REPEATABLE_READ",
            "row_number() over (",
            "partition by value.tenant_id,value.plan_id",
            "partition by value.tenant_id,value.run_id",
            "partition by value.tenant_id,value.intent_id,value.approval_instance_id",
            "partition by value.tenant_id,value.attempt_id",
            "json_length(batch.attempt_ids)",
            "values.bindUuid(planId)",
            "values.bindUuid(criteria.planId())",
            "values.bindNullableUuid(criteria.approvalInstanceId())",
            "values.bindInstant(criteria.from().toInstant())",
            "values.uuid(row, \"plan_id\")",
            "values.nullableUuid(row, \"attempt_id\")",
            "values.nullableInstant(row, \"latest_evidence_at\")",
            "order by sequence_no asc,approval_instance_id asc",
            "order by latest_evidence_at asc,sequence_no asc,approval_instance_id asc",
            "order by latest_evidence_at desc,sequence_no asc,approval_instance_id asc",
            "limit :limit offset :offset",
            "ap_process_migration_plan_aggregate",
            "ap_process_migration_orchestration_run",
            "ap_process_migration_orchestration_event",
            "ap_process_migration_kill_switch_observation",
            "ap_process_migration_exact_verification",
            "ap_process_migration_reconciliation",
            "ap_process_runtime_binding_evidence"
        )) {
            assertTrue(query.contains(required), () -> "missing ME2 boundary: " + required);
        }

        for (String forbidden : List.of(
            "filter (where",
            "join lateral",
            "::text",
            "::uuid",
            "::jsonb",
            "jsonb_array_length",
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
            assertFalse(lower.contains(forbidden), () -> "forbidden ME2 SQL: " + forbidden);
        }
    }

    @Test
    void trustedFactoryAdvancesOnlyM5E2AndRetainsPostgreSqlAuthority()
        throws IOException {
        String factory = Files.readString(FACTORY);
        String configuration = Files.readString(CONFIGURATION);
        String postgres = Files.readString(POSTGRES_QUERY);

        assertTrue(factory.contains("ApprovalDatabaseVendorResolver"));
        assertTrue(factory.contains("case POSTGRESQL"));
        assertTrue(factory.contains("case MYSQL"));
        assertTrue(factory.contains("JdbcApprovalMigrationDiagnosticsQuery"));
        assertTrue(factory.contains("JdbcMySqlApprovalMigrationDiagnosticsQuery"));

        assertTrue(configuration.contains(
            "JdbcApprovalMigrationDiagnosticsQueryFactory.create("
        ));
        assertFalse(configuration.contains(
            "new JdbcApprovalMigrationDiagnosticsQuery("
        ));
        assertTrue(configuration.contains(
            "JdbcApprovalMigrationOperationsQueryFactory.create("
        ));

        assertTrue(postgres.contains("left join lateral ("));
        assertTrue(postgres.contains("jsonb_array_length"));
        assertFalse(postgres.contains("JdbcMySqlApprovalMigrationDiagnosticsQuery"));
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
