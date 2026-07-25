package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExecutionAdmissionStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExecutionAdmissionStore.AdmissionRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExecutionAdmissionStore.AdmissionResult;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntentEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAuthorization;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanConsumption;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanProtocol.PlanStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.IntentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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

class JdbcApprovalMigrationExecutionAdmissionStoreIntegrationTest
    extends JdbcApprovalMigrationPlanStoreIntegrationTestSupport {

    private final List<AuditEvent> auditEvents = Collections.synchronizedList(new ArrayList<>());

    @Test
    void consumesPlanCreatesIntentAndReplaysWithoutDuplicateEvidence() {
        ApprovalMigrationPlan authorized = persistAuthorizedPlan();
        JdbcApprovalMigrationExecutionAdmissionStore admissions = admissionStore(auditEvents::add);
        AdmissionRequest request = admissionRequest(authorized, 1, hash('8'), "admission-key");

        AdmissionResult first = admissions.admit(request);
        AdmissionResult replay = admissions.admit(request);

        assertFalse(first.replayedExistingAdmission());
        assertTrue(replay.replayedExistingAdmission());
        assertEquals(first.plan(), replay.plan());
        assertEquals(first.intent(), replay.intent());
        assertEquals(PlanStatus.CONSUMED, first.plan().status());
        assertEquals(IntentStatus.PENDING, first.intent().status());
        assertEquals(1, count("ap_process_migration_plan_consumption"));
        assertEquals(1, count("ap_process_migration_intent"));
        assertEquals(1, count("ap_process_migration_intent_event"));
        assertEquals(3, count("ap_process_migration_plan_event"));
        assertEquals(1, auditEvents.size());
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
            "update ap_process_migration_plan_consumption set reason='tampered' "
                + "where tenant_id=? and plan_id=?",
            TENANT,
            authorized.planId()
        ));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
            "delete from ap_process_migration_plan_consumption "
                + "where tenant_id=? and plan_id=?",
            TENANT,
            authorized.planId()
        ));
    }

    @Test
    void changedPayloadReplayFailsClosedWithoutChangingAuthoritativeAdmission() {
        ApprovalMigrationPlan authorized = persistAuthorizedPlan();
        JdbcApprovalMigrationExecutionAdmissionStore admissions = admissionStore(auditEvents::add);
        AdmissionResult first = admissions.admit(
            admissionRequest(authorized, 2, hash('8'), "shared-key")
        );

        assertThrows(
            ApprovalMigrationExecutionAdmissionStore
                .MigrationExecutionAdmissionConflictException.class,
            () -> admissions.admit(admissionRequest(authorized, 3, hash('9'), "shared-key"))
        );
        assertEquals(
            first.consumption(),
            admissions.findConsumption(TENANT, authorized.planId()).orElseThrow()
        );
        assertEquals(1, count("ap_process_migration_plan_consumption"));
        assertEquals(1, count("ap_process_migration_intent"));
        assertEquals(3, count("ap_process_migration_plan_event"));
    }

    @Test
    void auditFailureRollsBackPlanIntentConsumptionAndEvents() {
        ApprovalMigrationPlan authorized = persistAuthorizedPlan();
        JdbcApprovalMigrationExecutionAdmissionStore failing = admissionStore(event -> {
            throw new IllegalStateException("audit persistence failed");
        });

        assertThrows(
            IllegalStateException.class,
            () -> failing.admit(admissionRequest(
                authorized,
                4,
                hash('a'),
                "audit-failure-key"
            ))
        );
        assertEquals(PlanStatus.AUTHORIZED, plans.findPlan(TENANT, PLAN_ID).orElseThrow().status());
        assertEquals(0, count("ap_process_migration_plan_consumption"));
        assertEquals(0, count("ap_process_migration_intent"));
        assertEquals(2, count("ap_process_migration_plan_event"));
    }

    @Test
    void concurrentExactAdmissionHasOneInsertAndOneAuthoritativeReplay() throws Exception {
        ApprovalMigrationPlan authorized = persistAuthorizedPlan();
        List<AuditEvent> events = Collections.synchronizedList(new ArrayList<>());
        JdbcApprovalMigrationExecutionAdmissionStore firstStore = admissionStore(events::add);
        JdbcApprovalMigrationExecutionAdmissionStore secondStore = admissionStore(events::add);
        AdmissionRequest firstRequest = admissionRequest(
            authorized,
            10,
            hash('b'),
            "concurrent-key"
        );
        AdmissionRequest secondRequest = admissionRequest(
            authorized,
            20,
            hash('b'),
            "concurrent-key"
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AdmissionResult> first = executor.submit(
                () -> gatedAdmit(firstStore, firstRequest, ready, start)
            );
            Future<AdmissionResult> second = executor.submit(
                () -> gatedAdmit(secondStore, secondRequest, ready, start)
            );
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<AdmissionResult> results = List.of(
                first.get(20, TimeUnit.SECONDS),
                second.get(20, TimeUnit.SECONDS)
            );
            assertEquals(
                1,
                results.stream().filter(AdmissionResult::replayedExistingAdmission).count()
            );
            assertEquals(results.get(0).intent(), results.get(1).intent());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
        assertEquals(1, count("ap_process_migration_plan_consumption"));
        assertEquals(1, count("ap_process_migration_intent"));
        assertEquals(1, events.size());
    }

    @Test
    void databaseRejectsConsumedPlanOrGovernedIntentWithoutExactCounterpart() {
        ApprovalMigrationPlan authorized = persistAuthorizedPlan();
        AdmissionRequest request = admissionRequest(
            authorized,
            30,
            hash('c'),
            "tamper-key"
        );
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("""
            update ap_process_migration_plan
            set status='CONSUMED',revision=3,updated_at=?,
                payload_json=jsonb_set(jsonb_set(jsonb_set(payload_json,
                  '{status}',to_jsonb('CONSUMED'::text)),
                  '{revision}',to_jsonb(3::bigint)),
                  '{updatedAt}',to_jsonb(?::text))
            where tenant_id=? and plan_id=?
            """,
            offset(request.consumption().consumedAt()),
            request.consumption().consumedAt().toString(),
            TENANT,
            PLAN_ID
        ));

        JdbcApprovalMigrationProtocolStore protocols = new JdbcApprovalMigrationProtocolStore(
            dataSource,
            mapper(),
            new JdbcTransactionManager(dataSource)
        );
        assertThrows(
            io.github.akaryc1b.approval.application.port.ApprovalMigrationProtocolStore
                .MigrationProtocolConflictException.class,
            () -> protocols.createIntent(request.intent(), request.intentEvent())
        );
        assertTrue(protocols.findIntent(TENANT, request.intent().intentId()).isEmpty());

        ApprovalMigrationIntent mismatched = mismatchedPlanHashIntent(request.intent());
        ApprovalMigrationIntentEvent mismatchedEvent = new ApprovalMigrationIntentEvent(
            new UUID(785, 31),
            mismatched.tenantId(),
            mismatched.intentId(),
            1,
            null,
            IntentStatus.PENDING,
            mismatched.operationReason(),
            mismatched.requestedBy(),
            mismatched.createdAt(),
            mismatched.requestId(),
            mismatched.traceId(),
            mismatched.auditChainReference()
        );
        assertThrows(
            io.github.akaryc1b.approval.application.port.ApprovalMigrationProtocolStore
                .MigrationProtocolConflictException.class,
            () -> protocols.createIntent(mismatched, mismatchedEvent)
        );
        assertTrue(protocols.findIntent(TENANT, mismatched.intentId()).isEmpty());
    }

    private static ApprovalMigrationIntent mismatchedPlanHashIntent(
        ApprovalMigrationIntent source
    ) {
        return new ApprovalMigrationIntent(
            new UUID(786, 31),
            source.tenantId(),
            source.planId(),
            hash('9'),
            source.definitionKey(),
            source.sourceReleaseVersion(),
            source.sourcePackageHash(),
            source.targetReleaseVersion(),
            source.targetPackageHash(),
            source.selectedInstanceCount(),
            source.status(),
            source.revision(),
            "mismatched-plan-hash-key",
            hash('7'),
            source.requestedBy(),
            source.operationReason(),
            source.expiresAt(),
            source.createdAt(),
            source.updatedAt(),
            "request-mismatched-plan-hash",
            source.traceId(),
            "audit-mismatched-plan-hash"
        );
    }

    private ApprovalMigrationPlan persistAuthorizedPlan() {
        ApprovalMigrationPlan plan = proposed(TENANT, PLAN_ID, "plan-key", hash('d'));
        plans.createPlan(plan, initialEvent(plan, "initial-plan"));
        ApprovalMigrationPlanAuthorization authorization = authorization(
            plan,
            "migration-approver",
            "authorization-key",
            hash('e')
        );
        ApprovalMigrationPlan authorized = plan.authorized(authorization);
        plans.authorizePlan(
            authorized,
            1,
            authorization,
            authorizationEvent(plan, authorized, authorization, "authorization-event")
        );
        return authorized;
    }

    private AdmissionRequest admissionRequest(
        ApprovalMigrationPlan authorized,
        long identity,
        String requestHash,
        String idempotencyKey
    ) {
        Instant consumedAt = NOW.plusSeconds(30);
        UUID intentId = new UUID(780, identity);
        UUID consumptionId = new UUID(781, identity);
        String intentEvidenceHash = hash('f');
        String auditReference = "audit-admission-" + identity;
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
            idempotencyKey,
            intentEvidenceHash,
            "migration-executor",
            "Admit exact authorized migration plan",
            authorized.expiresAt(),
            consumedAt,
            consumedAt,
            "request-admission-" + identity,
            "trace-admission",
            auditReference
        );
        ApprovalMigrationIntentEvent intentEvent = new ApprovalMigrationIntentEvent(
            new UUID(782, identity),
            authorized.tenantId(),
            intentId,
            1,
            null,
            IntentStatus.PENDING,
            "Admit exact authorized migration plan",
            "migration-executor",
            consumedAt,
            intent.requestId(),
            intent.traceId(),
            auditReference
        );
        ApprovalMigrationPlanConsumption consumption = new ApprovalMigrationPlanConsumption(
            consumptionId,
            authorized.tenantId(),
            authorized.planId(),
            authorized.planHash(),
            authorized.authorizationId(),
            authorized.authorizationEvidenceHash(),
            intentId,
            intentEvidenceHash,
            idempotencyKey,
            requestHash,
            "migration-executor",
            "Admit exact authorized migration plan",
            consumedAt,
            intent.requestId(),
            intent.traceId(),
            auditReference
        );
        ApprovalMigrationPlan consumed = consumed(authorized, consumedAt);
        ApprovalMigrationPlanEvent planEvent = new ApprovalMigrationPlanEvent(
            new UUID(783, identity),
            authorized.tenantId(),
            authorized.planId(),
            authorized.planHash(),
            consumed.revision(),
            PlanStatus.AUTHORIZED,
            PlanStatus.CONSUMED,
            "migration-executor",
            consumption.reason(),
            authorized.authorizationId(),
            authorized.authorizationEvidenceHash(),
            consumedAt,
            intent.requestId(),
            intent.traceId(),
            auditReference
        );
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("planHash", authorized.planHash());
        attributes.put("intentId", intentId.toString());
        AuditEvent audit = new AuditEvent(
            new UUID(784, identity),
            authorized.tenantId(),
            "migration-executor",
            "PROCESS_MIGRATION_PLAN_CONSUMED",
            "APPROVAL_MIGRATION_PLAN",
            authorized.planId().toString(),
            intent.requestId(),
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
            plan.planId(), plan.tenantId(), plan.assessmentId(), plan.assessmentReportHash(),
            plan.definitionKey(), plan.sourceReleaseVersion(), plan.sourcePackageHash(),
            plan.targetReleaseVersion(), plan.targetPackageHash(), plan.targetDeploymentRecordId(),
            plan.targetEngineDeploymentId(), plan.targetEngineDefinitionId(),
            plan.targetEngineVersion(), plan.selectedInstances(), PlanStatus.CONSUMED,
            plan.revision() + 1, plan.idempotencyKey(), plan.planHash(), plan.requestedBy(),
            plan.operationReason(), plan.assessedAt(), plan.createdAt(), plan.expiresAt(),
            consumedAt, plan.authorizationId(), plan.authorizationEvidenceHash(),
            plan.authorizedBy(), plan.authorizedAt(), plan.authorizationExpiresAt(),
            plan.requestId(), plan.traceId(), plan.auditChainReference()
        );
    }

    private JdbcApprovalMigrationExecutionAdmissionStore admissionStore(
        io.github.akaryc1b.approval.application.port.AuditEventSink sink
    ) {
        return new JdbcApprovalMigrationExecutionAdmissionStore(
            dataSource,
            mapper(),
            new JdbcTransactionManager(dataSource),
            sink
        );
    }

    private static AdmissionResult gatedAdmit(
        JdbcApprovalMigrationExecutionAdmissionStore store,
        AdmissionRequest request,
        CountDownLatch ready,
        CountDownLatch start
    ) throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return store.admit(request);
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

}
