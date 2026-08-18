package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.ClaimRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.ClaimResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore.ProvisioningRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore.FinalDisposition;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore.FinalizeRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore.PrepareRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationKillSwitch.Snapshot;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore.AggregationConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore.AggregationRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore.AggregationResult;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalRuntimeBindingStore;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.definition.ApprovalRuntimeBinding;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.CanarySelection;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.AggregateStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.CanaryStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.OrchestrationStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PauseReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationPlanAggregationStoreMySqlIntegrationTest
    extends MySqlApprovalProjectionStoreIntegrationSupport {

    private static final Instant NOW = Instant.parse("2026-08-17T08:00:00.123456500Z");
    private static final String WORKER = "worker-h8";
    private static final String SOURCE_BINDING_HASH = "9".repeat(64);
    private static final String TARGET_PACKAGE_HASH = "3".repeat(64);
    private static final String PLAN_HASH = "4".repeat(64);
    private static final String SNAPSHOT_HASH = "e".repeat(64);

    private ObjectMapper objectMapper;
    private JdbcTransactionManager transactionManager;
    private ApprovalReleasePackageStore releasePackages;
    private ApprovalReleaseDeploymentStore deployments;
    private ApprovalRuntimeBindingStore runtimeBindings;

    @BeforeEach
    @Override
    void reset() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        transactionManager = new JdbcTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        store = JdbcApprovalProjectionStoreFactory.create(dataSource, objectMapper);
        releasePackages = JdbcApprovalReleasePackageStoreFactory.create(dataSource);
        deployments = JdbcApprovalReleaseDeploymentStoreFactory.create(dataSource);
        runtimeBindings = JdbcApprovalRuntimeBindingStoreFactory.create(dataSource);
    }

    @Test
    void exactReplayCanonicalTimeChangedReplayTenantAndStaleRevisionFailClosed() {
        Authority authority = seedPendingAuthority("Tenant-H8-Replay");
        List<AuditEvent> audits = new ArrayList<>();
        ApprovalMigrationPlanAggregationStore aggregation = aggregationStore(audits);
        assertInstanceOf(
            JdbcMySqlCanonicalApprovalMigrationPlanAggregationStore.class,
            aggregation
        );
        AggregationRequest request = request(authority, 1, "request-h8-replay");

        AggregationResult first = aggregation.aggregate(request);
        AggregationResult replay = aggregation.aggregate(request);

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.aggregate(), replay.aggregate());
        assertEquals(AggregateStatus.NOT_STARTED, first.aggregate().status());
        assertEquals(1, first.aggregate().counts().selectedCount());
        assertEquals(1, first.aggregate().counts().pendingCount());
        assertEquals(1, first.aggregate().counts().unresolvedCount());
        assertEquals(PauseReason.NONE, first.aggregate().pauseReason());
        assertNull(first.completion());
        assertEquals(
            AuditHashCanonicalizer.canonicalInstant(request.happenedAt()),
            first.aggregate().aggregatedAt()
        );
        assertEquals(first.aggregate().aggregatedAt(), timestamp(
            "ap_process_migration_plan_aggregate",
            "aggregated_at",
            authority.tenantId()
        ));
        assertEquals(1, count("ap_process_migration_plan_aggregate", authority.tenantId()));
        assertEquals(1, count(
            "ap_process_migration_plan_aggregate_event",
            authority.tenantId()
        ));
        assertEquals(0, count("ap_process_migration_plan_completion", authority.tenantId()));
        assertEquals(1, audits.size());

        assertThrows(AggregationConflictException.class, () -> aggregation.aggregate(
            new AggregationRequest(
                request.context(),
                request.planId(),
                2,
                request.reason(),
                request.happenedAt()
            )
        ));
        assertThrows(AggregationConflictException.class, () -> aggregation.aggregate(
            request(authority, 2, "request-h8-stale-after-first")
        ));
        AggregationRequest crossTenant = new AggregationRequest(
            new RequestContext(
                authority.tenantId().toLowerCase(),
                "migration-operator",
                "request-h8-cross-tenant",
                "aggregation-key-cross-tenant",
                "trace-h8"
            ),
            authority.planId(),
            1,
            "Aggregate exact consumed plan for H8",
            NOW.plusSeconds(70)
        );
        assertThrows(
            AggregationConflictException.class,
            () -> aggregation.aggregate(crossTenant)
        );
    }

    @Test
    void unchangedInputAndConcurrentNodesProduceOneAuthoritativeAggregate() throws Exception {
        Authority unchanged = seedPendingAuthority("Tenant-H8-Unchanged");
        ApprovalMigrationPlanAggregationStore store = aggregationStore(new ArrayList<>());
        store.aggregate(request(unchanged, 1, "request-h8-unchanged-first"));
        assertThrows(AggregationConflictException.class, () -> store.aggregate(
            request(unchanged, 2, "request-h8-unchanged-second")
        ));
        assertEquals(1, count("ap_process_migration_plan_aggregate", unchanged.tenantId()));

        Authority concurrent = seedPendingAuthority("Tenant-H8-Concurrent");
        List<AuditEvent> audits = Collections.synchronizedList(new ArrayList<>());
        ApprovalMigrationPlanAggregationStore firstStore = aggregationStore(audits);
        ApprovalMigrationPlanAggregationStore secondStore = aggregationStore(audits);
        AggregationRequest request = request(concurrent, 1, "request-h8-concurrent");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<AggregationResult> left = executor.submit(
                () -> gatedAggregate(firstStore, request, ready, start)
            );
            Future<AggregationResult> right = executor.submit(
                () -> gatedAggregate(secondStore, request, ready, start)
            );
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<AggregationResult> results = List.of(left.get(), right.get());
            assertEquals(1, results.stream().filter(AggregationResult::replayed).count());
            assertEquals(results.get(0).aggregate(), results.get(1).aggregate());
        }

        assertEquals(1, count("ap_process_migration_plan_aggregate", concurrent.tenantId()));
        assertEquals(1, count(
            "ap_process_migration_plan_aggregate_event",
            concurrent.tenantId()
        ));
        assertEquals(1, audits.size());
    }

    @Test
    void realD7EvidenceAggregatesCanaryInProgressWithoutMutableAuthorityReads() {
        Authority authority = seedPendingAuthority("Tenant-H8-D7-Signals");
        prepareOrchestration(authority);

        AggregationResult result = aggregationStore(new ArrayList<>()).aggregate(
            request(authority, 1, "request-h8-d7-signals")
        );

        assertEquals(AggregateStatus.CANARY_IN_PROGRESS, result.aggregate().status());
        assertEquals(CanaryStatus.IN_PROGRESS, result.aggregate().canaryStatus());
        assertEquals(
            OrchestrationStatus.CANARY_IN_PROGRESS,
            result.aggregate().orchestrationStatus()
        );
        assertEquals(1, result.aggregate().counts().pendingCount());
        assertEquals(PauseReason.NONE, result.aggregate().pauseReason());
    }

    @Test
    void realD2AndD3TerminalFailureCreatesExactPlanCompletionEvidence() {
        Authority authority = seedPendingAuthority("Tenant-H8-Terminal");
        ClaimResult claimed = provisionAndClaim(authority);
        UUID attemptId = claimed.attempts().getFirst().attemptId();
        ApprovalMigrationEngineExecutionStore execution =
            JdbcApprovalMigrationEngineExecutionStoreFactory.create(
                dataSource,
                objectMapper,
                transactionManager,
                event -> {
                },
                UUID::randomUUID
            );
        var prepared = execution.prepare(new PrepareRequest(
            authority.tenantId(),
            attemptId,
            WORKER,
            2,
            1,
            NOW.plusSeconds(30),
            "request-h8-engine-prepare",
            "trace-h8"
        ));
        execution.finalizeOutcome(new FinalizeRequest(
            prepared,
            FinalDisposition.ENGINE_REJECTED,
            true,
            true,
            false,
            "ENGINE_REJECTED_TARGET",
            "engine rejected the exact target",
            SNAPSHOT_HASH,
            NOW.plusSeconds(40)
        ));

        AggregationResult result = aggregationStore(new ArrayList<>()).aggregate(
            request(authority, 1, "request-h8-terminal-aggregate")
        );

        assertEquals(
            AggregateStatus.COMPLETED_WITH_TERMINAL_FAILURE,
            result.aggregate().status()
        );
        assertEquals(1, result.aggregate().counts().terminalFailedCount());
        assertEquals(0, result.aggregate().counts().unresolvedCount());
        assertNotNull(result.completion());
        assertEquals(result.aggregate().aggregateId(), result.completion().aggregateId());
        assertEquals(1, count("ap_process_migration_plan_completion", authority.tenantId()));
    }

    @Test
    void incompleteD7SignalLineageFailsClosedAsInvalidEvidence() {
        Authority authority = seedPendingAuthority("Tenant-H8-Incomplete");
        insertIncompleteCanary(authority);

        AggregationResult result = aggregationStore(new ArrayList<>()).aggregate(
            request(authority, 1, "request-h8-incomplete")
        );

        assertEquals(
            AggregateStatus.INVALID_OR_INCOMPLETE_EVIDENCE,
            result.aggregate().status()
        );
        assertEquals(CanaryStatus.INVALID, result.aggregate().canaryStatus());
        assertEquals(OrchestrationStatus.PAUSED, result.aggregate().orchestrationStatus());
        assertEquals(PauseReason.INCOMPLETE_EVIDENCE, result.aggregate().pauseReason());
        assertTrue(result.aggregate().paused());
        assertNull(result.completion());
    }

    @Test
    void auditFailureRollsBackAndDatabaseGuardsRejectMutationDeletionAndForgedPayload() {
        Authority rollback = seedPendingAuthority("Tenant-H8-Audit-Rollback");
        ApprovalMigrationPlanAggregationStore failing =
            JdbcApprovalMigrationPlanAggregationStoreFactory.create(
                dataSource,
                objectMapper,
                transactionManager,
                event -> {
                    throw new IllegalStateException("H8 audit unavailable");
                },
                UUID::randomUUID
            );
        assertThrows(IllegalStateException.class, () -> failing.aggregate(
            request(rollback, 1, "request-h8-audit-rollback")
        ));
        assertEquals(0, count("ap_process_migration_plan_aggregate", rollback.tenantId()));
        assertEquals(0, count(
            "ap_process_migration_plan_aggregate_event",
            rollback.tenantId()
        ));
        assertEquals(0, count("ap_process_migration_plan_completion", rollback.tenantId()));

        Authority tamper = seedPendingAuthority("Tenant-H8-Tamper");
        AggregationResult result = aggregationStore(new ArrayList<>()).aggregate(
            request(tamper, 1, "request-h8-tamper")
        );
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "update ap_process_migration_plan_aggregate set request_id='tampered' "
                + "where tenant_id=? and aggregate_id=?",
            tamper.tenantId(),
            result.aggregate().aggregateId().toString()
        ));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "delete from ap_process_migration_plan_aggregate_event "
                + "where tenant_id=? and aggregate_id=?",
            tamper.tenantId(),
            result.aggregate().aggregateId().toString()
        ));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "insert into ap_process_migration_plan_aggregate ("
                + "tenant_id,aggregate_id,plan_id,intent_id,plan_hash,aggregate_revision,"
                + "status,terminal_outcome,selected_count,provisioned_attempt_count,"
                + "pending_count,claimed_count,engine_requested_count,verifying_count,"
                + "reconciling_count,unknown_count,manual_review_count,binding_conflict_count,"
                + "blocked_stale_count,terminal_failed_count,exact_success_count,unresolved_count,"
                + "canary_status,orchestration_status,paused,pause_reason,kill_switch_observed,"
                + "input_evidence_hash,predecessor_hash,operator_id,idempotency_key,request_hash,"
                + "aggregate_hash,aggregated_at,reason,request_id,audit_reference,payload_json"
                + ") select tenant_id,?,plan_id,intent_id,plan_hash,aggregate_revision+1,"
                + "status,terminal_outcome,selected_count,provisioned_attempt_count,"
                + "pending_count,claimed_count,engine_requested_count,verifying_count,"
                + "reconciling_count,unknown_count,manual_review_count,binding_conflict_count,"
                + "blocked_stale_count,terminal_failed_count,exact_success_count,unresolved_count,"
                + "canary_status,orchestration_status,paused,pause_reason,kill_switch_observed,"
                + "?,aggregate_hash,operator_id,?,request_hash,?,aggregated_at,reason,request_id,"
                + "audit_reference,payload_json from ap_process_migration_plan_aggregate "
                + "where tenant_id=? and aggregate_id=?",
            UUID.randomUUID().toString(),
            hash('b'),
            "forged-idempotency",
            hash('c'),
            tamper.tenantId(),
            result.aggregate().aggregateId().toString()
        ));
    }

    private ApprovalMigrationPlanAggregationStore aggregationStore(List<AuditEvent> audits) {
        return JdbcApprovalMigrationPlanAggregationStoreFactory.create(
            dataSource,
            objectMapper,
            transactionManager,
            audits::add,
            UUID::randomUUID
        );
    }

    private ClaimResult provisionAndClaim(Authority authority) {
        ApprovalMigrationAttemptProvisioningStore provisioning =
            JdbcApprovalMigrationAttemptProvisioningStoreFactory.create(
                dataSource,
                objectMapper,
                transactionManager,
                event -> {
                },
                UUID::randomUUID
            );
        var provisioned = provisioning.ensureInitialAttempts(new ProvisioningRequest(
            authority.tenantId(),
            authority.intentId(),
            WORKER,
            NOW.plusSeconds(10),
            "request-h8-provision",
            "trace-h8",
            hash('f')
        ));
        assertEquals(1, provisioned.createdCount());

        ApprovalMigrationAttemptClaimStore claims =
            JdbcApprovalMigrationAttemptClaimStoreFactory.create(
                dataSource,
                objectMapper,
                transactionManager,
                event -> {
                },
                UUID::randomUUID
            );
        return claims.claim(new ClaimRequest(
            authority.tenantId(),
            authority.intentId(),
            WORKER,
            1,
            NOW.plusSeconds(20),
            NOW.plusSeconds(180),
            "request-h8-claim",
            "trace-h8",
            hash('c')
        ));
    }

    private void prepareOrchestration(Authority authority) {
        ApprovalMigrationOrchestrationStore orchestration =
            JdbcApprovalMigrationOrchestrationStoreFactory.create(
                dataSource,
                objectMapper,
                transactionManager,
                event -> {
                },
                UUID::randomUUID
            );
        orchestration.prepare(new ApprovalMigrationOrchestrationStore.PrepareRequest(
            authority.tenantId(),
            authority.intentId(),
            1,
            1,
            new Snapshot(1, false, "CONFIGURED_OFF", hash('8')),
            NOW.plusSeconds(5),
            "request-h8-orchestration",
            "trace-h8"
        ));
    }

    private void insertIncompleteCanary(Authority authority) {
        CanarySelection canary = new CanarySelection(
            uuid(authority.tenantId(), "incomplete-canary"),
            authority.tenantId(),
            authority.planId(),
            authority.intentId(),
            "CANONICAL_FIRST_V1",
            1,
            authority.instanceId(),
            PLAN_HASH,
            hash('6'),
            hash('7'),
            NOW.plusSeconds(5),
            "request-h8-incomplete-canary",
            "trace-h8"
        );
        jdbc.update("""
            insert into ap_process_migration_canary_selection (
             tenant_id,selection_id,plan_id,intent_id,algorithm_version,sequence_no,
             approval_instance_id,plan_hash,instance_evidence_hash,selection_evidence_hash,
             recorded_at,request_id,trace_id,payload_json
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            canary.tenantId(),
            canary.selectionId().toString(),
            canary.planId().toString(),
            canary.intentId().toString(),
            canary.algorithmVersion(),
            canary.sequenceNo(),
            canary.approvalInstanceId().toString(),
            canary.planHash(),
            canary.instanceEvidenceHash(),
            canary.selectionEvidenceHash(),
            Timestamp.from(AuditHashCanonicalizer.canonicalInstant(canary.recordedAt())),
            canary.requestId(),
            canary.traceId(),
            writeJson(canary)
        );
    }

    private static AggregationRequest request(
        Authority authority,
        long revision,
        String requestId
    ) {
        return new AggregationRequest(
            new RequestContext(
                authority.tenantId(),
                "migration-operator",
                requestId,
                "aggregation-key-" + requestId,
                "trace-h8"
            ),
            authority.planId(),
            revision,
            "Aggregate exact consumed plan for H8",
            NOW.plusSeconds(60 + revision)
        );
    }

    private static AggregationResult gatedAggregate(
        ApprovalMigrationPlanAggregationStore store,
        AggregationRequest request,
        CountDownLatch ready,
        CountDownLatch start
    ) throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return store.aggregate(request);
    }

    private Authority seedPendingAuthority(String tenant) {
        UUID planId = uuid(tenant, "plan");
        UUID intentId = uuid(tenant, "intent");
        UUID instanceId = uuid(tenant, "instance");
        String businessKey = "business-" + tenant;
        String engineInstanceId = "engine-instance-" + tenant;

        MySqlApprovalProjectionProvenanceFixture.seed(
            jdbc,
            tenant,
            DEFINITION_KEY,
            DEFINITION_AT
        );
        ApprovalReleasePackage sourceRelease = releasePackages.find(
            tenant,
            DEFINITION_KEY,
            MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION
        ).orElseThrow();
        ApprovalReleaseDeployment sourceDeployment =
            MySqlApprovalReleaseLifecycleFixture.seedDeployed(
                deployments,
                sourceRelease,
                NOW.minusSeconds(300)
            );
        ApprovalReleasePackage targetRelease = MySqlApprovalReleaseLifecycleFixture.seedRelease(
            jdbc,
            releasePackages,
            tenant,
            DEFINITION_KEY,
            3,
            TARGET_PACKAGE_HASH,
            NOW.minusSeconds(240)
        );
        ApprovalReleaseDeployment targetDeployment =
            MySqlApprovalReleaseLifecycleFixture.seedDeployed(
                deployments,
                targetRelease,
                NOW.minusSeconds(180)
            );
        MySqlH2MigrationAttemptProvisioningFixture.seedActiveSourceRelease(
            dataSource,
            sourceRelease,
            WORKER
        );
        seedProjectionInstance(
            tenant,
            instanceId,
            businessKey,
            engineInstanceId,
            sourceRelease,
            sourceDeployment
        );
        runtimeBindings.save(binding(
            tenant,
            instanceId,
            businessKey,
            engineInstanceId,
            sourceRelease,
            sourceDeployment
        ));
        MySqlH3MigrationAttemptClaimAuthorityFixture.seed(
            jdbc,
            objectMapper,
            tenant,
            planId,
            intentId,
            instanceId,
            DEFINITION_KEY,
            WORKER,
            NOW,
            PLAN_HASH,
            SOURCE_BINDING_HASH,
            sourceRelease,
            targetRelease,
            targetDeployment
        );
        return new Authority(tenant, planId, intentId, instanceId);
    }

    private void seedProjectionInstance(
        String tenant,
        UUID instanceId,
        String businessKey,
        String engineInstanceId,
        ApprovalReleasePackage sourceRelease,
        ApprovalReleaseDeployment sourceDeployment
    ) {
        ApprovalProjectionStore projection = JdbcApprovalProjectionStoreFactory.create(
            dataSource,
            objectMapper
        );
        transactions.executeWithoutResult(status -> {
            projection.lockDefinition(tenant, DEFINITION_KEY, 1);
            if (projection.findDefinition(tenant, DEFINITION_KEY, 1).isEmpty()) {
                projection.saveDefinition(definition(tenant));
            }
            projection.lockBusinessKey(tenant, businessKey);
            projection.createInstance(
                instance(tenant, instanceId, engineInstanceId, businessKey),
                List.of()
            );
        });
        jdbc.update("""
            update ap_approval_instance set
              release_version=?,release_package_hash=?,form_package_version=?,
              form_package_hash=?,ui_schema_version=?,ui_schema_hash=?,
              engine_definition_id=?
            where tenant_id=? and instance_id=?
            """,
            sourceRelease.releaseVersion(),
            sourceRelease.packageHash(),
            sourceRelease.formPackageVersion(),
            sourceRelease.formPackageHash(),
            sourceRelease.uiSchemaVersion(),
            sourceRelease.uiSchemaHash(),
            sourceDeployment.engineDefinitionId(),
            tenant,
            instanceId.toString()
        );
    }

    private ApprovalRuntimeBinding binding(
        String tenant,
        UUID instanceId,
        String businessKey,
        String engineInstanceId,
        ApprovalReleasePackage sourceRelease,
        ApprovalReleaseDeployment sourceDeployment
    ) {
        return new ApprovalRuntimeBinding(
            tenant,
            instanceId,
            businessKey,
            engineInstanceId,
            sourceRelease.definitionKey(),
            sourceRelease.releaseVersion(),
            sourceRelease.packageHash(),
            sourceRelease.definitionVersion(),
            sourceRelease.definitionHash(),
            sourceRelease.formPackageVersion(),
            sourceRelease.formPackageHash(),
            sourceRelease.formVersion(),
            sourceRelease.formHash(),
            sourceRelease.uiSchemaVersion(),
            sourceRelease.uiSchemaHash(),
            sourceRelease.compilerVersion(),
            sourceRelease.compiledArtifactHash(),
            sourceRelease.bpmnHash(),
            sourceRelease.deploymentMetadataHash(),
            sourceDeployment.engineDeploymentId(),
            sourceDeployment.engineDefinitionId(),
            sourceDeployment.engineVersion(),
            SOURCE_BINDING_HASH,
            WORKER,
            NOW.minusSeconds(120),
            "request-h8-binding",
            "trace-h8",
            "audit-event:h8-binding"
        );
    }

    private int count(String table, String tenant) {
        Integer value = jdbc.queryForObject(
            "select count(*) from " + table + " where tenant_id=?",
            Integer.class,
            tenant
        );
        return value == null ? 0 : value;
    }

    private Instant timestamp(String table, String column, String tenant) {
        Timestamp value = jdbc.queryForObject(
            "select " + column + " from " + table + " where tenant_id=?",
            Timestamp.class,
            tenant
        );
        return value == null ? null : value.toInstant();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("H8 fixture JSON failed", exception);
        }
    }

    private static String hash(char value) {
        return Character.toString(value).repeat(64);
    }

    private static UUID uuid(String tenant, String value) {
        return UUID.nameUUIDFromBytes(
            ("mysql-h8:" + tenant + ':' + value).getBytes(StandardCharsets.UTF_8)
        );
    }

    private record Authority(
        String tenantId,
        UUID planId,
        UUID intentId,
        UUID instanceId
    ) {
    }
}
