package io.github.akaryc1b.approval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker;
import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker.WorkerMetrics;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.CalendarSnapshot;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.WorkingInterval;
import io.github.akaryc1b.approval.application.port.ApprovalSlaActionDispatcher;
import io.github.akaryc1b.approval.application.port.ApprovalSlaActionDispatcher.DispatchResult;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.IntentStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarIdentity;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarVersion;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.PolicyStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaPolicyIdentity;
import io.github.akaryc1b.approval.integration.jdbc.JdbcOutboxRepository;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaActionStateRecorder;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaExecutionStore;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaStore;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaTimeoutEventRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.CALENDAR;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.DEADLINE;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.FINISH;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.INSTANCE;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.POLICY;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.SLA;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.START;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.TASK;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.TENANT;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.calendar;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.id;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.intent;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.policy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/** Real migrations, immutable calendars, SLA worker, transactional timeout recorder and PostgreSQL Outbox. */
@Testcontainers
class ApprovalSlaLagPostgresTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("sla_lag_test").withUsername("approval").withPassword(UUID.randomUUID().toString());
    private DataSource dataSource;
    private DataSourceTransactionManager manager;
    private JdbcTemplate jdbc;
    private JdbcApprovalSlaStore store;
    private JdbcApprovalSlaExecutionStore executions;
    private JdbcApprovalSlaTimeoutEventRecorder recorder;
    private SimpleMeterRegistry registry;
    private MutableClock clock;

    @BeforeEach void initialize() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        manager = new DataSourceTransactionManager(dataSource); jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop schema public cascade"); jdbc.execute("create schema public");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        store = new JdbcApprovalSlaStore(dataSource, manager);
        executions = new JdbcApprovalSlaExecutionStore(dataSource, new ObjectMapper(), manager);
        registry = new SimpleMeterRegistry(); clock = new MutableClock(FINISH);
        recorder = new JdbcApprovalSlaTimeoutEventRecorder(dataSource, manager, executions,
            new JdbcApprovalSlaActionStateRecorder(dataSource, manager),
            new JdbcOutboxRepository(dataSource, new ObjectMapper()), "generic-rest", clock);
        seed();
        assertEquals(1, executions.enqueue(List.of(intent(IntentStatus.READY, true))));
    }
    @AfterEach void closeRegistry() { registry.close(); }

    @Test void retryAfterCommittedDetectionSamplesOnlyFinalSuccessAndUsesPinnedCalendarNotActiveVersion() {
        assertEquals(2, store.findCalendar(TENANT, CALENDAR).orElseThrow().activeVersion());
        AtomicInteger attempts = new AtomicInteger(); var metrics = metrics(store);
        ApprovalSlaActionDispatcher dispatcher = value -> {
            recorder.record(value);
            return attempts.getAndIncrement() == 0 ? DispatchResult.retryableFailure("FIXTURE_ACK_LOST", "fixture")
                : DispatchResult.succeeded();
        };
        assertEquals(1, worker(dispatcher, metrics).processTenant(TENANT).retryScheduled());
        assertEquals(1L, outboxCount()); assertTrue(registry.find("approval.sla.overdue.completion.lag").timers().isEmpty());
        clock.set(FINISH.plusSeconds(2));
        // Reconstruct the repository/worker rather than caching successful IDs in the observer.
        executions = new JdbcApprovalSlaExecutionStore(dataSource, new ObjectMapper(), manager);
        assertEquals(1, worker(dispatcher, metrics).processTenant(TENANT).succeeded());
        var actual = executions.findIntent(TENANT, id(6)).orElseThrow();
        assertEquals(IntentStatus.SUCCEEDED, actual.status()); assertEquals(2, actual.attemptCount());
        assertEquals(clock.instant(), actual.completedAt()); assertEquals(1L, outboxCount());
        assertEquals(1, registry.get("approval.sla.overdue.completion.lag").tag("time_basis", "working_time").timer().count());
        assertEquals(7202, registry.get("approval.sla.overdue.completion.lag").tag("time_basis", "working_time").timer().totalTime(TimeUnit.SECONDS));
        assertEquals(237602, registry.get("approval.sla.overdue.completion.lag").tag("time_basis", "natural_time").timer().totalTime(TimeUnit.SECONDS));
        assertEquals(0, worker(dispatcher, metrics).processTenant(TENANT).claimed());
        assertEquals(1, registry.get("approval.sla.overdue.completion.lag").tag("time_basis", "working_time").timer().count());
    }

    @Test void rolledBackOuterTransactionCannotProduceSamplesOrPerformObservationReads() {
        var observedStore = spy(store); var metrics = metrics(observedStore);
        var worker = worker(value -> { recorder.record(value); return DispatchResult.succeeded(); }, metrics);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            assertEquals(1, worker.processTenant(TENANT).succeeded());
            status.setRollbackOnly();
        });
        verify(observedStore, never()).findPolicyVersion(anyString(), any(), anyInt());
        assertEquals(0L, outboxCount()); assertEquals(IntentStatus.READY, executions.findIntent(TENANT, id(6)).orElseThrow().status());
        assertTrue(registry.find("approval.sla.overdue.completion.lag").timers().isEmpty());
        assertEquals(1, worker.processTenant(TENANT).succeeded());
        assertEquals(1L, outboxCount());
        assertEquals(1, registry.get("approval.sla.overdue.completion.lag").tag("time_basis", "working_time").timer().count());
    }

    @Test void actualSqlFailureInObservationRollsBackOnlyTheReadAndLeavesSuccessfulActionAndOutboxCommitted() {
        var broken = spy(store);
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            jdbc.queryForObject("select 1 / 0", Integer.class);
            throw new AssertionError("expected PostgreSQL division failure");
        }).when(broken).findPolicyVersion(TENANT, POLICY, 1);
        assertEquals(1, worker(value -> { recorder.record(value); return DispatchResult.succeeded(); }, metrics(broken))
            .processTenant(TENANT).succeeded());
        assertEquals(IntentStatus.SUCCEEDED, executions.findIntent(TENANT, id(6)).orElseThrow().status());
        assertEquals(1L, outboxCount());
        assertEquals(101L, jdbc.queryForObject("select last_action_sequence from ap_sla_instance", Long.class));
        assertTrue(registry.find("approval.sla.overdue.completion.lag").timers().isEmpty());
        assertEquals(1, registry.get("approval.sla.overdue.completion.observations.dropped").functionCounter().count());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    private WorkerMetrics metrics(ApprovalSlaStore source) {
        return new ApprovalSlaLagObservabilityConfiguration().calendarAwareSlaExecutionMetrics(WorkerMetrics.noop(), source,
            new ApprovalWorkingTimeCalculator(), manager, registry);
    }
    private ApprovalSlaExecutionWorker worker(ApprovalSlaActionDispatcher dispatcher, WorkerMetrics metrics) {
        return new ApprovalSlaExecutionWorker(executions, dispatcher, metrics, clock,
            new ApprovalSlaExecutionWorker.Configuration(true, "fixture-worker", 10, Duration.ofSeconds(60),
                Duration.ofSeconds(1), Duration.ofSeconds(10)), UUID::randomUUID);
    }
    private long outboxCount() { return jdbc.queryForObject("select count(*) from ap_outbox", Long.class); }

    private void seed() {
        var start = Timestamp.from(START);
        jdbc.update("""
            insert into ap_definition_version (tenant_id, definition_key, definition_version, form_key, form_version,
            compiler_version, content_hash, deployment_id, engine_definition_id, engine_version, published_by, published_at)
            values (?, 'purchasePayment',1,'purchasePayment',1,'compiler-v1',repeat('a',64),'lag-deployment',
            'lag-definition',1,'publisher',?)
            """, TENANT, start);
        jdbc.update("""
            insert into ap_approval_instance (instance_id, tenant_id, business_key, engine_instance_id,
            definition_key, definition_version, form_key, form_version, compiler_version, content_hash,
            initiator_id, amount, supplier, purchase_order_reference, attachment_ids_json, assignee_snapshot_json,
            request_hash, status, version, created_at, updated_at)
            values (?,?,'LAG-1','lag-engine','purchasePayment',1,'purchasePayment',1,'compiler-v1',
            repeat('a',64),'initiator',100,'fixture','PO-1','[]'::jsonb,'{}'::jsonb,repeat('b',64),'RUNNING',1,?,?)
            """, INSTANCE, TENANT, start, start);
        jdbc.update("""
            insert into ap_approval_task (task_id,instance_id,tenant_id,engine_task_id,task_definition_key,
            task_name,assignee_id,status,version,created_at,updated_at,completed_at)
            values (?,?,?,'lag-task','managerApproval','Approval','owner-a','PENDING',1,?,?,null)
            """, TASK, INSTANCE, TENANT, start, start);
        store.createCalendar(new CalendarIdentity(CALENDAR, TENANT, "lag-calendar", "Lag calendar", "Asia/Shanghai",
            CalendarStatus.DRAFT, null, "designer", START, START, 1));
        store.saveCalendarVersion(calendar(TENANT, 1, false), calendarRevision());
        store.publishCalendarVersion(TENANT, CALENDAR, 1, "publisher", START, calendarRevision());
        store.activateCalendarVersion(TENANT, CALENDAR, 1, "publisher", START, calendarRevision());
        store.createPolicy(new SlaPolicyIdentity(POLICY, TENANT, "lag-policy", "Lag policy", PolicyStatus.DRAFT,
            null, "designer", START, START, 1));
        store.savePolicyVersion(policy(true, TENANT, 1, false), policyRevision());
        store.publishPolicyVersion(TENANT, POLICY, 1, "publisher", START, policyRevision());
        store.activatePolicyVersion(TENANT, POLICY, 1, "publisher", START, policyRevision());
        var different = CalendarSnapshot.of(CALENDAR, TENANT, 2, "Asia/Shanghai",
            Map.of(DayOfWeek.MONDAY, List.of(new WorkingInterval(LocalTime.of(12, 0), LocalTime.of(13, 0)))),
            Map.of(), "e".repeat(64));
        store.saveCalendarVersion(new CalendarVersion(CALENDAR, TENANT, 2, null, null, different,
            CalendarStatus.DRAFT, false, null, null, START, START), calendarRevision());
        store.publishCalendarVersion(TENANT, CALENDAR, 2, "publisher", START, calendarRevision());
        store.activateCalendarVersion(TENANT, CALENDAR, 2, "publisher", START, calendarRevision());
        jdbc.update("""
            insert into ap_sla_instance (sla_instance_id,tenant_id,approval_instance_id,task_id,collaboration_participant_id,
            definition_key,task_definition_key,target_type,policy_id,policy_version,calendar_id,calendar_version,time_zone,
            responsible_user_id,original_responsible_user_id,started_at,due_at,next_reminder_at,overdue_at,paused_at,
            pause_reason,accumulated_paused_millis,terminal_at,terminal_reason,status,last_action_sequence,
            request_id,trace_id,version,created_at,updated_at)
            values (?,?,?,?,null,'purchasePayment','managerApproval','TASK',?,1,?,1,'Asia/Shanghai','owner-a','owner-a',
            ?,?,null,?,null,null,0,null,null,'ACTIVE',0,'request-lag','trace-lag',1,?,?)
            """, SLA, TENANT, INSTANCE, TASK, POLICY, CALENDAR, start, Timestamp.from(DEADLINE), Timestamp.from(DEADLINE), start, start);
    }
    private long calendarRevision() { return store.findCalendar(TENANT, CALENDAR).orElseThrow().version(); }
    private long policyRevision() { return store.findPolicy(TENANT, POLICY).orElseThrow().version(); }
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        MutableClock(Instant value) { now = new AtomicReference<>(value); }
        void set(Instant value) { now.set(value); }
        @Override public Instant instant() { return now.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("fixture is UTC only");
            return this;
        }
    }
}
