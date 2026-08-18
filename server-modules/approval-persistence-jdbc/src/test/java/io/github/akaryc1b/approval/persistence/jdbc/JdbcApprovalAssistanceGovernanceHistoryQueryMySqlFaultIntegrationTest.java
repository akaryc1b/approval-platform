package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistoryWindow;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcMySqlApprovalAssistanceGovernanceHistoryQuery.HistoryQueryException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalAssistanceGovernanceHistoryQueryMySqlFaultIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-17T11:00:00Z");

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("approval_mysql_ai_governance_history_fault")
        .withUsername("approval")
        .withPassword("approval")
        .withCommand(MySql84ProductionTestServer.command());

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
            configuredJdbcUrl(),
            MYSQL.getUsername(),
            MYSQL.getPassword()
        );
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/mysqlmigration")
            .failOnMissingLocations(true)
            .validateMigrationNaming(true)
            .load()
            .migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void unavailableEvidenceAuthorityFailsWithoutPartialSummaryOrRepairWrite() {
        int evidenceBefore = count("ap_ai_approval_assistance_evidence");
        int eventBefore = count("ap_ai_approval_assistance_evidence_event");
        int stateBefore = count("ap_ai_approval_assistance_evidence_state");

        FailAfterMetadataDataSource unavailable =
            new FailAfterMetadataDataSource(dataSource);
        JdbcTransactionManager transactionManager =
            new JdbcTransactionManager(unavailable);
        var query = new JdbcMySqlApprovalAssistanceGovernanceHistoryQuery(
            unavailable,
            transactionManager
        );

        assertThrows(
            HistoryQueryException.class,
            () -> query.summarize(new HistoryWindow(
                "Tenant-H9-Fault",
                NOW.minusSeconds(3_600),
                NOW,
                NOW
            ))
        );

        assertTrue(unavailable.acquisitions() >= 2);
        assertEquals(evidenceBefore, count("ap_ai_approval_assistance_evidence"));
        assertEquals(eventBefore, count("ap_ai_approval_assistance_evidence_event"));
        assertEquals(stateBefore, count("ap_ai_approval_assistance_evidence_state"));
    }

    private static int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static String configuredJdbcUrl() {
        String base = MYSQL.getJdbcUrl();
        String separator = base.contains("?") ? "&" : "?";
        return base + separator
            + "characterEncoding=UTF-8"
            + "&connectionCollation=utf8mb4_0900_as_cs"
            + "&connectionTimeZone=UTC"
            + "&forceConnectionTimeZoneToSession=true"
            + "&preserveInstants=true";
    }

    private static final class FailAfterMetadataDataSource implements DataSource {

        private final DataSource delegate;
        private final AtomicInteger acquisitions = new AtomicInteger();

        private FailAfterMetadataDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (acquisitions.incrementAndGet() == 1) {
                return delegate.getConnection();
            }
            throw unavailable();
        }

        @Override
        public Connection getConnection(String username, String password)
            throws SQLException {
            if (acquisitions.incrementAndGet() == 1) {
                return delegate.getConnection(username, password);
            }
            throw unavailable();
        }

        int acquisitions() {
            return acquisitions.get();
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter output) throws SQLException {
            delegate.setLogWriter(output);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger(
                JdbcApprovalAssistanceGovernanceHistoryQueryMySqlFaultIntegrationTest
                    .class
                    .getName()
            );
        }

        @Override
        public <T> T unwrap(Class<T> type) throws SQLException {
            return delegate.unwrap(type);
        }

        @Override
        public boolean isWrapperFor(Class<?> type) throws SQLException {
            return delegate.isWrapperFor(type);
        }

        private static SQLTransientConnectionException unavailable() {
            return new SQLTransientConnectionException(
                "synthetic MySQL evidence authority unavailable"
            );
        }
    }
}
