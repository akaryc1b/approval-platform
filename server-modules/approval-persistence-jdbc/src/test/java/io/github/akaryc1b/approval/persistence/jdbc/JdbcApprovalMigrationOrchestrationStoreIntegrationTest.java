package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExecutionAdmissionStore.AdmissionRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExecutionAdmissionStore.AdmissionResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationKillSwitch.Snapshot;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore.OrchestrationConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore.PrepareRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore.PreparedOrchestration;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntentEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.CanarySelection;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.PauseReason;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.RunEventType;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAuthorization;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanConsumption;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanProtocol.PlanStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.IntentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.support.JdbcTransactionManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.NOW;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.TENANT;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.hash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationOrchestrationStoreIntegrationTest
    extends JdbcApprovalMigrationPlanStoreIntegrationTestSupport {

    @Test
    void deterministicCanaryExactReplayAndChangedReplayFailClosed() {
        AdmissionResult admission = persistConsumedPlan(1);
        List<AuditEvent> audits = new ArrayList<>();
        JdbcApprovalMigrationOrchestrationStore orchestration = orchestrationStore(audits);
        PrepareRequest request = request(admission, 10, 1, 1, false, "orchestration-one");

        PreparedOrchestration first = orchestration.prepare(request);
        PreparedOrchestration replay = orchestration.prepare(request);

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(FIRST_INSTANCE, first.canary().approvalInstanceId());
        assertEquals(first.canary(), replay.canary());
        assertEquals(first.run(), replay.run());
        assertEquals(1, count("ap_process_migration_canary_selection"));
        assertEquals(1, count("ap_process_migration_orchestration_run"));
        assertEquals(1, count("ap_process_migration_orchestration_event"));
        assertEquals(1, audits.size());

        assertThrows(OrchestrationConflictException.class, () -> orchestration.prepare(
            request(admission, 20, 1, 1, false, "orchestration-one")
        ));
        assertEquals(1, count("ap_process_migration_orchestration_run"));
    }

    @Test
    void concurrentNodesPersistOneCanaryAndOneAuthoritativeRun() throws Exception {
        AdmissionResult admission = persistConsumedPlan(2);
        List<AuditEvent> audits = java.util.Collections.synchronizedList(new ArrayList<>());
        JdbcApprovalMigrationOrchestrationStore firstStore = orchestrationStore(audits);
        JdbcApprovalMigrationOrchestrationStore secondStore = orchestrationStore(audits);
        PrepareRequest request = request(admission, 25, 1, 1, false, "concurrent-run");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PreparedOrchestration> first = executor.submit(
                () -> gatedPrepare(firstStore, request, ready, start)
            );
            Future<PreparedOrchestration> second = executor.submit(
                () -> gatedPrepare(secondStore, request, ready, start)
            );
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<PreparedOrchestration> results = List.of(
                first.get(20, TimeUnit.SECONDS),
                second.get(20, TimeUnit.SECONDS)
            );
            assertEquals(1, results.stream().filter(PreparedOrchestration::replayed).count());
            assertEquals(results.get(0).canary(), results.get(1).canary());
            assertEquals(results.get(0).run(), results.get(1).run());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
        assertEquals(1, count("ap_process_migration_canary_selection"));
        assertEquals(1, count("ap_process_migration_orchestration_run"));
        assertEquals(1, audits.size());
    }

    @Test
    void activeKillSwitchProducesImmutableBlockedEvidence() {
        AdmissionResult admission = persistConsumedPlan(3);
        List<AuditEvent> audits = new ArrayList<>();
        PreparedOrchestration prepared = orchestrationStore(audits).prepare(
            request(admission, 10, 1, 2, true, "active-switch")
        );

        assertTrue(prepared.finalized());
        assertFalse(prepared.dispatchEligible());
        assertEquals(RunEventType.KILL_SWITCH_BLOCKED, prepared.latestEvent().eventType());
        assertEquals(PauseReason.KILL_SWITCH_ACTIVE, prepared.pauseReason());
        assertEquals(1, count("ap_process_migration_orchestration_run"));
        assertEquals(1, count("ap_process_migration_orchestration_event"));
        assertEquals(1, audits.size());
    }

    @Test
    void auditFailureRollsBackCanaryRunAndEventTogether() {
        AdmissionResult admission = persistConsumedPlan(4);
        JdbcApprovalMigrationOrchestrationStore orchestration = new JdbcApprovalMigrationOrchestrationStore(
            dataSource,
            mapper(),
            new JdbcTransactionManager(dataSource),
            event -> {
                throw new IllegalStateException("orchestration audit failed");
            },
            UUID::randomUUID
        );

        assertThrows(IllegalStateException.class, () -> orchestration.prepare(
            request(admission, 10, 1, 1, false, "audit-failure")
        ));
        assertEquals(0, count("ap_process_migration_canary_selection"));
        assertEquals(0, count("ap_process_migration_orchestration_run"));
        assertEquals(0, count("ap_process_migration_orchestration_event"));
    }

    @Test
    void databaseRejectsForgedCanaryAndEvidenceMutation() throws JsonProcessingException {
        AdmissionResult admission = persistConsumedPlan(5);
        CanarySelection forged = new CanarySelection(
            UUID.randomUUID(),
            TENANT,
            admission.plan().planId(),
            admission.intent().intentId(),
            ApprovalMigrationOrchestrationEvidence.CANARY_ALGORITHM_VERSION,
            1,
            SECOND_INSTANCE,
            admission.plan().planHash(),
            hash('4'),
            hash('5'),
            NOW.plusSeconds(50),
            "forged-canary",
            "trace-forged"
        );
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            insert into ap_process_migration_canary_selection (
             tenant_id,selection_id,plan_id,intent_id,algorithm_version,sequence_no,
             approval_instance_id,plan_hash,instance_evidence_hash,selection_evidence_hash,
             recorded_at,request_id,trace_id,payload_json
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb))
            """,
            forged.tenantId(),
            forged.selectionId(),
            forged.planId(),
            forged.intentId(),
            forged.algorithmVersion(),
            forged.sequenceNo(),
            forged.approvalInstanceId(),
            forged.planHash(),
            forged.instanceEvidenceHash(),
            forged.selectionEvidenceHash(),
            offset(forged.recordedAt()),
            forged.requestId(),
            forged.traceId(),
            mapper().writeValueAsString(forged)
        ));
        assertEquals(0, count("ap_process_migration_canary_selection"));

        JdbcApprovalMigrationOrchestrationStore orchestration = orchestrationStore(new ArrayList<>());
        PreparedOrchestration prepared = orchestration.prepare(
            request(admission, 10, 1, 1, false, "tamper-run")
        );
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "update ap_process_migration_canary_selection set request_id='tampered' "
                + "where tenant_id=? and selection_id=?",
            TENANT,
            prepared.canary().selectionId()
        ));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "delete from ap_process_migration_orchestration_run where tenant_id=? and run_id=?",
            TENANT,
            prepared.run().runId()
        ));
    }

    @Test
    void crossTenantIntentEnumerationFailsClosed() {
        JdbcApprovalMigrationOrchestrationStore orchestration = orchestrationStore(new ArrayList<>());
        assertThrows(OrchestrationConflictException.class, () -> orchestration.prepare(
            new PrepareRequest(
                "other-tenant",
                UUID.randomUUID(),
                10,
                1,
                new Snapshot(1, false, "CONFIGURED_OFF", hash('8')),
                NOW.plusSeconds(50),
                "cross-tenant",
                null
            )
        ));
        assertEquals(0, count("ap_process_migration_canary_selection"));
    }

    private JdbcApprovalMigrationOrchestrationStore orchestrationStore(List<AuditEvent> audits) {
        return new JdbcApprovalMigrationOrchestrationStore(
            dataSource,
            mapper(),
            new JdbcTransactionManager(dataSource),
            audits::add,
            UUID::randomUUID
        );
    }

    private static PrepareRequest request(
        AdmissionResult admission,
        int limit,
        long runRevision,
        long switchRevision,
        boolean switchEnabled,
        String requestId
    ) {
        return new PrepareRequest(
            admission.intent().tenantId(),
            admission.intent().intentId(),
            limit,
            runRevision,
            new Snapshot(
                switchRevision,
                switchEnabled,
                switchEnabled ? "EMERGENCY_STOP" : "CONFIGURED_OFF",
                hash('8')
            ),
            NOW.plusSeconds(50),
            requestId,
            "trace-orchestration"
        );
    }

    private static PreparedOrchestration gatedPrepare(
        JdbcApprovalMigrationOrchestrationStore store,
        PrepareRequest request,
        CountDownLatch ready,
        CountDownLatch start
    ) throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return store.prepare(request);
    }

    private AdmissionResult persistConsumedPlan(long identity) {
        ApprovalMigrationPlan plan = proposed(
            TENANT,
            PLAN_ID,
            "plan-key-" + identity,
            hash('d')
        );
        plans.createPlan(plan, initialEvent(plan, "initial-plan-" + identity));
        ApprovalMigrationPlanAuthorization authorization = authorization(
            plan,
            "migration-approver",
            "authorization-key-" + identity,
            hash('e')
        );
        ApprovalMigrationPlan authorized = plan.authorized(authorization);
        plans.authorizePlan(
            authorized,
            1,
            authorization,
            authorizationEvent(
                plan,
                authorized,
                authorization,
                "authorization-event-" + identity
            )
        );
        return new JdbcApprovalMigrationExecutionAdmissionStore(
            dataSource,
            mapper(),
            new JdbcTransactionManager(dataSource),
            event -> {
            }
        ).admit(admissionRequest(authorized, identity));
    }

    private AdmissionRequest admissionRequest(
        ApprovalMigrationPlan authorized,
        long identity
    ) {
        Instant consumedAt = NOW.plusSeconds(30);
        UUID intentId = new UUID(980, identity);
        String intentEvidenceHash = hash('f');
        String requestId = "request-admission-d7-" + identity;
        String auditReference = "audit-admission-d7-" + identity;
        ApprovalMigrationIntent intent = new ApprovalMigrationIntent(
            intentId,
            authorized.tenantId(),
            authorized.planId(),
            authorized.planHash(),
            authorized.definitionKey(),
            authorized.sourceReleaseVersion(),
            authorized.sourcePackageHash(),
            authorized.targetReleaseVersion(),
            authorized.targetPackageHash(),
            authorized.selectedInstanceCount(),
            IntentStatus.PENDING,
            1,
            "admission-d7-" + identity,
            intentEvidenceHash,
            "migration-executor",
            "Admit exact authorized migration plan for D7",
            authorized.expiresAt(),
            consumedAt,
            consumedAt,
            requestId,
            "trace-admission-d7",
            auditReference
        );
        ApprovalMigrationIntentEvent intentEvent = new ApprovalMigrationIntentEvent(
            new UUID(981, identity),
            intent.tenantId(),
            intent.intentId(),
            1,
            null,
            IntentStatus.PENDING,
            intent.operationReason(),
            intent.requestedBy(),
            consumedAt,
            requestId,
            intent.traceId(),
            auditReference
        );
        ApprovalMigrationPlanConsumption consumption = new ApprovalMigrationPlanConsumption(
            new UUID(982, identity),
            authorized.tenantId(),
            authorized.planId(),
            authorized.planHash(),
            authorized.authorizationId(),
            authorized.authorizationEvidenceHash(),
            intent.intentId(),
            intentEvidenceHash,
            intent.idempotencyKey(),
            hash('6'),
            intent.requestedBy(),
            intent.operationReason(),
            consumedAt,
            requestId,
            intent.traceId(),
            auditReference
        );
        ApprovalMigrationPlan consumed = consumed(authorized, consumedAt);
        ApprovalMigrationPlanEvent planEvent = new ApprovalMigrationPlanEvent(
            new UUID(983, identity),
            authorized.tenantId(),
            authorized.planId(),
            authorized.planHash(),
            consumed.revision(),
            PlanStatus.AUTHORIZED,
            PlanStatus.CONSUMED,
            intent.requestedBy(),
            intent.operationReason(),
            authorized.authorizationId(),
            authorized.authorizationEvidenceHash(),
            consumedAt,
            requestId,
            intent.traceId(),
            auditReference
        );
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("planHash", authorized.planHash());
        attributes.put("intentId", intent.intentId().toString());
        AuditEvent audit = new AuditEvent(
            new UUID(984, identity),
            authorized.tenantId(),
            intent.requestedBy(),
            "PROCESS_MIGRATION_PLAN_CONSUMED",
            "APPROVAL_MIGRATION_PLAN",
            authorized.planId().toString(),
            requestId,
            intent.traceId(),
            consumedAt,
            Map.copyOf(attributes)
        );
        return new AdmissionRequest(
            consumed,
            authorized.revision(),
            intent,
            intentEvent,
            consumption,
            planEvent,
            audit
        );
    }

    private static ApprovalMigrationPlan consumed(
        ApprovalMigrationPlan plan,
        Instant consumedAt
    ) {
        return new ApprovalMigrationPlan(
            plan.planId(),
            plan.tenantId(),
            plan.assessmentId(),
            plan.assessmentReportHash(),
            plan.definitionKey(),
            plan.sourceReleaseVersion(),
            plan.sourcePackageHash(),
            plan.targetReleaseVersion(),
            plan.targetPackageHash(),
            plan.targetDeploymentRecordId(),
            plan.targetEngineDeploymentId(),
            plan.targetEngineDefinitionId(),
            plan.targetEngineVersion(),
            plan.selectedInstances(),
            PlanStatus.CONSUMED,
            plan.revision() + 1,
            plan.idempotencyKey(),
            plan.planHash(),
            plan.requestedBy(),
            plan.operationReason(),
            plan.assessedAt(),
            plan.createdAt(),
            plan.expiresAt(),
            consumedAt,
            plan.authorizationId(),
            plan.authorizationEvidenceHash(),
            plan.authorizedBy(),
            plan.authorizedAt(),
            plan.authorizationExpiresAt(),
            plan.requestId(),
            plan.traceId(),
            plan.auditChainReference()
        );
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
