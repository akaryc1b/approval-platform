package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalAssistanceGovernanceHistoryQueryMySqlContractTest {

    @Test
    void usesPortableConditionalAggregationAndDeterministicBoundedReads() throws Exception {
        String source = source(
            "server-modules/approval-persistence-jdbc/src/main/java/"
                + "io/github/akaryc1b/approval/persistence/jdbc/"
                + "JdbcMySqlApprovalAssistanceGovernanceHistoryQuery.java"
        );
        String lower = source.toLowerCase(Locale.ROOT);

        assertTrue(lower.contains("sum(case when"));
        assertTrue(lower.contains("order by"));
        assertTrue(lower.contains(" limit ") || lower.contains("limit ?"));
        assertTrue(lower.contains("tenant_id"));
        assertTrue(
            lower.contains("datetime")
                || source.contains("AuditHashCanonicalizer")
                || source.contains("JdbcDatabaseValueAdapter")
        );

        for (String forbidden : new String[] {
            " filter (",
            " filter(",
            "::jsonb",
            "::uuid",
            " ilike ",
            "pg_advisory",
            "sql_calc_found_rows",
            "insert into",
            "update ap_",
            "delete from",
            "replace into",
            "insert ignore",
            "on duplicate key update",
            "foreign_key_checks",
            "set global",
            "set persist"
        }) {
            assertFalse(lower.contains(forbidden), "forbidden SQL/source token: " + forbidden);
        }
    }

    @Test
    void remainsInsidePersistenceAndDoesNotAcquireCommandAuthority() throws Exception {
        String source = source(
            "server-modules/approval-persistence-jdbc/src/main/java/"
                + "io/github/akaryc1b/approval/persistence/jdbc/"
                + "JdbcMySqlApprovalAssistanceGovernanceHistoryQuery.java"
        );

        for (String forbidden : new String[] {
            "@RestController",
            "@Scheduled",
            "org.flowable",
            "ApprovalCommand",
            "SecretMaterial",
            "ProviderRequest",
            "ProviderResponse",
            "JdbcTemplate.update(",
            ".update("
        }) {
            assertFalse(source.contains(forbidden), "forbidden authority token: " + forbidden);
        }
    }

    private static String source(String relative) throws Exception {
        Path root = repositoryRoot();
        Path file = root.resolve(relative);
        assertTrue(Files.isRegularFile(file), "missing source file: " + relative);
        return Files.readString(file);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                && Files.isDirectory(current.resolve("server-modules"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }
}
