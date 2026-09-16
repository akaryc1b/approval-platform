package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker;
import io.github.akaryc1b.approval.application.port.ApprovalSlaActionDispatcher.DispatchResult;
import io.github.akaryc1b.approval.application.port.ApprovalSlaActionStateRecorder.ActionStateException;
import io.github.akaryc1b.approval.application.port.ApprovalSlaActionStateRecorder.RecordResult;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ActionType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionIntent;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.IntentStatus;
import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.port.OrganizationConnector;
import io.github.akaryc1b.approval.connector.port.OrganizationConnector.NotificationDeliveryResult;
import io.github.akaryc1b.approval.integration.jdbc.JdbcOutboxRepository;
import io.github.akaryc1b.approval.integration.outbox.OutboxDispatcher;
import io.github.akaryc1b.approval.integration.outbox.OutboxMessage;
import io.github.akaryc1b.approval.integration.outbox.OutboxRepository;
import io.github.akaryc1b.approval.integration.outbox.SlaTimeoutNotificationConnector;
import io.github.akaryc1b.approval.integration.retry.ExponentialBackoffRetryPolicy;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real PostgreSQL claims/transactions/workers, seeded SLA evidence and a controlled notification recipient. */
@Testcontainers
class JdbcApprovalSlaTimeoutEventIntegrationTest {
    private static final String TENANT = "tenant-sla-timeout";
    private static final UUID INSTANCE = UUID.fromString("89000000-0000-0000-0000-000000000001");
    private static final UUID TASK = UUID.fromString("89000000-0000-0000-0000-000000000002");
    private static final UUID POLICY = UUID.fromString("89000000-0000-0000-0000-000000000003");
    private static final UUID SLA = UUID.fromString("89000000-0000-0000-0000-000000000004");
    private static final Instant START = Instant.parse("2026-09-16T00:00:00Z");
    private static final Instant DEADLINE = START.plusSeconds(7800);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("sla_timeout_event_test").withUsername("approval")
        .withPassword(UUID.randomUUID().toString());

    private DataSource dataSource;
    private DataSourceTransactionManager manager;
    private JdbcTemplate jdbc;
    private JdbcApprovalSlaExecutionStore executions;
    private JdbcApprovalSlaActionStateRecorder state;
    private OutboxRepository outbox;
    private MutableClock clock;

    @BeforeEach
    void migrateAndSeed() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        manager = new DataSourceTransactionManager(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop schema public cascade"); jdbc.execute("create schema public");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        executions = new JdbcApprovalSlaExecutionStore(dataSource, new ObjectMapper(), manager);
        state = new JdbcApprovalSlaActionStateRecorder(dataSource, manager);
        outbox = new JdbcOutboxRepository(dataSource, new ObjectMapper());
        clock = new MutableClock(START.plusSeconds(10800));
        seed();
    }

