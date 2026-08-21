package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.ClaimRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore.ProvisioningRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore.ExecutionConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore.FinalDisposition;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore.FinalizeRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore.PrepareRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore.PreparedDispatch;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalRuntimeBindingStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.definition.ApprovalRuntimeBinding;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationCommandFence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationEngineExecutionStoreMySqlIntegrationTest
    extends MySqlApprovalProjectionStoreIntegrationSupport {

    private static final Instant NOW = Instant.parse(
        "2026-08-12T01:00:00.123456500Z"
    );
    private static final String WORKER = "worker-h4";
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
    void prepareAndReturnedOutcomeCommitExactEvidenceAndTypedAttemptState() {
        Authority authority = seedClaimedAuthority("Tenant-H4-Success");
        List<AuditEvent> audits = new ArrayList<>();
        ApprovalMigrationEngineExecutionStore execution = executionStore(audits::add);
        assertInstanceOf(JdbcMySqlApprovalMigrationEngineExecutionStore.class, execution);

        PreparedDispatch prepared = execution.prepare(prepareRequest(authority));

        assertEquals(AttemptStatus.ENGINE_REQUESTED, prepared.attempt().status());
        assertEquals(EngineOutcome.NOT_REQUESTED, prepared.attempt().engineOutcome());
        assertEquals(3, prepared.attempt().revision());
        assertNull(prepared.attempt().leaseOwner());
        assertNull(prepared.attempt().leaseUntil());
        assertEquals(
            prepared.engineRequestId().toString(),
            prepared.attempt().engineRequestReference()
        );
        assertEquals(authority.engineInstanceId(), prepared.engineCommand().engineInstanceId());
        assertEquals(
            authority.sourceDeployment().engineDefinitionId(),
            prepared.engineCommand().sourceEngineDefinitionId()
        );
        assertEquals(
            authority.targetDeployment().engineDeploymentId(),
            prepared.engineCommand().targetEngineDeploymentId()
        );
        assertEquals(
            authority.targetDeployment().engineDefinitionId(),
            prepared.engineCommand().targetEngineDefinitionId()
        );
        assertEquals(1, count("ap_process_migration_engine_request", authority.tenantId()));
        assertEquals(3, count("ap_process_migration_attempt_event", authority.tenantId()));
        assertEquals(1, audits.size());
        assertEquals(
            AuditHashCanonicalizer.canonicalInstant(NOW.plusSeconds(20)),
            prepared.preparedAt()
        );
        assertEquals(
            prepared.preparedAt(),
            timestamp(
                "ap_process_migration_engine_request",
                "requested_at",
                authority.tenantId()
            )
        );

        ApprovalMigrationAttempt finalized = execution.finalizeOutcome(
            returned(prepared, NOW.plusSeconds(30))
        );

        assertEquals(AttemptStatus.VERIFYING, finalized.status());
        assertEquals(EngineOutcome.ACCEPTED, finalized.engineOutcome());
        assertEquals(FailureClass.NONE, finalized.failureClass());
        assertNull(finalized.errorSummary());
        assertEquals(4, finalized.revision());
        assertEquals(1, count("ap_process_migration_engine_outcome", authority.tenantId()));
        assertEquals(4, count("ap_process_migration_attempt_event", authority.tenantId()));
        assertEquals(2, audits.size());
        assertEquals("CALL_RETURNED_AWAITING_VERIFICATION", outcomeDisposition(authority));
        assertEquals(finalized, attemptPayload(authority));
    }

    @Test
    void ambiguousOutcomeBecomesUnknownOnceAndDuplicateFinalizationFails() {
        Authority authority = seedClaimedAuthority("Tenant-H4-Ambiguous");
        ApprovalMigrationEngineExecutionStore execution = executionStore(event -> {
        });
        PreparedDispatch prepared = execution.prepare(prepareRequest(authority));
        FinalizeRequest ambiguous = new FinalizeRequest(
            prepared,
            FinalDisposition.AMBIGUOUS_UNKNOWN,
            true,
            false,
            true,
            "RESPONSE_LOST",
            "engine response unavailable after dispatch",
            SNAPSHOT_HASH,
            NOW.plusSeconds(30)
        );

        ApprovalMigrationAttempt unknown = execution.finalizeOutcome(ambiguous);

        assertEquals(AttemptStatus.UNKNOWN, unknown.status());
        assertEquals(EngineOutcome.UNKNOWN, unknown.engineOutcome());
        assertEquals(FailureClass.ENGINE_OUTCOME_UNKNOWN, unknown.failureClass());
        assertTrue(unknown.errorSummary().startsWith("RESPONSE_LOST:"));
        assertEquals(1, count("ap_process_migration_engine_outcome", authority.tenantId()));
        assertThrows(ExecutionConflictException.class, () -> execution.finalizeOutcome(ambiguous));
        assertEquals(1, count("ap_process_migration_engine_outcome", authority.tenantId()));
        assertEquals(4, attemptRevision(authority));
    }

    @Test
    void engineRejectedBecomesFailedTerminal() {
        Authority authority = seedClaimedAuthority("Tenant-H4-Rejected");
        ApprovalMigrationEngineExecutionStore execution = executionStore(event -> {
        });
        PreparedDispatch prepared = execution.prepare(prepareRequest(authority));

        ApprovalMigrationAttempt rejected = execution.finalizeOutcome(new FinalizeRequest(
            prepared,
            FinalDisposition.ENGINE_REJECTED,
            true,
            true,
            false,
            "ENGINE_REJECTED_TARGET",
            "engine rejected the requested target",
            SNAPSHOT_HASH,
            NOW.plusSeconds(30)
        ));

        assertEquals(AttemptStatus.FAILED_TERMINAL, rejected.status());
        assertEquals(EngineOutcome.REJECTED, rejected.engineOutcome());
        assertEquals(FailureClass.ENGINE_REJECTED, rejected.failureClass());
        assertTrue(rejected.errorSummary().startsWith("ENGINE_REJECTED_TARGET:"));
        assertEquals(1, count("ap_process_migration_engine_outcome", authority.tenantId()));
    }

    @Test
    void staleTenantAttemptFenceRuntimeBindingAndTargetAuthorityFailClosed() {
        Authority stale = seedClaimedAuthority("Tenant-H4-Stale");
        ApprovalMigrationEngineExecutionStore execution = executionStore(event -> {
        });

        assertThrows(
            ExecutionConflictException.class,
            () -> execution.prepare(new PrepareRequest(
                stale.tenantId().toLowerCase(),
                stale.attemptId(),
                WORKER,
                2,
                1,
                NOW.plusSeconds(20),
                "request-h4-wrong-tenant",
                "trace-h4"
            ))
        );
        assertThrows(
            ExecutionConflictException.class,
            () -> execution.prepare(new PrepareRequest(
                stale.tenantId(),
                stale.attemptId(),
                WORKER,
                1,
                1,
                NOW.plusSeconds(20),
                "request-h4-stale-attempt",
                "trace-h4"
            ))
        );
        assertThrows(
            ExecutionConflictException.class,
            () -> execution.prepare(new PrepareRequest(
                stale.tenantId(),
                stale.attemptId(),
                WORKER,
                2,
                2,
                NOW.plusSeconds(20),
                "request-h4-stale-fence",
                "trace-h4"
            ))
        );
        assertEquals(0, count("ap_process_migration_engine_request", stale.tenantId()));
        assertEquals(AttemptStatus.CLAIMED, attemptPayload(stale).status());

        Authority bindingDrift = seedClaimedAuthority("Tenant-H4-Binding-Drift");
        assertEquals(1, jdbc.update(
            "update ap_process_runtime_binding set binding_evidence_hash=? "
                + "where tenant_id=? and approval_instance_id=?",
            "a".repeat(64),
            bindingDrift.tenantId(),
            bindingDrift.instanceId().toString()
        ));
        assertThrows(
            ExecutionConflictException.class,
            () -> execution.prepare(prepareRequest(bindingDrift))
        );
        assertEquals(
            0,
            count("ap_process_migration_engine_request", bindingDrift.tenantId())
        );

        Authority targetDrift = seedClaimedAuthority("Tenant-H4-Target-Drift");
        assertEquals(1, jdbc.update(
            "update ap_process_migration_plan set target_engine_definition_id=? "
                + "where tenant_id=? and plan_id=?",
            "stale-target-definition",
            targetDrift.tenantId(),
            targetDrift.planId().toString()
        ));
        assertThrows(
            ExecutionConflictException.class,
            () -> execution.prepare(prepareRequest(targetDrift))
        );
        assertEquals(0, count("ap_process_migration_engine_request", targetDrift.tenantId()));
    }

    @Test
    void concurrentPrepareCreatesExactlyOneImmutableRequest() throws Exception {
        Authority authority = seedClaimedAuthority("Tenant-H4-Concurrent");
        ApprovalMigrationEngineExecutionStore first = executionStore(event -> {
        });
        ApprovalMigrationEngineExecutionStore second = executionStore(event -> {
        });
        PrepareRequest request = prepareRequest(authority);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Object> left = executor.submit(() -> prepareConcurrently(
                first,
                request,
                ready,
                start
            ));
            Future<Object> right = executor.submit(() -> prepareConcurrently(
                second,
                request,
                ready,
                start
            ));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Object> results = List.of(left.get(), right.get());
            assertEquals(
                1,
                results.stream().filter(PreparedDispatch.class::isInstance).count()
            );
            assertEquals(
                1,
                results.stream().filter(ExecutionConflictException.class::isInstance).count()
            );
        }

        assertEquals(1, count("ap_process_migration_engine_request", authority.tenantId()));
        assertEquals(3, attemptRevision(authority));
        assertEquals(AttemptStatus.ENGINE_REQUESTED, attemptPayload(authority).status());
    }

    @Test
    void prepareAuditFailureRollsBackRequestAttemptAndTransitionEvent() {
        Authority authority = seedClaimedAuthority("Tenant-H4-Prepare-Rollback");
        ApprovalMigrationEngineExecutionStore failing = executionStore(event -> {
            throw new IllegalStateException("H4 prepare audit unavailable");
        });

        assertThrows(
            IllegalStateException.class,
            () -> failing.prepare(prepareRequest(authority))
        );

        assertEquals(0, count("ap_process_migration_engine_request", authority.tenantId()));
        ApprovalMigrationAttempt attempt = attemptPayload(authority);
        assertEquals(AttemptStatus.CLAIMED, attempt.status());
        assertEquals(2, attempt.revision());
        assertEquals(WORKER, attempt.leaseOwner());
        assertEquals(2, count("ap_process_migration_attempt_event", authority.tenantId()));
    }

    @Test
    void finalizationAuditFailureRollsBackOutcomeAttemptAndTransitionEvent() {
        Authority authority = seedClaimedAuthority("Tenant-H4-Finalize-Rollback");
        ApprovalMigrationEngineExecutionStore execution = executionStore(event -> {
        });
        PreparedDispatch prepared = execution.prepare(prepareRequest(authority));
        ApprovalMigrationEngineExecutionStore failing = executionStore(event -> {
            throw new IllegalStateException("H4 finalization audit unavailable");
        });

        assertThrows(
            IllegalStateException.class,
            () -> failing.finalizeOutcome(returned(prepared, NOW.plusSeconds(30)))
        );

        assertEquals(0, count("ap_process_migration_engine_outcome", authority.tenantId()));
        ApprovalMigrationAttempt attempt = attemptPayload(authority);
        assertEquals(AttemptStatus.ENGINE_REQUESTED, attempt.status());
        assertEquals(3, attempt.revision());
        assertEquals(
            prepared.engineRequestId().toString(),
            attempt.engineRequestReference()
        );
        assertEquals(3, count("ap_process_migration_attempt_event", authority.tenantId()));
    }

    private Object prepareConcurrently(
        ApprovalMigrationEngineExecutionStore execution,
        PrepareRequest request,
        CountDownLatch ready,
        CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        try {
            return execution.prepare(request);
        } catch (ExecutionConflictException exception) {
            return exception;
        }
    }

    private ApprovalMigrationEngineExecutionStore executionStore(AuditEventSink audit) {
        return JdbcApprovalMigrationEngineExecutionStoreFactory.create(
            dataSource,
            objectMapper,
            transactionManager,
            audit,
            UUID::randomUUID
        );
    }

    private PrepareRequest prepareRequest(Authority authority) {
        return new PrepareRequest(
            authority.tenantId(),
            authority.attemptId(),
            WORKER,
            2,
            1,
            NOW.plusSeconds(20),
            "request-h4-prepare-" + authority.tenantId(),
            "trace-h4"
        );
    }

    private FinalizeRequest returned(PreparedDispatch prepared, Instant happenedAt) {
        return new FinalizeRequest(
            prepared,
            FinalDisposition.CALL_RETURNED_AWAITING_VERIFICATION,
            true,
            true,
            false,
            "ENGINE_CALL_RETURNED",
            "returned; exact verification still required",
            SNAPSHOT_HASH,
            happenedAt
        );
    }

    private Authority seedClaimedAuthority(String tenant) {
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
        ApprovalReleasePackage targetRelease =
            MySqlApprovalReleaseLifecycleFixture.seedRelease(
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
            tenant,
            intentId,
            WORKER,
            NOW,
            "request-h4-provision",
            "trace-h4",
            "f".repeat(64)
        ));
        ApprovalMigrationAttempt pending = provisioned.initialAttempts().getFirst();
        ApprovalMigrationAttemptClaimStore claims =
            JdbcApprovalMigrationAttemptClaimStoreFactory.create(
                dataSource,
                objectMapper,
                transactionManager,
                event -> {
                },
                UUID::randomUUID
            );
        var claimed = claims.claim(new ClaimRequest(
            tenant,
            intentId,
            WORKER,
            1,
            NOW.plusSeconds(10),
            NOW.plusSeconds(300),
            "request-h4-claim",
            "trace-h4",
            "1".repeat(64)
        ));
        ApprovalMigrationAttempt attempt = claimed.attempts().getFirst();
        ApprovalMigrationCommandFence fence = claimed.fences().getFirst();
        assertEquals(pending.attemptId(), attempt.attemptId());
        assertEquals(AttemptStatus.CLAIMED, attempt.status());
        assertEquals(2, attempt.revision());
        assertEquals(1, fence.revision());

        return new Authority(
            tenant,
            planId,
            intentId,
            instanceId,
            attempt.attemptId(),
            fence.fenceId(),
            engineInstanceId,
            sourceRelease,
            sourceDeployment,
            targetRelease,
            targetDeployment
        );
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
            "request-h4-binding",
            "trace-h4",
            "audit-event:h4-binding"
        );
    }

    private ApprovalMigrationAttempt attemptPayload(Authority authority) {
        String payload = jdbc.queryForObject(
            "select payload_json from ap_process_migration_attempt "
                + "where tenant_id=? and attempt_id=?",
            String.class,
            authority.tenantId(),
            authority.attemptId().toString()
        );
        return new JdbcApprovalMigrationJson(objectMapper).read(
            payload,
            ApprovalMigrationAttempt.class
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

    private long attemptRevision(Authority authority) {
        Long value = jdbc.queryForObject(
            "select revision from ap_process_migration_attempt "
                + "where tenant_id=? and attempt_id=?",
            Long.class,
            authority.tenantId(),
            authority.attemptId().toString()
        );
        return value == null ? -1 : value;
    }

    private Instant timestamp(String table, String column, String tenant) {
        Timestamp value = jdbc.queryForObject(
            "select " + column + " from " + table + " where tenant_id=?",
            Timestamp.class,
            tenant
        );
        return value == null ? null : value.toInstant();
    }

    private String outcomeDisposition(Authority authority) {
        return jdbc.queryForObject(
            "select disposition from ap_process_migration_engine_outcome "
                + "where tenant_id=? and attempt_id=?",
            String.class,
            authority.tenantId(),
            authority.attemptId().toString()
        );
    }

    private static UUID uuid(String tenant, String value) {
        return UUID.nameUUIDFromBytes(
            ("mysql-h4:" + tenant + ':' + value).getBytes(StandardCharsets.UTF_8)
        );
    }

    private record Authority(
        String tenantId,
        UUID planId,
        UUID intentId,
        UUID instanceId,
        UUID attemptId,
        UUID fenceId,
        String engineInstanceId,
        ApprovalReleasePackage sourceRelease,
        ApprovalReleaseDeployment sourceDeployment,
        ApprovalReleasePackage targetRelease,
        ApprovalReleaseDeployment targetDeployment
    ) {
    }
}
