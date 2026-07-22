package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseDispositionService;
import io.github.akaryc1b.approval.application.ApprovalReleasePackageHasher;
import io.github.akaryc1b.approval.application.port.ApprovalEffectiveReleaseDeactivationPort;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalProcessReleaseDispositionIntegrationTest {

    private static final String TENANT = "tenant-disposition-transaction";
    private static final String DEFINITION_KEY = "purchasePayment";
    private static final String PACKAGE_HASH = "8".repeat(64);
    private static final Instant NOW = Instant.parse("2026-07-22T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_disposition_transaction_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static ObjectMapper objectMapper;
    private static PlatformTransactionManager transactions;

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
        objectMapper = new ObjectMapper().findAndRegisterModules();
        transactions = new DataSourceTransactionManager(dataSource);
    }

    @BeforeEach
    void reset() {
        jdbc.execute("""
            truncate table
                ap_command_idempotency,
                ap_audit_event,
                ap_audit_chain_state,
                ap_process_release_lifecycle_history,
                ap_process_release_lifecycle,
                ap_approval_effective_release,
                ap_approval_release_activation_history,
                ap_approval_release_package
            cascade
            """);
        seedReleasePackage();
    }

    @Test
    void deprecationCommitsLifecycleAuditEffectiveClearAndIdempotencyTogether() {
        seedActiveLifecycle();
        seedEffectiveRelease();
        ApprovalProcessReleaseDispositionService service = service(
            new JdbcApprovalEffectiveReleaseDeactivationPort(dataSource),
            auditSink()
        );
        var command = command("deprecate-key", "Stop new starts after release review");

        var result = service.deprecate(command);
        var replay = service.deprecate(command);

        assertEquals(State.DEPRECATED, result.lifecycle().lifecycleState());
        assertEquals(result, replay);
        assertEquals(0, count("ap_approval_effective_release"));
        assertEquals(1, count("ap_process_release_lifecycle_history"));
        assertEquals(1, count("ap_audit_event"));
        assertEquals(1, count("ap_command_idempotency"));
        assertEquals(
            "DEPRECATED",
            jdbc.queryForObject(
                "select lifecycle_state from ap_process_release_lifecycle "
                    + "where tenant_id = ? and definition_key = ? and release_version = 1",
                String.class,
                TENANT,
                DEFINITION_KEY
            )
        );
        assertEquals(1, count("ap_approval_release_package"));
    }

    @Test
    void failedEffectiveClearRollsBackLifecycleAuditAndIdempotencyEvidence() {
        seedActiveLifecycle();
        seedEffectiveRelease();
        ApprovalEffectiveReleaseDeactivationPort failingClear = (tenant, key, revision) -> false;
        ApprovalProcessReleaseDispositionService service = service(failingClear, auditSink());

        assertThrows(
            ApprovalProcessReleaseDispositionService.DispositionEvidenceConflictException.class,
            () -> service.deprecate(command(
                "deprecate-conflict-key",
                "Reject concurrent effective release change"
            ))
        );

        assertEquals(1, count("ap_approval_effective_release"));
        assertEquals(0, count("ap_process_release_lifecycle_history"));
        assertEquals(0, count("ap_audit_event"));
        assertEquals(0, count("ap_command_idempotency"));
        assertEquals(
            "ACTIVE",
            jdbc.queryForObject(
                "select lifecycle_state from ap_process_release_lifecycle "
                    + "where tenant_id = ? and definition_key = ? and release_version = 1",
                String.class,
                TENANT,
                DEFINITION_KEY
            )
        );
    }

    @Test
    void retirementKeepsImmutablePackageAndNeverRequiresRuntimeMutation() {
        seedPublishedLifecycle();
        ApprovalProcessReleaseDispositionService service = service(
            new JdbcApprovalEffectiveReleaseDeactivationPort(dataSource),
            auditSink()
        );

        var result = service.retire(command("retire-key", "Retire superseded release safely"));

        assertEquals(State.RETIRED, result.lifecycle().lifecycleState());
        assertEquals(0, result.runtimeUsageCount());
        assertEquals(1, count("ap_approval_release_package"));
        assertEquals(0, count("ap_approval_effective_release"));
        assertEquals(1, count("ap_process_release_lifecycle_history"));
        assertEquals(1, count("ap_audit_event"));
        assertTrue(jdbc.queryForObject(
            "select retired_at is not null from ap_process_release_lifecycle "
                + "where tenant_id = ? and definition_key = ? and release_version = 1",
            Boolean.class,
            TENANT,
            DEFINITION_KEY
        ));
    }

    private ApprovalProcessReleaseDispositionService service(
        ApprovalEffectiveReleaseDeactivationPort deactivation,
        AuditEventSink auditEvents
    ) {
        return new ApprovalProcessReleaseDispositionService(
            new JdbcIdempotencyGuard(
                dataSource,
                objectMapper,
                transactions,
                CLOCK
            ),
            new JdbcApprovalProcessReleaseStore(dataSource),
            new JdbcApprovalEffectiveReleaseStore(dataSource),
            deactivation,
            new JdbcApprovalRuntimeBindingStore(dataSource),
            auditEvents,
            new ApprovalReleasePackageHasher(),
            CLOCK,
            UUID::randomUUID
        );
    }

    private AuditEventSink auditSink() {
        return new JdbcAuditEventSink(dataSource, objectMapper, transactions);
    }

    private static ApprovalProcessReleaseDispositionService.DispositionCommand command(
        String idempotencyKey,
        String reason
    ) {
        return new ApprovalProcessReleaseDispositionService.DispositionCommand(
            new RequestContext(
                TENANT,
                "operator-disposition",
                "request-" + idempotencyKey,
                idempotencyKey,
                "trace-disposition"
            ),
            DEFINITION_KEY,
            1,
            2,
            reason
        );
    }

    private void seedReleasePackage() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set session_replication_role = replica");
                statement.execute("""
                    insert into ap_approval_release_package (
                        tenant_id, definition_key, release_version,
                        definition_version, definition_hash,
                        form_package_version, form_package_hash,
                        form_version, form_hash,
                        ui_schema_version, ui_schema_hash,
                        compiler_version, bpmn_resource_name, bpmn_artifact,
                        compiled_artifact_hash, bpmn_hash, dmn_artifact, dmn_hash,
                        deployment_metadata_hash, package_hash, source_draft_id,
                        published_by, published_at
                    ) values (
                        '%s', '%s', 1, 1, '%s', 1, '%s', 1, '%s', 1, '%s',
                        'compiler-v1', 'process.bpmn20.xml', '<definitions/>',
                        '%s', '%s', null, null, '%s', '%s', '%s'::uuid,
                        'publisher', timestamptz '2026-07-22 08:00:00+00'
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
                        PACKAGE_HASH,
                        UUID.fromString("91000000-0000-0000-0000-000000000001")
                    ));
                statement.execute("set session_replication_role = origin");
            }
            return null;
        });
    }

    private void seedPublishedLifecycle() {
        seedLifecycle("PUBLISHED", null, null);
    }

    private void seedActiveLifecycle() {
        seedLifecycle(
            "ACTIVE",
            "timestamptz '2026-07-22 08:30:00+00'",
            null
        );
    }

    private void seedLifecycle(String state, String activatedAt, String deprecatedAt) {
        String activated = activatedAt == null ? "null" : activatedAt;
        String deprecated = deprecatedAt == null ? "null" : deprecatedAt;
        jdbc.execute("""
            insert into ap_process_release_lifecycle (
                tenant_id, definition_key, release_version, release_package_hash,
                lifecycle_state, revision, published_by, published_at,
                activated_at, deprecated_at, retired_at,
                last_transition_by, last_transition_at, last_transition_reason,
                last_idempotency_key, last_request_id, last_trace_id,
                last_audit_chain_reference
            ) values (
                '%s', '%s', 1, '%s', '%s', 2, 'publisher',
                timestamptz '2026-07-22 08:00:00+00', %s, %s, null,
                'operator-activation', timestamptz '2026-07-22 08:30:00+00',
                'Activate reviewed release', 'activation-key',
                'request-activation', 'trace-activation', 'audit-event:activation'
            )
            """.formatted(
                TENANT,
                DEFINITION_KEY,
                PACKAGE_HASH,
                state,
                activated,
                deprecated
            ));
    }

    private void seedEffectiveRelease() {
        jdbc.update(
            """
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
                ?, ?, 1, null, ?, 1, ?, 1, ?, 1, ?, 1, ?,
                'compiler-v1', ?, ?, ?, 'engine-deployment-1',
                'engine-definition-1', 1, 'ACTIVE', 4,
                'operator-activation', timestamptz '2026-07-22 08:30:00+00',
                'Activate reviewed release', 'request-activation', 'trace-activation'
            )
            """,
            TENANT,
            DEFINITION_KEY,
            PACKAGE_HASH,
            "1".repeat(64),
            "2".repeat(64),
            "3".repeat(64),
            "4".repeat(64),
            "5".repeat(64),
            "6".repeat(64),
            "7".repeat(64)
        );
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
