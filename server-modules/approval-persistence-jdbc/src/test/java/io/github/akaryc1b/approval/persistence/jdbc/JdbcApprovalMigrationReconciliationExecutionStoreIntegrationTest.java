package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalMigrationReconciliationService;
import io.github.akaryc1b.approval.application.ApprovalMigrationReconciliationService.ReconciliationRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationReconciliationStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationReconciliationStore.FinalizeRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationReconciliationStore.PrepareRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationReconciliationStore.PreparedReconciliation;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptTransition;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot.DefinitionEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot.TaskEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationExactVerification;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.ReconciliationStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliationObservation;
import io.github.akaryc1b.approval.engine.ProcessInstanceVerificationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.support.JdbcTransactionManager;

import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.NOW;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.TENANT;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.attempt;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.attemptEvent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.hash;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.initialAttemptEvent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.initialIntentEvent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.intent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationReconciliationExecutionStoreIntegrationTest
    extends AbstractJdbcApprovalMigrationProtocolIntegrationTest {

    private static final Instant RECONCILIATION_TIME = NOW.plusSeconds(20);
    private static final UUID ENGINE_REQUEST_ID =
        UUID.fromString("58000000-0000-0000-0000-000000000001");
    private static final UUID ENGINE_OUTCOME_ID =
        UUID.fromString("58000000-0000-0000-0000-000000000002");
    private static final String SOURCE = "source-definition";
    private static final String TARGET = "target-definition";
    private static final String WORKER = "reconciler-d6";

    private ObjectMapper objectMapper;
    private JdbcTransactionManager transactionManager;
    private AtomicLong sequence;
    private JdbcApprovalMigrationReconciliationExecutionStore reconciliationStore;
    private ApprovalMigrationAttempt unknown;

    @BeforeEach
    void setUpReconciliation() {
        jdbc.execute("truncate table ap_audit_event,ap_audit_chain_state cascade");
        objectMapper = new ObjectMapper().findAndRegisterModules();
        transactionManager = new JdbcTransactionManager(dataSource);
        sequence = new AtomicLong();
        reconciliationStore = store(new JdbcAuditEventSink(
            dataSource,
            objectMapper,
            transactionManager
        ));
        unknown = persistUnknown();
        seedAmbiguousOutcome();
    }

    @Test
    void sourceObservationClosesNoRetryAndExactReplaySkipsAnotherRead() {
        AtomicInteger reads = new AtomicInteger();
        ApprovalMigrationReconciliationService service = service(command -> {
            reads.incrementAndGet();
            return runtimeSnapshot(SOURCE);
        }, RECONCILIATION_TIME);

        var first = service.reconcile(request("request-d6-source", WORKER, unknown.revision()));
        var replay = service.reconcile(request("request-d6-source", WORKER, unknown.revision()));

        assertEquals(
            ApprovalMigrationReconciliationObservation.ReconciliationDisposition
                .SOURCE_CONFIRMED_NO_RETRY,
            first.disposition()
        );
        assertEquals(AttemptStatus.BLOCKED_STALE, first.attempt().status());
        assertEquals(ReconciliationStatus.RESOLVED_SOURCE, first.reconciliation().status());
        assertEquals("RELEASED", textValue(
            "select status from ap_process_migration_reconciliation_lease "
                + "where tenant_id=? and attempt_id=?",
            TENANT,
            unknown.attemptId()
        ));
        assertEquals(1, reads.get());
        assertTrue(replay.replayed());
        assertEquals(1, count("ap_process_migration_reconciliation_observation"));
        assertEquals(2, count("ap_process_migration_reconciliation_lease_event"));
        assertEquals(2, count("ap_process_migration_reconciliation"));
        assertThrows(
            ApprovalMigrationReconciliationStore.ReconciliationConflictException.class,
            () -> service.reconcile(request("request-d6-changed", WORKER, unknown.revision()))
        );
    }

    @Test
    void exactTargetRequiresManualBindingCasAndNeverMutatesBinding() {
        var result = service(command -> runtimeSnapshot(TARGET), RECONCILIATION_TIME)
            .reconcile(request("request-d6-target", WORKER, unknown.revision()));

        assertEquals(
            ApprovalMigrationReconciliationObservation.ReconciliationDisposition
                .TARGET_CONFIRMED_BINDING_CAS_REQUIRED,
            result.disposition()
        );
        assertEquals(AttemptStatus.RECONCILING, result.attempt().status());
        assertEquals(ReconciliationStatus.MANUAL_REVIEW_REQUIRED, result.reconciliation().status());
        assertEquals(0, count("ap_process_runtime_binding"));
        assertEquals(0, count("ap_process_migration_instance_completion"));
    }

    @Test
    void readFailureRemainsManualAndDoesNotRedispatchMigration() {
        var result = service(command -> {
            throw new ProcessInstanceVerificationPort.VerificationReadException(
                "CONNECTION_RESET",
                "read reset",
                new IllegalStateException("reset")
            );
        }, RECONCILIATION_TIME).reconcile(
            request("request-d6-read-failure", WORKER, unknown.revision())
        );

        assertEquals(
            ApprovalMigrationReconciliationObservation.ReconciliationDisposition
                .MANUAL_REVIEW_REQUIRED,
            result.disposition()
        );
        assertEquals(ReconciliationStatus.MANUAL_REVIEW_REQUIRED, result.reconciliation().status());
        assertFalse(result.observation().snapshot().readSucceeded());
        assertEquals("CONNECTION_RESET", result.observation().snapshot().readFailureCode());
        assertEquals(1, count("ap_process_migration_engine_request"));
        assertEquals(1, count("ap_process_migration_engine_outcome"));
    }

    @Test
    void expiredIndependentLeaseAllowsOneTakeover() {
        PreparedReconciliation first = reconciliationStore.prepare(new PrepareRequest(
            TENANT,
            unknown.attemptId(),
            "reconciler-old",
            unknown.revision(),
            RECONCILIATION_TIME,
            RECONCILIATION_TIME.plusSeconds(30),
            "request-d6-old",
            "trace-d6"
        ));
        PreparedReconciliation takeover = reconciliationStore.prepare(new PrepareRequest(
            TENANT,
            unknown.attemptId(),
            WORKER,
            first.attempt().revision(),
            RECONCILIATION_TIME.plusSeconds(30),
            RECONCILIATION_TIME.plusSeconds(90),
            "request-d6-takeover",
            "trace-d6"
        ));
        var classification = ApprovalMigrationExactVerification.classify(
            runtimeSnapshot(TARGET),
            SOURCE,
            TARGET
        );
        var result = reconciliationStore.finalizeObservation(new FinalizeRequest(
            takeover,
            runtimeSnapshot(TARGET),
            classification,
            RECONCILIATION_TIME.plusSeconds(31)
        ));

        assertEquals(2, takeover.lease().revision());
        assertEquals(WORKER, takeover.lease().workerId());
        assertEquals(
            ApprovalMigrationReconciliationObservation.ReconciliationDisposition
                .TARGET_CONFIRMED_BINDING_CAS_REQUIRED,
            result.disposition()
        );
        assertEquals(3, count("ap_process_migration_reconciliation_lease_event"));
    }

    @Test
    void prepareAuditFailureRollsBackUnknownTransitionAndLease() {
        JdbcApprovalMigrationReconciliationExecutionStore failing = store(event -> {
            throw new IllegalStateException("audit unavailable");
        });
        ApprovalMigrationReconciliationService service = service(
            failing,
            command -> runtimeSnapshot(TARGET),
            RECONCILIATION_TIME
        );

        assertThrows(
            IllegalStateException.class,
            () -> service.reconcile(request(
                "request-d6-audit-prepare",
                WORKER,
                unknown.revision()
            ))
        );
        assertEquals("UNKNOWN", attemptStatus());
        assertEquals(0, count("ap_process_migration_reconciliation"));
        assertEquals(0, count("ap_process_migration_reconciliation_lease"));
        assertEquals(0, count("ap_process_migration_reconciliation_observation"));
    }

    @Test
    void finalizeAuditFailureRollsBackObservationConclusionAndLeaseRelease() {
        PreparedReconciliation prepared = reconciliationStore.prepare(new PrepareRequest(
            TENANT,
            unknown.attemptId(),
            WORKER,
            unknown.revision(),
            RECONCILIATION_TIME,
            RECONCILIATION_TIME.plusSeconds(300),
            "request-d6-audit-finalize",
            "trace-d6"
        ));
        JdbcApprovalMigrationReconciliationExecutionStore failing = store(event -> {
            throw new IllegalStateException("audit unavailable");
        });
        ApprovalMigrationEngineSnapshot snapshot = runtimeSnapshot(TARGET);
        var classification = ApprovalMigrationExactVerification.classify(snapshot, SOURCE, TARGET);

        assertThrows(
            IllegalStateException.class,
            () -> failing.finalizeObservation(new FinalizeRequest(
                prepared,
                snapshot,
                classification,
                RECONCILIATION_TIME.plusSeconds(1)
            ))
        );
        assertEquals("RECONCILING", attemptStatus());
        assertEquals("ACTIVE", textValue(
            "select status from ap_process_migration_reconciliation_lease "
                + "where tenant_id=? and attempt_id=?",
            TENANT,
            unknown.attemptId()
        ));
        assertEquals(1, count("ap_process_migration_reconciliation"));
        assertEquals(0, count("ap_process_migration_reconciliation_observation"));
    }

    @Test
    void observationAndLeaseEventsAreAppendOnly() {
        service(command -> runtimeSnapshot(TARGET), RECONCILIATION_TIME)
            .reconcile(request("request-d6-immutable", WORKER, unknown.revision()));

        assertThrows(DataAccessException.class, () -> jdbc.update(
            "delete from ap_process_migration_reconciliation_observation "
                + "where tenant_id=? and attempt_id=?",
            TENANT,
            unknown.attemptId()
        ));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "update ap_process_migration_reconciliation_lease_event set worker_id='attacker' "
                + "where tenant_id=? and attempt_id=? and revision=1",
            TENANT,
            unknown.attemptId()
        ));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "delete from ap_process_migration_reconciliation_lease "
                + "where tenant_id=? and attempt_id=?",
            TENANT,
            unknown.attemptId()
        ));
    }

    private JdbcApprovalMigrationReconciliationExecutionStore store(AuditEventSink audit) {
        return new JdbcApprovalMigrationReconciliationExecutionStore(
            dataSource,
            objectMapper,
            transactionManager,
            audit,
            () -> new UUID(0x5800000000000000L, sequence.incrementAndGet())
        );
    }

    private ApprovalMigrationReconciliationService service(
        ProcessInstanceVerificationPort engine,
        Instant time
    ) {
        return service(reconciliationStore, engine, time);
    }

    private ApprovalMigrationReconciliationService service(
        JdbcApprovalMigrationReconciliationExecutionStore executionStore,
        ProcessInstanceVerificationPort engine,
        Instant time
    ) {
        return new ApprovalMigrationReconciliationService(
            executionStore,
            engine,
            Clock.fixed(time, ZoneOffset.UTC),
            Duration.ofMinutes(5)
        );
    }

    private static ReconciliationRequest request(
        String requestId,
        String worker,
        long expectedRevision
    ) {
        return new ReconciliationRequest(
            TENANT,
            ApprovalMigrationJdbcFixtures.ATTEMPT_ID,
            worker,
            expectedRevision,
            requestId,
            "trace-d6"
        );
    }

    private ApprovalMigrationAttempt persistUnknown() {
        var migrationIntent = intent();
        store.createIntent(
            migrationIntent,
            initialIntentEvent(migrationIntent, "d6-intent")
        );
        ApprovalMigrationAttempt pending = attempt();
        store.createAttempt(pending, initialAttemptEvent(pending, "d6-attempt"));
        ApprovalMigrationAttempt claimed = pending.transitioned(
            new ApprovalMigrationAttemptTransition(
                AttemptStatus.CLAIMED,
                EngineOutcome.NOT_REQUESTED,
                "worker-original",
                NOW.plusSeconds(120),
                null,
                FailureClass.NONE,
                null,
                NOW.plusSeconds(2)
            )
        );
        store.transitionAttempt(
            claimed,
            pending.revision(),
            "worker-original",
            attemptEvent(claimed, AttemptStatus.PENDING, "d6-claim")
        );
        ApprovalMigrationAttempt requested = claimed.transitioned(
            new ApprovalMigrationAttemptTransition(
                AttemptStatus.ENGINE_REQUESTED,
                EngineOutcome.NOT_REQUESTED,
                null,
                null,
                ENGINE_REQUEST_ID.toString(),
                FailureClass.NONE,
                null,
                NOW.plusSeconds(3)
            )
        );
        store.transitionAttempt(
            requested,
            claimed.revision(),
            "worker-original",
            attemptEvent(requested, AttemptStatus.CLAIMED, "d6-engine-request")
        );
        ApprovalMigrationAttempt result = requested.transitioned(
            new ApprovalMigrationAttemptTransition(
                AttemptStatus.UNKNOWN,
                EngineOutcome.UNKNOWN,
                null,
                null,
                ENGINE_REQUEST_ID.toString(),
                FailureClass.ENGINE_OUTCOME_UNKNOWN,
                "Engine response was not observed",
                NOW.plusSeconds(4)
            )
        );
        store.transitionAttempt(
            result,
            requested.revision(),
            attemptEvent(result, AttemptStatus.ENGINE_REQUESTED, "d6-unknown")
        );
        return result;
    }

    private void seedAmbiguousOutcome() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set session_replication_role = replica");
                statement.executeUpdate("""
                    insert into ap_process_migration_engine_request (
                     tenant_id,engine_request_id,intent_id,attempt_id,approval_instance_id,
                     worker_id,attempt_revision,fence_id,fence_revision,
                     engine_instance_id,source_binding_evidence_hash,source_engine_definition_id,
                     target_release_version,target_package_hash,target_engine_deployment_id,
                     target_engine_definition_id,activity_mapping_json,
                     request_hash,evidence_hash,requested_at,request_id,trace_id,payload_json
                    ) values (
                     '%s','%s','%s','%s','%s','worker-original',2,'%s',1,
                     'engine-instance-one','%s','%s',2,'%s','deployment-target','%s','[]'::jsonb,
                     '%s','%s',timestamptz '%s','request-engine-d6','trace-d6','{}'::jsonb
                    )
                    """.formatted(
                    TENANT,
                    ENGINE_REQUEST_ID,
                    unknown.intentId(),
                    unknown.attemptId(),
                    unknown.approvalInstanceId(),
                    UUID.fromString("58000000-0000-0000-0000-000000000003"),
                    unknown.expectedBindingEvidenceHash(),
                    SOURCE,
                    hash('c'),
                    TARGET,
                    hash('1'),
                    hash('2'),
                    offset(NOW.plusSeconds(3))
                ));
                statement.executeUpdate("""
                    insert into ap_process_migration_engine_outcome (
                     tenant_id,engine_outcome_id,engine_request_id,intent_id,attempt_id,worker_id,
                     expected_attempt_revision,expected_fence_revision,disposition,
                     engine_call_attempted,engine_call_returned,engine_call_may_have_occurred,
                     stable_code,bounded_summary,pre_dispatch_snapshot_hash,outcome_hash,
                     recorded_at,request_id,trace_id,payload_json
                    ) values (
                     '%s','%s','%s','%s','%s','worker-original',3,1,'AMBIGUOUS_UNKNOWN',
                     true,false,true,'TIMEOUT','Engine response was not observed','%s','%s',
                     timestamptz '%s','request-outcome-d6','trace-d6','{}'::jsonb
                    )
                    """.formatted(
                    TENANT,
                    ENGINE_OUTCOME_ID,
                    ENGINE_REQUEST_ID,
                    unknown.intentId(),
                    unknown.attemptId(),
                    hash('3'),
                    hash('4'),
                    offset(NOW.plusSeconds(4))
                ));
            } finally {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("set session_replication_role = origin");
                }
            }
            return null;
        });
    }

    private static ApprovalMigrationEngineSnapshot runtimeSnapshot(String definition) {
        return new ApprovalMigrationEngineSnapshot(
            true,
            null,
            true,
            definition,
            "deployment",
            false,
            List.of("review"),
            List.of(new DefinitionEvidence("EXECUTION", "execution", definition)),
            List.of(new TaskEvidence(hash('5'), "review", definition, false)),
            List.of(),
            List.of(),
            List.of(hash('6')),
            List.of(hash('7')),
            true,
            definition,
            null,
            null,
            List.of(new TaskEvidence(hash('5'), "review", definition, false)),
            false,
            hash('8')
        );
    }

    private String attemptStatus() {
        return textValue(
            "select status from ap_process_migration_attempt where tenant_id=? and attempt_id=?",
            TENANT,
            unknown.attemptId()
        );
    }

    private String textValue(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, String.class, arguments);
    }

    private static String offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC).toString();
    }
}
