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
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationCommandFence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationCommandFence.FenceStatus;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(5, attemptRevision(authority));
        assertEquals(2, new JdbcApprovalMigrationBindingRevisionReader(dataSource)
            .currentRevision(authority.tenantId(), authority.attemptId()));
        assertNotNull(completed.completionEvidence().completionEvidenceHash());
        assertEquals(
            completed.completionEvidence().completionEvidenceHash(),
            replay.completionEvidence().completionEvidenceHash()
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
            authority.request().happenedAt(),
            authority.request().requestId(),
            authority.request().traceId()
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
        assertNotNull(conflict.conflictEvidence().conflictEvidenceHash());
        assertEquals(
            conflict.conflictEvidence().conflictEvidenceHash(),
            replay.conflictEvidence().conflictEvidenceHash()
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
            authority.request().happenedAt(),
            "request-h1-changed",
            authority.request().traceId()
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

        MySqlH1MigrationPlanAuthorityFixtureAdapter.seed(
            tenant,
            planId,
            intentId,
            sourceRelease,
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
            sourceDeployment,
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
                NOW,
                "request-h1-complete",
                "trace-h1"
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
        ApprovalReleasePackage sourceRelease,
        ApprovalReleasePackage targetRelease,
        ApprovalReleaseDeployment targetDeployment
    ) {
        UUID authorizationId = uuid(tenant, "authorization");
        String authorizationHash = "7".repeat(64);
        String intentHash = "8".repeat(64);
        jdbc.update("""
            insert into ap_process_migration_plan (
              tenant_id,plan_id,idempotency_key,plan_hash,assessment_id,
              assessment_report_hash,definition_key,source_release_version,
              source_package_hash,target_release_version,target_package_hash,
              target_deployment_record_id,target_engine_deployment_id,
              target_engine_definition_id,target_engine_version,selected_instance_count,
              status,revision,requested_by,operation_reason,assessed_at,created_at,
              expires_at,updated_at,authorization_id,authorization_evidence_hash,
              authorized_by,authorized_at,authorization_expires_at,request_id,
              trace_id,audit_chain_reference,payload_json
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            tenant,
            planId.toString(),
            "plan-h1-" + tenant,
            PLAN_HASH,
            uuid(tenant, "assessment").toString(),
            "6".repeat(64),
            DEFINITION_KEY,
            sourceRelease.releaseVersion(),
            sourceRelease.packageHash(),
            targetRelease.releaseVersion(),
            targetRelease.packageHash(),
            targetDeployment.deploymentRecordId().toString(),
            targetDeployment.engineDeploymentId(),
            targetDeployment.engineDefinitionId(),
            targetDeployment.engineVersion(),
            1,
            "CONSUMED",
            3,
            WORKER,
            "H1 D5 exact target CAS",
            NOW.minusSeconds(100),
            NOW.minusSeconds(100),
            NOW.plusSeconds(3600),
            NOW.minusSeconds(80),
            authorizationId.toString(),
            authorizationHash,
            WORKER,
            NOW.minusSeconds(90),
            NOW.plusSeconds(3500),
            "request-h1-plan",
            "trace-h1",
            "audit-event:h1-plan",
            "{}"
        );
        jdbc.update("""
            insert into ap_process_migration_intent (
              tenant_id,intent_id,idempotency_key,plan_id,plan_hash,definition_key,
              source_release_version,source_package_hash,target_release_version,
              target_package_hash,status,revision,intent_evidence_hash,payload_json,
              created_at,updated_at
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            tenant,
            intentId.toString(),
            "intent-h1-" + tenant,
            planId.toString(),
            PLAN_HASH,
            DEFINITION_KEY,
            sourceRelease.releaseVersion(),
            sourceRelease.packageHash(),
            targetRelease.releaseVersion(),
            targetRelease.packageHash(),
            "RUNNING",
            2,
            intentHash,
            "{}",
            NOW.minusSeconds(80),
            NOW.minusSeconds(60)
        );
        jdbc.update("""
            insert into ap_process_migration_plan_consumption (
              tenant_id,consumption_id,plan_id,plan_hash,authorization_id,
              authorization_evidence_hash,intent_id,intent_evidence_hash,
              idempotency_key,request_hash,consumed_by,reason,consumed_at,
              request_id,trace_id,audit_chain_reference,payload_json
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            tenant,
            uuid(tenant, "consumption").toString(),
            planId.toString(),
            PLAN_HASH,
            authorizationId.toString(),
            authorizationHash,
            intentId.toString(),
            intentHash,
            "intent-h1-" + tenant,
            "a".repeat(64),
            WORKER,
            "H1 D5 test consumption",
            NOW.minusSeconds(55),
            "request-h1-consumption",
            "trace-h1",
            "audit-event:h1-consumption",
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
        ApprovalReleaseDeployment sourceDeployment,
        ApprovalReleaseDeployment targetDeployment
    ) {
        ApprovalMigrationAttempt attempt = new ApprovalMigrationAttempt(
            attemptId,
            tenant,
            intentId,
            instanceId,
            engineInstanceId,
            1,
            null,
            SOURCE_BINDING_HASH,
            sourceDeployment.engineDefinitionId(),
            targetDeployment.engineDefinitionId(),
            AttemptStatus.VERIFYING,
            EngineOutcome.ACCEPTED,
            4,
            null,
            null,
            engineRequestId.toString(),
            FailureClass.NONE,
            null,
            NOW.minusSeconds(50),
            NOW.minusSeconds(20),
            "request-h1-attempt",
            "trace-h1"
        );
        jdbc.update("""
            insert into ap_process_migration_attempt (
              tenant_id,attempt_id,intent_id,approval_instance_id,attempt_number,
              parent_attempt_id,status,revision,engine_outcome,lease_actor,
              lease_owner,lease_until,engine_request_reference,failure_class,
              error_summary,expected_binding_evidence_hash,payload_json,
              created_at,updated_at
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            tenant,
            attemptId.toString(),
            intentId.toString(),
            instanceId.toString(),
            1,
            null,
            "VERIFYING",
            4,
            "ACCEPTED",
            WORKER,
            null,
            null,
            engineRequestId.toString(),
            "NONE",
            null,
            SOURCE_BINDING_HASH,
            writeJson(attempt),
            NOW.minusSeconds(50),
            NOW.minusSeconds(20)
        );
        jdbc.update("""
            insert into ap_process_migration_attempt_event (
              tenant_id,event_id,attempt_id,revision,from_status,to_status,
              engine_outcome,lease_actor,lease_owner,lease_until,
              engine_request_reference,failure_class,error_summary,payload_json,
              happened_at
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            tenant,
            uuid(tenant, "attempt-event").toString(),
            attemptId.toString(),
            4,
            "ENGINE_REQUESTED",
            "VERIFYING",
            "ACCEPTED",
            null,
            null,
            null,
            engineRequestId.toString(),
            "NONE",
            null,
            "{}",
            NOW.minusSeconds(20)
        );

        ApprovalMigrationCommandFence fence = new ApprovalMigrationCommandFence(
            fenceId,
            tenant,
            instanceId,
            attemptId,
            ApprovalCommandOperation.MIGRATION,
            FenceStatus.ACTIVE,
            1,
            WORKER,
            NOW.plusSeconds(600),
            "fence-h1-" + tenant,
            "b".repeat(64),
            NOW.minusSeconds(40),
            NOW.minusSeconds(40),
            null,
            "request-h1-fence",
            "trace-h1"
        );
        jdbc.update("""
            insert into ap_approval_instance_command_fence (
              tenant_id,fence_id,approval_instance_id,attempt_id,operation,status,
              revision,lease_owner,lease_until,idempotency_key,request_hash,
              acquired_at,updated_at,released_at,request_id,trace_id,payload_json
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            tenant,
            fenceId.toString(),
            instanceId.toString(),
            attemptId.toString(),
            "MIGRATION",
            "ACTIVE",
            1,
            WORKER,
            NOW.plusSeconds(600),
            fence.idempotencyKey(),
            fence.requestHash(),
            NOW.minusSeconds(40),
            NOW.minusSeconds(40),
            null,
            "request-h1-fence",
            "trace-h1",
            writeJson(fence)
        );
        jdbc.update("""
            insert into ap_approval_instance_command_fence_event (
              tenant_id,event_id,fence_id,approval_instance_id,attempt_id,revision,
              from_status,to_status,lease_actor,lease_owner,lease_until,happened_at,
              request_id,trace_id,payload_json
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            tenant,
            uuid(tenant, "fence-event").toString(),
            fenceId.toString(),
            instanceId.toString(),
            attemptId.toString(),
            1,
            null,
            "ACTIVE",
            WORKER,
            WORKER,
            NOW.plusSeconds(600),
            NOW.minusSeconds(40),
            "request-h1-fence",
            "trace-h1",
            "{}"
        );

        insertEngineRequestOutcome(
            tenant,
            intentId,
            attemptId,
            fenceId,
            engineRequestId,
            engineOutcomeId,
            instanceId,
            engineInstanceId,
            sourceDeployment,
            targetDeployment
        );

        ApprovalMigrationExactVerification verification = exactVerification(
            tenant,
            intentId,
            attemptId,
            verificationId,
            engineRequestId,
            engineOutcomeId,
            sourceDeployment,
            targetDeployment
        );
        ApprovalMigrationEngineSnapshot snapshot = verification.snapshot();
        jdbc.update("""
            insert into ap_process_migration_exact_verification (
              tenant_id,verification_id,intent_id,attempt_id,engine_request_id,
              engine_outcome_id,worker_id,expected_attempt_revision,
              expected_fence_revision,source_engine_definition_id,
              target_engine_definition_id,classification,read_succeeded,
              runtime_present,history_present,truncated,
              observed_runtime_definition_id,observed_history_definition_id,
              snapshot_hash,request_hash,verification_evidence_hash,recorded_at,
              request_id,trace_id,payload_json
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            tenant,
            verificationId.toString(),
            intentId.toString(),
            attemptId.toString(),
            engineRequestId.toString(),
            engineOutcomeId.toString(),
            WORKER,
            4,
            1,
            sourceDeployment.engineDefinitionId(),
            targetDeployment.engineDefinitionId(),
            verification.classification().name(),
            snapshot.readSucceeded(),
            snapshot.runtimePresent(),
            snapshot.historyPresent(),
            snapshot.truncated(),
            snapshot.runtimeEngineDefinitionId(),
            snapshot.historicEngineDefinitionId(),
            snapshot.snapshotHash(),
            verification.requestHash(),
            verification.verificationEvidenceHash(),
            verification.recordedAt(),
            verification.requestId(),
            verification.traceId(),
            writeJson(verification)
        );
    }

    private ApprovalMigrationExactVerification exactVerification(
        String tenant,
        UUID intentId,
        UUID attemptId,
        UUID verificationId,
        UUID engineRequestId,
        UUID engineOutcomeId,
        ApprovalReleaseDeployment sourceDeployment,
        ApprovalReleaseDeployment targetDeployment
    ) {
        TaskEvidence task = new TaskEvidence(
            "2".repeat(64),
            "managerApproval",
            targetDeployment.engineDefinitionId(),
            false
        );
        ApprovalMigrationEngineSnapshot snapshot = new ApprovalMigrationEngineSnapshot(
            true,
            null,
            true,
            targetDeployment.engineDefinitionId(),
            targetDeployment.engineDeploymentId(),
            false,
            List.of("managerApproval"),
            List.of(new DefinitionEvidence(
                "EXECUTION_ACTIVITY",
                "execution-h1",
                targetDeployment.engineDefinitionId()
            )),
            List.of(task),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            true,
            targetDeployment.engineDefinitionId(),
            null,
            null,
            List.of(task),
            false,
            "c".repeat(64)
        );
        return new ApprovalMigrationExactVerification(
            verificationId,
            tenant,
            intentId,
            attemptId,
            engineRequestId,
            engineOutcomeId,
            sourceDeployment.engineDefinitionId(),
            targetDeployment.engineDefinitionId(),
            ExactClassification.EXACT_TARGET_RUNTIME,
            snapshot,
            "d".repeat(64),
            VERIFICATION_HASH,
            NOW.minusSeconds(5),
            "request-h1-verification",
            "trace-h1"
        );
    }

    private void insertEngineRequestOutcome(
        String tenant,
        UUID intentId,
        UUID attemptId,
        UUID fenceId,
        UUID engineRequestId,
        UUID engineOutcomeId,
        UUID instanceId,
        String engineInstanceId,
        ApprovalReleaseDeployment sourceDeployment,
        ApprovalReleaseDeployment targetDeployment
    ) {
        String requestHash = "e".repeat(64);
        String evidenceHash = "f".repeat(64);
        jdbc.update("""
            insert into ap_process_migration_engine_request (
              tenant_id,engine_request_id,intent_id,attempt_id,approval_instance_id,
              worker_id,attempt_revision,fence_id,fence_revision,engine_instance_id,
              source_binding_evidence_hash,source_engine_definition_id,
              target_release_version,target_package_hash,target_engine_deployment_id,
              target_engine_definition_id,activity_mapping_json,request_hash,evidence_hash,
              requested_at,request_id,trace_id,payload_json
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            tenant,
            engineRequestId.toString(),
            intentId.toString(),
            attemptId.toString(),
            instanceId.toString(),
            WORKER,
            2,
            fenceId.toString(),
            1,
            engineInstanceId,
            SOURCE_BINDING_HASH,
            sourceDeployment.engineDefinitionId(),
            targetDeployment.releaseVersion(),
            TARGET_PACKAGE_HASH,
            targetDeployment.engineDeploymentId(),
            targetDeployment.engineDefinitionId(),
            "[]",
            requestHash,
            evidenceHash,
            NOW.minusSeconds(15),
            "request-h1-engine",
            "trace-h1",
            "{}"
        );
        jdbc.update("""
            insert into ap_process_migration_engine_outcome (
              tenant_id,engine_outcome_id,engine_request_id,intent_id,attempt_id,
              worker_id,expected_attempt_revision,expected_fence_revision,disposition,
              engine_call_attempted,engine_call_returned,engine_call_may_have_occurred,
              stable_code,bounded_summary,pre_dispatch_snapshot_hash,outcome_hash,
              recorded_at,request_id,trace_id,payload_json
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            tenant,
            engineOutcomeId.toString(),
            engineRequestId.toString(),
            intentId.toString(),
            attemptId.toString(),
            WORKER,
            3,
            1,
            "CALL_RETURNED_AWAITING_VERIFICATION",
            true,
            true,
            false,
            "ENGINE_CALL_RETURNED",
            null,
            "1".repeat(64),
            "2".repeat(64),
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

    private static final class DeterministicUuidSupplier
        implements java.util.function.Supplier<UUID> {
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
