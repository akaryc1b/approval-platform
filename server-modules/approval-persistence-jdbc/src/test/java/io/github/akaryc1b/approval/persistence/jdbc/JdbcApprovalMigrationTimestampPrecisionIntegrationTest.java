package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.NOW;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.TENANT;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.hash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Disposable PostgreSQL only: numeric payload compatibility and JDBC microsecond rounding. */
@EnabledIf("databaseAvailable")
class JdbcApprovalMigrationTimestampPrecisionIntegrationTest {
    private static final String EXTERNAL = System.getenv("APPROVAL_TEST_DB_URL");
    private static PostgreSQLContainer postgres;
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;
    private String schema;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    static boolean databaseAvailable() { return EXTERNAL != null || DockerClientFactory.instance().isDockerAvailable(); }

    @BeforeEach void prepareV49() {
        String url; String user; String password;
        if (EXTERNAL != null) {
            if (!EXTERNAL.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/[a-zA-Z0-9_]+")) {
                throw new IllegalArgumentException("test database must be explicit loopback PostgreSQL");
            }
            url = EXTERNAL; user = System.getenv("APPROVAL_TEST_DB_USERNAME"); password = System.getenv("APPROVAL_TEST_DB_PASSWORD");
        } else {
            if (postgres == null) { postgres = new PostgreSQLContainer("postgres:16-alpine"); postgres.start(); }
            url = postgres.getJdbcUrl(); user = postgres.getUsername(); password = postgres.getPassword();
        }
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "precision_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create schema " + schema);
        dataSource = new DriverManagerDataSource(url + "?currentSchema=" + schema + ",public", user, password);
        jdbc = new JdbcTemplate(dataSource);
        migrate("49");
    }
    @AfterEach void cleanup() { if (admin != null && schema != null) admin.execute("drop schema " + schema + " cascade"); }
    @AfterAll static void stopContainer() { if (postgres != null) postgres.stop(); }

    private void migrate(String target) {
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration", "classpath:m6f/db/migration").target(target).load().migrate();
    }

    @Test void numericEpochMatchesActualJdbcAtNanosecondHalfTiesCarryAndPreEpoch() throws Exception {
        List<Instant> values = List.of(
            Instant.parse("2026-10-25T12:34:56.123456499Z"), Instant.parse("2026-10-25T12:34:56.123456500Z"),
            Instant.parse("2026-10-25T12:34:56.123456501Z"), Instant.parse("2026-10-25T12:34:56.999999500Z"),
            Instant.parse("1969-12-31T23:59:59.123456499Z"), Instant.parse("1969-12-31T23:59:59.123456500Z"),
            Instant.parse("1969-12-31T23:59:59.999999500Z"), Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("1900-01-01T00:00:00.000000500Z"), Instant.parse("2100-01-01T00:00:00.999999500Z"));
        long beforeMismatch = values.stream().filter(value -> !matchesJdbc(value)).count();
        assertTrue(beforeMismatch > 0, "V49 must reproduce numeric-double precision defect");
        migrate("51");
        for (Instant value : values) {
            assertTrue(matchesJdbc(value), value.toString());
            String numeric = mapper.writeValueAsString(Map.of("at", value));
            assertTrue(mapper.readTree(numeric).path("at").isNumber());
            assertEquals(value, mapper.readValue(mapper.writeValueAsString(value), Instant.class));
        }
        assertEquals(true, jdbc.queryForObject("select ap_migration_json_instant_v37('{\"at\":\"2026-10-25T20:00:00+08:00\"}'::jsonb,'at') = ?::timestamptz",
            Boolean.class, Instant.parse("2026-10-25T12:00:00Z").atOffset(ZoneOffset.UTC)));
        assertEquals(true, jdbc.queryForObject("select ap_migration_json_instant_v37('{\"at\":null}'::jsonb,'at') is null", Boolean.class));
        assertThrows(DataAccessException.class, () -> jdbc.queryForObject(
            "select ap_migration_json_instant_v37('{\"at\":true}'::jsonb,'at')", Object.class));
    }

    private boolean matchesJdbc(Instant value) {
        String numeric = new JdbcApprovalMigrationJson(mapper).write(Map.of("at", value));
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "select ap_migration_json_instant_v37(?::jsonb,'at') = ?::timestamptz", Boolean.class,
            numeric, value.atOffset(ZoneOffset.UTC)));
    }

    @Test void upgradingV49PreservesExistingNumericPlanAndAcceptsPreviouslyRejectedNanoseconds() throws Exception {
        var releases = new MigrationPrecisionReleaseFixture(dataSource, mapper).publish(TENANT);
        jdbc.update("""
            insert into ap_approval_release_deployment (
              deployment_record_id,tenant_id,definition_key,release_version,release_package_hash,status,
              attempt_count,engine_deployment_id,engine_definition_id,engine_version,requested_by,created_at,updated_at,deployed_at
            ) values (?,?,?,2,?,'DEPLOYED',1,'engine-deployment-v2','engine-definition-v2',2,'release-operator',?,?,?)
            """, UUID.fromString("77000000-0000-0000-0000-000000000030"), TENANT, releases.get(1).definitionKey(), releases.get(1).packageHash(),
            NOW.minusSeconds(500).atOffset(ZoneOffset.UTC), NOW.minusSeconds(400).atOffset(ZoneOffset.UTC), NOW.minusSeconds(400).atOffset(ZoneOffset.UTC));
        var store = new JdbcApprovalMigrationPlanStore(dataSource, mapper, new JdbcTransactionManager(dataSource));
        var original = plan("legacy", hash('d'), releases);
        store.createPlan(original, JdbcApprovalMigrationPlanStoreIntegrationTestSupport.initialEvent(original, "legacy-event"));
        String originalPayload = payload(original.planId());
        assertTrue(mapper.readTree(originalPayload).path("createdAt").isNumber());
        ObjectNode changed = mapper.valueToTree(plan("precision", hash('e'), releases));
        Instant precise = NOW.plusNanos(123456500);
        changed.set("createdAt", mapper.valueToTree(precise)); changed.set("updatedAt", mapper.valueToTree(precise));
        ApprovalMigrationPlan rejected = mapper.treeToValue(changed, ApprovalMigrationPlan.class);
        var event = JdbcApprovalMigrationPlanStoreIntegrationTestSupport.initialEvent(rejected, "precision-event");
        var failure = assertThrows(io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanStore.MigrationPlanConflictException.class,
            () -> store.createPlan(rejected, event));
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        assertTrue(cause instanceof java.sql.SQLException);
        assertEquals("23514", ((java.sql.SQLException) cause).getSQLState());
        migrate("51");
        assertEquals(originalPayload, payload(original.planId()));
        assertEquals(original, store.findPlan(TENANT, original.planId()).orElseThrow());
        assertTrue(store.createPlan(original, JdbcApprovalMigrationPlanStoreIntegrationTestSupport.initialEvent(original, "legacy-event")).replayedExistingPlan());
        store.createPlan(rejected, event);
        assertEquals(rejected, store.findPlan(TENANT, rejected.planId()).orElseThrow());
        assertEquals(originalPayload, payload(original.planId()));
        assertTrue(mapper.readTree(payload(rejected.planId())).path("createdAt").isNumber());
    }
    private ApprovalMigrationPlan plan(String key, String hash,
        List<io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage> releases) throws Exception {
        ObjectNode value = mapper.valueToTree(JdbcApprovalMigrationPlanStoreIntegrationTestSupport.proposed(
            TENANT, UUID.randomUUID(), key, hash));
        value.put("definitionKey", releases.get(0).definitionKey());
        value.put("sourcePackageHash", releases.get(0).packageHash());
        value.put("targetPackageHash", releases.get(1).packageHash());
        return mapper.treeToValue(value, ApprovalMigrationPlan.class);
    }
    private String payload(UUID id) { return jdbc.queryForObject("select payload_json::text from ap_process_migration_plan where tenant_id=? and plan_id=?", String.class, TENANT, id); }
}
