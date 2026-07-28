package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationRuntimeBindingCasStore.BindingCasDisposition;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationRuntimeBindingCasStore.BindingCasException;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationRuntimeBindingCasStore.BindingCasResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationRuntimeBindingCasStore.CompletionRequest;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.definition.ApprovalRuntimeBinding;
import io.github.akaryc1b.approval.domain.migration.ApprovalCommandOperation;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationCommandFence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot.DefinitionEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot.TaskEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationExactVerification;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationExactVerification.ExactClassification;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalMigrationRuntimeBindingCasStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_migration_binding_cas")
        .withUsername("approval")
        .withPassword("approval");

    private static final Instant NOW = Instant.parse("2026-07-26T16:00:00Z");
    private static final String TENANT = JdbcRuntimeBindingStartTestFixture.TENANT;
    private static final String DEFINITION_KEY = JdbcRuntimeBindingStartTestFixture.DEFINITION_KEY;
    private static final UUID INSTANCE_ID = JdbcRuntimeBindingStartTestFixture.INSTANCE_ID;
    private static final String BUSINESS_KEY = "business-d5";
    private static final String ENGINE_INSTANCE = "engine-instance-d5";
    private static final String SOURCE_DEFINITION =
        JdbcRuntimeBindingStartTestFixture.ENGINE_DEFINITION_ID;
    private static final String SOURCE_BINDING_HASH = "9".repeat(64);
    private static final UUID TARGET_DEPLOYMENT_RECORD =
        UUID.fromString("53000000-0000-0000-0000-000000000001");
    private static final String TARGET_DEPLOYMENT = "engine-deployment-d5-target";
    private static final String TARGET_DEFINITION = "engine-definition:d5-target:2";
    private static final UUID PLAN_ID =
        UUID.fromString("53000000-0000-0000-0000-000000000002");
    private static final UUID AUTHORIZATION_ID =
        UUID.fromString("53000000-0000-0000-0000-000000000003");
    private static final UUID INTENT_ID =
        UUID.fromString("53000000-0000-0000-0000-000000000004");
    private static final UUID ATTEMPT_ID =
        UUID.fromString("53000000-0000-0000-0000-000000000005");
    private static final UUID FENCE_ID =
        UUID.fromString("53000000-0000-0000-0000-000000000006");
    private static final UUID VERIFICATION_ID =
        UUID.fromString("53000000-0000-0000-0000-000000000007");
    private static final UUID ENGINE_REQUEST_ID =
        UUID.fromString("53000000-0000-0000-0000-000000000008");
    private static final UUID ENGINE_OUTCOME_ID =
        UUID.fromString("53000000-0000-0000-0000-000000000009");
    private static final UUID CONSUMPTION_ID =
        UUID.fromString("53000000-0000-0000-0000-000000000010");
    private static final String WORKER = "worker-d5";
    private static final String PLAN_HASH = "4".repeat(64);
    private static final String INTENT_HASH = "5".repeat(64);
    private static final String AUTHORIZATION_HASH = "6".repeat(64);
    private static final String VERIFICATION_HASH = "7".repeat(64);

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;
    private JdbcTransactionManager transactionManager;
    private ApprovalReleasePackage sourcePackage;
    private ApprovalReleasePackage targetPackage;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            truncate table ap_process_migration_binding_cas_conflict,
              ap_process_migration_instance_completion,
              ap_process_migration_exact_verification,
              ap_process_migration_engine_outcome,
              ap_process_migration_engine_request,
              ap_approval_instance_command_fence_event,
              ap_approval_instance_command_fence,
              ap_process_migration_attempt_event,
              ap_process_migration_attempt,
              ap_process_migration_plan_consumption,
              ap_process_migration_intent_event,
              ap_process_migration_intent,
              ap_process_migration_plan_event,
              ap_process_migration_plan_authorization,
              ap_process_migration_plan_instance,
              ap_process_migration_plan,
              ap_process_runtime_binding_evidence,
              ap_process_runtime_binding cascade
            """);
        JdbcRuntimeBindingStartTestFixture.reset(jdbc);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        transactionManager = new JdbcTransactionManager(dataSource);
        sourcePackage = JdbcRuntimeBindingStartTestFixture.seedReleaseEvidence(dataSource);
        targetPackage = seedTargetRelease();
        seedSourceInstanceAndBinding();
    }

    @Test
    void exactTargetCasCompletesBindingProjectionAttemptFenceAndReplayOnce() {
        seedMigrationAuthority(SOURCE_BINDING_HASH);
        JdbcApprovalMigrationRuntimeBindingCasStore store = rawStore(
            new JdbcAuditEventSink(dataSource, objectMapper, transactionManager)
        );

        BindingCasResult first = store.complete(request("request-d5-complete"));
        BindingCasResult replay = store.complete(request("request-d5-complete"));

        assertEquals(BindingCasDisposition.COMPLETED, first.disposition());
        assertEquals(BindingCasDisposition.REPLAYED_COMPLETION, replay.disposition());
        assertTrue(first.completed());
        assertEquals(AttemptStatus.SUCCEEDED, first.attempt().status());
        assertEquals(EngineOutcome.CONFIRMED, first.attempt().engineOutcome());
        assertEquals(2, first.bindingEvidence().bindingRevision());
        assertEquals(SOURCE_BINDING_HASH, first.bindingEvidence().previousBindingEvidenceHash());
        assertNotEquals(SOURCE_BINDING_HASH, first.bindingEvidence().bindingEvidenceHash());
        assertCompletedProjection();

        assertThrows(
            BindingCasException.class,
            () -> store.complete(request("request-d5-changed"))
        );
        assertAppendOnlyEvidence();
    }

    @Test
    void concurrentSerializedCasProducesOneCompletionAndOneExactReplay() throws Exception {
        seedMigrationAuthority(SOURCE_BINDING_HASH);
        var audit = new JdbcAuditEventSink(dataSource, objectMapper, transactionManager);
        var firstStore = new PostgresSerializedApprovalMigrationRuntimeBindingCasStore(
            dataSource,
            rawStore(audit)
        );
        var secondStore = new PostgresSerializedApprovalMigrationRuntimeBindingCasStore(
            dataSource,
            rawStore(audit)
        );
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<BindingCasResult> first = executor.submit(() -> {
                await(start);
                return firstStore.complete(request("request-d5-concurrent"));
            });
            Future<BindingCasResult> second = executor.submit(() -> {
                await(start);
                return secondStore.complete(request("request-d5-concurrent"));
            });
            start.countDown();

            List<BindingCasDisposition> dispositions = List.of(
                first.get(10, TimeUnit.SECONDS).disposition(),
                second.get(10, TimeUnit.SECONDS).disposition()
            );
            assertTrue(dispositions.contains(BindingCasDisposition.COMPLETED));
            assertTrue(dispositions.contains(BindingCasDisposition.REPLAYED_COMPLETION));
        }

        assertCompletedProjection();
    }

    @Test
    void staleBindingCasRecordsObservedConflictAndRequiresReconciliationWithoutMutation() {
        String staleExpectedHash = "e".repeat(64);
        seedMigrationAuthority(staleExpectedHash);
        JdbcApprovalMigrationRuntimeBindingCasStore store = rawStore(
            new JdbcAuditEventSink(dataSource, objectMapper, transactionManager)
        );

        BindingCasResult first = store.complete(request("request-d5-conflict"));
        BindingCasResult replay = store.complete(request("request-d5-conflict"));

        assertEquals(BindingCasDisposition.RECONCILIATION_REQUIRED, first.disposition());
        assertEquals(BindingCasDisposition.REPLAYED_CONFLICT, replay.disposition());
        assertEquals(AttemptStatus.RECONCILING, first.attempt().status());
        assertEquals(EngineOutcome.VERIFICATION_MISMATCH, first.attempt().engineOutcome());
        assertEquals(SOURCE_BINDING_HASH, first.conflictEvidence().observedBindingEvidenceHash());
        assertEquals(1L, first.conflictEvidence().observedBindingRevision());
        assertEquals(1L, bindingRevision());
        assertEquals(1, bindingReleaseVersion());
        assertEquals(SOURCE_DEFINITION, bindingDefinitionId());
        assertEquals(1, count("ap_process_runtime_binding_evidence"));
        assertEquals(0, count("ap_process_migration_instance_completion"));
        assertEquals(1, count("ap_process_migration_binding_cas_conflict"));
        assertEquals("RECONCILING", attemptStatus());
        assertEquals(5L, attemptRevision());
        assertEquals("ACTIVE", fenceStatus());
        assertEquals(1L, fenceRevision());
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from ap_audit_event "
                + "where action='PROCESS_MIGRATION_BINDING_CAS_CONFLICT_RECORDED'",
            Integer.class
        ));
        assertThrows(
            BindingCasException.class,
            () -> store.complete(request("request-d5-conflict-changed"))
        );
    }

    @Test
    void auditFailureRollsBackBindingCompletionAttemptAndFenceTogether() {
        seedMigrationAuthority(SOURCE_BINDING_HASH);
        JdbcApprovalMigrationRuntimeBindingCasStore store = rawStore(event -> {
            throw new IllegalStateException("audit unavailable");
        });

        assertThrows(
            IllegalStateException.class,
            () -> store.complete(request("request-d5-audit-failure"))
        );

        assertEquals(1L, bindingRevision());
        assertEquals(1, bindingReleaseVersion());
        assertEquals(1, instanceReleaseVersion());
        assertEquals(1, count("ap_process_runtime_binding_evidence"));
        assertEquals(0, count("ap_process_migration_instance_completion"));
        assertEquals(0, count("ap_process_migration_binding_cas_conflict"));
        assertEquals("VERIFYING", attemptStatus());
        assertEquals(4L, attemptRevision());
        assertEquals("ACTIVE", fenceStatus());
        assertEquals(1L, fenceRevision());
        assertEquals(0, count("ap_audit_event"));
    }

    private JdbcApprovalMigrationRuntimeBindingCasStore rawStore(
        io.github.akaryc1b.approval.application.port.AuditEventSink audit
    ) {
        AtomicLong sequence = new AtomicLong();
        return new JdbcApprovalMigrationRuntimeBindingCasStore(
            dataSource,
            objectMapper,
            transactionManager,
            audit,
            () -> new UUID(0x5300000000000000L, sequence.incrementAndGet())
        );
    }

    private CompletionRequest request(String requestId) {
        return new CompletionRequest(
            TENANT,
            ATTEMPT_ID,
            VERIFICATION_ID,
            WORKER,
            4,
            1,
            1,
            NOW.plusSeconds(30),
            requestId,
            "trace-d5"
        );
    }

    private ApprovalReleasePackage seedTargetRelease() {
        ApprovalReleasePackage value = new ApprovalReleasePackage(
            TENANT,
            DEFINITION_KEY,
            2,
            sourcePackage.definitionVersion(),
            sourcePackage.definitionHash(),
            sourcePackage.formPackageVersion(),
            sourcePackage.formPackageHash(),
            sourcePackage.formVersion(),
            sourcePackage.formHash(),
            sourcePackage.uiSchemaVersion(),
            sourcePackage.uiSchemaHash(),
            sourcePackage.compilerVersion(),
            sourcePackage.bpmnResourceName(),
            sourcePackage.bpmnArtifact(),
            sourcePackage.compiledArtifactHash(),
            sourcePackage.bpmnHash(),
            sourcePackage.dmnArtifact(),
            sourcePackage.dmnHash(),
            sourcePackage.deploymentMetadataHash(),
            hash('a'),
            UUID.fromString("53000000-0000-0000-0000-000000000011"),
            "publisher-d5",
            NOW.plusSeconds(1)
        );
        JdbcRuntimeBindingStartTestFixture.insertPackage(dataSource, value);
        jdbc.update("""
            insert into ap_process_release_lifecycle (
              tenant_id,definition_key,release_version,release_package_hash,
              lifecycle_state,revision,published_by,published_at,
              activated_at,deprecated_at,retired_at,last_transition_by,
              last_transition_at,last_transition_reason,last_idempotency_key,
              last_request_id,last_trace_id,last_audit_chain_reference
            ) values (?,?,?,?,'PUBLISHED',1,?,?,null,null,null,?,?,?, ?,?,?,?)
            """,
            TENANT,
            DEFINITION_KEY,
            value.releaseVersion(),
            value.packageHash(),
            value.publishedBy(),
            offset(value.publishedAt()),
            value.publishedBy(),
            offset(value.publishedAt()),
            "D5 target lifecycle fixture",
            "target-lifecycle-d5",
            "request-target-lifecycle-d5",
            "trace-d5",
            "audit-target-lifecycle-d5"
        );
        new JdbcApprovalReleaseDeploymentStore(dataSource).save(new ApprovalReleaseDeployment(
            TARGET_DEPLOYMENT_RECORD,
            TENANT,
            DEFINITION_KEY,
            value.releaseVersion(),
            value.packageHash(),
            ApprovalReleaseDeployment.Status.DEPLOYED,
            1,
            TARGET_DEPLOYMENT,
            TARGET_DEFINITION,
            2,
            null,
            null,
            "deployer-d5",
            NOW.plusSeconds(2),
            NOW.plusSeconds(2),
            NOW.plusSeconds(2)
        ));
        return value;
    }

    private void seedSourceInstanceAndBinding() {
        jdbc.update("""
            insert into ap_approval_instance (
              instance_id,tenant_id,business_key,engine_instance_id,
              definition_key,definition_version,form_key,form_version,
              compiler_version,content_hash,release_version,release_package_hash,
              form_package_version,form_package_hash,ui_schema_version,ui_schema_hash,
              engine_definition_id,initiator_id,amount,supplier,purchase_order_reference,
              attachment_ids_json,assignee_snapshot_json,request_hash,status,version,
              created_at,updated_at
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, ?,?,cast(? as jsonb),
              cast(? as jsonb),?,'RUNNING',1,?,?)
            """,
            INSTANCE_ID,
            TENANT,
            BUSINESS_KEY,
            ENGINE_INSTANCE,
            DEFINITION_KEY,
            sourcePackage.definitionVersion(),
            DEFINITION_KEY,
            sourcePackage.formVersion(),
            sourcePackage.compilerVersion(),
            sourcePackage.definitionHash(),
            sourcePackage.releaseVersion(),
            sourcePackage.packageHash(),
            sourcePackage.formPackageVersion(),
            sourcePackage.formPackageHash(),
            sourcePackage.uiSchemaVersion(),
            sourcePackage.uiSchemaHash(),
            SOURCE_DEFINITION,
            "initiator-d5",
            BigDecimal.ONE,
            "supplier-d5",
            "po-d5",
            "[]",
            "{}",
            hash('b'),
            offset(NOW.plusSeconds(3)),
            offset(NOW.plusSeconds(3))
        );
        new JdbcApprovalRuntimeBindingStore(dataSource).save(new ApprovalRuntimeBinding(
            TENANT,
            INSTANCE_ID,
            BUSINESS_KEY,
            ENGINE_INSTANCE,
            DEFINITION_KEY,
            sourcePackage.releaseVersion(),
            sourcePackage.packageHash(),
            sourcePackage.definitionVersion(),
            sourcePackage.definitionHash(),
            sourcePackage.formPackageVersion(),
            sourcePackage.formPackageHash(),
            sourcePackage.formVersion(),
            sourcePackage.formHash(),
            sourcePackage.uiSchemaVersion(),
            sourcePackage.uiSchemaHash(),
            sourcePackage.compilerVersion(),
            sourcePackage.compiledArtifactHash(),
            sourcePackage.bpmnHash(),
            sourcePackage.deploymentMetadataHash(),
            JdbcRuntimeBindingStartTestFixture.ENGINE_DEPLOYMENT_ID,
            SOURCE_DEFINITION,
            1,
            SOURCE_BINDING_HASH,
            "starter-d5",
            NOW.plusSeconds(3),
            "request-start-d5",
            "trace-d5",
            "audit:start-d5"
        ));
        assertEquals(1, count("ap_process_runtime_binding_evidence"));
    }

    private void seedMigrationAuthority(String expectedBindingHash) {
        ApprovalMigrationAttempt attempt = new ApprovalMigrationAttempt(
            ATTEMPT_ID,
            TENANT,
            INTENT_ID,
            INSTANCE_ID,
            ENGINE_INSTANCE,
            1,
            null,
            expectedBindingHash,
            SOURCE_DEFINITION,
            TARGET_DEFINITION,
            AttemptStatus.VERIFYING,
            EngineOutcome.ACCEPTED,
            4,
            null,
            null,
            ENGINE_REQUEST_ID.toString(),
            FailureClass.NONE,
            null,
            NOW.plusSeconds(10),
            NOW.plusSeconds(20),
            "request-attempt-d5",
            "trace-d5"
        );
        ApprovalMigrationCommandFence fence = new ApprovalMigrationCommandFence(
            FENCE_ID,
            TENANT,
            INSTANCE_ID,
            ATTEMPT_ID,
            ApprovalCommandOperation.MIGRATION,
            ApprovalMigrationCommandFence.FenceStatus.ACTIVE,
            1,
            WORKER,
            NOW.plusSeconds(600),
            "fence-d5",
            hash('2'),
            NOW.plusSeconds(10),
            NOW.plusSeconds(10),
            null,
            "request-fence-d5",
            "trace-d5"
        );
        ApprovalMigrationExactVerification verification = exactVerification();

        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set session_replication_role = replica");
                statement.executeUpdate(planSql());
                statement.executeUpdate(intentSql());
                statement.executeUpdate(consumptionSql());
                statement.executeUpdate(attemptSql(attempt));
                statement.executeUpdate(attemptEventSql());
                statement.executeUpdate(fenceSql(fence));
                statement.executeUpdate(fenceEventSql());
                statement.executeUpdate(verificationSql(verification));
            } finally {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("set session_replication_role = origin");
                }
            }
            return null;
        });
    }

    private ApprovalMigrationExactVerification exactVerification() {
        TaskEvidence task = new TaskEvidence(hash('3'), "managerApproval", TARGET_DEFINITION, false);
        ApprovalMigrationEngineSnapshot snapshot = new ApprovalMigrationEngineSnapshot(
            true,
            null,
            true,
            TARGET_DEFINITION,
            TARGET_DEPLOYMENT,
            false,
            List.of("managerApproval"),
            List.of(new DefinitionEvidence("EXECUTION_ACTIVITY", "execution-d5", TARGET_DEFINITION)),
            List.of(task),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            true,
            TARGET_DEFINITION,
            null,
            null,
            List.of(task),
            false,
            hash('4')
        );
        return new ApprovalMigrationExactVerification(
            VERIFICATION_ID,
            TENANT,
            INTENT_ID,
            ATTEMPT_ID,
            ENGINE_REQUEST_ID,
            ENGINE_OUTCOME_ID,
            SOURCE_DEFINITION,
            TARGET_DEFINITION,
            ExactClassification.EXACT_TARGET_RUNTIME,
            snapshot,
            hash('5'),
            VERIFICATION_HASH,
            NOW.plusSeconds(25),
            "request-verification-d5",
            "trace-d5"
        );
    }

    private String planSql() {
        return """
            insert into ap_process_migration_plan (
              tenant_id,plan_id,idempotency_key,plan_hash,assessment_id,assessment_report_hash,
              definition_key,source_release_version,source_package_hash,target_release_version,
              target_package_hash,target_deployment_record_id,target_engine_deployment_id,
              target_engine_definition_id,target_engine_version,selected_instance_count,status,
              revision,requested_by,operation_reason,assessed_at,created_at,expires_at,updated_at,
              authorization_id,authorization_evidence_hash,authorized_by,authorized_at,
              authorization_expires_at,request_id,trace_id,audit_chain_reference,payload_json
            ) values (
              '%s','%s','plan-d5','%s','%s','%s','%s',1,'%s',2,'%s','%s','%s','%s',2,1,
              'CONSUMED',3,'operator-d5','D5 exact target CAS',timestamptz '%s',
              timestamptz '%s',timestamptz '%s',timestamptz '%s','%s','%s','approver-d5',
              timestamptz '%s',timestamptz '%s','request-plan-d5','trace-d5','audit-plan-d5',
              '{}'::jsonb
            )
            """.formatted(
            TENANT,
            PLAN_ID,
            PLAN_HASH,
            UUID.fromString("53000000-0000-0000-0000-000000000014"),
            hash('6'),
            DEFINITION_KEY,
            sourcePackage.packageHash(),
            targetPackage.packageHash(),
            TARGET_DEPLOYMENT_RECORD,
            TARGET_DEPLOYMENT,
            TARGET_DEFINITION,
            offsetText(NOW),
            offsetText(NOW.plusSeconds(1)),
            offsetText(NOW.plusSeconds(3600)),
            offsetText(NOW.plusSeconds(10)),
            AUTHORIZATION_ID,
            AUTHORIZATION_HASH,
            offsetText(NOW.plusSeconds(5)),
            offsetText(NOW.plusSeconds(3500))
        );
    }

    private String intentSql() {
        return """
            insert into ap_process_migration_intent (
              tenant_id,intent_id,idempotency_key,plan_id,plan_hash,definition_key,
              source_release_version,source_package_hash,target_release_version,target_package_hash,
              status,revision,intent_evidence_hash,payload_json,created_at,updated_at
            ) values ('%s','%s','intent-d5','%s','%s','%s',1,'%s',2,'%s','RUNNING',2,'%s',
              '{}'::jsonb,timestamptz '%s',timestamptz '%s')
            """.formatted(
            TENANT,
            INTENT_ID,
            PLAN_ID,
            PLAN_HASH,
            DEFINITION_KEY,
            sourcePackage.packageHash(),
            targetPackage.packageHash(),
            INTENT_HASH,
            offsetText(NOW.plusSeconds(10)),
            offsetText(NOW.plusSeconds(20))
        );
    }

    private String consumptionSql() {
        return """
            insert into ap_process_migration_plan_consumption (
              tenant_id,consumption_id,plan_id,plan_hash,authorization_id,
              authorization_evidence_hash,intent_id,intent_evidence_hash,idempotency_key,
              request_hash,consumed_by,reason,consumed_at,request_id,trace_id,
              audit_chain_reference,payload_json
            ) values ('%s','%s','%s','%s','%s','%s','%s','%s','intent-d5','%s',
              'operator-d5','D5 test consumption',timestamptz '%s','request-consume-d5',
              'trace-d5','audit-consume-d5','{}'::jsonb)
            """.formatted(
            TENANT,
            CONSUMPTION_ID,
            PLAN_ID,
            PLAN_HASH,
            AUTHORIZATION_ID,
            AUTHORIZATION_HASH,
            INTENT_ID,
            INTENT_HASH,
            hash('7'),
            offsetText(NOW.plusSeconds(10))
        );
    }

    private String attemptSql(ApprovalMigrationAttempt value) {
        return """
            insert into ap_process_migration_attempt (
              tenant_id,attempt_id,intent_id,approval_instance_id,attempt_number,parent_attempt_id,
              status,revision,engine_outcome,lease_actor,lease_owner,lease_until,
              engine_request_reference,failure_class,error_summary,expected_binding_evidence_hash,
              payload_json,created_at,updated_at
            ) values ('%s','%s','%s','%s',1,null,'VERIFYING',4,'ACCEPTED','%s',null,null,
              '%s','NONE',null,'%s','%s'::jsonb,timestamptz '%s',timestamptz '%s')
            """.formatted(
            TENANT,
            ATTEMPT_ID,
            INTENT_ID,
            INSTANCE_ID,
            WORKER,
            ENGINE_REQUEST_ID,
            value.expectedBindingEvidenceHash(),
            json(value),
            offsetText(value.createdAt()),
            offsetText(value.updatedAt())
        );
    }

    private String attemptEventSql() {
        return """
            insert into ap_process_migration_attempt_event (
              tenant_id,event_id,attempt_id,revision,from_status,to_status,engine_outcome,
              lease_actor,lease_owner,lease_until,engine_request_reference,failure_class,
              error_summary,payload_json,happened_at
            ) values ('%s','%s','%s',4,'ENGINE_REQUESTED','VERIFYING','ACCEPTED',null,null,null,
              '%s','NONE',null,'{}'::jsonb,timestamptz '%s')
            """.formatted(
            TENANT,
            UUID.fromString("53000000-0000-0000-0000-000000000012"),
            ATTEMPT_ID,
            ENGINE_REQUEST_ID,
            offsetText(NOW.plusSeconds(20))
        );
    }

    private String fenceSql(ApprovalMigrationCommandFence value) {
        return """
            insert into ap_approval_instance_command_fence (
              tenant_id,fence_id,approval_instance_id,attempt_id,operation,status,revision,
              lease_owner,lease_until,idempotency_key,request_hash,acquired_at,updated_at,
              released_at,request_id,trace_id,payload_json
            ) values ('%s','%s','%s','%s','MIGRATION','ACTIVE',1,'%s',timestamptz '%s',
              '%s','%s',timestamptz '%s',timestamptz '%s',null,'%s','%s','%s'::jsonb)
            """.formatted(
            TENANT,
            value.fenceId(),
            INSTANCE_ID,
            ATTEMPT_ID,
            WORKER,
            offsetText(value.leaseUntil()),
            value.idempotencyKey(),
            value.requestHash(),
            offsetText(value.acquiredAt()),
            offsetText(value.updatedAt()),
            value.requestId(),
            value.traceId(),
            json(value)
        );
    }

    private String fenceEventSql() {
        return """
            insert into ap_approval_instance_command_fence_event (
              tenant_id,event_id,fence_id,approval_instance_id,attempt_id,revision,
              from_status,to_status,lease_actor,lease_owner,lease_until,happened_at,
              request_id,trace_id,payload_json
            ) values ('%s','%s','%s','%s','%s',1,null,'ACTIVE','%s','%s',
              timestamptz '%s',timestamptz '%s','request-fence-d5','trace-d5','{}'::jsonb)
            """.formatted(
            TENANT,
            UUID.fromString("53000000-0000-0000-0000-000000000013"),
            FENCE_ID,
            INSTANCE_ID,
            ATTEMPT_ID,
            WORKER,
            WORKER,
            offsetText(NOW.plusSeconds(600)),
            offsetText(NOW.plusSeconds(10))
        );
    }

    private String verificationSql(ApprovalMigrationExactVerification value) {
        return """
            insert into ap_process_migration_exact_verification (
              tenant_id,verification_id,intent_id,attempt_id,engine_request_id,
              engine_outcome_id,worker_id,expected_attempt_revision,expected_fence_revision,
              source_engine_definition_id,target_engine_definition_id,classification,
              read_succeeded,runtime_present,history_present,truncated,
              observed_runtime_definition_id,observed_history_definition_id,
              snapshot_hash,request_hash,verification_evidence_hash,recorded_at,
              request_id,trace_id,payload_json
            ) values ('%s','%s','%s','%s','%s','%s','%s',4,1,'%s','%s',
              'EXACT_TARGET_RUNTIME',true,true,true,false,'%s','%s','%s','%s','%s',
              timestamptz '%s','%s','%s','%s'::jsonb)
            """.formatted(
            TENANT,
            VERIFICATION_ID,
            INTENT_ID,
            ATTEMPT_ID,
            ENGINE_REQUEST_ID,
            ENGINE_OUTCOME_ID,
            WORKER,
            SOURCE_DEFINITION,
            TARGET_DEFINITION,
            TARGET_DEFINITION,
            TARGET_DEFINITION,
            value.snapshot().snapshotHash(),
            value.requestHash(),
            value.verificationEvidenceHash(),
            offsetText(value.recordedAt()),
            value.requestId(),
            value.traceId(),
            json(value)
        );
    }

    private void assertCompletedProjection() {
        assertEquals(2L, bindingRevision());
        assertEquals(2, bindingReleaseVersion());
        assertEquals(TARGET_DEFINITION, bindingDefinitionId());
        assertEquals(2, instanceReleaseVersion());
        assertEquals(TARGET_DEFINITION, textValue(
            "select engine_definition_id from ap_approval_instance "
                + "where tenant_id=? and instance_id=?",
            TENANT,
            INSTANCE_ID
        ));
        assertEquals(2, count("ap_process_runtime_binding_evidence"));
        assertEquals(1, count("ap_process_migration_instance_completion"));
        assertEquals(0, count("ap_process_migration_binding_cas_conflict"));
        assertEquals("SUCCEEDED", attemptStatus());
        assertEquals(5L, attemptRevision());
        assertEquals("RELEASED", fenceStatus());
        assertEquals(2L, fenceRevision());
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from ap_audit_event "
                + "where action='PROCESS_MIGRATION_INSTANCE_COMPLETED'",
            Integer.class
        ));
        assertEquals(2, jdbc.queryForObject(
            "select count(*) from ap_process_migration_attempt_event "
                + "where tenant_id=? and attempt_id=?",
            Integer.class,
            TENANT,
            ATTEMPT_ID
        ));
        assertEquals(2, jdbc.queryForObject(
            "select count(*) from ap_approval_instance_command_fence_event "
                + "where tenant_id=? and fence_id=?",
            Integer.class,
            TENANT,
            FENCE_ID
        ));
    }

    private void assertAppendOnlyEvidence() {
        assertThrows(
            DataAccessException.class,
            () -> jdbc.update(
                "delete from ap_process_migration_instance_completion "
                    + "where tenant_id=? and attempt_id=?",
                TENANT,
                ATTEMPT_ID
            )
        );
        assertThrows(
            DataAccessException.class,
            () -> jdbc.update(
                "update ap_process_runtime_binding_evidence set request_id='tampered' "
                    + "where tenant_id=? and approval_instance_id=? and binding_revision=2",
                TENANT,
                INSTANCE_ID
            )
        );
    }

    private long bindingRevision() {
        return longValue(
            "select binding_revision from ap_process_runtime_binding "
                + "where tenant_id=? and approval_instance_id=?",
            TENANT,
            INSTANCE_ID
        );
    }

    private int bindingReleaseVersion() {
        return intValue(
            "select release_version from ap_process_runtime_binding "
                + "where tenant_id=? and approval_instance_id=?",
            TENANT,
            INSTANCE_ID
        );
    }

    private String bindingDefinitionId() {
        return textValue(
            "select engine_definition_id from ap_process_runtime_binding "
                + "where tenant_id=? and approval_instance_id=?",
            TENANT,
            INSTANCE_ID
        );
    }

    private int instanceReleaseVersion() {
        return intValue(
            "select release_version from ap_approval_instance "
                + "where tenant_id=? and instance_id=?",
            TENANT,
            INSTANCE_ID
        );
    }

    private String attemptStatus() {
        return textValue(
            "select status from ap_process_migration_attempt where tenant_id=? and attempt_id=?",
            TENANT,
            ATTEMPT_ID
        );
    }

    private long attemptRevision() {
        return longValue(
            "select revision from ap_process_migration_attempt where tenant_id=? and attempt_id=?",
            TENANT,
            ATTEMPT_ID
        );
    }

    private String fenceStatus() {
        return textValue(
            "select status from ap_approval_instance_command_fence where tenant_id=? and fence_id=?",
            TENANT,
            FENCE_ID
        );
    }

    private long fenceRevision() {
        return longValue(
            "select revision from ap_approval_instance_command_fence where tenant_id=? and fence_id=?",
            TENANT,
            FENCE_ID
        );
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private int intValue(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private long longValue(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    private String textValue(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, String.class, arguments);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value).replace("'", "''");
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("test evidence JSON failed", exception);
        }
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static String offsetText(Instant value) {
        return offset(value).toString();
    }

    private static String hash(char value) {
        return Character.toString(value).repeat(64);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out awaiting concurrent CAS start");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrent CAS test was interrupted", exception);
        }
    }
}
