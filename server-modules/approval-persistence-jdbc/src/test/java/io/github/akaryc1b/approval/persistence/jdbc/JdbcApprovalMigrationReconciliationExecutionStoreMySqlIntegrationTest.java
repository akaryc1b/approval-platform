package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalMigrationReconciliationService;
import io.github.akaryc1b.approval.application.ApprovalMigrationReconciliationService.ReconciliationRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore.FinalDisposition;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationReconciliationStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationReconciliationStore.FinalizeRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationReconciliationStore.PrepareRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationReconciliationStore.PreparedReconciliation;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot.DefinitionEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot.TaskEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationExactVerification;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.ReconciliationStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliationObservation.ReconciliationDisposition;
import io.github.akaryc1b.approval.engine.ProcessInstanceVerificationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationReconciliationExecutionStoreMySqlIntegrationTest
    extends MySqlApprovalProjectionStoreIntegrationSupport {

    private static final Instant H4_NOW = Instant.parse(
        "2026-08-12T01:00:00.123456500Z"
    );
    private static final Instant RECONCILIATION_TIME = H4_NOW.plusSeconds(40);
    private static final String H4_WORKER = "worker-h4";
    private static final String H6_WORKER = "worker-h6";
    private static final String SNAPSHOT_HASH = "e".repeat(64);

    private ObjectMapper objectMapper;
    private JdbcTransactionManager transactionManager;
    private AtomicLong identifiers;

    @BeforeEach
    @Override
    void reset() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        transactionManager = new JdbcTransactionManager(dataSource);
        identifiers = new AtomicLong();
    }

    @Test
    void sourceObservationClosesNoRetryAndExactReplaySkipsAnotherRead() {
        Authority authority = seedUnknownAuthority("Tenant-H6-Source");
        AtomicInteger reads = new AtomicInteger();
        ApprovalMigrationReconciliationStore store = reconciliationStore(event -> {
        });
        assertInstanceOf(
            JdbcMySqlApprovalMigrationReconciliationExecutionStore.class,
            store
        );
        ApprovalMigrationReconciliationService service = service(store, command -> {
            reads.incrementAndGet();
            return runtimeSnapshot(authority.sourceDefinitionId());
        }, RECONCILIATION_TIME);

        var first = service.reconcile(request(authority, "request-h6-source", H6_WORKER));
        var replay = service.reconcile(request(authority, "request-h6-source", H6_WORKER));

        assertEquals(ReconciliationDisposition.SOURCE_CONFIRMED_NO_RETRY, first.disposition());
        assertEquals(AttemptStatus.BLOCKED_STALE, first.attempt().status());
        assertEquals(ReconciliationStatus.RESOLVED_SOURCE, first.reconciliation().status());
        assertEquals("RELEASED", leaseStatus(authority));
        assertEquals(1, reads.get());
        assertTrue(replay.replayed());
        assertEquals(1, countRows(
            "ap_process_migration_reconciliation_observation",
            authority
        ));
        assertEquals(2, countRows(
            "ap_process_migration_reconciliation_lease_event",
            authority
        ));
        assertEquals(2, countRows("ap_process_migration_reconciliation", authority));
        assertThrows(
            ApprovalMigrationReconciliationStore.ReconciliationConflictException.class,
            () -> service.reconcile(request(
                authority,
                "request-h6-source-changed",
                H6_WORKER
            ))
        );
    }

    @Test
    void exactTargetRequiresSeparateD5AndNeverMutatesBinding() {
        Authority authority = seedUnknownAuthority("Tenant-H6-Target");
        long revision = bindingRevision(authority);
        String evidenceHash = bindingEvidenceHash(authority);

        var result = service(
            reconciliationStore(event -> {
            }),
            command -> runtimeSnapshot(authority.targetDefinitionId()),
            RECONCILIATION_TIME
        ).reconcile(request(authority, "request-h6-target", H6_WORKER));

        assertEquals(
            ReconciliationDisposition.TARGET_CONFIRMED_BINDING_CAS_REQUIRED,
            result.disposition()
        );
        assertEquals(AttemptStatus.RECONCILING, result.attempt().status());
        assertEquals(
            ReconciliationStatus.MANUAL_REVIEW_REQUIRED,
            result.reconciliation().status()
        );
        assertEquals(revision, bindingRevision(authority));
        assertEquals(evidenceHash, bindingEvidenceHash(authority));
        assertEquals(0, countRows(
            "ap_process_migration_instance_completion",
            authority
        ));
    }

    @Test
    void readFailureRemainsManualAndDoesNotRedispatchMigration() {
        Authority authority = seedUnknownAuthority("Tenant-H6-Read-Failure");

        var result = service(
            reconciliationStore(event -> {
            }),
            command -> {
                throw new ProcessInstanceVerificationPort.VerificationReadException(
                    "CONNECTION_RESET",
                    "read reset",
                    new IllegalStateException("reset")
                );
            },
            RECONCILIATION_TIME
        ).reconcile(request(authority, "request-h6-read-failure", H6_WORKER));

        assertEquals(ReconciliationDisposition.MANUAL_REVIEW_REQUIRED, result.disposition());
        assertEquals(
            ReconciliationStatus.MANUAL_REVIEW_REQUIRED,
            result.reconciliation().status()
        );
        assertFalse(result.observation().snapshot().readSucceeded());
        assertEquals("CONNECTION_RESET", result.observation().snapshot().readFailureCode());
        assertEquals(1, countRows("ap_process_migration_engine_request", authority));
        assertEquals(1, countRows("ap_process_migration_engine_outcome", authority));
    }

    @Test
    void expiredLeaseAllowsOneStrictTakeover() {
        Authority authority = seedUnknownAuthority("Tenant-H6-Takeover");
        ApprovalMigrationReconciliationStore store = reconciliationStore(event -> {
        });
        PreparedReconciliation first = store.prepare(new PrepareRequest(
            authority.tenantId(),
            authority.attemptId(),
            "worker-h6-old",
            authority.unknown().revision(),
            RECONCILIATION_TIME,
            RECONCILIATION_TIME.plusSeconds(30),
            "request-h6-old",
            "trace-h6"
        ));
        PreparedReconciliation takeover = store.prepare(new PrepareRequest(
            authority.tenantId(),
            authority.attemptId(),
            H6_WORKER,
            first.attempt().revision(),
            RECONCILIATION_TIME.plusSeconds(30),
            RECONCILIATION_TIME.plusSeconds(90),
            "request-h6-takeover",
            "trace-h6"
        ));
        ApprovalMigrationEngineSnapshot snapshot = runtimeSnapshot(
            authority.targetDefinitionId()
        );
        var classification = ApprovalMigrationExactVerification.classify(
            snapshot,
            authority.sourceDefinitionId(),
            authority.targetDefinitionId()
        );

        var result = store.finalizeObservation(new FinalizeRequest(
            takeover,
            snapshot,
            classification,
            RECONCILIATION_TIME.plusSeconds(31)
        ));

        assertEquals(2, takeover.lease().revision());
        assertEquals(H6_WORKER, takeover.lease().workerId());
        assertEquals(
            ReconciliationDisposition.TARGET_CONFIRMED_BINDING_CAS_REQUIRED,
            result.disposition()
        );
        assertEquals(3, countRows(
            "ap_process_migration_reconciliation_lease_event",
            authority
        ));
    }

    @Test
    void staleTenantRevisionAndAuditFailuresFailClosed() {
        Authority authority = seedUnknownAuthority("Tenant-H6-Fail-Closed");
        ApprovalMigrationReconciliationStore store = reconciliationStore(event -> {
        });

        assertThrows(
            ApprovalMigrationReconciliationStore.ReconciliationConflictException.class,
            () -> store.prepare(new PrepareRequest(
                authority.tenantId().toLowerCase(),
                authority.attemptId(),
                H6_WORKER,
                authority.unknown().revision(),
                RECONCILIATION_TIME,
                RECONCILIATION_TIME.plusSeconds(300),
                "request-h6-wrong-tenant",
                "trace-h6"
            ))
        );
        assertThrows(
            ApprovalMigrationReconciliationStore.ReconciliationConflictException.class,
            () -> store.prepare(new PrepareRequest(
                authority.tenantId(),
                authority.attemptId(),
                H6_WORKER,
                authority.unknown().revision() - 1,
                RECONCILIATION_TIME,
                RECONCILIATION_TIME.plusSeconds(300),
                "request-h6-stale-revision",
                "trace-h6"
            ))
        );

        ApprovalMigrationReconciliationStore failing = reconciliationStore(event -> {
            throw new IllegalStateException("audit unavailable during D6 prepare");
        });
        assertThrows(
            IllegalStateException.class,
            () -> service(
                failing,
                command -> runtimeSnapshot(authority.targetDefinitionId()),
                RECONCILIATION_TIME
            ).reconcile(request(authority, "request-h6-audit-rollback", H6_WORKER))
        );
        assertEquals(AttemptStatus.UNKNOWN, attempt(authority).status());
        assertEquals(authority.unknown().revision(), attempt(authority).revision());
        assertEquals(0, countRows("ap_process_migration_reconciliation", authority));
        assertEquals(0, countRows(
            "ap_process_migration_reconciliation_lease",
            authority
        ));
    }

    @Test
    void finalizeAuditFailureRollsBackObservationAndLeaseRelease() {
        Authority authority = seedUnknownAuthority("Tenant-H6-Finalize-Rollback");
        ApprovalMigrationReconciliationStore store = reconciliationStore(event -> {
        });
        PreparedReconciliation prepared = store.prepare(new PrepareRequest(
            authority.tenantId(),
            authority.attemptId(),
            H6_WORKER,
            authority.unknown().revision(),
            RECONCILIATION_TIME,
            RECONCILIATION_TIME.plusSeconds(300),
            "request-h6-finalize-rollback",
            "trace-h6"
        ));
        ApprovalMigrationReconciliationStore failing = reconciliationStore(event -> {
            throw new IllegalStateException("audit unavailable during D6 finalize");
        });
        ApprovalMigrationEngineSnapshot snapshot = runtimeSnapshot(
            authority.targetDefinitionId()
        );
        var classification = ApprovalMigrationExactVerification.classify(
            snapshot,
            authority.sourceDefinitionId(),
            authority.targetDefinitionId()
        );

        assertThrows(
            IllegalStateException.class,
            () -> failing.finalizeObservation(new FinalizeRequest(
                prepared,
                snapshot,
                classification,
                RECONCILIATION_TIME.plusSeconds(1)
            ))
        );

        assertEquals(AttemptStatus.RECONCILING, attempt(authority).status());
        assertEquals("ACTIVE", leaseStatus(authority));
        assertEquals(1, countRows("ap_process_migration_reconciliation", authority));
        assertEquals(0, countRows(
            "ap_process_migration_reconciliation_observation",
            authority
        ));
    }

    private Authority seedUnknownAuthority(String tenant) {
        JdbcApprovalMigrationEngineExecutionStoreMySqlIntegrationTest fixture =
            new JdbcApprovalMigrationEngineExecutionStoreMySqlIntegrationTest();
        fixture.reset();
        Object claimed = invoke(fixture, "seedClaimedAuthority", tenant);
        UUID attemptId = (UUID) accessor(claimed, "attemptId");
        UUID instanceId = (UUID) accessor(claimed, "instanceId");
        ApprovalReleaseDeployment source = (ApprovalReleaseDeployment) accessor(
            claimed,
            "sourceDeployment"
        );
        ApprovalReleaseDeployment target = (ApprovalReleaseDeployment) accessor(
            claimed,
            "targetDeployment"
        );
        ApprovalMigrationEngineExecutionStore execution =
            JdbcApprovalMigrationEngineExecutionStoreFactory.create(
                dataSource,
                objectMapper,
                transactionManager,
                event -> {
                },
                UUID::randomUUID
            );
        var prepared = execution.prepare(new ApprovalMigrationEngineExecutionStore.PrepareRequest(
            tenant,
            attemptId,
            H4_WORKER,
            2,
            1,
            H4_NOW.plusSeconds(20),
            "request-h4-for-h6-prepare-" + tenant,
            "trace-h6"
        ));
        ApprovalMigrationAttempt unknown = execution.finalizeOutcome(
            new ApprovalMigrationEngineExecutionStore.FinalizeRequest(
                prepared,
                FinalDisposition.AMBIGUOUS_UNKNOWN,
                true,
                false,
                true,
                "RESPONSE_LOST",
                "engine response unavailable after dispatch",
                SNAPSHOT_HASH,
                H4_NOW.plusSeconds(30)
            )
        );
        assertEquals(AttemptStatus.UNKNOWN, unknown.status());
        assertEquals(EngineOutcome.UNKNOWN, unknown.engineOutcome());
        assertEquals(FailureClass.ENGINE_OUTCOME_UNKNOWN, unknown.failureClass());
        assertEquals(4, unknown.revision());
        return new Authority(
            tenant,
            instanceId,
            attemptId,
            source.engineDefinitionId(),
            target.engineDefinitionId(),
            unknown
        );
    }

    private ApprovalMigrationReconciliationStore reconciliationStore(AuditEventSink audit) {
        return JdbcApprovalMigrationReconciliationExecutionStoreFactory.create(
            dataSource,
            objectMapper,
            transactionManager,
            audit,
            () -> new UUID(0x6800000000000000L, identifiers.incrementAndGet())
        );
    }

    private ApprovalMigrationReconciliationService service(
        ApprovalMigrationReconciliationStore store,
        ProcessInstanceVerificationPort engine,
        Instant time
    ) {
        return new ApprovalMigrationReconciliationService(
            store,
            engine,
            Clock.fixed(time, ZoneOffset.UTC),
            Duration.ofMinutes(5)
        );
    }

    private ReconciliationRequest request(
        Authority authority,
        String requestId,
        String worker
    ) {
        return new ReconciliationRequest(
            authority.tenantId(),
            authority.attemptId(),
            worker,
            authority.unknown().revision(),
            requestId,
            "trace-h6"
        );
    }

    private ApprovalMigrationAttempt attempt(Authority authority) {
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

    private int countRows(String table, Authority authority) {
        Integer value = jdbc.queryForObject(
            "select count(*) from " + table + " where tenant_id=? and attempt_id=?",
            Integer.class,
            authority.tenantId(),
            authority.attemptId().toString()
        );
        return value == null ? 0 : value;
    }

    private String leaseStatus(Authority authority) {
        return jdbc.queryForObject(
            "select status from ap_process_migration_reconciliation_lease "
                + "where tenant_id=? and attempt_id=?",
            String.class,
            authority.tenantId(),
            authority.attemptId().toString()
        );
    }

    private long bindingRevision(Authority authority) {
        Long value = jdbc.queryForObject(
            "select binding_revision from ap_process_runtime_binding "
                + "where tenant_id=? and approval_instance_id=?",
            Long.class,
            authority.tenantId(),
            authority.instanceId().toString()
        );
        return value == null ? -1 : value;
    }

    private String bindingEvidenceHash(Authority authority) {
        return jdbc.queryForObject(
            "select binding_evidence_hash from ap_process_runtime_binding "
                + "where tenant_id=? and approval_instance_id=?",
            String.class,
            authority.tenantId(),
            authority.instanceId().toString()
        );
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
            List.of(new TaskEvidence("5".repeat(64), "review", definition, false)),
            List.of(),
            List.of(),
            List.of("6".repeat(64)),
            List.of("7".repeat(64)),
            true,
            definition,
            null,
            null,
            List.of(new TaskEvidence("5".repeat(64), "review", definition, false)),
            false,
            "8".repeat(64)
        );
    }

    private static Object invoke(Object target, String methodName, Object argument) {
        Method method = Arrays.stream(target.getClass().getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(methodName))
            .filter(candidate -> candidate.getParameterCount() == 1)
            .findFirst()
            .orElseThrow();
        try {
            method.setAccessible(true);
            return method.invoke(target, argument);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("H4 fixture method is inaccessible", exception);
        } catch (InvocationTargetException exception) {
            throw propagate(exception.getCause());
        }
    }

    private static Object accessor(Object target, String accessorName) {
        try {
            Method method = target.getClass().getDeclaredMethod(accessorName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new IllegalStateException("H4 authority accessor is unavailable", exception);
        } catch (InvocationTargetException exception) {
            throw propagate(exception.getCause());
        }
    }

    private static RuntimeException propagate(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("H4 fixture invocation failed", cause);
    }

    private record Authority(
        String tenantId,
        UUID instanceId,
        UUID attemptId,
        String sourceDefinitionId,
        String targetDefinitionId,
        ApprovalMigrationAttempt unknown
    ) {
    }
}
