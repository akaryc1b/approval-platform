package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExecutionAdmissionStore.AdmissionRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExecutionAdmissionStore.AdmissionResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore.AggregationConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore.AggregationRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore.AggregationResult;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntentEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAuthorization;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanConsumption;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.AggregateStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PauseReason;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationPlanAggregationStoreIntegrationTest
    extends JdbcApprovalMigrationPlanStoreIntegrationTestSupport {

    @Test
    void exactReplayAndChangedReplayAreDeterministic() {
        AdmissionResult admission = persistConsumedPlan(1);
        List<AuditEvent> audits = new ArrayList<>();
        ApprovalMigrationPlanAggregationStore store = rawStore(audits);
        AggregationRequest request = request(admission, 1, "aggregate-one");

        AggregationResult first = store.aggregate(request);
        AggregationResult replay = store.aggregate(request);

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.aggregate(), replay.aggregate());
        assertEquals(AggregateStatus.NOT_STARTED, first.aggregate().status());
        assertEquals(2, first.aggregate().counts().selectedCount());
        assertEquals(2, first.aggregate().counts().pendingCount());
        assertEquals(2, first.aggregate().counts().unresolvedCount());
        assertEquals(PauseReason.NONE, first.aggregate().pauseReason());
        assertNull(first.completion());
        assertEquals(1, count("ap_process_migration_plan_aggregate"));
        assertEquals(1, count("ap_process_migration_plan_aggregate_event"));
        assertEquals(0, count("ap_process_migration_plan_completion"));
        assertEquals(1, audits.size());
        assertEquals("migration-operator", audits.get(0).operatorId());

        assertThrows(AggregationConflictException.class, () -> store.aggregate(
            new AggregationRequest(
                request.context(),
                request.planId(),
                2,
                request.reason(),
                request.happenedAt()
            )
        ));
        assertEquals(1, count("ap_process_migration_plan_aggregate"));
    }

    @Test
    void unchangedEvidenceCannotCreateASecondAggregateRevision() {
        AdmissionResult admission = persistConsumedPlan(2);
        ApprovalMigrationPlanAggregationStore store = rawStore(new ArrayList<>());

        AggregationResult first = store.aggregate(request(admission, 1, "aggregate-first"));

        assertThrows(AggregationConflictException.class, () -> store.aggregate(
            request(admission, 2, "aggregate-second")
        ));
        assertEquals(1, count("ap_process_migration_plan_aggregate"));
        assertEquals(1, count("ap_process_migration_plan_aggregate_event"));
        assertEquals(first.aggregate().aggregateHash(), jdbc.queryForObject(
            "select aggregate_hash from ap_process_migration_plan_aggregate "
                + "where tenant_id=? and plan_id=?",
            String.class,
            TENANT,
            admission.intent().planId()
        ));
    }

    @Test
    void concurrentNodesProduceOneAuthoritativeAggregate() throws Exception {
        AdmissionResult admission = persistConsumedPlan(3);
        List<AuditEvent> audits = java.util.Collections.synchronizedList(new ArrayList<>());
        ApprovalMigrationPlanAggregationStore firstStore = serializedStore(audits);
        ApprovalMigrationPlanAggregationStore secondStore = serializedStore(audits);
        AggregationRequest request = request(admission, 1, "aggregate-concurrent");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AggregationResult> first = executor.submit(
                () -> gatedAggregate(firstStore, request, ready, start)
            );
            Future<AggregationResult> second = executor.submit(
                () -> gatedAggregate(secondStore, request, ready, start)
            );
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<AggregationResult> results = List.of(
                first.get(20, TimeUnit.SECONDS),
                second.get(20, TimeUnit.SECONDS)
            );
            assertEquals(1, results.stream().filter(AggregationResult::replayed).count());
            assertEquals(results.get(0).aggregate(), results.get(1).aggregate());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
        assertEquals(1, count("ap_process_migration_plan_aggregate"));
        assertEquals(1, count("ap_process_migration_plan_aggregate_event"));
        assertEquals(1, audits.size());
    }

    @Test
    void auditFailureRollsBackAggregateEventAndCompletionTogether() {
        AdmissionResult admission = persistConsumedPlan(4);
        ApprovalMigrationPlanAggregationStore store = new JdbcApprovalMigrationPlanAggregationStore(
            dataSource,
            mapper(),
            new JdbcTransactionManager(dataSource),
            event -> {
                throw new IllegalStateException("aggregation audit failed");
            },
            UUID::randomUUID
        );

        assertThrows(IllegalStateException.class, () -> store.aggregate(
            request(admission, 1, "aggregate-audit-failure")
        ));
        assertEquals(0, count("ap_process_migration_plan_aggregate"));
        assertEquals(0, count("ap_process_migration_plan_aggregate_event"));
        assertEquals(0, count("ap_process_migration_plan_completion"));
    }

    @Test
    void crossTenantAndStaleRevisionFailClosed() {
        AdmissionResult admission = persistConsumedPlan(5);
        ApprovalMigrationPlanAggregationStore store = rawStore(new ArrayList<>());

        AggregationRequest valid = request(admission, 1, "aggregate-valid-shape");
        assertThrows(AggregationConflictException.class, () -> store.aggregate(
            new AggregationRequest(
                new RequestContext(
                    "other-tenant",
                    valid.operatorId(),
                    "aggregate-cross-tenant",
                    "aggregation-cross-tenant",
                    null
                ),
                valid.planId(),
                1,
                valid.reason(),
                NOW.plusSeconds(60)
            )
        ));
        assertThrows(AggregationConflictException.class, () -> store.aggregate(
            request(admission, 2, "aggregate-stale-revision")
        ));
        assertEquals(0, count("ap_process_migration_plan_aggregate"));
    }

    @Test
    void aggregateEvidenceIsAppendOnlyAndPayloadBound() {
        AdmissionResult admission = persistConsumedPlan(6);
        AggregationResult result = rawStore(new ArrayList<>()).aggregate(
            request(admission, 1, "aggregate-tamper")
        );

        assertThrows(DataAccessException.class, () -> jdbc.update(
            "update ap_process_migration_plan_aggregate set request_id='tampered' "
                + "where tenant_id=? and aggregate_id=?",
            TENANT,
            result.aggregate().aggregateId()
        ));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "delete from ap_process_migration_plan_aggregate_event "
                + "where tenant_id=? and aggregate_id=?",
            TENANT,
            result.aggregate().aggregateId()
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
                + "input_evidence_hash,aggregate_hash,operator_id,?,request_hash,"
                + "aggregate_hash,aggregated_at,reason,request_id,audit_reference,payload_json "
                + "from ap_process_migration_plan_aggregate where tenant_id=? and aggregate_id=?",
            UUID.randomUUID(),
            "tampered-idempotency",
            TENANT,
            result.aggregate().aggregateId()
        ));
    }

    private ApprovalMigrationPlanAggregationStore rawStore(List<AuditEvent> audits) {
        return new JdbcApprovalMigrationPlanAggregationStore(
            dataSource,
            mapper(),
            new JdbcTransactionManager(dataSource),
            audits::add,
            UUID::randomUUID
        );
    }

    private ApprovalMigrationPlanAggregationStore serializedStore(List<AuditEvent> audits) {
        return new PostgresSerializedApprovalMigrationPlanAggregationStore(
            dataSource,
            rawStore(audits)
        );
    }

    private static AggregationRequest request(
        AdmissionResult admission,
        long revision,
        String requestId
    ) {
        return new AggregationRequest(
            new RequestContext(
                admission.intent().tenantId(),
                "migration-operator",
                requestId,
                "aggregation-key-" + requestId,
                "trace-aggregation"
            ),
            admission.intent().planId(),
            revision,
            "Aggregate exact consumed plan for D8",
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

    private AdmissionResult persistConsumedPlan(long identity) {
        ApprovalMigrationPlan plan = proposed(
            TENANT,
            PLAN_ID,
            "plan-aggregation-" + identity,
            hash('d')
        );
        plans.createPlan(plan, initialEvent(plan, "initial-plan-aggregation-" + identity));
        ApprovalMigrationPlanAuthorization authorization = authorization(
            plan,
            "migration-approver",
            "authorization-aggregation-" + identity,
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
                "authorization-event-aggregation-" + identity
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
        UUID intentId = new UUID(1_080, identity);
        String intentEvidenceHash = hash('f');
        String requestId = "request-admission-aggregation-" + identity;
        String auditReference = "audit-admission-aggregation-" + identity;
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
            "admission-aggregation-" + identity,
            intentEvidenceHash,
            "migration-executor",
            "Admit exact authorized migration plan for D8",
            authorized.expiresAt(),
            consumedAt,
            consumedAt,
            requestId,
            "trace-admission-aggregation",
            auditReference
        );
        ApprovalMigrationIntentEvent intentEvent = new ApprovalMigrationIntentEvent(
            new UUID(1_081, identity),
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
            new UUID(1_082, identity),
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
            new UUID(1_083, identity),
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
            new UUID(1_084, identity),
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