    @Test
    void sourceRollbackAndReconstructedRecorderReplayKeepOneDurableEvent() {
        ExecutionIntent claimed = claim();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            assertEquals(RecordResult.RECORDED, recorder(outbox).record(claimed));
            status.setRollbackOnly();
        });
        assertEmpty();
        assertEquals(RecordResult.RECORDED, recorder(outbox).record(claimed));
        String identity = jdbc.queryForObject("select event_id::text from ap_outbox", String.class);
        assertEquals(RecordResult.ALREADY_RECORDED, recorder(outbox).record(claimed));
        assertEquals(identity, jdbc.queryForObject("select event_id::text from ap_outbox", String.class));
        assertEquals(1L, count("ap_outbox")); assertEquals(101L, sequence());
        assertEquals("TASK_TIMEOUT_DETECTED.v1", jdbc.queryForObject("select event_type from ap_outbox", String.class));
        assertEquals("owner-a", jdbc.queryForObject("select payload_json->>'recipientId' from ap_outbox", String.class));
        assertEquals("PENDING", jdbc.queryForObject("select status from ap_outbox", String.class));
    }

    @Test
    void appendFailureRollsBackBothTheWrittenEventAndTheOverdueSequence() {
        ExecutionIntent claimed = claim();
        OutboxRepository failing = (OutboxRepository) Proxy.newProxyInstance(OutboxRepository.class.getClassLoader(),
            new Class<?>[] { OutboxRepository.class }, (proxy, method, args) -> {
                assertEquals("append", method.getName());
                outbox.append((OutboxMessage) args[0]);
                throw new IllegalStateException("private database information");
            });
        var failure = assertThrows(ActionStateException.class, () -> recorder(failing).record(claimed));
        assertTrue(failure.retryable()); assertEquals("SLA_TIMEOUT_PERSISTENCE_UNAVAILABLE", failure.code());
        assertFalse(failure.getMessage().contains("private")); assertEmpty();
        assertEquals(RecordResult.RECORDED, recorder(outbox).record(claimed));
    }

    @Test
    void concurrentRecordersReleaseOnlyOneNotificationEvent() throws Exception {
        ExecutionIntent claimed = claim();
        CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<RecordResult> call = () -> {
                ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS));
                return recorder(outbox).record(claimed);
            };
            var one = threads.submit(call); var two = threads.submit(call);
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            var results = List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
            assertTrue(results.contains(RecordResult.RECORDED));
            assertTrue(results.contains(RecordResult.ALREADY_RECORDED));
        } finally { start.countDown(); }
        assertEquals(1L, count("ap_outbox")); assertEquals(101L, sequence());
    }

    @Test
    void expiredAndReplacedClaimsCannotEmitButAReclaimedCurrentIntentCan() {
        ExecutionIntent old = claim();
        clock.set(old.leaseUntil());
        assertThrows(ActionStateException.class, () -> recorder(outbox).record(old)); assertEmpty();
        clock.set(old.leaseUntil().plusSeconds(1));
        var current = executions.claimDue(TENANT, clock.instant(), 1, "replacement", clock.instant().plusSeconds(60)).getFirst();
        assertThrows(ActionStateException.class, () -> recorder(outbox).record(old)); assertEmpty();
        assertEquals(RecordResult.RECORDED, recorder(outbox).record(current));
    }

    @Test
    void earlyDeadlineChangedOwnerAndPausedSourceCannotGenerateTimeouts() {
        ExecutionIntent claimed = claim();
        clock.set(DEADLINE.minusSeconds(1));
        assertThrows(ActionStateException.class, () -> recorder(outbox).record(claimed)); assertEmpty();
        clock.set(START.plusSeconds(10800));
        jdbc.update("update ap_sla_instance set overdue_at=?", java.sql.Timestamp.from(clock.instant().plusSeconds(1)));
        assertThrows(ActionStateException.class, () -> recorder(outbox).record(claimed)); assertEmpty();
        jdbc.update("update ap_sla_instance set overdue_at=?, responsible_user_id='owner-b'", java.sql.Timestamp.from(DEADLINE));
        assertThrows(ActionStateException.class, () -> recorder(outbox).record(claimed)); assertEmpty();
        jdbc.update("update ap_sla_instance set responsible_user_id='owner-a', status='PAUSED', paused_at=?, pause_reason='fixture'",
            java.sql.Timestamp.from(clock.instant()));
        assertThrows(ActionStateException.class, () -> recorder(outbox).record(claimed)); assertEmpty();
    }

    @Test
    void crossTenantAndUnclaimedInputsFailWithoutChangingSourceOrQueue() {
        ExecutionIntent ready = ready("other-tenant");
        assertThrows(ActionStateException.class, () -> recorder(outbox).record(ready)); assertEmpty();
        var claimed = claim();
        var foreign = new ExecutionIntent(claimed.intentId(), "other-tenant", SLA, INSTANCE, TASK, null, POLICY, 1,
            null, null, null, ActionType.OVERDUE, 101, DEADLINE, DEADLINE, IntentStatus.CLAIMED,
            claimed.leaseOwner(), claimed.leaseUntil(), 0, 3, DEADLINE, "timeout-action", Map.of(),
            "owner-a", "request-timeout", "trace-timeout", claimed.version(), START, claimed.updatedAt(),
            null, null, null, null, null);
        assertThrows(ActionStateException.class, () -> recorder(outbox).record(foreign)); assertEmpty();
    }

    @Test
    void actualSlaAndOutboxWorkersKeepOnePersistentRecipientEffectAfterLostAcknowledgement() {
        executions.enqueue(List.of(ready(TENANT)));
        var worker = new ApprovalSlaExecutionWorker(executions, intent -> {
            recorder(outbox).record(intent); return DispatchResult.succeeded();
        }, (action, result, failure) -> { }, clock,
            new ApprovalSlaExecutionWorker.Configuration(true, "sla-worker", 5, Duration.ofSeconds(60),
                Duration.ofSeconds(1), Duration.ofSeconds(5)), UUID::randomUUID);
        assertEquals(1, worker.processTenant(TENANT).succeeded());
        assertEquals(0, worker.processTenant(TENANT).claimed());
        assertEquals("SUCCEEDED", jdbc.queryForObject("select status from ap_sla_execution_intent", String.class));
        assertEquals(1L, count("ap_outbox"));
        // This table is a test-only persistent recipient, not a migration or a real contact service.
        jdbc.execute("create table fixture_notification_receipt (tenant_id text, dedup_key text, primary key(tenant_id,dedup_key))");
        AtomicInteger calls = new AtomicInteger();
        OrganizationConnector recipient = (OrganizationConnector) Proxy.newProxyInstance(
            OrganizationConnector.class.getClassLoader(), new Class<?>[] { OrganizationConnector.class },
            (proxy, method, args) -> {
                assertEquals("sendNotification", method.getName());
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "external notification outside source transaction");
                var context = (ConnectorContext) args[0]; var notification = (OrganizationConnector.UserNotification) args[1];
                assertEquals(TENANT, context.tenantId()); assertEquals("owner-a", notification.recipientUserId());
                assertEquals("request-timeout", context.requestId()); assertEquals("trace-timeout", context.traceId());
                jdbc.update("insert into fixture_notification_receipt values (?,?) on conflict do nothing",
                    context.tenantId(), notification.deduplicationKey());
                return calls.incrementAndGet() == 1
                    ? NotificationDeliveryResult.failed(true, "ACK_LOST", "accepted but acknowledgement lost")
                    : NotificationDeliveryResult.delivered("persistent-receipt");
            });
        assertEquals(0L, count("fixture_notification_receipt"));
        assertEquals(1, dispatcher(recipient).dispatchBatch(10, "notify-1").rescheduled());
        assertEquals(1L, count("fixture_notification_receipt"));
        assertEquals("PENDING", jdbc.queryForObject("select status from ap_outbox", String.class));
        clock.set(clock.instant().plusSeconds(2));
        // Reconstruct both delivery adapter and dispatcher; deduplication must survive outside them.
        assertEquals(1, dispatcher(recipient).dispatchBatch(10, "notify-2").delivered());
        assertEquals(0, dispatcher(recipient).dispatchBatch(10, "notify-3").claimed());
        assertEquals(2, calls.get()); assertEquals(1L, count("fixture_notification_receipt"));
        assertEquals(1L, count("ap_outbox")); assertEquals(101L, sequence());
        assertEquals("DELIVERED", jdbc.queryForObject("select status from ap_outbox", String.class));
    }

    private OutboxDispatcher dispatcher(OrganizationConnector recipient) {
        var adapter = new SlaTimeoutNotificationConnector(recipient, "generic-rest", clock);
        return new OutboxDispatcher(outbox, key -> {
            assertEquals(SlaTimeoutNotificationConnector.ROUTE, key); return adapter;
        }, new ExponentialBackoffRetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1), 3, 0),
            clock, Duration.ofSeconds(30));
    }

    private JdbcApprovalSlaTimeoutEventRecorder recorder(OutboxRepository repository) {
        return new JdbcApprovalSlaTimeoutEventRecorder(dataSource, manager, executions, state, repository,
            "generic-rest", clock);
    }

    private ExecutionIntent claim() {
        executions.enqueue(List.of(ready(TENANT)));
        return executions.claimDue(TENANT, clock.instant(), 1, "source-worker", clock.instant().plusSeconds(60)).getFirst();
    }

    private ExecutionIntent ready(String tenant) {
        return new ExecutionIntent(UUID.randomUUID(), tenant, SLA, INSTANCE, TASK, null, POLICY, 1,
            null, null, null, ActionType.OVERDUE, 101, DEADLINE, DEADLINE, IntentStatus.READY,
            null, null, 0, 3, DEADLINE, "timeout-action", Map.of(), "owner-a", "request-timeout",
            "trace-timeout", 1, START, START, null, null, null, null, null);
    }

    private long count(String table) {
        if (!List.of("ap_outbox", "fixture_notification_receipt").contains(table)) throw new AssertionError("unknown fixture table");
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }
    private long sequence() { return jdbc.queryForObject("select last_action_sequence from ap_sla_instance", Long.class); }
    private void assertEmpty() { assertEquals(0L, count("ap_outbox")); assertEquals(0L, sequence()); }

    private void seed() {
        var start = java.sql.Timestamp.from(START);
        jdbc.update("""
            insert into ap_definition_version (tenant_id, definition_key, definition_version, form_key, form_version,
            compiler_version, content_hash, deployment_id, engine_definition_id, engine_version, published_by, published_at)
            values (?, 'purchasePayment',1,'purchasePayment',1,'compiler-v1',repeat('a',64),'timeout-deployment',
            'timeout-definition',1,'publisher',?)
            """, TENANT, start);
        jdbc.update("""
            insert into ap_approval_instance (instance_id, tenant_id, business_key, engine_instance_id,
            definition_key, definition_version, form_key, form_version, compiler_version, content_hash,
            initiator_id, amount, supplier, purchase_order_reference, attachment_ids_json, assignee_snapshot_json,
            request_hash, status, version, created_at, updated_at)
            values (?,?,'TIMEOUT-1','timeout-engine','purchasePayment',1,'purchasePayment',1,'compiler-v1',
            repeat('a',64),'initiator',100,'fixture','PO-1','[]'::jsonb,'{}'::jsonb,repeat('b',64),'RUNNING',1,?,?)
            """, INSTANCE, TENANT, start, start);
        jdbc.update("""
            insert into ap_approval_task (task_id,instance_id,tenant_id,engine_task_id,task_definition_key,
            task_name,assignee_id,status,version,created_at,updated_at,completed_at)
            values (?,?,?,'timeout-task','managerApproval','Approval','owner-a','PENDING',1,?,?,null)
            """, TASK, INSTANCE, TENANT, start, start);
        jdbc.update("""
            insert into ap_sla_policy (policy_id,tenant_id,policy_key,display_name,status,active_version,
            created_by,created_at,updated_at,version)
            values (?,?,'timeout-policy','Timeout','DRAFT',null,'designer',?,?,1)
            """, POLICY, TENANT, start, start);
        jdbc.update("""
            insert into ap_sla_policy_version (policy_id,tenant_id,policy_version,definition_key,release_version,
            task_definition_key,target_type,duration_mode,duration_millis,calendar_id,calendar_version,
            calendar_content_hash,time_zone,first_reminder_offset_millis,repeat_reminder_interval_millis,
            maximum_reminder_count,overdue_offset_millis,escalation_strategy,escalation_target,automatic_action_policy,
            pause_rules_json,content_hash,status,immutable,published_by,published_at,created_at,updated_at)
            values (?,?,1,'purchasePayment',1,'managerApproval','TASK','NATURAL_TIME',7200000,null,null,null,'UTC',
            null,null,0,600000,null,null,'NONE','{}'::jsonb,repeat('c',64),'ACTIVE',true,'publisher',?,?,?)
            """, POLICY, TENANT, start, start, start);
        jdbc.update("update ap_sla_policy set status='ACTIVE',active_version=1,version=2 where tenant_id=?", TENANT);
        jdbc.update("""
            insert into ap_sla_instance (sla_instance_id,tenant_id,approval_instance_id,task_id,collaboration_participant_id,
            definition_key,task_definition_key,target_type,policy_id,policy_version,calendar_id,calendar_version,time_zone,
            responsible_user_id,original_responsible_user_id,started_at,due_at,next_reminder_at,overdue_at,paused_at,
            pause_reason,accumulated_paused_millis,terminal_at,terminal_reason,status,last_action_sequence,
            request_id,trace_id,version,created_at,updated_at)
            values (?,?,?,?,null,'purchasePayment','managerApproval','TASK',?,1,null,null,'UTC','owner-a','owner-a',
            ?,?,null,?,null,null,0,null,null,'ACTIVE',0,'request-create','trace-create',1,?,?)
            """, SLA, TENANT, INSTANCE, TASK, POLICY, start, java.sql.Timestamp.from(START.plusSeconds(7200)),
                java.sql.Timestamp.from(DEADLINE), start, start);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        private MutableClock(Instant now) { this.now = new AtomicReference<>(now); }
        void set(Instant value) { now.set(value); }
        @Override public Instant instant() { return now.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("fixture is UTC only");
            return this;
        }
    }
}
