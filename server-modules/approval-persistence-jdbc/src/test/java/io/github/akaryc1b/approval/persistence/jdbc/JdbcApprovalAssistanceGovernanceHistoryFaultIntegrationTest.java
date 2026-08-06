package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistoryWindow;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalAssistanceGovernanceHistoryQuery
    .HistoryQueryException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalAssistanceGovernanceHistoryFaultIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-06T04:00:00Z");
    private static final String EVIDENCE_TABLE = "ap_ai_approval_assistance_evidence";
    private static final String HIDDEN_TABLE = "ap_ai_approval_assistance_evidence_p7_hidden";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_ai_governance_history_fault_test")
        .withUsername("approval")
        .withPassword("approval");

    private static JdbcTemplate jdbc;
    private static JdbcApprovalAssistanceGovernanceHistoryQuery query;

    @BeforeAll
    static void migrate() {
        DataSource dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();
        jdbc = new JdbcTemplate(dataSource);
        query = new JdbcApprovalAssistanceGovernanceHistoryQuery(
            dataSource,
            new DataSourceTransactionManager(dataSource)
        );
    }

    @Test
    void unavailableV49TableReturnsNoPartialSummaryAndPerformsNoRepairWrite() {
        int stateBefore = count("ap_ai_approval_assistance_evidence_state");
        int eventBefore = count("ap_ai_approval_assistance_evidence_event");
        jdbc.execute("alter table " + EVIDENCE_TABLE + " rename to " + HIDDEN_TABLE);
        try {
            assertThrows(HistoryQueryException.class, () -> query.summarize(window()));
        } finally {
            jdbc.execute("alter table " + HIDDEN_TABLE + " rename to " + EVIDENCE_TABLE);
        }

        assertEquals(stateBefore, count("ap_ai_approval_assistance_evidence_state"));
        assertEquals(eventBefore, count("ap_ai_approval_assistance_evidence_event"));
        assertEquals(0, count(EVIDENCE_TABLE));
    }

    private static int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static HistoryWindow window() {
        return new HistoryWindow(
            "tenant-p7-history-fault",
            NOW.minusSeconds(3_600),
            NOW,
            NOW
        );
    }
}
