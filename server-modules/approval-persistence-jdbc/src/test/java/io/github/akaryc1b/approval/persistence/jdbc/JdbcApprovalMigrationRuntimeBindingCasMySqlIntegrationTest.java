package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalInstanceCommandFence;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationRuntimeBindingCasStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationRuntimeBindingCasStore.BindingCasDisposition;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationRuntimeBindingCasStore.BindingCasException;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationRuntimeBindingCasStore.CompletionRequest;
import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalRuntimeBindingStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.definition.ApprovalProcessRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.definition.ApprovalRuntimeBinding;
import io.github.akaryc1b.approval.domain.migration.ApprovalCommandOperation;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationCommandFence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationCommandFence.FenceStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.LeaseActor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationRuntimeBindingCasMySqlIntegrationTest
    extends MySqlApprovalProjectionStoreIntegrationSupport {

    private static final Instant NOW = Instant.parse("2026-08-11T08:00:00Z");
    private static final String WORKER = "worker-h1";
    private static final String SOURCE_BINDING_HASH = "9".repeat(64);
    private static final String TARGET_PACKAGE_HASH = "3".repeat(64);
    private static final String PLAN_HASH = "4".repeat(64);
    private static final String VERIFICATION_HASH = "5".repeat(64);

    private ObjectMapper objectMapper;
    private JdbcTransactionManager transactionManager;
    private ApprovalReleasePackageStore releasePackages;
    private ApprovalReleaseDeploymentStore deployments;
    private ApprovalProcessReleaseStore processReleases;
    private ApprovalRuntimeBindingStore runtimeBindings;
    private ApprovalInstanceCommandFence commandFence;

    @BeforeEach
    @Override
    void reset() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        transactionManager = new JdbcTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        store = JdbcApprovalProjectionStoreFactory.create(dataSource, objectMapper);
        releasePackages = JdbcApprovalReleasePackageStoreFactory.create(dataSource);
        deployments = JdbcApprovalReleaseDeploymentStoreFactory.create(dataSource);
        processReleases = JdbcApprovalProcessReleaseStoreFactory.create(dataSource);
        runtimeBindings = JdbcApprovalRuntimeBindingStoreFactory.create(dataSource);
        commandFence = JdbcApprovalInstanceCommandFenceFactory.create(dataSource);
    }

    @Test
    void commandFenceRequiresTransactionAndBlocksBusinessCommands() {
        Authority authority = seedAuthority("Tenant-H1-Fence");
        assertInstanceOf(JdbcMySqlApprovalInstanceCommandFence.class, commandFence);
        assertThrows(
            IllegalStateException.class,
            () -> commandFence.guardBusinessCommand(
                authority.tenantId(),
                authority.instanceId(),
                ApprovalCommandOperation.COMPLETE,
                NOW
            )
        );

        assertThrows(
            ApprovalInstanceCommandFence.InstanceCommandFencedException.class,
            () -> transactions.executeWithoutResult(status ->
                commandFence.guardBusinessCommand(
                    authority.tenantId(),
                    authority.instanceId(),
                    ApprovalCommandOperation.COMPLETE,
                    NOW
                )
            )
        );
    }

    @Test
    void exactCompletionCommitsBindingProjectionHistoryAttemptFenceAndReplay() {
        Authority authority = seedAuthority("Tenant-H1-Success");
        ApprovalMigrationRuntimeBindingCasStore cas = casStore(event -> {
        });
        assertInstanceOf(JdbcMySqlApprovalMigrationRuntimeBindingCasStore.class, cas);

        var completed = cas.complete(authority.request());
        var replay = cas.complete(authority.request());

        assertEquals(BindingCasDisposition.COMPLETED, completed.disposition());
        assertEquals(BindingCasDisposition.REPLAYED_COMPLETION, replay.disposition());
        assertEquals(5, completed.attempt().revision());
        assertEquals("SUCCEEDED", completed.attempt().status().name());
        assertEquals(2, completed.bindingEvidence().bindingRevision());
        assertEquals(
            SOURCE_BINDING_HASH,
            completed.bindingEvidence().previousBindingEvidenceHash()
        );
        assertEquals(2, bindingRevision(authority));
        assertEquals(TARGET_PACKAGE_HASH, bindingPackageHash(authority));
        assertEquals(authority.targetDeployment().engineDefinitionId(), bindingEngine(authority));
        assertEquals(TARGET_PACKAGE_HASH, instancePackageHash(authority));
        assertEquals(authority.targetDeployment().engineDefinitionId(), instanceEngine(authority));
        assertEquals(2, countBindingEvidence(authority));
        assertEquals(List.of(1L, 2L), bindingEvidenceRevisions(authority));
        assertEquals(1, countRows("ap_process_migration_instance_completion", authority));
        assertEquals(0, countRows("ap_process_migration_binding_cas_conflict", authority));
        assertEquals("RELEASED", fenceStatus(authority));
        assertEquals(2, fenceRevision(authority));
        assertEquals(1, countRows("ap_process_migration_instance_completion", authority));
        assertEquals(2, countBindingEvidence(authority));
        assertEquals(5, attemptRevision(authority));
        assertEquals(2, new JdbcApprovalMigrationBindingRevisionReader(dataSource)
            .currentRevision(authority.tenantId(), authority.attemptId()));
        assertNotNull(completed.completion().completionEvidenceHash());
        assertEquals(
            completed.completion().completionEvidenceHash(),
            replay.completion().completionEvidenceHash()
        );
    }

    @Test
    void staleBindingRecordsConflictRetainsFenceAndReplaysWithoutMutation() {
        Authority authority = seedAuthority("Tenant-H1-Conflict");
        ApprovalMigrationRuntimeBindingCasStore cas = casStore(event -> {
        });
        CompletionRequest stale = new CompletionRequest(
            authority.request().tenantId(),
            authority.request().attemptId(),
            authority.request().verificationId(),
            authority.request().workerId(),
            authority.request().expectedAttemptRevision(),
            authority.request().expectedFenceRevision(),
            2,
            authority.request().requestId(),
            authority.request().traceId(),
            authority.request().happenedAt()
        );

        var conflict = cas.complete(stale);
        var replay = cas.complete(stale);

        assertEquals(BindingCasDisposition.RECONCILIATION_REQUIRED, conflict.disposition());
        assertEquals(BindingCasDisposition.REPLAYED_CONFLICT, replay.disposition());
        assertEquals(5, conflict.attempt().revision());
        assertEquals("RECONCILING", conflict.attempt().status().name());
        assertEquals(1, bindingRevision(authority));
        assertEquals(authority.sourceRelease().packageHash(), bindingPackageHash(authority));
        assertEquals(authority.sourceDeployment().engineDefinitionId(), bindingEngine(authority));
        assertEquals(authority.sourceRelease().packageHash(), instancePackageHash(authority));
        assertEquals(authority.sourceDeployment().engineDefinitionId(), instanceEngine(authority));
        assertEquals(1, countRows("ap_process_migration_binding_cas_conflict", authority));
        assertEquals(0, countRows("ap_process_migration_instance_completion", authority));
        assertEquals("ACTIVE", fenceStatus(authority));
        assertEquals(1, fenceRevision(authority));
        assertEquals(1, countBindingEvidence(authority));
        assertNotNull(conflict.conflict().conflictEvidenceHash());
        assertEquals(
            conflict.conflict().conflictEvidenceHash(),
            replay.conflict().conflictEvidenceHash()
        );
    }

    @Test
    void auditFailureAfterRealCasMutationsRollsEntireLocalTransactionBack() {
        Authority authority = seedAuthority("Tenant-H1-Rollback");
        ApprovalMigrationRuntimeBindingCasStore cas = casStore(event -> {
            throw new IllegalStateException("audit unavailable after D5 mutations");
        });

        assertThrows(IllegalStateException.class, () -> cas.complete(authority.request()));

        assertEquals(1, bindingRevision(authority));
        assertEquals(authority.sourceRelease().packageHash(), bindingPackageHash(authority));
        assertEquals(authority.sourceDeployment().engineDefinitionId(), bindingEngine(authority));
        assertEquals(authority.sourceRelease().packageHash(), instancePackageHash(authority));
        assertEquals(authority.sourceDeployment().engineDefinitionId(), instanceEngine(authority));
        assertEquals(0, countRows("ap_process_migration_instance_completion", authority));
        assertEquals(0, countRows("ap_process_migration_binding_cas_conflict", authority));
        assertEquals(0, countBindingEvidence(authority));
        assertEquals(4, attemptRevision(authority));
        assertEquals("VERIFYING", attemptStatus(authority));
        assertEquals("ACTIVE", fenceStatus(authority));
        assertEquals(1, fenceRevision(authority));
    }

    @Test
    void concurrentSameAttemptSerializesToOneCompletionAndOneReplay() throws Exception {
        Authority authority = seedAuthority("Tenant-H1-Concurrent");
        ApprovalMigrationRuntimeBindingCasStore cas = casStore(event -> {
        });
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<BindingCasDisposition> first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return cas.complete(authority.request()).disposition();
            });
            Future<BindingCasDisposition> second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return cas.complete(authority.request()).disposition();
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<BindingCasDisposition> results = List.of(first.get(), second.get());
            assertTrue(results.contains(BindingCasDisposition.COMPLETED));
            assertTrue(results.contains(BindingCasDisposition.REPLAYED_COMPLETION));
            assertNotEquals(results.get(0), results.get(1));
        }

        assertEquals(1, countRows("ap_process_migration_instance_completion", authority));
        assertEquals(2, countBindingEvidence(authority));
        assertEquals(2, bindingRevision(authority));
        assertEquals(5, attemptRevision(authority));
        assertEquals("RELEASED", fenceStatus(authority));
    }

    @Test
    void changedPayloadReplayIsRejectedWithoutFurtherMutation() {
        Authority authority = seedAuthority("Tenant-H1-Replay-Conflict");
        ApprovalMigrationRuntimeBindingCasStore cas = casStore(event -> {
        });
        cas.complete(authority.request());
        CompletionRequest changed = new CompletionRequest(
            authority.request().tenantId(),
            authority.request().attemptId(),
            authority.request().verificationId(),
            authority.request().workerId(),
            authority.request().expectedAttemptRevision(),
            authority.request().expectedFenceRevision(),
            authority.request().expectedBindingRevision(),
            "request-h1-changed",
            authority.request().traceId(),
            authority.request().happenedAt()
        );

        assertThrows(BindingCasException.class, () -> cas.complete(changed));
        assertEquals(1, countRows("ap_process_migration_instance_completion", authority));
        assertEquals(2, countBindingEvidence(authority));
        assertEquals(2, bindingRevision(authority));
    }

    private ApprovalMigrationRuntimeBindingCasStore casStore(AuditEventSink audit) {
        return JdbcApprovalMigrationRuntimeBindingCasStoreFactory.create(
            dataSource,
            objectMapper,
            transactionManager,
            audit,
            new DeterministicUuidSupplier()
        );
    }

    private Authority seedAuthority(String tenant) {
        UUID planId = uuid(tenant, "plan");
        UUID intentId = uuid(tenant, "intent");
        UUID attemptId = uuid(tenant, "attempt");
        UUID fenceId = uuid(tenant, "fence");
        UUID verificationId = uuid(tenant, "verification");
        UUID engineRequestId = uuid(tenant, "engine-request");
        UUID engineOutcomeId = uuid(tenant, "engine-outcome");
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
        seedLifecycle(sourceRelease, true);
        seedLifecycle(targetRelease, false);
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

        insertPlanIntentConsumption(
            tenant,
            planId,
            intentId,
            instanceId,
            sourceRelease,
            sourceDeployment,
            targetRelease,
            targetDeployment
        );
        insertAttemptFenceEngineVerification(
            tenant,
            intentId,
            attemptId,
            fenceId,
            verificationId,
            engineRequestId,
            engineOutcomeId,
            instanceId,
            engineInstanceId,
            sourceRelease,
            sourceDeployment,
            targetRelease,
            targetDeployment
        );

        return new Authority(
            tenant,
            instanceId,
            attemptId,
            fenceId,
            verificationId,
            sourceRelease,
            sourceDeployment,
            targetRelease,
            targetDeployment,
            new CompletionRequest(
                tenant,
                attemptId,
                verificationId,
                WORKER,
                4,
                1,
                1,
                "request-h1-complete",
                "trace-h1",
                NOW
            )
        );
    }

    private void seedLifecycle(ApprovalReleasePackage releasePackage, boolean active) {
        ApprovalProcessRelease.Transition publish = new ApprovalProcessRelease.Transition(
            uuid(releasePackage.tenantId(), "publish-" + releasePackage.releaseVersion()),
            releasePackage.tenantId(),
            releasePackage.definitionKey(),
            releasePackage.releaseVersion(),
            releasePackage.packageHash(),
            State.DRAFT,
            State.PUBLISHED,
            1,
            "Publish release for H1 D5 fixture",
            "h1-publish-" + releasePackage.releaseVersion(),
            releasePackage.publishedBy(),
            "request-h1-publish-" + releasePackage.releaseVersion(),
            "trace-h1",
            "audit-event:h1-publish-" + releasePackage.releaseVersion(),
            releasePackage.publishedAt()
        );
        ApprovalProcessRelease published = ApprovalProcessRelease.published(
            releasePackage,
            publish
        );
        processReleases.savePublished(published, publish);
        if (!active) {
            return;
        }
        ApprovalProcessRelease.Transition activate = new ApprovalProcessRelease.Transition(
            uuid(releasePackage.tenantId(), "activate-" + releasePackage.releaseVersion()),
            releasePackage.tenantId(),
            releasePackage.definitionKey(),
            releasePackage.releaseVersion(),
            releasePackage.packageHash(),
            State.PUBLISHED,
            State.ACTIVE,
            2,
            "Activate source release for H1 D5 fixture",
            "h1-activate-" + releasePackage.releaseVersion(),
            WORKER,
            "request-h1-activate-" + releasePackage.releaseVersion(),
            "trace-h1",
            "audit-event:h1-activate-" + releasePackage.releaseVersion(),
            releasePackage.publishedAt().plusSeconds(1)
        );
        assertTrue(processReleases.transition(
            published.transitioned(activate),
            published.revision(),
            activate
        ));
    }

    private void seedProjectionInstance(
        String tenant,
        UUID instanceId,
        String businessKey,
        String engineInstanceId,
        ApprovalReleasePackage sourceRelease,
        ApprovalReleaseDeployment sourceDeployment
    ) {
        ApprovalProjectionStore raw = JdbcApprovalProjectionStoreFactory.create(
            dataSource,
            objectMapper
        );
        transactions.executeWithoutResult(status -> {
            raw.lockDefinition(
                tenant,
                DEFINITION_KEY,
                MySqlApprovalProjectionProvenanceFixture.DEFINITION_VERSION
            );
            if (raw.findDefinition(
                tenant,
                DEFINITION_KEY,
                MySqlApprovalProjectionProvenanceFixture.DEFINITION_VERSION
            ).isEmpty()) {
                raw.saveDefinition(definition(tenant));
            }
            raw.lockBusinessKey(tenant, businessKey);
            raw.createInstance(
                instance(tenant, instanceId, engineInstanceId, businessKey),
                List.of()
            );
        });
        jdbc.update("""
            update ap_approval_instance set
              release_version=?, release_package_hash=?, form_package_version=?,
              form_package_hash=?, ui_schema_version=?, ui_schema_hash=?,
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
            "request-h1-binding",
            "trace-h1",
            "audit-event:h1-binding"
        );
    }

    private void insertPlanIntentConsumption(
        String tenant,
        UUID planId,
        UUID intentId,
        UUID instanceId,
        ApprovalReleasePackage sourceRelease,
        ApprovalReleaseDeployment sourceDeployment,
        ApprovalReleasePackage targetRelease,
        ApprovalReleaseDeployment targetDeployment
    ) {
        jdbc.update("""
            insert into ap_process_migration_plan (
              tenant_id,plan_id,definition_key,status,plan_hash,
              source_release_version,source_package_hash,target_release_version,
              target_package_hash,target_engine_deployment_id,
              target_engine_definition_id,target_engine_version,payload_json,
              created_at,updated_at
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            tenant,
            planId.toString(),
            DEFINITION_KEY,
            "CONSUMED",
            PLAN_HASH,
            sourceRelease.releaseVersion(),
            sourceRelease.packageHash(),
            targetRelease.releaseVersion(),
            targetRelease.packageHash(),
            targetDeployment.engineDeploymentId(),
            targetDeployment.engineDefinitionId(),
            targetDeployment.engineVersion(),
            "{}",
            NOW.minusSeconds(90),
            NOW.minusSeconds(90)
        );
        jdbc.update("""
            insert into ap_process_migration_intent (
              tenant_id,intent_id,plan_id,plan_hash,approval_instance_id,current_run_no,
              idempotency_key,status,requested_by,requested_at,reason,request_id,trace_id,
              source_release_version,source_package_hash,target_release_version,
              target_package_hash,target_engine_deployment_id,target_engine_definition_id,
              target_engine_version,payload_json,created_at,updated_at
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            tenant,
            intentId.toString(),
            planId.toString(),
            PLAN_HASH,
            instanceId.toString(),
            1,
            "h1-intent-" + tenant,
            "RUNNING",
            WORKER,
            NOW.minusSeconds(80),
            "H1 D5 fixture",
            "request-h1-intent",
            "trace-h1",
            sourceRelease.releaseVersion(),
            sourceRelease.packageHash(),
            targetRelease.releaseVersion(),
            targetRelease.packageHash(),
            targetDeployment.engineDeploymentId(),
            targetDeployment.engineDefinitionId(),
            targetDeployment.engineVersion(),
            "{}",
            NOW.minusSeconds(80),
            NOW.minusSeconds(80)
        );
        jdbc.update("""
            insert into ap_process_migration_plan_consumption (
              tenant_id,plan_id,intent_id,plan_hash,consumed_by,consumed_at,
              request_id,trace_id,payload_json
            ) values (?,?,?,?,?,?,?,?,?)
            """,
            tenant,
            planId.toString(),
            intentId.toString(),
            PLAN_HASH,
            WORKER,
            NOW.minusSeconds(75),
            "request-h1-consumption",
            "trace-h1",
            "{}"
        );
    }

    private void insertAttemptFenceEngineVerification(
        String tenant,
        UUID intentId,
        UUID attemptId,
        UUID fenceId,
        UUID verificationId,
        UUID engineRequestId,
        UUID engineOutcomeId,
        UUID instanceId,
        String engineInstanceId,
        ApprovalReleasePackage sourceRelease,
        ApprovalReleaseDeployment sourceDeployment,
        ApprovalReleasePackage targetRelease,
        ApprovalReleaseDeployment targetDeployment
    ) {
        Map<String, Object> attemptPayload = Map.ofEntries(
            Map.entry("attemptId", attemptId.toString()),
            Map.entry("tenantId", tenant),
            Map.entry("intentId", intentId.toString()),
            Map.entry("approvalInstanceId", instanceId.toString()),
            Map.entry("runNo", 1),
            Map.entry("ordinal", 1),
            Map.entry("definitionKey", DEFINITION_KEY),
            Map.entry("sourceEngineDefinitionId", sourceDeployment.engineDefinitionId()),
            Map.entry("targetEngineDefinitionId", targetDeployment.engineDefinitionId()),
            Map.entry("engineInstanceId", engineInstanceId),
            Map.entry("sourceReleaseVersion", sourceRelease.releaseVersion()),
            Map.entry("sourcePackageHash", sourceRelease.packageHash()),
            Map.entry("targetReleaseVersion", targetRelease.releaseVersion()),
            Map.entry("targetPackageHash", targetRelease.packageHash()),
            Map.entry("expectedBindingEvidenceHash", SOURCE_BINDING_HASH),
            Map.entry("status", "VERIFYING"),
            Map.entry("revision", 4),
            Map.entry("engineOutcome", "CONFIRMED"),
            Map.entry("engineRequestReference", "engine-request-h1"),
            Map.entry("failureClass", "NONE"),
            Map.entry("idempotencyKey", "h1-attempt-" + tenant),
            Map.entry("requestedBy", WORKER),
            Map.entry("requestedAt", NOW.minusSeconds(70).toString()),
            Map.entry("createdAt", NOW.minusSeconds(70).toString()),
            Map.entry("updatedAt", NOW.minusSeconds(20).toString()),
            Map.entry("requestId", "request-h1-attempt"),
            Map.entry("traceId", "trace-h1")
        );
        jdbc.update("""
            insert into ap_process_migration_attempt (
              tenant_id,attempt_id,intent_id,approval_instance_id,run_no,ordinal,
              definition_key,source_engine_definition_id,target_engine_definition_id,
              engine_instance_id,source_release_version,source_package_hash,
              target_release_version,target_package_hash,expected_binding_evidence_hash,
              status,revision,engine_outcome,lease_actor,lease_owner,lease_until,
              engine_request_reference,failure_class,error_summary,idempotency_key,
              requested_by,requested_at,payload_json,created_at,updated_at
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            tenant,
            attemptId.toString(),
            intentId.toString(),
            instanceId.toString(),
            1,
            1,
            DEFINITION_KEY,
            sourceDeployment.engineDefinitionId(),
            targetDeployment.engineDefinitionId(),
            engineInstanceId,
            sourceRelease.releaseVersion(),
            sourceRelease.packageHash(),
            targetRelease.releaseVersion(),
            targetRelease.packageHash(),
            SOURCE_BINDING_HASH,
            "VERIFYING",
            4,
            "CONFIRMED",
            null,
            null,
            null,
            "engine-request-h1",
            "NONE",
            null,
            "h1-attempt-" + tenant,
            WORKER,
            NOW.minusSeconds(70),
            writeJson(attemptPayload),
            NOW.minusSeconds(70),
            NOW.minusSeconds(20)
        );

        ApprovalMigrationCommandFence fence = new ApprovalMigrationCommandFence(
            fenceId,
            tenant,
            instanceId,
            attemptId,
            1,
            FenceStatus.ACTIVE,
            LeaseActor.MIGRATION,
            WORKER,
            NOW.plusSeconds(600),
            NOW.minusSeconds(30),
            NOW.minusSeconds(30),
            null,
            "request-h1-fence",
            "trace-h1"
        );
        jdbc.update("""
            insert into ap_approval_instance_command_fence (
              tenant_id,fence_id,approval_instance_id,attempt_id,revision,status,
              lease_actor,lease_owner,lease_until,created_at,updated_at,released_at,
              request_id,trace_id,payload_json
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            tenant,
            fenceId.toString(),
            instanceId.toString(),
            attemptId.toString(),
            1,
            "ACTIVE",
            "MIGRATION",
            WORKER,
            NOW.plusSeconds(600),
            NOW.minusSeconds(30),
            NOW.minusSeconds(30),
            null,
            "request-h1-fence",
            "trace-h1",
            writeJson(fence)
        );

        insertEngineRequestOutcome(
            tenant,
            attemptId,
            instanceId,
            engineRequestId,
            engineOutcomeId,
            engineInstanceId,
            sourceDeployment,
            targetDeployment
        );

        Map<String, Object> verificationPayload = Map.ofEntries(
            Map.entry("verificationId", verificationId.toString()),
            Map.entry("tenantId", tenant),
            Map.entry("intentId", intentId.toString()),
            Map.entry("attemptId", attemptId.toString()),
            Map.entry("approvalInstanceId", instanceId.toString()),
            Map.entry("engineRequestId", engineRequestId.toString()),
            Map.entry("engineOutcomeId", engineOutcomeId.toString()),
            Map.entry("workerId", WORKER),
            Map.entry("expectedAttemptRevision", 4),
            Map.entry("expectedFenceRevision", 1),
            Map.entry("sourceEngineDefinitionId", sourceDeployment.engineDefinitionId()),
            Map.entry("targetEngineDefinitionId", targetDeployment.engineDefinitionId()),
            Map.entry("exactTargetRuntime", true),
            Map.entry("verificationEvidenceHash", VERIFICATION_HASH),
            Map.entry("verifiedAt", NOW.minusSeconds(5).toString()),
            Map.entry("requestId", "request-h1-verification"),
            Map.entry("traceId", "trace-h1")
        );
        jdbc.update("""
            insert into ap_process_migration_exact_verification (
              tenant_id,verification_id,intent_id,attempt_id,approval_instance_id,
              engine_request_id,engine_outcome_id,worker_id,expected_attempt_revision,
              expected_fence_revision,source_engine_definition_id,
              target_engine_definition_id,exact_target_runtime,
              verification_evidence_hash,verified_at,request_id,trace_id,payload_json
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            tenant,
            verificationId.toString(),
            intentId.toString(),
            attemptId.toString(),
            instanceId.toString(),
            engineRequestId.toString(),
            engineOutcomeId.toString(),
            WORKER,
            4,
            1,
            sourceDeployment.engineDefinitionId(),
            targetDeployment.engineDefinitionId(),
            true,
            VERIFICATION_HASH,
            NOW.minusSeconds(5),
            "request-h1-verification",
            "trace-h1",
            writeJson(verificationPayload)
        );
    }

    private void insertEngineRequestOutcome(
        String tenant,
        UUID attemptId,
        UUID instanceId,
        UUID engineRequestId,
        UUID engineOutcomeId,
        String engineInstanceId,
        ApprovalReleaseDeployment sourceDeployment,
        ApprovalReleaseDeployment targetDeployment
    ) {
        String requestHash = "6".repeat(64);
        String requestTokenHash = "7".repeat(64);
        jdbc.update("""
            insert into ap_process_migration_engine_request (
              tenant_id,engine_request_id,attempt_id,approval_instance_id,worker_id,
              engine_instance_id,source_engine_definition_id,target_engine_definition_id,
              request_hash,idempotency_key,request_token_sha256,status,requested_at,
              request_id,trace_id,payload_json
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            tenant,
            engineRequestId.toString(),
            attemptId.toString(),
            instanceId.toString(),
            WORKER,
            engineInstanceId,
            sourceDeployment.engineDefinitionId(),
            targetDeployment.engineDefinitionId(),
            requestHash,
            "h1-engine-request-" + tenant,
            requestTokenHash,
            "COMPLETED",
            NOW.minusSeconds(15),
            "request-h1-engine",
            "trace-h1",
            "{}"
        );
        jdbc.update("""
            insert into ap_process_migration_engine_outcome (
              tenant_id,engine_outcome_id,engine_request_id,attempt_id,
              approval_instance_id,worker_id,engine_instance_id,
              source_engine_definition_id,target_engine_definition_id,outcome,
              output_snapshot_json,completed_at,request_id,trace_id,payload_json
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            tenant,
            engineOutcomeId.toString(),
            engineRequestId.toString(),
            attemptId.toString(),
            instanceId.toString(),
            WORKER,
            engineInstanceId,
            sourceDeployment.engineDefinitionId(),
            targetDeployment.engineDefinitionId(),
            "CONFIRMED",
            "{}",
            NOW.minusSeconds(10),
            "request-h1-engine-outcome",
            "trace-h1",
            "{}"
        );
    }

    private long bindingRevision(Authority authority) {
        return jdbc.queryForObject(
            "select binding_revision from ap_process_runtime_binding "
                + "where tenant_id=? and approval_instance_id=?",
            Long.class,
            authority.tenantId(),
            authority.instanceId().toString()
        );
    }

    private String bindingPackageHash(Authority authority) {
        return jdbc.queryForObject(
            "select release_package_hash from ap_process_runtime_binding "
                + "where tenant_id=? and approval_instance_id=?",
            String.class,
            authority.tenantId(),
            authority.instanceId().toString()
        );
    }

    private String bindingEngine(Authority authority) {
        return jdbc.queryForObject(
            "select engine_definition_id from ap_process_runtime_binding "
                + "where tenant_id=? and approval_instance_id=?",
            String.class,
            authority.tenantId(),
            authority.instanceId().toString()
        );
    }

    private String instancePackageHash(Authority authority) {
        return jdbc.queryForObject(
            "select release_package_hash from ap_approval_instance "
                + "where tenant_id=? and instance_id=?",
            String.class,
            authority.tenantId(),
            authority.instanceId().toString()
        );
    }

    private String instanceEngine(Authority authority) {
        return jdbc.queryForObject(
            "select engine_definition_id from ap_approval_instance "
                + "where tenant_id=? and instance_id=?",
            String.class,
            authority.tenantId(),
            authority.instanceId().toString()
        );
    }

    private int countBindingEvidence(Authority authority) {
        return jdbc.queryForObject(
            "select count(*) from ap_process_runtime_binding_evidence "
                + "where tenant_id=? and approval_instance_id=?",
            Integer.class,
            authority.tenantId(),
            authority.instanceId().toString()
        );
    }

    private List<Long> bindingEvidenceRevisions(Authority authority) {
        return jdbc.queryForList(
            "select binding_revision from ap_process_runtime_binding_evidence "
                + "where tenant_id=? and approval_instance_id=? order by binding_revision",
            Long.class,
            authority.tenantId(),
            authority.instanceId().toString()
        );
    }

    private int countRows(String table, Authority authority) {
        return jdbc.queryForObject(
            "select count(*) from " + table + " where tenant_id=? and attempt_id=?",
            Integer.class,
            authority.tenantId(),
            authority.attemptId().toString()
        );
    }

    private long attemptRevision(Authority authority) {
        return jdbc.queryForObject(
            "select revision from ap_process_migration_attempt "
                + "where tenant_id=? and attempt_id=?",
            Long.class,
            authority.tenantId(),
            authority.attemptId().toString()
        );
    }

    private String attemptStatus(Authority authority) {
        return jdbc.queryForObject(
            "select status from ap_process_migration_attempt "
                + "where tenant_id=? and attempt_id=?",
            String.class,
            authority.tenantId(),
            authority.attemptId().toString()
        );
    }

    private String fenceStatus(Authority authority) {
        return jdbc.queryForObject(
            "select status from ap_approval_instance_command_fence "
                + "where tenant_id=? and fence_id=?",
            String.class,
            authority.tenantId(),
            authority.fenceId().toString()
        );
    }

    private long fenceRevision(Authority authority) {
        return jdbc.queryForObject(
            "select revision from ap_approval_instance_command_fence "
                + "where tenant_id=? and fence_id=?",
            Long.class,
            authority.tenantId(),
            authority.fenceId().toString()
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("H1 fixture JSON failed", exception);
        }
    }

    private static UUID uuid(String tenant, String value) {
        return UUID.nameUUIDFromBytes(
            ("mysql-h1:" + tenant + ':' + value).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static final class DeterministicUuidSupplier implements java.util.function.Supplier<UUID> {
        private final List<UUID> values = new ArrayList<>();
        private int sequence;

        private DeterministicUuidSupplier() {
            for (int index = 1; index <= 64; index++) {
                values.add(UUID.nameUUIDFromBytes(
                    ("mysql-h1:evidence:" + index).getBytes(StandardCharsets.UTF_8)
                ));
            }
        }

        @Override
        public synchronized UUID get() {
            return values.get(sequence++);
        }
    }

    private record Authority(
        String tenantId,
        UUID instanceId,
        UUID attemptId,
        UUID fenceId,
        UUID verificationId,
        ApprovalReleasePackage sourceRelease,
        ApprovalReleaseDeployment sourceDeployment,
        ApprovalReleasePackage targetRelease,
        ApprovalReleaseDeployment targetDeployment,
        CompletionRequest request
    ) {
    }
}
