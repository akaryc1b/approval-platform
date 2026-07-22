package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ActionType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.AttemptResult;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionIntent;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionIntentCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.IntentStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ReplayRequest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalSlaExecutionStoreIntegrationTest {

    private static final String TENANT_ID = "tenant-sla-execution";
    private static final String OTHER_TENANT_ID = "tenant-sla-execution-other";
    private static final String RESPONSIBLE_USER = "owner-execution";
    private static final UUID POLICY_ID = UUID.fromString(
        "61000000-0000-0000-0000-000000000001"
    );
    private static final UUID APPROVAL_INSTANCE_ID = UUID.fromString(
        "61000000-0000-0000-0000-000000000002"
    );
    private static final UUID TASK_ID = UUID.fromString(
        "61000000-0000-0000-0000-000000000003"
    );
    private static final UUID SLA_INSTANCE_ID = UUID.fromString(
        "61000000-0000-0000-0000-000000000004"
    );
    private static final Instant NOW = Instant.parse("2026-07-22T08:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_sla_execution_test")
        .withUsername("approval")
        .withPassword("approval");

    private JdbcApprovalSlaExecutionStore store;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeEach
    void migrateAndSeed() {
        DataSource dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop schema public cascade");
        jdbc.execute("create schema public");
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();
        seedSlaEvidence();
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(
            dataSource
        );
        transactions = new TransactionTemplate(transactionManager);
        store = new JdbcApprovalSlaExecutionStore(
            dataSource,
            new ObjectMapper(),
            transactionManager
        );
    }

    @Test
    void enqueueIsIdempotentQueryableAndTenantScoped() {
        ExecutionIntent intent = intent(1, 3, ActionType.REMINDER);

        assertEquals(1, store.enqueue(List.of(intent)));
        assertEquals(0, store.enqueue(List.of(intent)));
        assertTrue(store.findIntent(OTHER_TENANT_ID, intent.intentId()).isEmpty());
        assertEquals(List.of(TENANT_ID), store.findRunnableTenants(NOW, 10));

        var page = store.findIntents(new ExecutionIntentCriteria(
            TENANT_ID,
            Set.of(IntentStatus.READY),
            Set.of(ActionType.REMINDER),
            NOW.minus(Duration.ofHours(1)),
            NOW.plus(Duration.ofHours(1)),
            intent.requestId(),
            RESPONSIBLE_USER,
            20,
            0
        ));
        assertEquals(1, page.total());
        assertEquals(intent.intentId(), page.items().getFirst().intentId());
        assertFalse(page.hasMore());

        var summary = store.summarize(TENANT_ID, NOW);
        assertEquals(1, summary.ready());
        assertEquals(0, summary.claimed());
        assertEquals(0, summary.dead());
    }

    @Test
    void twoWorkersClaimExclusiveBatchesWithoutDuplicateIntent() throws Exception {
        List<ExecutionIntent> intents = java.util.stream.IntStream.rangeClosed(1, 20)
            .mapToObj(sequence -> intent(sequence, 3, ActionType.REMINDER))
            .toList();
        assertEquals(20, store.enqueue(intents));

        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<ExecutionIntent>> workerOne = executor.submit(() -> claimAfterSignal(
                workersReady,
                start,
                "worker-one"
            ));
            Future<List<ExecutionIntent>> workerTwo = executor.submit(() -> claimAfterSignal(
                workersReady,
                start,
                "worker-two"
            ));
            assertTrue(workersReady.await(10, TimeUnit.SECONDS));
            start.countDown();

            List<ExecutionIntent> first = workerOne.get(30, TimeUnit.SECONDS);
            List<ExecutionIntent> second = workerTwo.get(30, TimeUnit.SECONDS);
            assertEquals(10, first.size());
            assertEquals(10, second.size());

            Set<UUID> firstIds = first.stream()
                .map(ExecutionIntent::intentId)
                .collect(java.util.stream.Collectors.toSet());
            Set<UUID> secondIds = second.stream()
                .map(ExecutionIntent::intentId)
                .collect(java.util.stream.Collectors.toSet());
            assertTrue(java.util.Collections.disjoint(firstIds, secondIds));
            Set<UUID> allIds = new HashSet<>(firstIds);
            allIds.addAll(secondIds);
            assertEquals(20, allIds.size());
            assertTrue(first.stream().allMatch(value -> "worker-one".equals(value.leaseOwner())));
            assertTrue(second.stream().allMatch(value -> "worker-two".equals(value.leaseOwner())));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void unexpiredLeaseIsProtectedExpiredLeaseIsReclaimedAndStaleVersionFails() {
        ExecutionIntent intent = intent(1, 3, ActionType.OVERDUE);
        assertEquals(1, store.enqueue(List.of(intent)));

        ExecutionIntent claimed = store.claimDue(
            TENANT_ID,
            NOW,
            1,
            "worker-a",
            NOW.plus(Duration.ofMinutes(5))
        ).getFirst();
        assertTrue(store.claimDue(
            TENANT_ID,
            NOW.plus(Duration.ofMinutes(1)),
            1,
            "worker-b",
            NOW.plus(Duration.ofMinutes(6))
        ).isEmpty());

        assertThrows(ExecutionConflictException.class, () -> store.markSucceeded(
            TENANT_ID,
            intent.intentId(),
            claimed.version() - 1,
            "worker-a",
            attemptId(1),
            NOW,
            NOW.plusSeconds(1),
            NOW.plusSeconds(2),
            "request-stale",
            "trace-stale"
        ));

        Instant reclaimTime = NOW.plus(Duration.ofMinutes(6));
        ExecutionIntent reclaimed = store.claimDue(
            TENANT_ID,
            reclaimTime,
            1,
            "worker-b",
            reclaimTime.plus(Duration.ofMinutes(5))
        ).getFirst();
        assertEquals(intent.intentId(), reclaimed.intentId());
        assertEquals("worker-b", reclaimed.leaseOwner());
        assertTrue(reclaimed.version() > claimed.version());

        ExecutionIntent succeeded = store.markSucceeded(
            TENANT_ID,
            intent.intentId(),
            reclaimed.version(),
            "worker-b",
            attemptId(2),
            reclaimTime,
            reclaimTime.plusSeconds(1),
            reclaimTime.plusSeconds(2),
            "request-success",
            "trace-success"
        );
        assertEquals(IntentStatus.SUCCEEDED, succeeded.status());
        assertEquals(1, succeeded.attemptCount());
        assertNull(succeeded.leaseOwner());
        assertEquals(1, store.findAttempts(TENANT_ID, intent.intentId()).size());
        assertEquals(
            AttemptResult.SUCCEEDED,
            store.findAttempts(TENANT_ID, intent.intentId()).getFirst().result()
        );
    }

    @Test
    void retryableFailureBacksOffThenMaxAttemptsAndPermanentFailureBecomeDead() {
        ExecutionIntent retrying = intent(1, 2, ActionType.REMINDER);
        assertEquals(1, store.enqueue(List.of(retrying)));

        ExecutionIntent firstClaim = claimSpecific(retrying.intentId(), "worker-retry", NOW);
        ExecutionIntent waiting = store.markFailed(
            TENANT_ID,
            retrying.intentId(),
            firstClaim.version(),
            "worker-retry",
            attemptId(10),
            NOW,
            NOW.plusSeconds(1),
            NOW.plusSeconds(2),
            true,
            NOW.plus(Duration.ofMinutes(1)),
            "CONNECTOR_TEMPORARY",
            "temporary connector failure",
            "request-retry-one",
            "trace-retry-one"
        );
        assertEquals(IntentStatus.RETRY_WAIT, waiting.status());
        assertEquals(1, waiting.attemptCount());
        assertTrue(store.claimDue(
            TENANT_ID,
            NOW.plusSeconds(30),
            1,
            "worker-too-early",
            NOW.plus(Duration.ofMinutes(2))
        ).isEmpty());

        Instant retryTime = NOW.plus(Duration.ofMinutes(2));
        ExecutionIntent secondClaim = claimSpecific(
            retrying.intentId(),
            "worker-retry-two",
            retryTime
        );
        ExecutionIntent dead = store.markFailed(
            TENANT_ID,
            retrying.intentId(),
            secondClaim.version(),
            "worker-retry-two",
            attemptId(11),
            retryTime,
            retryTime.plusSeconds(1),
            retryTime.plusSeconds(2),
            true,
            null,
            "CONNECTOR_TEMPORARY",
            "temporary connector failure persisted",
            "request-retry-two",
            "trace-retry-two"
        );
        assertEquals(IntentStatus.DEAD, dead.status());
        assertEquals(2, dead.attemptCount());
        assertEquals(
            List.of(AttemptResult.RETRYABLE_FAILURE, AttemptResult.PERMANENT_FAILURE),
            store.findAttempts(TENANT_ID, retrying.intentId()).stream()
                .map(ApprovalSlaExecutionStore.ExecutionAttempt::result)
                .toList()
        );

        ExecutionIntent permanent = intent(2, 3, ActionType.AUTOMATIC_ACTION);
        assertEquals(1, store.enqueue(List.of(permanent)));
        ExecutionIntent permanentClaim = claimSpecific(
            permanent.intentId(),
            "worker-permanent",
            NOW
        );
        ExecutionIntent permanentlyDead = store.markFailed(
            TENANT_ID,
            permanent.intentId(),
            permanentClaim.version(),
            "worker-permanent",
            attemptId(12),
            NOW,
            NOW.plusSeconds(1),
            NOW.plusSeconds(2),
            false,
            null,
            "ACTION_NOT_ALLOWED",
            "immutable policy does not allow the action",
            "request-permanent",
            "trace-permanent"
        );
        assertEquals(IntentStatus.DEAD, permanentlyDead.status());
        assertEquals(1, permanentlyDead.attemptCount());
    }

    @Test
    void responsibilityChangeOnlyUpdatesFutureIntentsAndTerminalCancellationClearsLeases() {
        ExecutionIntent first = intent(1, 3, ActionType.ESCALATION);
        ExecutionIntent second = intent(2, 3, ActionType.ESCALATION);
        assertEquals(2, store.enqueue(List.of(first, second)));
        ExecutionIntent claimed = store.claimDue(
            TENANT_ID,
            NOW,
            1,
            "worker-active",
            NOW.plus(Duration.ofMinutes(5))
        ).getFirst();

        assertEquals(1, store.updateFutureResponsibleUser(
            TENANT_ID,
            SLA_INSTANCE_ID,
            "owner-reassigned",
            NOW.plusSeconds(1)
        ));
        ExecutionIntent unclaimed = first.intentId().equals(claimed.intentId()) ? second : first;
        assertEquals(
            RESPONSIBLE_USER,
            store.findIntent(TENANT_ID, claimed.intentId()).orElseThrow().responsibleUserId()
        );
        assertEquals(
            "owner-reassigned",
            store.findIntent(TENANT_ID, unclaimed.intentId()).orElseThrow().responsibleUserId()
        );

        assertEquals(2, store.cancelActiveForSla(
            TENANT_ID,
            SLA_INSTANCE_ID,
            NOW.plusSeconds(2),
            "SLA_TERMINAL"
        ));
        for (ExecutionIntent current : List.of(
            store.findIntent(TENANT_ID, first.intentId()).orElseThrow(),
            store.findIntent(TENANT_ID, second.intentId()).orElseThrow()
        )) {
            assertEquals(IntentStatus.CANCELLED, current.status());
            assertNull(current.leaseOwner());
            assertNull(current.leaseUntil());
            assertEquals("SLA_CANCELLED", current.lastErrorCode());
        }
    }

    @Test
    void replayCreatesNewIntentPreservesDeadHistoryAndEvidenceIsAppendOnly() {
        ExecutionIntent original = intent(1, 1, ActionType.OVERDUE);
        assertEquals(1, store.enqueue(List.of(original)));
        ExecutionIntent claimed = claimSpecific(original.intentId(), "worker-dead", NOW);
        ExecutionIntent dead = store.markFailed(
            TENANT_ID,
            original.intentId(),
            claimed.version(),
            "worker-dead",
            attemptId(20),
            NOW,
            NOW.plusSeconds(1),
            NOW.plusSeconds(2),
            false,
            null,
            "OVERDUE_CONFLICT",
            "optimistic SLA transition conflict",
            "request-dead",
            "trace-dead"
        );
        assertEquals(IntentStatus.DEAD, dead.status());

        ReplayRequest request = new ReplayRequest(
            replayId(1),
            intentId(100),
            TENANT_ID,
            original.intentId(),
            "Retry after the responsible SLA conflict was reviewed",
            "replay-request-1",
            "execution-replay-1",
            "operator-governance",
            NOW.plus(Duration.ofMinutes(5)),
            "audit-chain-1",
            "request-replay",
            "trace-replay"
        );
        var replayed = store.replayDead(request);
        assertFalse(replayed.replayedExistingRequest());
        assertNotEquals(original.intentId(), replayed.intent().intentId());
        assertEquals(original.intentId(), replayed.intent().sourceIntentId());
        assertEquals(IntentStatus.READY, replayed.intent().status());
        assertEquals(2, replayed.intent().actionSequence());
        assertEquals(IntentStatus.DEAD, store.findIntent(
            TENANT_ID,
            original.intentId()
        ).orElseThrow().status());

        ReplayRequest duplicate = new ReplayRequest(
            replayId(2),
            intentId(101),
            TENANT_ID,
            original.intentId(),
            request.replayReason(),
            request.replayIdempotencyKey(),
            "execution-replay-duplicate",
            request.requestedBy(),
            request.requestedAt(),
            request.auditChainReference(),
            request.requestId(),
            request.traceId()
        );
        var repeated = store.replayDead(duplicate);
        assertTrue(repeated.replayedExistingRequest());
        assertEquals(replayed.intent().intentId(), repeated.intent().intentId());
        assertEquals(2, jdbc.queryForObject(
            "select count(*) from ap_sla_execution_intent where tenant_id=?",
            Integer.class,
            TENANT_ID
        ));
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from ap_sla_execution_replay where tenant_id=?",
            Integer.class,
            TENANT_ID
        ));

        assertThrows(DataAccessException.class, () -> jdbc.update(
            """
            update ap_sla_execution_attempt set worker_id='tampered'
            where tenant_id=? and intent_id=?
            """,
            TENANT_ID,
            original.intentId()
        ));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            """
            delete from ap_sla_execution_replay
            where tenant_id=? and replay_id=?
            """,
            TENANT_ID,
            request.replayId()
        ));
        assertEquals(1, store.findAttempts(TENANT_ID, original.intentId()).size());
    }

    @Test
    void outerTransactionRollbackCoversIntentAndWorkerOutcomeTogether() {
        ExecutionIntent rolledBack = intent(1, 3, ActionType.REMINDER);
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            assertEquals(1, store.enqueue(List.of(rolledBack)));
            throw new IllegalStateException("rollback intent creation");
        }));
        assertTrue(store.findIntent(TENANT_ID, rolledBack.intentId()).isEmpty());

        ExecutionIntent persisted = intent(2, 3, ActionType.REMINDER);
        assertEquals(1, store.enqueue(List.of(persisted)));
        ExecutionIntent claimed = claimSpecific(persisted.intentId(), "worker-rollback", NOW);
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            store.markSucceeded(
                TENANT_ID,
                persisted.intentId(),
                claimed.version(),
                "worker-rollback",
                attemptId(30),
                NOW,
                NOW.plusSeconds(1),
                NOW.plusSeconds(2),
                "request-worker-rollback",
                "trace-worker-rollback"
            );
            throw new IllegalStateException("rollback worker outcome");
        }));
        ExecutionIntent unchanged = store.findIntent(
            TENANT_ID,
            persisted.intentId()
        ).orElseThrow();
        assertEquals(IntentStatus.CLAIMED, unchanged.status());
        assertEquals(0, unchanged.attemptCount());
        assertTrue(store.findAttempts(TENANT_ID, persisted.intentId()).isEmpty());
    }

    private List<ExecutionIntent> claimAfterSignal(
        CountDownLatch workersReady,
        CountDownLatch start,
        String workerId
    ) throws InterruptedException {
        workersReady.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return store.claimDue(
            TENANT_ID,
            NOW,
            10,
            workerId,
            NOW.plus(Duration.ofMinutes(5))
        );
    }

    private ExecutionIntent claimSpecific(UUID intentId, String workerId, Instant now) {
        return store.claimDue(
            TENANT_ID,
            now,
            1,
            workerId,
            now.plus(Duration.ofMinutes(5))
        ).stream().filter(value -> value.intentId().equals(intentId)).findFirst().orElseThrow();
    }

    private ExecutionIntent intent(int sequence, int maxAttempts, ActionType actionType) {
        Instant scheduledAt = NOW.minus(Duration.ofMinutes(5));
        return new ExecutionIntent(
            intentId(sequence),
            TENANT_ID,
            SLA_INSTANCE_ID,
            APPROVAL_INSTANCE_ID,
            TASK_ID,
            null,
            POLICY_ID,
            1,
            null,
            null,
            null,
            actionType,
            sequence,
            scheduledAt,
            scheduledAt,
            IntentStatus.READY,
            null,
            null,
            0,
            maxAttempts,
            scheduledAt,
            "execution-intent-" + sequence,
            Map.of(
                "authoritativeDueAt",
                NOW.plus(Duration.ofHours(1)).toString(),
                "policyVersion",
                1
            ),
            RESPONSIBLE_USER,
            "request-execution-" + sequence,
            "trace-execution-" + sequence,
            1,
            NOW.minus(Duration.ofMinutes(10)),
            NOW.minus(Duration.ofMinutes(10)),
            null,
            null,
            null,
            null,
            null
        );
    }

    private void seedSlaEvidence() {
        jdbc.update(
            """
            insert into ap_definition_version (
                tenant_id, definition_key, definition_version, form_key, form_version,
                compiler_version, content_hash, deployment_id, engine_definition_id,
                engine_version, published_by, published_at
            ) values (?, 'purchasePayment', 1, 'purchasePayment', 1,
                'compiler-execution', repeat('a', 64), 'deployment-execution',
                'engine-definition-execution', 1, 'execution-test',
                timestamptz '2026-07-22 07:00:00+00')
            """,
            TENANT_ID
        );
        jdbc.update(
            """
            insert into ap_approval_instance (
                instance_id, tenant_id, business_key, engine_instance_id,
                definition_key, definition_version, form_key, form_version,
                compiler_version, content_hash, initiator_id, amount, supplier,
                purchase_order_reference, attachment_ids_json, assignee_snapshot_json,
                request_hash, status, version, created_at, updated_at
            ) values (?, ?, 'EXECUTION-1', 'engine-execution-1',
                'purchasePayment', 1, 'purchasePayment', 1,
                'compiler-execution', repeat('a', 64), 'initiator-execution',
                100, 'supplier-execution', 'PO-EXECUTION', '[]'::jsonb,
                '{}'::jsonb, repeat('b', 64), 'RUNNING', 1,
                timestamptz '2026-07-22 07:00:00+00',
                timestamptz '2026-07-22 07:00:00+00')
            """,
            APPROVAL_INSTANCE_ID,
            TENANT_ID
        );
        jdbc.update(
            """
            insert into ap_approval_task (
                task_id, instance_id, tenant_id, engine_task_id,
                task_definition_key, task_name, assignee_id, status,
                version, created_at, updated_at, completed_at
            ) values (?, ?, ?, 'engine-task-execution', 'managerApproval',
                'Manager approval', ?, 'PENDING', 1,
                timestamptz '2026-07-22 07:00:00+00',
                timestamptz '2026-07-22 07:00:00+00', null)
            """,
            TASK_ID,
            APPROVAL_INSTANCE_ID,
            TENANT_ID,
            RESPONSIBLE_USER
        );
        jdbc.update(
            """
            insert into ap_sla_policy (
                policy_id, tenant_id, policy_key, display_name, status,
                active_version, created_by, created_at, updated_at, version
            ) values (?, ?, 'executionPolicy', 'Execution policy', 'DRAFT',
                null, 'execution-test', timestamptz '2026-07-22 07:00:00+00',
                timestamptz '2026-07-22 07:00:00+00', 1)
            """,
            POLICY_ID,
            TENANT_ID
        );
        jdbc.update(
            """
            insert into ap_sla_policy_version (
                policy_id, tenant_id, policy_version, definition_key,
                release_version, task_definition_key, target_type, duration_mode,
                duration_millis, calendar_id, calendar_version,
                calendar_content_hash, time_zone, first_reminder_offset_millis,
                repeat_reminder_interval_millis, maximum_reminder_count,
                overdue_offset_millis, escalation_strategy, escalation_target,
                automatic_action_policy, pause_rules_json, content_hash, status,
                immutable, published_by, published_at, created_at, updated_at
            ) values (?, ?, 1, 'purchasePayment', null, 'managerApproval',
                'TASK', 'NATURAL_TIME', 3600000, null, null, null, 'UTC',
                1800000, null, 1, 0, null, null, 'NONE', '{}'::jsonb,
                repeat('c', 64), 'ACTIVE', true, 'execution-test',
                timestamptz '2026-07-22 07:00:00+00',
                timestamptz '2026-07-22 07:00:00+00',
                timestamptz '2026-07-22 07:00:00+00')
            """,
            POLICY_ID,
            TENANT_ID
        );
        jdbc.update(
            """
            update ap_sla_policy
            set status='ACTIVE', active_version=1,
                updated_at=timestamptz '2026-07-22 07:00:01+00', version=2
            where tenant_id=? and policy_id=?
            """,
            TENANT_ID,
            POLICY_ID
        );
        jdbc.update(
            """
            insert into ap_sla_instance (
                sla_instance_id, tenant_id, approval_instance_id, task_id,
                collaboration_participant_id, definition_key, task_definition_key,
                target_type, policy_id, policy_version, calendar_id,
                calendar_version, time_zone, responsible_user_id,
                original_responsible_user_id, started_at, due_at,
                next_reminder_at, overdue_at, paused_at, pause_reason,
                accumulated_paused_millis, terminal_at, terminal_reason, status,
                last_action_sequence, request_id, trace_id, version,
                created_at, updated_at
            ) values (?, ?, ?, ?, null, 'purchasePayment', 'managerApproval',
                'TASK', ?, 1, null, null, 'UTC', ?, ?,
                timestamptz '2026-07-22 07:00:00+00',
                timestamptz '2026-07-22 09:00:00+00',
                timestamptz '2026-07-22 08:30:00+00',
                timestamptz '2026-07-22 09:00:00+00', null, null, 0,
                null, null, 'ACTIVE', 0, 'request-sla-execution',
                'trace-sla-execution', 1,
                timestamptz '2026-07-22 07:00:00+00',
                timestamptz '2026-07-22 07:00:00+00')
            """,
            SLA_INSTANCE_ID,
            TENANT_ID,
            APPROVAL_INSTANCE_ID,
            TASK_ID,
            POLICY_ID,
            RESPONSIBLE_USER,
            RESPONSIBLE_USER
        );
    }

    private static UUID intentId(int sequence) {
        return UUID.fromString("62000000-0000-0000-0000-%012d".formatted(sequence));
    }

    private static UUID attemptId(int sequence) {
        return UUID.fromString("63000000-0000-0000-0000-%012d".formatted(sequence));
    }

    private static UUID replayId(int sequence) {
        return UUID.fromString("64000000-0000-0000-0000-%012d".formatted(sequence));
    }
}
