package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalAssistanceGovernanceHistoryQueryMySqlContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path MYSQL_QUERY = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc/"
            + "JdbcMySqlApprovalAssistanceGovernanceHistoryQuery.java"
    );
    private static final Path FACTORY = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc/"
            + "JdbcApprovalAssistanceGovernanceHistoryQueryFactory.java"
    );
    private static final Path CONFIGURATION = ROOT.resolve(
        "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
            + "ApprovalAssistanceProductionConfiguration.java"
    );

    @Test
    void mysqlAuthorityIsReadOnlyRepeatableReadAndUsesExactAggregateDialect()
        throws IOException {
        String query = Files.readString(MYSQL_QUERY);
        String lower = query.toLowerCase();

        for (String required : List.of(
            "setReadOnly(true)",
            "TransactionDefinition.ISOLATION_REPEATABLE_READ",
            "JdbcDatabaseValueAdapter.resolve(source)",
            "values.bindInstant(window.fromInclusive())",
            "values.bindInstant(window.toExclusive())",
            "values.bindInstant(window.observedAt())",
            "sum(case when s.state='ACTIVE' then 1 else 0 end)",
            "sum(case when s.state='TOMBSTONED' then 1 else 0 end)",
            "sum(case when e.provider_invocation_started then 1 else 0 end)",
            "sum(case when e.advisory_result_present then 1 else 0 end)",
            "sum(case when e.retry_attempted then 1 else 0 end)",
            "sum(case when e.post_invocation_fallback_attempted then 1 else 0 end)",
            "count(distinct e.version_evidence_hash)",
            "e.tenant_id=:tenantId",
            "e.recorded_at>=:fromInclusive",
            "e.recorded_at<:toExclusive"
        )) {
            assertTrue(query.contains(required), () -> "missing H9 boundary: " + required);
        }

        for (String forbidden : List.of(
            "filter (where",
            "insert into",
            "update ",
            "delete from",
            "replace ",
            "on duplicate key update",
            "lock tables",
            "get_lock(",
            "set global",
            "set persist",
            "act_"
        )) {
            assertFalse(lower.contains(forbidden), () -> "forbidden H9 SQL: " + forbidden);
        }
    }

    @Test
    void trustedFactoryAndProductionCompositionSelectTheDialect() throws IOException {
        String factory = Files.readString(FACTORY);
        String configuration = Files.readString(CONFIGURATION);

        assertTrue(factory.contains("ApprovalDatabaseVendorResolver"));
        assertTrue(factory.contains("case POSTGRESQL"));
        assertTrue(factory.contains("case MYSQL"));
        assertTrue(factory.contains("JdbcApprovalAssistanceGovernanceHistoryQuery"));
        assertTrue(factory.contains("JdbcMySqlApprovalAssistanceGovernanceHistoryQuery"));

        assertTrue(configuration.contains(
            "JdbcApprovalAssistanceGovernanceHistoryQueryFactory.create("
        ));
        assertFalse(configuration.contains(
            "new JdbcApprovalAssistanceGovernanceHistoryQuery("
        ));
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
