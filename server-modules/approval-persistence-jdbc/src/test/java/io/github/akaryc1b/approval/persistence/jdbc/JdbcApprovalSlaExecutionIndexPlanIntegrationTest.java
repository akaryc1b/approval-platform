package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcApprovalSlaExecutionIndexPlanIntegrationTest {

    private static final String TENANT_ID = "tenant-execution-index-plan";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_execution_index_plan_test")
        .withUsername("approval")
        .withPassword("approval");

    private JdbcTemplate jdbc;

    @BeforeAll
    void migrateSeedAndAnalyze() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop schema public cascade");
        jdbc.execute("create schema public");
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                connection,
                new ClassPathResource("db/testdata/m4-sla-execution-index-plan.sql")
            );
        }
        jdbc.execute("analyze");
    }

    @Test
    void readyPollingUsesReadyPartialIndex() throws Exception {
        assertPlan("status='READY' and next_attempt_at<=timestamptz '2026-01-02 00:00:00+00'"
            + " order by next_attempt_at,scheduled_at,intent_id limit 50",
            "idx_sla_execution_intent_ready_poll");
    }

    @Test
    void retryWaitPollingUsesReadyPartialIndex() throws Exception {
        assertPlan("status='RETRY_WAIT' and next_attempt_at<="
            + "timestamptz '2026-01-02 00:00:00+00'"
            + " order by next_attempt_at,scheduled_at,intent_id limit 50",
            "idx_sla_execution_intent_ready_poll");
    }

    @Test
    void expiredLeaseRecoveryUsesExpiredLeaseIndex() throws Exception {
        assertPlan("status='CLAIMED' and lease_until<timestamptz '2026-01-02 00:00:00+00'"
            + " order by lease_until,intent_id limit 50",
            "idx_sla_execution_intent_expired_lease");
    }

    @Test
    void deadManagementUsesDeadPartialIndex() throws Exception {
        assertPlan("status='DEAD' order by dead_at desc,intent_id limit 50",
            "idx_sla_execution_intent_dead_management");
    }

    @Test
    void idempotencyLookupUsesUniqueIndex() throws Exception {
        assertPlan("idempotency_key='execution-idempotency-17777'",
            "uk_sla_execution_intent_idempotency");
    }

    @Test
    void slaIntentHistoryUsesHistoryIndex() throws Exception {
        assertPlan("sla_instance_id=md5('sla-execution-17777')::uuid"
            + " order by created_at,intent_id",
            "idx_sla_execution_intent_sla_history");
    }

    @Test
    void requestIdLookupUsesRequestIndex() throws Exception {
        assertPlan("request_id='execution-request-17777'"
            + " order by created_at desc,intent_id limit 50",
            "idx_sla_execution_intent_request");
    }

    private void assertPlan(String predicateAndOrder, String expectedIndex) throws Exception {
        String sql = "select * from ap_sla_execution_intent where tenant_id=? and "
            + predicateAndOrder;
        assertUsesIndex(explain(sql, TENANT_ID), expectedIndex);
    }

    private JsonNode explain(String sql, Object... arguments) throws Exception {
        String json = jdbc.queryForObject(
            "explain (format json) " + sql,
            String.class,
            arguments
        );
        assertNotNull(json, "PostgreSQL must return a JSON execution plan");
        JsonNode root = JSON.readTree(json);
        assertTrue(root.isArray() && !root.isEmpty(), () -> "invalid JSON plan: " + json);
        JsonNode plan = root.get(0).get("Plan");
        assertNotNull(plan, () -> "missing Plan node: " + json);
        return plan;
    }

    private static void assertUsesIndex(JsonNode plan, String expectedIndex) {
        Set<String> indexNames = new HashSet<>();
        List<String> nodeTypes = new ArrayList<>();
        collectPlanEvidence(plan, indexNames, nodeTypes);
        assertTrue(
            indexNames.contains(expectedIndex),
            () -> "expected " + expectedIndex + " but indexes were " + indexNames
                + " in plan " + plan.toPrettyString()
        );
        assertTrue(
            nodeTypes.stream().anyMatch(type -> type.contains("Index") || type.contains("Bitmap")),
            () -> "expected index or bitmap execution but nodes were " + nodeTypes
                + " in plan " + plan.toPrettyString()
        );
    }

    private static void collectPlanEvidence(
        JsonNode node,
        Set<String> indexNames,
        List<String> nodeTypes
    ) {
        if (node.hasNonNull("Node Type")) {
            nodeTypes.add(node.get("Node Type").asText());
        }
        if (node.hasNonNull("Index Name")) {
            indexNames.add(node.get("Index Name").asText());
        }
        JsonNode children = node.get("Plans");
        if (children != null && children.isArray()) {
            children.forEach(child -> collectPlanEvidence(child, indexNames, nodeTypes));
        }
    }
}
