package io.github.akaryc1b.approval.persistence.jdbc;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalReleaseLifecycleMigrationIntegrationTest {

    private static final String TENANT = "tenant-reactivation";
    private static final String DEFINITION_KEY = "purchasePayment";
    private static final String RELEASE_ONE_HASH = "1".repeat(64);
    private static final String RELEASE_TWO_HASH = "2".repeat(64);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_release_lifecycle_migration_test")
        .withUsername("approval")
        .withPassword("approval");

    @Test
    void v32BackfillPreservesRollbackReactivationHistory() {
        DataSource dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .target(MigrationVersion.fromVersion("31"))
            .load()
            .migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedRollbackHistory(jdbc);

        Flyway latest = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load();
        latest.migrate();

        assertEquals("32", latest.info().current().getVersion().getVersion());
        assertTrue(latest.validateWithResult().validationSuccessful);
        assertCurrentLifecycle(jdbc);
        assertTransitionHistory(jdbc);
    }

    private static void seedRollbackHistory(JdbcTemplate jdbc) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set session_replication_role = replica");
                insertRelease(statement, 1, RELEASE_ONE_HASH, "a", "e", "f", "0", "01");
                insertRelease(statement, 2, RELEASE_TWO_HASH, "3", "4", "5", "6", "02");
                insertActivation(
                    statement,
                    "10000000-0000-0000-0000-000000000001",
                    1,
                    null,
                    RELEASE_ONE_HASH,
                    1,
                    "engine-deployment-r1",
                    "engine-definition-r1",
                    "ACTIVATE",
                    1,
                    "2026-01-01T00:02:00Z",
                    "request-1"
                );
                insertActivation(
                    statement,
                    "10000000-0000-0000-0000-000000000002",
                    2,
                    1,
                    RELEASE_TWO_HASH,
                    2,
                    "engine-deployment-r2",
                    "engine-definition-r2",
                    "ACTIVATE",
                    2,
                    "2026-01-01T00:03:00Z",
                    "request-2"
                );
                insertActivation(
                    statement,
                    "10000000-0000-0000-0000-000000000003",
                    1,
                    2,
                    RELEASE_ONE_HASH,
                    1,
                    "engine-deployment-r1",
                    "engine-definition-r1",
                    "ROLLBACK",
                    3,
                    "2026-01-01T00:04:00Z",
                    "request-3"
                );
                insertEffectiveRelease(statement);
                statement.execute("set session_replication_role = origin");
            }
            return null;
        });
    }

    private static void insertRelease(
        Statement statement,
        int releaseVersion,
        String packageHash,
        String definitionHashCharacter,
        String compiledHashCharacter,
        String bpmnHashCharacter,
        String metadataHashCharacter,
        String draftSuffix
    ) throws SQLException {
        String definitionHash = definitionHashCharacter.repeat(64);
        String formPackageHash = "b".repeat(64);
        String formHash = "c".repeat(64);
        String uiHash = "d".repeat(64);
        statement.execute("""
            insert into ap_approval_release_package (
                tenant_id, definition_key, release_version, definition_version,
                definition_hash, form_package_version, form_package_hash,
                form_version, form_hash, ui_schema_version, ui_schema_hash,
                compiler_version, bpmn_resource_name, bpmn_artifact,
                compiled_artifact_hash, bpmn_hash, dmn_artifact, dmn_hash,
                deployment_metadata_hash, package_hash, source_draft_id,
                published_by, published_at
            ) values (
                '%s', '%s', %d, %d,
                '%s', 1, '%s',
                1, '%s', 1, '%s',
                'compiler-v1', 'purchase-payment.bpmn20.xml', '<definitions/>',
                '%s', '%s', null, null,
                '%s', '%s',
                '20000000-0000-0000-0000-0000000000%s'::uuid,
                'publisher', timestamptz '2026-01-01 00:0%d:00+00'
            )
            """.formatted(
                TENANT,
                DEFINITION_KEY,
                releaseVersion,
                releaseVersion,
                definitionHash,
                formPackageHash,
                formHash,
                uiHash,
                compiledHashCharacter.repeat(64),
                bpmnHashCharacter.repeat(64),
                metadataHashCharacter.repeat(64),
                packageHash,
                draftSuffix,
                releaseVersion - 1
            ));
    }

    private static void insertActivation(
        Statement statement,
        String activationId,
        int releaseVersion,
        Integer previousReleaseVersion,
        String packageHash,
        int definitionVersion,
        String engineDeploymentId,
        String engineDefinitionId,
        String action,
        long revision,
        String activatedAt,
        String requestId
    ) throws SQLException {
        String previous = previousReleaseVersion == null
            ? "null"
            : previousReleaseVersion.toString();
        statement.execute("""
            insert into ap_approval_release_activation_history (
                activation_id, tenant_id, definition_key, release_version,
                previous_release_version, release_package_hash,
                definition_version, form_package_version, compiler_version,
                engine_deployment_id, engine_definition_id, engine_version,
                action, revision, activated_by, activated_at,
                change_reason, request_id, trace_id
            ) values (
                '%s'::uuid, '%s', '%s', %d,
                %s, '%s',
                %d, 1, 'compiler-v1',
                '%s', '%s', %d,
                '%s', %d, 'operator', timestamptz '%s',
                'governed transition', '%s', 'trace-%d'
            )
            """.formatted(
                activationId,
                TENANT,
                DEFINITION_KEY,
                releaseVersion,
                previous,
                packageHash,
                definitionVersion,
                engineDeploymentId,
                engineDefinitionId,
                definitionVersion,
                action,
                revision,
                activatedAt,
                requestId,
                revision
            ));
    }

    private static void insertEffectiveRelease(Statement statement) throws SQLException {
        statement.execute("""
            insert into ap_approval_effective_release (
                tenant_id, definition_key, effective_release_version,
                previous_release_version, release_package_hash,
                definition_version, definition_hash,
                form_package_version, form_package_hash,
                form_schema_version, form_schema_hash,
                ui_schema_version, ui_schema_hash,
                compiler_version, compiled_artifact_hash,
                bpmn_hash, deployment_metadata_hash,
                engine_deployment_id, engine_definition_id, engine_version,
                status, revision, activated_by, activated_at,
                change_reason, request_id, trace_id
            ) values (
                'tenant-reactivation', 'purchasePayment', 1,
                2, '%s',
                1, '%s',
                1, '%s',
                1, '%s',
                1, '%s',
                'compiler-v1', '%s',
                '%s', '%s',
                'engine-deployment-r1', 'engine-definition-r1', 1,
                'ACTIVE', 3, 'operator', timestamptz '2026-01-01 00:04:00+00',
                'rollback to release one', 'request-3', 'trace-3'
            )
            """.formatted(
                RELEASE_ONE_HASH,
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(64),
                "d".repeat(64),
                "e".repeat(64),
                "f".repeat(64),
                "0".repeat(64)
            ));
    }

    private static void assertCurrentLifecycle(JdbcTemplate jdbc) {
        LifecycleRow releaseOne = jdbc.queryForObject(
            """
            select lifecycle_state, revision, activated_at, deprecated_at,
                last_transition_at
            from ap_process_release_lifecycle
            where tenant_id = ? and definition_key = ? and release_version = 1
            """,
            (resultSet, rowNumber) -> new LifecycleRow(
                resultSet.getString("lifecycle_state"),
                resultSet.getLong("revision"),
                resultSet.getObject("activated_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("deprecated_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("last_transition_at", OffsetDateTime.class).toInstant()
            ),
            TENANT,
            DEFINITION_KEY
        );
        assertEquals("ACTIVE", releaseOne.lifecycleState());
        assertEquals(4L, releaseOne.revision());
        assertEquals(
            Instant.parse("2026-01-01T00:02:00Z"),
            releaseOne.activatedAt()
        );
        assertEquals(
            Instant.parse("2026-01-01T00:03:00Z"),
            releaseOne.deprecatedAt()
        );
        assertEquals(
            Instant.parse("2026-01-01T00:04:00Z"),
            releaseOne.lastTransitionAt()
        );

        assertEquals(1, jdbc.queryForObject(
            """
            select count(*) from ap_process_release_lifecycle
            where tenant_id = ? and definition_key = ? and lifecycle_state = 'ACTIVE'
            """,
            Integer.class,
            TENANT,
            DEFINITION_KEY
        ));
        assertEquals("DEPRECATED", jdbc.queryForObject(
            """
            select lifecycle_state from ap_process_release_lifecycle
            where tenant_id = ? and definition_key = ? and release_version = 2
            """,
            String.class,
            TENANT,
            DEFINITION_KEY
        ));
    }

    private record LifecycleRow(
        String lifecycleState,
        long revision,
        Instant activatedAt,
        Instant deprecatedAt,
        Instant lastTransitionAt
    ) {
    }

    private static void assertTransitionHistory(JdbcTemplate jdbc) {
        assertEquals(
            List.of(
                "DRAFT->PUBLISHED",
                "PUBLISHED->ACTIVE",
                "ACTIVE->DEPRECATED",
                "DEPRECATED->ACTIVE"
            ),
            jdbc.queryForList(
                """
                select from_state || '->' || to_state
                from ap_process_release_lifecycle_history
                where tenant_id = ? and definition_key = ? and release_version = 1
                order by revision
                """,
                String.class,
                TENANT,
                DEFINITION_KEY
            )
        );
    }
}
