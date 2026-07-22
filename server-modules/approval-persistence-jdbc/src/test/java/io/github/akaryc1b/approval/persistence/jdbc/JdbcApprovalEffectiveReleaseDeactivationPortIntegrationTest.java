package io.github.akaryc1b.approval.persistence.jdbc;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalEffectiveReleaseDeactivationPortIntegrationTest {

    private static final String TENANT = "tenant-effective-deactivation";
    private static final String DEFINITION_KEY = "purchasePayment";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_effective_deactivation_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static JdbcApprovalEffectiveReleaseDeactivationPort deactivation;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
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
        deactivation = new JdbcApprovalEffectiveReleaseDeactivationPort(dataSource);
    }

    @BeforeEach
    void reset() {
        jdbc.execute("truncate table ap_approval_release_activation_history cascade");
        jdbc.execute("truncate table ap_approval_effective_release cascade");
        seedEffectiveRelease();
        seedActivationHistory();
    }

    @Test
    void clearIsTenantScopedAndRevisionGuarded() {
        assertFalse(deactivation.clear("other-tenant", DEFINITION_KEY, 4));
        assertFalse(deactivation.clear(TENANT, DEFINITION_KEY, 3));
        assertEquals(1, count("ap_approval_effective_release"));

        assertTrue(deactivation.clear(TENANT, DEFINITION_KEY, 4));
        assertEquals(0, count("ap_approval_effective_release"));
        assertFalse(deactivation.clear(TENANT, DEFINITION_KEY, 4));
    }

    @Test
    void clearPreservesImmutableActivationHistory() {
        assertTrue(deactivation.clear(TENANT, DEFINITION_KEY, 4));

        assertEquals(0, count("ap_approval_effective_release"));
        assertEquals(1, count("ap_approval_release_activation_history"));
        assertEquals(
            "ACTIVATE",
            jdbc.queryForObject(
                "select action from ap_approval_release_activation_history "
                    + "where tenant_id = ? and definition_key = ?",
                String.class,
                TENANT,
                DEFINITION_KEY
            )
        );
    }

    private void seedEffectiveRelease() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set session_replication_role = replica");
                statement.execute("""
                    insert into ap_approval_effective_release (
                        tenant_id, definition_key, effective_release_version,
                        previous_release_version, release_package_hash,
                        definition_version, definition_hash,
                        form_package_version, form_package_hash,
                        form_schema_version, form_schema_hash,
                        ui_schema_version, ui_schema_hash,
                        compiler_version, compiled_artifact_hash, bpmn_hash,
                        deployment_metadata_hash, engine_deployment_id,
                        engine_definition_id, engine_version, status, revision,
                        activated_by, activated_at, change_reason, request_id, trace_id
                    ) values (
                        '%s', '%s', 2, 1, '%s', 2, '%s', 1, '%s',
                        1, '%s', 1, '%s', 'compiler-v1', '%s', '%s', '%s',
                        'engine-deployment-2', 'engine-definition-2', 2, 'ACTIVE', 4,
                        'operator-effective', timestamptz '2026-07-22 08:00:00+00',
                        'Activate reviewed release', 'request-effective', 'trace-effective'
                    )
                    """.formatted(
                        TENANT,
                        DEFINITION_KEY,
                        "1".repeat(64),
                        "2".repeat(64),
                        "3".repeat(64),
                        "4".repeat(64),
                        "5".repeat(64),
                        "6".repeat(64),
                        "7".repeat(64),
                        "8".repeat(64)
                    ));
                statement.execute("set session_replication_role = origin");
            }
            return null;
        });
    }

    private void seedActivationHistory() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set session_replication_role = replica");
                statement.execute("""
                    insert into ap_approval_release_activation_history (
                        activation_id, tenant_id, definition_key, release_version,
                        previous_release_version, release_package_hash,
                        definition_version, form_package_version, compiler_version,
                        engine_deployment_id, engine_definition_id, engine_version,
                        action, revision, activated_by, activated_at,
                        change_reason, request_id, trace_id
                    ) values (
                        '%s'::uuid, '%s', '%s', 2, 1, '%s', 2, 1, 'compiler-v1',
                        'engine-deployment-2', 'engine-definition-2', 2,
                        'ACTIVATE', 4, 'operator-effective',
                        timestamptz '2026-07-22 08:00:00+00',
                        'Activate reviewed release', 'request-effective', 'trace-effective'
                    )
                    """.formatted(
                        UUID.fromString("90000000-0000-0000-0000-000000000001"),
                        TENANT,
                        DEFINITION_KEY,
                        "1".repeat(64)
                    ));
                statement.execute("set session_replication_role = origin");
            }
            return null;
        });
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
