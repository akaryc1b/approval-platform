package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.ClaimRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.ClaimResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore.ProvisioningRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationKillSwitch.Snapshot;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore.DispatchRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore.FinalizeRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore.OrchestrationConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore.PrepareRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore.PreparedOrchestration;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalRuntimeBindingStore;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.definition.ApprovalRuntimeBinding;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.CanaryGate;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.PauseReason;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.RunEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationOrchestrationStoreMySqlIntegrationTest
    extends MySqlApprovalProjectionStoreIntegrationSupport {

    private static final Instant NOW = Instant.parse("2026-08-14T08:00:00.123456500Z");
    private static final String WORKER = "worker-h7";
    private static final String SOURCE_BINDING_HASH = "9".repeat(64);
    private static final String TARGET_PACKAGE_HASH = "3".repeat(64);
    private static final String PLAN_HASH = "4".repeat(64);

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
    void prepareDispatchAndFinalizeUseRealClaimFenceAndStrictReplay() {
        Authority authority = seedPendingAuthority("Tenant-H7-Protocol");
        List<AuditEvent> audits = new ArrayList<>();
        ApprovalMigrationOrchestrationStore orchestration = orchestrationStore(audits);
        assertInstanceOf(
            JdbcMySqlCanonicalApprovalMigrationOrchestrationStore.class,
            orchestration
        );
        PrepareRequest prepareRequest = prepareRequest(
            authority,
            1,
            1,
            false,
            "request-h7-prepare"
        );

        PreparedOrchestration prepared = orchestration.prepare(prepareRequest);
        PreparedOrchestration prepareReplay = orchestration.prepare(prepareRequest);

        assertFalse(prepared.replayed());
        assertTrue(prepareReplay.replayed());
        assertEquals(CanaryGate.PENDING, prepared.canaryGate());
        assertEquals(PauseReason.NONE, prepared.pauseReason());
        assertEquals(RunEventType.PREPARED, prepared.latestEvent().eventType());
        assertTrue(prepared.dispatchEligible());
        assertFalse(prepared.finalized());
        assertEquals(authority.instanceId(), prepared.canary().approvalInstanceId());
        assertEquals(prepared.run(), prepareReplay.run());
        assertEquals(prepared.canary(), prepareReplay.canary());

        ClaimResult claimed = provisionAndClaim(authority);
        UUID attemptId = claimed.attempts().getFirst().attemptId();
        DispatchRequest dispatchRequest = new DispatchRequest(
            prepared.run(),
            attemptId,
            1,
            1,
            snapshot(1, false),
            NOW.plusSeconds(40),
            "request-h7-dispatch",
            "trace-h7"
        );
        var dispatch = orchestration.authorizeDispatch(dispatchRequest);
        var dispatchReplay = orchestration.authorizeDispatch(dispatchRequest);

        assertTrue(dispatch.allowed());
        assertFalse(dispatch.replayed());
        assertTrue(dispatchReplay.replayed());
        assertEquals(PauseReason.NONE, dispatch.pauseReason());
        assertEquals(RunEventType.DISPATCH_ALLOWED, dispatch.event().eventType());
        assertEquals(dispatch.observation(), dispatchReplay.observation());
        assertThrows(
            OrchestrationConflictException.class,
            () -> orchestration.authorizeDispatch(new DispatchRequest(
                prepared.run(),
                attemptId,
                1,
                1,
                snapshot(1, false),
                NOW.plusSeconds(40),
                "request-h7-dispatch-changed",
                "trace-h7"
            ))
        );

        FinalizeRequest finalizeRequest = new FinalizeRequest(
            prepared,
            claimed.batch(),
            claimed.batch().claimedAttemptIds(),
            NOW.plusSeconds(50),
            "request-h7-finalize",
            "trace-h7"
        );
        var finalized = orchestration.finalizeRun(finalizeRequest);
        var finalizeReplay = orchestration.finalizeRun(finalizeRequest);

        assertFalse(finalized.replayed());
        assertTrue(finalizeReplay.replayed());
        assertNotNull(finalized.batch());
        assertEquals(finalized.batch(), finalizeReplay.batch());
        assertEquals(RunEventType.PAUSED, finalized.event().eventType());
        assertEquals(PauseReason.CANARY_IN_FLIGHT, finalized.pauseReason());
        assertFalse(finalized.planExactlyCompleted());
        assertEquals(1, count("ap_process_migration_canary_selection", authority.tenantId()));
        assertEquals(1, count("ap_process_migration_orchestration_run", authority.tenantId()));
        assertEquals(3, count("ap_process_migration_orchestration_event", authority.tenantId()));
        assertEquals(1, count(
            "ap_process_migration_kill_switch_observation",
            authority.tenantId()
        ));
        assertEquals(1, count("ap_process_migration_orchestration_batch", authority.tenantId()));
        assertEquals(3, audits.size());
    }

    @Test
    void concurrentPreparePersistsOneCanaryOneRunAndOneAudit() throws Exception {
        Authority authority = seedPendingAuthority("Tenant-H7-Concurrent");
        List<AuditEvent> audits = Collections.synchronizedList(new ArrayList<>());
        ApprovalMigrationOrchestrationStore firstStore = orchestrationStore(audits);
        ApprovalMigrationOrchestrationStore secondStore = orchestrationStore(audits);
        PrepareRequest request = prepareRequest(
            authority,
            1,
            1,
            false,
            "request-h7-concurrent"
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<PreparedOrchestration> first = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return firstStore.prepare(request);
            });
            Future<PreparedOrchestration> second = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return secondStore.prepare(request);
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<PreparedOrchestration> results = List.of(first.get(), second.get());

            assertEquals(1, results.stream().filter(PreparedOrchestration::replayed).count());
            assertEquals(results.get(0).run(), results.get(1).run());
            assertEquals(results.get(0).canary(), results.get(1).canary());
        }

        assertEquals(1, count("ap_process_migration_canary_selection", authority.tenantId()));
        assertEquals(1, count("ap_process_migration_orchestration_run", authority.tenantId()));
        assertEquals(1, count("ap_process_migration_orchestration_event", authority.tenantId()));
        assertEquals(1, audits.size());
    }

    @Test
    void activeKillSwitchCreatesDurableBlockedTerminalEvidence() {
        Authority authority = seedPendingAuthority("Tenant-H7-Kill-Switch");
        List<AuditEvent> audits = new ArrayList<>();

        PreparedOrchestration prepared = orchestrationStore(audits).prepare(
            prepareRequest(authority, 1, 7, true, "request-h7-kill-switch")
        );

        assertTrue(prepared.finalized());
        assertFalse(prepared.dispatchEligible());
        assertEquals(RunEventType.KILL_SWITCH_BLOCKED, prepared.latestEvent().eventType());
        assertEquals(PauseReason.KILL_SWITCH_ACTIVE, prepared.pauseReason());
        assertEquals(1, count("ap_process_migration_orchestration_run", authority.tenantId()));
        assertEquals(1, count("ap_process_migration_orchestration_event", authority.tenantId()));
        assertEquals(1, audits.size());
    }

    @Test
    void staleRevisionAndTenantMismatchFailClosedWithoutEvidence() {
        Authority authority = seedPendingAuthority("Tenant-H7-Isolation");
        ApprovalMigrationOrchestrationStore orchestration = orchestrationStore(new ArrayList<>());

        assertThrows(
            OrchestrationConflictException.class,
            () -> orchestration.prepare(prepareRequest(
                authority,
                2,
                1,
                false,
                "request-h7-stale"
            ))
        );
        assertThrows(
            OrchestrationConflictException.class,
            () -> orchestration.prepare(new PrepareRequest(
                authority.tenantId().toLowerCase(),
                authority.intentId(),
                1,
                1,
                snapshot(1, false),
                NOW.plusSeconds(10),
                "request-h7-wrong-tenant",
                "trace-h7"
            ))
        );

        assertEquals(0, count("ap_process_migration_canary_selection", authority.tenantId()));
        assertEquals(0, count("ap_process_migration_orchestration_run", authority.tenantId()));
        assertEquals(0, count("ap_process_migration_orchestration_event", authority.tenantId()));
    }

    @Test
    void auditFailureRollsBackCanaryRunAndEventAtomically() {
        Authority authority = seedPendingAuthority("Tenant-H7-Audit-Rollback");
        ApprovalMigrationOrchestrationStore orchestration =
            JdbcApprovalMigrationOrchestrationStoreFactory.create(
                dataSource,
                objectMapper,
                transactionManager,
                event -> {
                    throw new IllegalStateException("H7 audit unavailable");
                },
                UUID::randomUUID
            );

        assertThrows(
            IllegalStateException.class,
            () -> orchestration.prepare(prepareRequest(
                authority,
                1,
                1,
                false,
                "request-h7-audit-rollback"
            ))
        );
        assertEquals(0, count("ap_process_migration_canary_selection", authority.tenantId()));
        assertEquals(0, count("ap_process_migration_orchestration_run", authority.tenantId()));
        assertEquals(0, count("ap_process_migration_orchestration_event", authority.tenantId()));
    }

    @Test
    void mysqlGuardsRejectCanaryMutationAndRunDeletion() {
        Authority authority = seedPendingAuthority("Tenant-H7-Tamper");
        PreparedOrchestration prepared = orchestrationStore(new ArrayList<>()).prepare(
            prepareRequest(authority, 1, 1, false, "request-h7-tamper")
        );

        assertThrows(DataAccessException.class, () -> jdbc.update(
            "update ap_process_migration_canary_selection set request_id='tampered' "
                + "where tenant_id=? and selection_id=?",
            authority.tenantId(),
            prepared.canary().selectionId().toString()
        ));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "delete from ap_process_migration_orchestration_run "
                + "where tenant_id=? and run_id=?",
            authority.tenantId(),
            prepared.run().runId().toString()
        ));
    }

    private ApprovalMigrationOrchestrationStore orchestrationStore(List<AuditEvent> audits) {
        return JdbcApprovalMigrationOrchestrationStoreFactory.create(
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
            "request-h7-provision",
            "trace-h7",
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
            "request-h7-claim",
            "trace-h7",
            hash('c')
        ));
    }

    private PrepareRequest prepareRequest(
        Authority authority,
        long expectedRunRevision,
        long killSwitchRevision,
        boolean enabled,
        String requestId
    ) {
        return new PrepareRequest(
            authority.tenantId(),
            authority.intentId(),
            1,
            expectedRunRevision,
            snapshot(killSwitchRevision, enabled),
            NOW.plusSeconds(5),
            requestId,
            "trace-h7"
        );
    }

    private static Snapshot snapshot(long revision, boolean enabled) {
        return new Snapshot(
            revision,
            enabled,
            enabled ? "EMERGENCY_STOP" : "CONFIGURED_OFF",
            hash('8')
        );
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
            "request-h7-binding",
            "trace-h7",
            "audit-event:h7-binding"
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

    private static String hash(char value) {
        return Character.toString(value).repeat(64);
    }

    private static UUID uuid(String tenant, String value) {
        return UUID.nameUUIDFromBytes(
            ("mysql-h7:" + tenant + ':' + value).getBytes(StandardCharsets.UTF_8)
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
