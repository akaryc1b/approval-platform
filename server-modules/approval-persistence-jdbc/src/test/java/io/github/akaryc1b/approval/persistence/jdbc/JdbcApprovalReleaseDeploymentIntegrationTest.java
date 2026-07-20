package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalDefinitionHasher;
import io.github.akaryc1b.approval.application.ApprovalReleaseDeploymentService;
import io.github.akaryc1b.approval.application.ApprovalReleasePackageHasher;
import io.github.akaryc1b.approval.application.ApprovalReleasePreflightService;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionSimulator;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionValidator;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.engine.ApprovalEngine;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalReleaseDeploymentIntegrationTest {

    private static final String TENANT = "tenant-deployment";
    private static final String DEFINITION_KEY = "purchase-payment";
    private static final Instant NOW = Instant.parse("2026-07-19T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String DEFINITION_HASH = "1".repeat(64);
    private static final String FORM_PACKAGE_HASH = "2".repeat(64);
    private static final String FORM_HASH = "3".repeat(64);
    private static final String UI_HASH = "4".repeat(64);
    private static final String COMPILED_HASH = "5".repeat(64);
    private static final String BPMN_ARTIFACT = """
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
          <process id="purchase-payment" isExecutable="true" />
        </definitions>
        """;
    private static final ApprovalReleasePackageHasher RELEASE_HASHER =
        new ApprovalReleasePackageHasher();
    private static final String BPMN_HASH = RELEASE_HASHER.artifactHash(BPMN_ARTIFACT);
    private static final String METADATA_HASH = RELEASE_HASHER.deploymentMetadataHash(
        "1.2.0",
        "process.bpmn20.xml",
        BPMN_HASH
    );
    private static final String PACKAGE_HASH = RELEASE_HASHER.hash(
        DEFINITION_KEY, 1, 2, DEFINITION_HASH,
        1, FORM_PACKAGE_HASH, 1, FORM_HASH, 1, UI_HASH,
        "1.2.0", "process.bpmn20.xml", BPMN_ARTIFACT,
        COMPILED_HASH, BPMN_HASH, null, null, METADATA_HASH
    );

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("approval_release_deployment")
            .withUsername("approval")
            .withPassword("approval");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private JdbcApprovalReleaseDeploymentStore deployments;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource configured = new DriverManagerDataSource();
        configured.setDriverClassName("org.postgresql.Driver");
        configured.setUrl(POSTGRES.getJdbcUrl());
        configured.setUsername(POSTGRES.getUsername());
        configured.setPassword(POSTGRES.getPassword());
        dataSource = configured;
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @BeforeEach
    void reset() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            truncate table
                ap_approval_release_deployment,
                ap_approval_release_package,
                ap_approval_compiled_artifact,
                ap_approval_definition,
                ap_approval_design_draft,
                ap_form_package,
                ap_form_design_draft,
                ap_form_ui_schema,
                ap_form_definition,
                ap_command_idempotency,
                ap_audit_event
            cascade
            """);
        seedReleasePackage();
        deployments = new JdbcApprovalReleaseDeploymentStore(dataSource);
    }

    @Test
    void persistsTenantIsolationCasAndStateTransitions() {
        ApprovalReleaseDeployment pending = pending(TENANT, 1, PACKAGE_HASH);
        deployments.save(pending);

        assertTrue(deployments.find(TENANT, DEFINITION_KEY, 1).isPresent());
        assertTrue(deployments.find("tenant-other", DEFINITION_KEY, 1).isEmpty());
        assertFalse(deployments.update(failed(pending), 0));

        ApprovalReleaseDeployment failed = failed(pending);
        assertTrue(deployments.update(failed, 1));
        ApprovalReleaseDeployment retry = retryPending(failed);
        assertTrue(deployments.update(retry, 1));
        ApprovalReleaseDeployment deployed = deployed(retry);
        assertTrue(deployments.update(deployed, 2));

        ApprovalReleaseDeployment stored = deployments.find(
            TENANT,
            DEFINITION_KEY,
            1
        ).orElseThrow();
        assertEquals(ApprovalReleaseDeployment.Status.DEPLOYED, stored.status());
        assertEquals(2, stored.attemptCount());
        assertEquals("engine-deployment-2", stored.engineDeploymentId());

        assertThrows(
            DataIntegrityViolationException.class,
            () -> deployments.save(pending("tenant-other", 1, PACKAGE_HASH))
        );
    }

    @Test
    void servicePersistsFailureRetriesAndNeverDuplicatesSuccessfulDeployment() {
        FailingOnceEngine engine = new FailingOnceEngine();
        ApprovalReleaseDeploymentService service = service(engine);

        var initialPreflight = deploymentPreflight();
        var failed = service.deploy(command("request-1", "key-1", initialPreflight));
        var requestReplay = service.deploy(command("request-2", "key-1", initialPreflight));
        var retryPreflight = deploymentPreflight();
        assertEquals(List.of("PREVIOUS_DEPLOYMENT_FAILED"), retryPreflight.warningCodes());
        var retried = service.deploy(command("request-3", "key-2", retryPreflight));
        var semanticReplay = service.deploy(command("request-4", "key-3", retryPreflight));

        assertEquals(ApprovalReleaseDeployment.Status.FAILED, failed.deployment().status());
        assertEquals(failed, requestReplay);
        assertEquals(ApprovalReleaseDeployment.Status.DEPLOYED, retried.deployment().status());
        assertEquals(2, retried.deployment().attemptCount());
        assertTrue(semanticReplay.replayedExistingDeployment());
        assertEquals(retried.deployment(), semanticReplay.deployment());
        assertEquals(2, engine.calls());
        assertEquals(1, count("ap_approval_release_deployment"));
        assertEquals(2, count("ap_audit_event"));
        assertEquals(
            "PREVIOUS_DEPLOYMENT_FAILED",
            jdbc.queryForObject(
                "select attributes_json ->> 'acknowledgedWarningCodes' "
                    + "from ap_audit_event where action = 'APPROVAL_RELEASE_DEPLOYED'",
                String.class
            )
        );
    }

    private ApprovalReleaseDeploymentService service(ApprovalEngine engine) {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AuditEventSink auditEvents = new JdbcAuditEventSink(dataSource, objectMapper);
        JdbcApprovalReleasePackageStore releaseStore =
            new JdbcApprovalReleasePackageStore(dataSource);
        return new ApprovalReleaseDeploymentService(
            new JdbcIdempotencyGuard(
                dataSource,
                objectMapper,
                new DataSourceTransactionManager(dataSource),
                CLOCK
            ),
            releaseStore,
            deployments,
            preflight(releaseStore, objectMapper),
            engine,
            auditEvents,
            CLOCK,
            UUID::randomUUID
        );
    }

    private ApprovalReleasePreflightService.PreflightReport deploymentPreflight() {
        return preflight(
            new JdbcApprovalReleasePackageStore(dataSource),
            new ObjectMapper().findAndRegisterModules()
        ).preflightDeployment(new ApprovalReleasePreflightService.DeploymentRequest(
            TENANT,
            DEFINITION_KEY,
            1,
            "flowable-primary"
        ));
    }

    private static ApprovalReleaseDeploymentService.DeployCommand command(
        String requestId,
        String idempotencyKey,
        ApprovalReleasePreflightService.PreflightReport preflight
    ) {
        return new ApprovalReleaseDeploymentService.DeployCommand(
            new RequestContext(
                TENANT,
                "operator-a",
                requestId,
                idempotencyKey,
                requestId + "-trace"
            ),
            DEFINITION_KEY,
            1,
            preflight.deploymentTarget(),
            preflight.preflightHash(),
            preflight.warningCodes()
        );
    }

    private static ApprovalReleasePreflightService preflight(
        JdbcApprovalReleasePackageStore releaseStore,
        ObjectMapper objectMapper
    ) {
        ApprovalDefinitionValidator validator = new ApprovalDefinitionValidator();
        return new ApprovalReleasePreflightService(
            new JdbcApprovalDesignDraftStore(dataSource, objectMapper),
            new JdbcApprovalDefinitionVersionStore(dataSource, objectMapper),
            releaseStore,
            new JdbcApprovalReleaseDeploymentStore(dataSource),
            new JdbcApprovalFormPackageStore(dataSource),
            new JdbcApprovalFormStore(dataSource, objectMapper),
            new JdbcApprovalUiSchemaStore(dataSource, objectMapper),
            validator,
            new ApprovalDefinitionSimulator(validator),
            new ApprovalDslCompiler(validator),
            new ApprovalDefinitionHasher(),
            RELEASE_HASHER
        );
    }

    private void seedReleasePackage() {
        UUID formDraftId = UUID.randomUUID();
        UUID approvalDraftId = UUID.randomUUID();
        OffsetDateTime now = NOW.atOffset(ZoneOffset.UTC);

        jdbc.update(
            """
            insert into ap_form_definition (
                tenant_id, form_key, form_version, schema_version, name,
                field_count, schema_json, content_hash, published_by, published_at
            ) values (?, ?, 1, '1.0', 'Form', 1, cast('{}' as jsonb), ?, 'publisher', ?)
            """,
            TENANT,
            DEFINITION_KEY,
            FORM_HASH,
            now
        );
        jdbc.update(
            """
            insert into ap_form_ui_schema (
                tenant_id, form_key, form_version, ui_schema_version,
                schema_version, name, section_count, schema_json,
                content_hash, published_by, published_at
            ) values (?, ?, 1, 1, '1.0', 'UI', 1, cast('{}' as jsonb),
                      ?, 'publisher', ?)
            """,
            TENANT,
            DEFINITION_KEY,
            UI_HASH,
            now
        );
        jdbc.update(
            """
            insert into ap_form_design_draft (
                tenant_id, draft_id, form_key, name, form_version,
                ui_schema_version, form_schema_json, ui_schema_json,
                revision, status, created_by, updated_by, created_at, updated_at
            ) values (?, ?, ?, 'Form draft', 1, 1, cast('{}' as jsonb),
                      cast('{}' as jsonb), 1, 'DRAFT', 'publisher', 'publisher', ?, ?)
            """,
            TENANT,
            formDraftId,
            DEFINITION_KEY,
            now,
            now
        );
        jdbc.update(
            """
            insert into ap_form_package (
                tenant_id, form_key, package_version, form_version, form_hash,
                ui_schema_version, ui_schema_hash, package_hash,
                source_draft_id, published_by, published_at
            ) values (?, ?, 1, 1, ?, 1, ?, ?, ?, 'publisher', ?)
            """,
            TENANT,
            DEFINITION_KEY,
            FORM_HASH,
            UI_HASH,
            FORM_PACKAGE_HASH,
            formDraftId,
            now
        );
        jdbc.update(
            """
            update ap_form_design_draft
            set status = 'PUBLISHED', published_package_version = 1
            where tenant_id = ? and draft_id = ?
            """,
            TENANT,
            formDraftId
        );
        jdbc.update(
            """
            insert into ap_approval_design_draft (
                tenant_id, draft_id, definition_key, name, definition_version,
                approval_dsl_json, form_package_version, form_package_hash,
                revision, status, created_by, updated_by, created_at, updated_at
            ) values (?, ?, ?, 'Approval draft', 2, cast('{}' as jsonb),
                      1, ?, 1, 'DRAFT', 'publisher', 'publisher', ?, ?)
            """,
            TENANT,
            approvalDraftId,
            DEFINITION_KEY,
            FORM_PACKAGE_HASH,
            now,
            now
        );
        jdbc.update(
            """
            insert into ap_approval_definition (
                tenant_id, definition_key, definition_version, definition_hash,
                form_package_version, form_package_hash, approval_dsl_json,
                source_draft_id, published_by, published_at
            ) values (?, ?, 2, ?, 1, ?, cast('{}' as jsonb), ?, 'publisher', ?)
            """,
            TENANT,
            DEFINITION_KEY,
            DEFINITION_HASH,
            FORM_PACKAGE_HASH,
            approvalDraftId,
            now
        );
        jdbc.update(
            """
            insert into ap_approval_compiled_artifact (
                tenant_id, definition_key, definition_version, definition_hash,
                form_version, form_hash, compiler_version, resource_name,
                bpmn_xml, compiled_artifact_hash, bpmn_hash, created_at
            ) values (?, ?, 2, ?, 1, ?, '1.2.0', 'process.bpmn20.xml',
                      ?, ?, ?, ?)
            """,
            TENANT,
            DEFINITION_KEY,
            DEFINITION_HASH,
            FORM_HASH,
            BPMN_ARTIFACT,
            COMPILED_HASH,
            BPMN_HASH,
            now
        );
        jdbc.update(
            """
            insert into ap_approval_release_package (
                tenant_id, definition_key, release_version,
                definition_version, definition_hash,
                form_package_version, form_package_hash,
                form_version, form_hash, ui_schema_version, ui_schema_hash,
                compiler_version, bpmn_resource_name, bpmn_artifact,
                compiled_artifact_hash, bpmn_hash, deployment_metadata_hash,
                package_hash, source_draft_id, published_by, published_at
            ) values (?, ?, 1, 2, ?, 1, ?, 1, ?, 1, ?, '1.2.0',
                      'process.bpmn20.xml', ?, ?, ?, ?, ?, ?,
                      'publisher', ?)
            """,
            TENANT,
            DEFINITION_KEY,
            DEFINITION_HASH,
            FORM_PACKAGE_HASH,
            FORM_HASH,
            UI_HASH,
            BPMN_ARTIFACT,
            COMPILED_HASH,
            BPMN_HASH,
            METADATA_HASH,
            PACKAGE_HASH,
            approvalDraftId,
            now
        );
        jdbc.update(
            """
            update ap_approval_design_draft
            set status = 'PUBLISHED',
                published_definition_version = 2,
                published_release_version = 1
            where tenant_id = ? and draft_id = ?
            """,
            TENANT,
            approvalDraftId
        );
    }

    private static ApprovalReleaseDeployment pending(
        String tenantId,
        int attemptCount,
        String packageHash
    ) {
        return new ApprovalReleaseDeployment(
            UUID.randomUUID(),
            tenantId,
            DEFINITION_KEY,
            1,
            packageHash,
            ApprovalReleaseDeployment.Status.PENDING,
            attemptCount,
            null,
            null,
            null,
            null,
            null,
            "operator-a",
            NOW,
            NOW,
            null
        );
    }

    private static ApprovalReleaseDeployment failed(
        ApprovalReleaseDeployment pending
    ) {
        return new ApprovalReleaseDeployment(
            pending.deploymentRecordId(),
            pending.tenantId(),
            pending.definitionKey(),
            pending.releaseVersion(),
            pending.releasePackageHash(),
            ApprovalReleaseDeployment.Status.FAILED,
            pending.attemptCount(),
            null,
            null,
            null,
            "ENGINE_UNAVAILABLE",
            "engine unavailable",
            pending.requestedBy(),
            pending.createdAt(),
            NOW,
            null
        );
    }

    private static ApprovalReleaseDeployment retryPending(
        ApprovalReleaseDeployment failed
    ) {
        return new ApprovalReleaseDeployment(
            failed.deploymentRecordId(),
            failed.tenantId(),
            failed.definitionKey(),
            failed.releaseVersion(),
            failed.releasePackageHash(),
            ApprovalReleaseDeployment.Status.PENDING,
            failed.attemptCount() + 1,
            null,
            null,
            null,
            null,
            null,
            failed.requestedBy(),
            failed.createdAt(),
            NOW,
            null
        );
    }

    private static ApprovalReleaseDeployment deployed(
        ApprovalReleaseDeployment pending
    ) {
        return new ApprovalReleaseDeployment(
            pending.deploymentRecordId(),
            pending.tenantId(),
            pending.definitionKey(),
            pending.releaseVersion(),
            pending.releasePackageHash(),
            ApprovalReleaseDeployment.Status.DEPLOYED,
            pending.attemptCount(),
            "engine-deployment-2",
            "engine-definition-2",
            2,
            null,
            null,
            pending.requestedBy(),
            pending.createdAt(),
            NOW,
            NOW
        );
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static final class FailingOnceEngine implements ApprovalEngine {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public DeploymentResult deploy(DeployCommand command) {
            int call = calls.incrementAndGet();
            if (call == 1) {
                throw new EngineOperationException(
                    "ENGINE_UNAVAILABLE",
                    "engine unavailable"
                );
            }
            return new DeploymentResult(
                "engine-deployment-" + call,
                "engine-definition-" + call,
                command.definitionKey(),
                call
            );
        }

        @Override
        public StartResult start(StartCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TaskSnapshot> findActiveTasks(TaskQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskResult complete(CompleteTaskCommand command) {
            throw new UnsupportedOperationException();
        }

        int calls() {
            return calls.get();
        }
    }
}
