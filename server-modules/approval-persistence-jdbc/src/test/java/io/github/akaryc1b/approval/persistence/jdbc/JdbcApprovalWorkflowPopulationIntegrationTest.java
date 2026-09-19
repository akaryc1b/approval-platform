package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.ApprovalWorkflowPopulationReader.Snapshot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real PostgreSQL and projection writes. SLA rows are explicit fixtures, not engine-timer acceptance. */
@Testcontainers
class JdbcApprovalWorkflowPopulationIntegrationTest {
    @Container
    static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES =
        new org.testcontainers.postgresql.PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("workflow_population_test").withUsername("approval")
            .withPassword(UUID.randomUUID().toString());
    private static DataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcApprovalProjectionStore store;
    private JdbcApprovalSlaStore slas;
    private JdbcApprovalWorkflowPopulationReader reader;
    private TransactionTemplate transaction;
    private Instant now;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void reset() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("truncate table ap_approval_task, ap_approval_instance, ap_definition_version, ap_sla_policy cascade");
        now = jdbc.queryForObject("select statement_timestamp()", OffsetDateTime.class).toInstant();
        var manager = new JdbcTransactionManager(dataSource);
        transaction = new TransactionTemplate(manager); transaction.setTimeout(10);
        store = new JdbcApprovalProjectionStore(dataSource, new ObjectMapper().findAndRegisterModules());
        slas = new JdbcApprovalSlaStore(dataSource, manager);
        reader = new JdbcApprovalWorkflowPopulationReader(dataSource);
        for (String tenant : List.of("tenant-a", "tenant-b")) {
            store.saveDefinition(new PublishedDefinition(tenant, "population", 1, "population-form", 1,
                "1", "a".repeat(64), "deployment-" + tenant, "engine-" + tenant, 1, "initiator", now.minusSeconds(86400)));
        }
    }

    @Test
    void emptyAndUncoveredPopulationsAreNotInferredFromCounterHistory() {
        counts(reader.read(), 0,0,0,0,0,0);
        Target target = create("tenant-a");
        var before = store.findInstance("tenant-a", target.instance().instanceId()).orElseThrow();
        counts(reader.read(), 1,0,0,1,0,0);
        assertEquals(before, store.findInstance("tenant-a", target.instance().instanceId()).orElseThrow());
        assertTrue(reader.read().observedAt().compareTo(now) >= 0);
    }

    @Test
    void taskTimeoutDoesNotBecomeProcessTimeoutAndDueIsNotOverdue() {
        Target target = create("tenant-a");
        sla(target, "TASK", now.minusSeconds(60));
        counts(reader.read(), 1,0,0,1,1,1);
        // due_at is already past, but the authoritative overdue_at remains in the future.
        sla(target, "PROCESS", now.plusSeconds(600));
        counts(reader.read(), 1,1,0,1,1,1);
    }

    @Test
    void pauseAndResumeFollowStoredDeadlineWithoutManufacturingTimeoutEvents() {
        Target target = create("tenant-a"); UUID id = sla(target, "PROCESS", now.minusSeconds(60));
        counts(reader.read(), 1,1,1,1,0,0);
        slas.pause("tenant-a", id, 1, now, "fixture pause");
        counts(reader.read(), 1,1,0,1,0,0);
        slas.resume("tenant-a", id, 2, now.plusSeconds(3600), null, now.plusSeconds(4200),
            Duration.ofSeconds(60), now.plusSeconds(60));
        counts(reader.read(), 1,1,0,1,0,0);
        assertEquals(0L, jdbc.queryForObject("select count(*) from ap_outbox", Long.class));
    }

    @Test
    void completingTasksRemainActiveButCanceledTasksDoNot() {
        Target target = create("tenant-a"); sla(target, "TASK", now.minusSeconds(60));
        TaskProjection claimed = transaction.execute(status -> store.claimPendingTask(
            "tenant-a", target.task().taskId(), "assignee", now));
        counts(reader.read(), 1,0,0,1,1,1);
        transaction.executeWithoutResult(status -> store.cancelClaimedTaskAndSynchronize("tenant-a",
            target.instance().instanceId(), claimed.taskId(), claimed.version(), List.of(), now));
        counts(reader.read(), 1,0,0,0,0,0);
    }

    @Test
    void terminalProcessesAndTheirStaleSlaRowsCannotInflateCurrentPopulations() {
        for (InstanceStatus outcome : List.of(InstanceStatus.COMPLETED, InstanceStatus.REJECTED, InstanceStatus.WITHDRAWN)) {
            Target target = create("tenant-a");
            sla(target, "PROCESS", now.minusSeconds(60)); sla(target, "TASK", now.minusSeconds(60));
            counts(reader.read(), 1,1,1,1,1,1);
            transaction.executeWithoutResult(status -> {
                if (outcome == InstanceStatus.WITHDRAWN) {
                    store.withdrawRunningInstance("tenant-a", target.instance().instanceId(), "initiator", now);
                } else {
                    TaskProjection claimed = store.claimPendingTask("tenant-a", target.task().taskId(), "assignee", now);
                    store.completeTaskAndSynchronize("tenant-a", target.instance().instanceId(), claimed.taskId(),
                        claimed.version(), List.of(), outcome, now);
                }
            });
            counts(reader.read(), 0,0,0,0,0,0);
        }
        assertEquals(6L, jdbc.queryForObject("select count(*) from ap_sla_instance where status='ACTIVE'", Long.class));
        assertEquals(2L, jdbc.queryForObject("select count(*) from ap_sla_policy_version where status='ACTIVE'", Long.class));
        assertEquals(List.of(3L, 3L), jdbc.queryForList(
            "select count(*) from ap_sla_instance group by policy_id order by policy_id", Long.class));
    }

    @Test
    void tenantsAndSlaScopesRemainIsolatedAndReaderRestartKeepsDatabaseCounts() {
        Target a = create("tenant-a"), b = create("tenant-b");
        sla(a, "TASK", now.minusSeconds(60)); sla(b, "PROCESS", now.minusSeconds(60));
        counts(reader.read(), 2,1,1,2,1,1);
        var restarted = new JdbcApprovalWorkflowPopulationReader(dataSource);
        counts(restarted.read(), 2,1,1,2,1,1);
        assertFalse(restarted.read().toString().contains("tenant-"));
        transaction.executeWithoutResult(status -> store.withdrawRunningInstance("tenant-a", a.instance().instanceId(), "initiator", now));
        counts(reader.read(), 1,1,1,1,0,0);
    }

    @Test
    void independentSnapshotCannotSeeUncommittedOrRolledBackRows() {
        transaction.executeWithoutResult(status -> {
            Target target = create("tenant-a"); sla(target, "PROCESS", now.minusSeconds(60));
            counts(reader.read(), 0,0,0,0,0,0); status.setRollbackOnly();
        });
        counts(reader.read(), 0,0,0,0,0,0);
        transaction.executeWithoutResult(status -> {
            Target target = create("tenant-a"); sla(target, "PROCESS", now.minusSeconds(60));
            counts(reader.read(), 0,0,0,0,0,0);
        });
        counts(reader.read(), 1,1,1,1,0,0);
    }

    @Test
    void lockedSourceTimesOutAndARealSubsequentReadRecovers() throws Exception {
        create("tenant-a");
        try (var blocker = dataSource.getConnection(); var statement = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            statement.execute("lock table ap_sla_instance in access exclusive mode");
            try {
                assertThrows(IllegalStateException.class, reader::read);
            } finally { blocker.rollback(); }
        }
        counts(reader.read(), 1,0,0,1,0,0);
    }

    private Target create(String tenant) {
        String key = UUID.randomUUID().toString(); Instant started = now.minusSeconds(86400);
        var instance = new InstanceProjection(UUID.randomUUID(), tenant, key, "engine-" + key,
            "population", 1, "population-form", 1, "1", "a".repeat(64), "initiator", BigDecimal.ONE,
            "Fixture", "PO-" + key, List.of(), new AssigneeSnapshot("assignee", "reviewer", List.of("finance"), Map.of()),
            "b".repeat(64), InstanceStatus.RUNNING, 1, started, started);
        var task = new TaskProjection(UUID.randomUUID(), instance.instanceId(), tenant, key,
            "approval", "Approval", "assignee", TaskStatus.PENDING, 1, started, started, null);
        transaction.executeWithoutResult(status -> store.createInstance(instance, List.of(task)));
        return new Target(instance, task);
    }

    private UUID sla(Target target, String scope, Instant overdueAt) {
        assertTrue(scope.equals("PROCESS") || scope.equals("TASK"));
        UUID id = UUID.randomUUID(), policy = UUID.randomUUID();
        var parameters = new MapSqlParameterSource().addValue("id", id).addValue("policy", policy)
            .addValue("tenant", target.instance().tenantId()).addValue("instance", target.instance().instanceId())
            .addValue("task", scope.equals("TASK") ? target.task().taskId() : null)
            .addValue("node", scope.equals("TASK") ? "approval" : null).addValue("scope", scope)
            .addValue("key", "population-" + policy).addValue("started", offset(now.minusSeconds(86400)))
            .addValue("due", offset(overdueAt.minusSeconds(3600))).addValue("overdue", offset(overdueAt));
        var named = new NamedParameterJdbcTemplate(dataSource);
        transaction.executeWithoutResult(status -> {
            // Several instances share one effective policy for each tenant/definition/target scope.
            // Preserve uk_sla_policy_active_target instead of inventing another active policy.
            var existing = named.query("""
                select policy_id from ap_sla_policy_version
                where tenant_id=:tenant and definition_key='population' and release_version=1
                  and task_definition_key is not distinct from cast(:node as varchar)
                  and target_type=:scope and status='ACTIVE'
                """, parameters, (rows, index) -> rows.getObject("policy_id", UUID.class));
            assertTrue(existing.size() <= 1, "fixture must respect the unique effective SLA target");
            if (existing.isEmpty()) {
                named.update("""
                    insert into ap_sla_policy (policy_id,tenant_id,policy_key,display_name,status,active_version,
                        created_by,created_at,updated_at,version)
                    values (:policy,:tenant,:key,'Population','DRAFT',null,'designer',:started,:started,1)
                    """, parameters);
                named.update("""
                    insert into ap_sla_policy_version (policy_id,tenant_id,policy_version,definition_key,release_version,
                        task_definition_key,target_type,duration_mode,duration_millis,calendar_id,calendar_version,
                        calendar_content_hash,time_zone,first_reminder_offset_millis,repeat_reminder_interval_millis,
                        maximum_reminder_count,overdue_offset_millis,escalation_strategy,escalation_target,automatic_action_policy,
                        pause_rules_json,content_hash,status,immutable,published_by,published_at,created_at,updated_at)
                    values (:policy,:tenant,1,'population',1,:node,:scope,'NATURAL_TIME',7200000,null,null,null,'UTC',
                        null,null,0,3600000,null,null,'NONE','{}'::jsonb,repeat('c',64),'ACTIVE',true,'publisher',:started,:started,:started)
                    """, parameters);
                named.update("update ap_sla_policy set status='ACTIVE',active_version=1,version=2 where policy_id=:policy and tenant_id=:tenant", parameters);
            } else {
                parameters.addValue("policy", existing.getFirst());
            }
            named.update("""
                insert into ap_sla_instance (sla_instance_id,tenant_id,approval_instance_id,task_id,collaboration_participant_id,
                    definition_key,task_definition_key,target_type,policy_id,policy_version,calendar_id,calendar_version,time_zone,
                    responsible_user_id,original_responsible_user_id,started_at,due_at,next_reminder_at,overdue_at,paused_at,
                    pause_reason,accumulated_paused_millis,terminal_at,terminal_reason,status,last_action_sequence,
                    request_id,trace_id,version,created_at,updated_at)
                values (:id,:tenant,:instance,:task,null,'population',:node,:scope,:policy,1,null,null,'UTC','assignee','assignee',
                    :started,:due,null,:overdue,null,null,0,null,null,'ACTIVE',0,'request-fixture','trace-fixture',1,:started,:started)
                """, parameters);
        });
        return id;
    }
    private static OffsetDateTime offset(Instant value) { return OffsetDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static void counts(Snapshot actual, long processes, long processCovered, long processOverdue,
                               long tasks, long taskCovered, long taskOverdue) {
        assertEquals(new Snapshot(actual.observedAt(), processes, processCovered, processOverdue,
            tasks, taskCovered, taskOverdue), actual);
    }
    private record Target(InstanceProjection instance, TaskProjection task) { }
}
