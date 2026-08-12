package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.ClaimRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore.ProvisioningRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExactVerificationStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExactVerificationStore.PreparedVerification;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExactVerificationStore.StoredVerification;
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
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot.DefinitionEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot.TaskEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationExactVerification;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationExactVerification.ExactClassification;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
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

class JdbcApprovalMigrationExactVerificationStoreMySqlIntegrationTest
    extends MySqlApprovalProjectionStoreIntegrationSupport {

    private static final Instant NOW = Instant.parse(
        "2026-08-12T02:00:00.123456500Z"
    );
    private static final String WORKER = "worker-h5";
    private static final String SOURCE_BINDING_HASH = "9".repeat(64);
    private static final String TARGET_PACKAGE_HASH = "3".repeat(64);
    private static final String PLAN_HASH = "4".repeat(64);
    private static final String PRE_DISPATCH_HASH = "e".repeat(64);
    private static final String SNAPSHOT_HASH = "a".repeat(64);
    private static final String TASK_HASH = "b".repeat(64);

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
    void exactTargetUsesRealH2H3H4LineageAndStrictlyReplaysOneEvidence() {
        Authority authority = seedVerifyingAuthority("Tenant-H5-Exact");
        List<AuditEvent> audits = new ArrayList<>();
        ApprovalMigrationExactVerificationStore verification = verificationStore(
            audits::add
        );
        assertInstanceOf(
            JdbcMySqlApprovalMigrationExactVerificationStore.class,
            verification
        );

        ApprovalMigrationExactVerificationStore.PrepareRequest request =
            verificationRequest(authority, "request-h5-exact");
        PreparedVerification prepared = verification.prepare(request);
        assertNull(prepared.replay());
        assertEquals(AttemptStatus.VERIFYING, prepared.attempt().status());
        assertEquals(4, prepared.attempt().revision());
        assertEquals(
            authority.engineInstanceId(),
            prepared.engineCommand().engineInstanceId()
        );

        ApprovalMigrationEngineSnapshot snapshot = targetSnapshot(authority);
        ExactClassification classification = ApprovalMigrationExactVerification.classify(
            snapshot,
            authority.sourceDeployment().engineDefinitionId(),
            authority.targetDeployment().engineDefinitionId()
        );
        assertEquals(ExactClassification.EXACT_TARGET_RUNTIME, classification);

        StoredVerification stored = verification.finalizeVerification(
            new ApprovalMigrationExactVerificationStore.FinalizeRequest(
                prepared,
                snapshot,
                classification,
                NOW.plusSeconds(50)
            )
        );

        assertFalse(stored.replayed());
        assertEquals(
            ExactClassification.EXACT_TARGET_RUNTIME,
            stored.evidence().classification()
        );
        assertEquals(AttemptStatus.VERIFYING, stored.attempt().status());
        assertEquals(EngineOutcome.ACCEPTED, stored.attempt().engineOutcome());
        assertEquals(4, stored.attempt().revision());
        assertEquals(
            1,
            count("ap_process_migration_exact_verification", authority.tenantId())
        );
        assertEquals(
            4,
            count("ap_process_migration_attempt_event", authority.tenantId())
        );
        assertEquals(1, audits.size());

        PreparedVerification replay = verification.prepare(request);
        assertNotNull(replay.replay());
        assertTrue(replay.replay().replayed());
        assertEquals(stored.evidence(), replay.replay().evidence());
        assertEquals(
            1,
            count("ap_process_migration_exact_verification", authority.tenantId())
        );
        assertEquals(
            4,
            count("ap_process_migration_attempt_event", authority.tenantId())
        );
        assertEquals(1, audits.size());

        assertThrows(
            ApprovalMigrationExactVerificationStore.VerificationConflictException.class,
            () -> verification.prepare(verificationRequest(
                authority,
                "request-h5-exact-changed"
            ))
        );
        assertEquals(
            1,
            count("ap_process_migration_exact_verification", authority.tenantId())
        );
    }

    @Test
    void exactSourcePersistsEvidenceAndMovesAttemptToReconciliationOnce() {
        Authority authority = seedVerifyingAuthority("Tenant-H5-Mismatch");
        List<AuditEvent> audits = new ArrayList<>();
        ApprovalMigrationExactVerificationStore verification = verificationStore(
            audits::add
        );
        PreparedVerification prepared = verification.prepare(
            verificationRequest(authority, "request-h5-mismatch")
        );
        ApprovalMigrationEngineSnapshot snapshot = sourceSnapshot(authority);
        ExactClassification classification = ApprovalMigrationExactVerification.classify(
            snapshot,
            authority.sourceDeployment().engineDefinitionId(),
            authority.targetDeployment().engineDefinitionId()
        );
        assertEquals(ExactClassification.EXACT_SOURCE_RUNTIME, classification);

        StoredVerification stored = verification.finalizeVerification(
            new ApprovalMigrationExactVerificationStore.FinalizeRequest(
                prepared,
                snapshot,
                classification,
                NOW.plusSeconds(50)
            )
        );

        assertFalse(stored.replayed());
        assertEquals(
            ExactClassification.EXACT_SOURCE_RUNTIME,
            stored.evidence().classification()
        );
        assertEquals(AttemptStatus.RECONCILING, stored.attempt().status());
        assertEquals(
            EngineOutcome.VERIFICATION_MISMATCH,
            stored.attempt().engineOutcome()
        );
        assertEquals(
            FailureClass.RECONCILIATION_REQUIRED,
            stored.attempt().failureClass()
        );
        assertEquals(5, stored.attempt().revision());
        assertEquals(
            prepared.engineRequestId().toString(),
            stored.attempt().engineRequestReference()
        );
        assertTrue(stored.attempt().errorSummary().contains("EXACT_SOURCE_RUNTIME"));
        assertEquals(
            1,
            count("ap_process_migration_exact_verification", authority.tenantId())
        );
        assertEquals(
            5,
            count("ap_process_migration_attempt_event", authority.tenantId())
        );
        assertEquals(1, audits.size());
    }

    @Test
    void staleAuthorityAndClientClassificationFailClosedBeforeEvidence() {
        Authority authority = seedVerifyingAuthority("Tenant-H5-Stale");
        ApprovalMigrationExactVerificationStore verification = verificationStore(event -> {
        });

        assertThrows(
            ApprovalMigrationExactVerificationStore.VerificationConflictException.class,
            () -> verification.prepare(new ApprovalMigrationExactVerificationStore.PrepareRequest(
                authority.tenantId().toLowerCase(),
                authority.attemptId(),
                WORKER,
                4,
                1,
                NOW.plusSeconds(40),
                "request-h5-wrong-tenant",
                "trace-h5"
            ))
        );
        assertThrows(
            ApprovalMigrationExactVerificationStore.VerificationConflictException.class,
            () -> verification.prepare(new ApprovalMigrationExactVerificationStore.PrepareRequest(
                authority.tenantId(),
                authority.attemptId(),
                WORKER,
                3,
                1,
                NOW.plusSeconds(40),
                "request-h5-stale-attempt",
                "trace-h5"
            ))
        );
        assertThrows(
            ApprovalMigrationExactVerificationStore.VerificationConflictException.class,
            () -> verification.prepare(new ApprovalMigrationExactVerificationStore.PrepareRequest(
                authority.tenantId(),
                authority.attemptId(),
                WORKER,
                4,
                2,
                NOW.plusSeconds(40),
                "request-h5-stale-fence",
                "trace-h5"
            ))
        );
        assertThrows(
            ApprovalMigrationExactVerificationStore.VerificationConflictException.class,
            () -> verification.prepare(new ApprovalMigrationExactVerificationStore.PrepareRequest(
                authority.tenantId(),
                authority.attemptId(),
                "worker-h5-foreign",
                4,
                1,
                NOW.plusSeconds(40),
                "request-h5-wrong-worker",
                "trace-h5"
            ))
        );

        PreparedVerification prepared = verification.prepare(
            verificationRequest(authority, "request-h5-derived-classification")
        );
        ApprovalMigrationEngineSnapshot target = targetSnapshot(authority);
        assertThrows(
            IllegalArgumentException.class,
            () -> new ApprovalMigrationExactVerificationStore.FinalizeRequest(
                prepared,
                target,
                ExactClassification.EXACT_SOURCE_RUNTIME,
                NOW.plusSeconds(50)
            )
        );

        assertEquals(
            0,
            count("ap_process_migration_exact_verification", authority.tenantId())
        );
        ApprovalMigrationAttempt attempt = attemptPayload(authority);
        assertEquals(AttemptStatus.VERIFYING, attempt.status());
        assertEquals(4, attempt.revision());
        assertEquals(
            4,
            count("ap_process_migration_attempt_event", authority.tenantId())
        );
    }

    @Test
    void corruptedH4ImmutableEvidenceFailsClosedBeforeH5Evidence() {
        ApprovalMigrationExactVerificationStore verification = verificationStore(event -> {
        });

        Authority requestTamper = seedVerifyingAuthority("Tenant-H5-Request-Tamper");
        assertEquals(1, jdbc.update(
            """
            update ap_process_migration_engine_request
            set payload_json=json_set(
                payload_json,
                '$.targetEngineDeploymentId',
                ?
            )
            where tenant_id=? and attempt_id=?
            """,
            "tampered-engine-deployment",
            requestTamper.tenantId(),
            requestTamper.attemptId().toString()
        ));
        assertThrows(
            IllegalStateException.class,
            () -> verification.prepare(verificationRequest(
                requestTamper,
                "request-h5-request-tamper"
            ))
        );
        assertEquals(
            0,
            count(
                "ap_process_migration_exact_verification",
                requestTamper.tenantId()
            )
        );
        assertEquals(AttemptStatus.VERIFYING, attemptPayload(requestTamper).status());
        assertEquals(
            1,
            count("ap_process_migration_engine_request", requestTamper.tenantId())
        );
        assertEquals(
            1,
            count("ap_process_migration_engine_outcome", requestTamper.tenantId())
        );

        Authority outcomeTamper = seedVerifyingAuthority("Tenant-H5-Outcome-Tamper");
        assertEquals(1, jdbc.update(
            """
            update ap_process_migration_engine_outcome
            set outcome_hash=?
            where tenant_id=? and attempt_id=?
            """,
            "c".repeat(64),
            outcomeTamper.tenantId(),
            outcomeTamper.attemptId().toString()
        ));
        assertThrows(
            IllegalStateException.class,
            () -> verification.prepare(verificationRequest(
                outcomeTamper,
                "request-h5-outcome-tamper"
            ))
        );
        assertEquals(
            0,
            count(
                "ap_process_migration_exact_verification",
                outcomeTamper.tenantId()
            )
        );
        assertEquals(AttemptStatus.VERIFYING, attemptPayload(outcomeTamper).status());
        assertEquals(
            1,
            count("ap_process_migration_engine_request", outcomeTamper.tenantId())
        );
        assertEquals(
            1,
            count("ap_process_migration_engine_outcome", outcomeTamper.tenantId())
        );
    }

    @Test
    void concurrentFinalizationAdmitsOnlyOneAuthoritativeEffect() throws Exception {
        Authority authority = seedVerifyingAuthority("Tenant-H5-Concurrent");
        List<AuditEvent> audits = Collections.synchronizedList(new ArrayList<>());
        ApprovalMigrationExactVerificationStore first = verificationStore(audits::add);
        ApprovalMigrationExactVerificationStore second = verificationStore(audits::add);
        ApprovalMigrationExactVerificationStore.PrepareRequest request =
            verificationRequest(authority, "request-h5-concurrent");
        PreparedVerification leftPrepared = first.prepare(request);
        PreparedVerification rightPrepared = second.prepare(request);
        ApprovalMigrationEngineSnapshot snapshot = sourceSnapshot(authority);
        ExactClassification classification = ApprovalMigrationExactVerification.classify(
            snapshot,
            authority.sourceDeployment().engineDefinitionId(),
            authority.targetDeployment().engineDefinitionId()
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Object> left = executor.submit(() -> finalizeConcurrently(
                first,
                leftPrepared,
                snapshot,
                classification,
                ready,
                start
            ));
            Future<Object> right = executor.submit(() -> finalizeConcurrently(
                second,
                rightPrepared,
                snapshot,
                classification,
                ready,
                start
            ));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Object> results = List.of(left.get(), right.get());
            long firstEffects = results.stream()
                .filter(StoredVerification.class::isInstance)
                .map(StoredVerification.class::cast)
                .filter(result -> !result.replayed())
                .count();
            long replayOrConflict = results.stream().filter(result ->
                result instanceof ApprovalMigrationExactVerificationStore
                    .VerificationConflictException
                    || result instanceof StoredVerification stored && stored.replayed()
            ).count();
            assertEquals(1, firstEffects);
            assertEquals(1, replayOrConflict);
        }

        assertEquals(
            1,
            count("ap_process_migration_exact_verification", authority.tenantId())
        );
        assertEquals(
            5,
            count("ap_process_migration_attempt_event", authority.tenantId())
        );
        assertEquals(1, audits.size());
        ApprovalMigrationAttempt attempt = attemptPayload(authority);
        assertEquals(AttemptStatus.RECONCILING, attempt.status());
        assertEquals(5, attempt.revision());
    }

    @Test
    void finalizationAuditFailureRollsBackEvidenceAttemptAndEvent() {
        Authority authority = seedVerifyingAuthority("Tenant-H5-Rollback");
        ApprovalMigrationExactVerificationStore failing = verificationStore(event -> {
            throw new IllegalStateException("H5 verification audit unavailable");
        });
        PreparedVerification prepared = failing.prepare(
            verificationRequest(authority, "request-h5-rollback")
        );
        ApprovalMigrationEngineSnapshot snapshot = sourceSnapshot(authority);
        ExactClassification classification = ApprovalMigrationExactVerification.classify(
            snapshot,
            authority.sourceDeployment().engineDefinitionId(),
            authority.targetDeployment().engineDefinitionId()
        );

        assertThrows(
            IllegalStateException.class,
            () -> failing.finalizeVerification(
                new ApprovalMigrationExactVerificationStore.FinalizeRequest(
                    prepared,
                    snapshot,
                    classification,
                    NOW.plusSeconds(50)
                )
            )
        );

        assertEquals(
            0,
            count("ap_process_migration_exact_verification", authority.tenantId())
        );
        ApprovalMigrationAttempt attempt = attemptPayload(authority);
        assertEquals(AttemptStatus.VERIFYING, attempt.status());
        assertEquals(EngineOutcome.ACCEPTED, attempt.engineOutcome());
        assertEquals(4, attempt.revision());
        assertEquals(
            4,
            count("ap_process_migration_attempt_event", authority.tenantId())
        );
    }

    private Object finalizeConcurrently(
        ApprovalMigrationExactVerificationStore verification,
        PreparedVerification prepared,
        ApprovalMigrationEngineSnapshot snapshot,
        ExactClassification classification,
        CountDownLatch ready,
        CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        try {
            return verification.finalizeVerification(
                new ApprovalMigrationExactVerificationStore.FinalizeRequest(
                    prepared,
                    snapshot,
                    classification,
                    NOW.plusSeconds(50)
                )
            );
        } catch (
            ApprovalMigrationExactVerificationStore.VerificationConflictException exception
        ) {
            return exception;
        }
    }

    private ApprovalMigrationExactVerificationStore verificationStore(
        AuditEventSink audit
    ) {
        return JdbcApprovalMigrationExactVerificationStoreFactory.create(
            dataSource,
            objectMapper,
            transactionManager,
            audit,
            UUID::randomUUID
        );
    }

    private ApprovalMigrationExactVerificationStore.PrepareRequest verificationRequest(
        Authority authority,
        String requestId
    ) {
        return new ApprovalMigrationExactVerificationStore.PrepareRequest(
            authority.tenantId(),
            authority.attemptId(),
            WORKER,
            4,
            1,
            NOW.plusSeconds(40),
            requestId,
            "trace-h5"
        );
    }

    private Authority seedVerifyingAuthority(String tenant) {
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
            "request-h5-provision",
            "trace-h5",
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
            "request-h5-claim",
            "trace-h5",
            "1".repeat(64)
        ));
        ApprovalMigrationAttempt claimedAttempt = claimed.attempts().getFirst();
        ApprovalMigrationCommandFence fence = claimed.fences().getFirst();
        assertEquals(pending.attemptId(), claimedAttempt.attemptId());
        assertEquals(AttemptStatus.CLAIMED, claimedAttempt.status());
        assertEquals(2, claimedAttempt.revision());
        assertEquals(1, fence.revision());

        ApprovalMigrationEngineExecutionStore execution =
            JdbcApprovalMigrationEngineExecutionStoreFactory.create(
                dataSource,
                objectMapper,
                transactionManager,
                event -> {
                },
                UUID::randomUUID
            );
        var dispatch = execution.prepare(
            new ApprovalMigrationEngineExecutionStore.PrepareRequest(
                tenant,
                claimedAttempt.attemptId(),
                WORKER,
                2,
                1,
                NOW.plusSeconds(20),
                "request-h5-engine-prepare",
                "trace-h5"
            )
        );
        ApprovalMigrationAttempt verifying = execution.finalizeOutcome(
            new ApprovalMigrationEngineExecutionStore.FinalizeRequest(
                dispatch,
                ApprovalMigrationEngineExecutionStore.FinalDisposition
                    .CALL_RETURNED_AWAITING_VERIFICATION,
                true,
                true,
                false,
                "ENGINE_CALL_RETURNED",
                "returned; exact verification still required",
                PRE_DISPATCH_HASH,
                NOW.plusSeconds(30)
            )
        );
        assertEquals(AttemptStatus.VERIFYING, verifying.status());
        assertEquals(EngineOutcome.ACCEPTED, verifying.engineOutcome());
        assertEquals(4, verifying.revision());

        return new Authority(
            tenant,
            planId,
            intentId,
            instanceId,
            verifying.attemptId(),
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
            "request-h5-binding",
            "trace-h5",
            "audit-event:h5-binding"
        );
    }

    private ApprovalMigrationEngineSnapshot targetSnapshot(Authority authority) {
        return snapshot(authority.targetDeployment().engineDefinitionId());
    }

    private ApprovalMigrationEngineSnapshot sourceSnapshot(Authority authority) {
        return snapshot(authority.sourceDeployment().engineDefinitionId());
    }

    private ApprovalMigrationEngineSnapshot snapshot(String definitionId) {
        return new ApprovalMigrationEngineSnapshot(
            true,
            null,
            true,
            definitionId,
            "deployment-h5",
            false,
            List.of("review"),
            List.of(new DefinitionEvidence("EXECUTION", "execution", definitionId)),
            List.of(new TaskEvidence(TASK_HASH, "review", definitionId, false)),
            List.of(),
            List.of(),
            List.of(TASK_HASH),
            List.of(TASK_HASH),
            true,
            definitionId,
            null,
            null,
            List.of(new TaskEvidence(TASK_HASH, "review", definitionId, false)),
            false,
            SNAPSHOT_HASH
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

    private static UUID uuid(String tenant, String value) {
        return UUID.nameUUIDFromBytes(
            ("mysql-h5:" + tenant + ':' + value).getBytes(StandardCharsets.UTF_8)
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
