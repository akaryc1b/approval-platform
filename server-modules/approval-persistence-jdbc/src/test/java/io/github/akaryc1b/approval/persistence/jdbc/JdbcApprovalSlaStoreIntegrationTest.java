package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.CalendarSnapshot;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.DayOverride;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.WorkingInterval;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.AutomaticAction;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarIdentity;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarVersion;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.PolicyStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.ResponsibilityChange;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.ResponsibilityChangeSource;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaDurationMode;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstance;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstanceCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaPolicyIdentity;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaPolicyVersion;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTargetType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTerminalReason;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalSlaStoreIntegrationTest {

    private static final String TENANT_ID = "tenant-jdbc-sla";
    private static final String OTHER_TENANT_ID = "tenant-jdbc-other";
    private static final UUID CALENDAR_ID = UUID.fromString("51000000-0000-0000-0000-000000000001");
    private static final UUID POLICY_ID = UUID.fromString("52000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_POLICY_ID = UUID.fromString("52000000-0000-0000-0000-000000000002");
    private static final UUID APPROVAL_INSTANCE_ID =
        UUID.fromString("53000000-0000-0000-0000-000000000001");
    private static final UUID TASK_ID = UUID.fromString("54000000-0000-0000-0000-000000000001");
    private static final UUID SLA_INSTANCE_ID =
        UUID.fromString("55000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-07-22T10:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_sla_store_test")
        .withUsername("approval")
        .withPassword("approval");

    private JdbcTemplate jdbc;
    private JdbcApprovalSlaStore store;
    private DataSourceTransactionManager transactionManager;

    @BeforeEach
    void migrateFreshSchema() {
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
        transactionManager = new DataSourceTransactionManager(dataSource);
        store = new JdbcApprovalSlaStore(dataSource, transactionManager);
    }

    @Test
    void calendarVersionsAreImmutableTenantIsolatedAndSingleActive() {
        CalendarIdentity created = store.createCalendar(calendarIdentity());
        assertEquals(1, created.version());

        CalendarVersion savedV1 = store.saveCalendarVersion(calendarVersion(1, "calendar-hash-v1"), 1);
        assertEquals(CalendarStatus.DRAFT, savedV1.status());
        CalendarVersion publishedV1 = store.publishCalendarVersion(
            TENANT_ID,
            CALENDAR_ID,
            1,
            "publisher-a",
            NOW.plusSeconds(10),
            2
        );
        assertTrue(publishedV1.immutable());
        CalendarIdentity activeV1 = store.activateCalendarVersion(
            TENANT_ID,
            CALENDAR_ID,
            1,
            "activator-a",
            NOW.plusSeconds(20),
            3
        );
        assertEquals(1, activeV1.activeVersion());
        assertEquals(CalendarStatus.ACTIVE, activeV1.status());

        assertThrows(
            SlaConflictException.class,
            () -> store.saveCalendarVersion(calendarVersion(1, "calendar-hash-mutated"), 4)
        );
        assertTrue(store.findCalendar(OTHER_TENANT_ID, CALENDAR_ID).isEmpty());

        store.saveCalendarVersion(calendarVersion(2, "calendar-hash-v2"), 4);
        store.publishCalendarVersion(
            TENANT_ID,
            CALENDAR_ID,
            2,
            "publisher-b",
            NOW.plusSeconds(30),
            5
        );
        CalendarIdentity activeV2 = store.activateCalendarVersion(
            TENANT_ID,
            CALENDAR_ID,
            2,
            "activator-b",
            NOW.plusSeconds(40),
            6
        );

        assertEquals(2, activeV2.activeVersion());
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from ap_work_calendar_version where tenant_id=? and status='ACTIVE'",
            Integer.class,
            TENANT_ID
        ));
        assertEquals("INACTIVE", jdbc.queryForObject(
            """
            select status from ap_work_calendar_version
            where tenant_id=? and calendar_id=? and calendar_version=1
            """,
            String.class,
            TENANT_ID,
            CALENDAR_ID
        ));
        assertThrows(
            SlaConflictException.class,
            () -> store.activateCalendarVersion(
                TENANT_ID,
                CALENDAR_ID,
                1,
                "stale-activator",
                NOW.plusSeconds(50),
                3
            )
        );
    }

    @Test
    void policyActivationUsesPublishedCalendarSnapshotAndKeepsOneActiveTarget() {
        activateCalendarVersionOne();

        SlaPolicyIdentity first = store.createPolicy(policyIdentity(POLICY_ID, "policy-primary"));
        store.savePolicyVersion(workingPolicy(POLICY_ID, 1, "policy-hash-primary"), first.version());
        store.publishPolicyVersion(
            TENANT_ID,
            POLICY_ID,
            1,
            "publisher-a",
            NOW.plusSeconds(30),
            2
        );
        store.activatePolicyVersion(
            TENANT_ID,
            POLICY_ID,
            1,
            "activator-a",
            NOW.plusSeconds(40),
            3
        );

        assertTrue(store.findEffectivePolicy(
            TENANT_ID,
            "purchasePayment",
            null,
            "managerApproval",
            SlaTargetType.TASK
        ).isPresent());
        assertThrows(
            SlaConflictException.class,
            () -> store.savePolicyVersion(
                workingPolicy(POLICY_ID, 1, "policy-hash-mutated"),
                4
            )
        );

        SlaPolicyIdentity second = store.createPolicy(
            policyIdentity(SECOND_POLICY_ID, "policy-secondary")
        );
        store.savePolicyVersion(
            workingPolicy(SECOND_POLICY_ID, 1, "policy-hash-secondary"),
            second.version()
        );
        store.publishPolicyVersion(
            TENANT_ID,
            SECOND_POLICY_ID,
            1,
            "publisher-b",
            NOW.plusSeconds(50),
            2
        );
        store.activatePolicyVersion(
            TENANT_ID,
            SECOND_POLICY_ID,
            1,
            "activator-b",
            NOW.plusSeconds(60),
            3
        );

        assertEquals(1, jdbc.queryForObject(
            """
            select count(*) from ap_sla_policy_version
            where tenant_id=? and definition_key='purchasePayment'
              and task_definition_key='managerApproval' and target_type='TASK' and status='ACTIVE'
            """,
            Integer.class,
            TENANT_ID
        ));
        assertEquals("INACTIVE", jdbc.queryForObject(
            "select status from ap_sla_policy where tenant_id=? and policy_id=?",
            String.class,
            TENANT_ID,
            POLICY_ID
        ));
        assertTrue(store.findPolicy(OTHER_TENANT_ID, POLICY_ID).isEmpty());
    }

    @Test
    void slaInstancesSupportIdempotencyCasHistoryQueriesIsolationAndRollback() {
        createPublishedNaturalPolicy();
        seedApprovalEvidence();
        SlaInstance original = taskSla(SLA_INSTANCE_ID, "request-create");

        assertEquals(1, store.createInstances(List.of(original)));
        assertEquals(0, store.createInstances(List.of(original)));
        assertTrue(store.findActiveTaskInstance(TENANT_ID, TASK_ID).isPresent());
        assertTrue(store.findVisibleTaskSla(TENANT_ID, TASK_ID, "owner-a").isPresent());
        assertTrue(store.findVisibleTaskSla(TENANT_ID, TASK_ID, "unknown-user").isEmpty());
        assertTrue(store.findInstance(OTHER_TENANT_ID, SLA_INSTANCE_ID).isEmpty());

        SlaInstance paused = store.pause(
            TENANT_ID,
            SLA_INSTANCE_ID,
            1,
            NOW.plus(Duration.ofMinutes(10)),
            "planned maintenance"
        );
        assertEquals(SlaStatus.PAUSED, paused.status());
        assertThrows(
            SlaConflictException.class,
            () -> store.pause(
                TENANT_ID,
                SLA_INSTANCE_ID,
                1,
                NOW.plus(Duration.ofMinutes(11)),
                "stale pause"
            )
        );

        Instant resumedDueAt = NOW.plus(Duration.ofHours(3));
        SlaInstance resumed = store.resume(
            TENANT_ID,
            SLA_INSTANCE_ID,
            2,
            resumedDueAt,
            resumedDueAt.minus(Duration.ofMinutes(30)),
            resumedDueAt.plus(Duration.ofMinutes(15)),
            Duration.ofMinutes(20),
            NOW.plus(Duration.ofMinutes(20))
        );
        assertEquals(SlaStatus.ACTIVE, resumed.status());
        assertEquals(3, resumed.version());

        ResponsibilityChange change = new ResponsibilityChange(
            UUID.fromString("56000000-0000-0000-0000-000000000001"),
            SLA_INSTANCE_ID,
            TENANT_ID,
            "owner-a",
            "owner-b",
            ResponsibilityChangeSource.MANUAL_TRANSFER,
            "manual workload transfer",
            "operator-a",
            NOW.plus(Duration.ofMinutes(30)),
            "request-transfer",
            "trace-transfer"
        );
        SlaInstance reassigned = store.changeResponsibility(change, 3);
        assertEquals("owner-b", reassigned.responsibleUserId());
        assertEquals(4, reassigned.version());
        assertEquals(1, store.findResponsibilityChanges(TENANT_ID, SLA_INSTANCE_ID, 20).size());
        assertEquals(1, store.findByRequestId(TENANT_ID, "request-transfer", 20, 0).total());
        assertEquals(1, store.findUpcoming(
            TENANT_ID,
            NOW,
            NOW.plus(Duration.ofHours(4)),
            20,
            0
        ).total());
        assertEquals(1, store.findInstances(new SlaInstanceCriteria(
            TENANT_ID,
            SlaStatus.ACTIVE,
            "owner-b",
            NOW.plus(Duration.ofHours(4)),
            NOW.minus(Duration.ofHours(1)),
            null,
            20,
            0
        )).total());

        assertEquals(1, store.terminalTask(
            TENANT_ID,
            TASK_ID,
            SlaTerminalReason.TASK_COMPLETED,
            NOW.plus(Duration.ofMinutes(40))
        ));
        SlaInstance terminal = store.findInstance(TENANT_ID, SLA_INSTANCE_ID).orElseThrow();
        assertEquals(SlaStatus.TERMINAL, terminal.status());
        assertThrows(
            SlaConflictException.class,
            () -> store.pause(
                TENANT_ID,
                SLA_INSTANCE_ID,
                terminal.version(),
                NOW.plus(Duration.ofMinutes(50)),
                "terminal pause"
            )
        );

        UUID rollbackSlaId = UUID.fromString("55000000-0000-0000-0000-000000000002");
        SlaInstance rollbackInstance = processSla(rollbackSlaId, "request-rollback");
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transactions.execute(status -> {
            assertEquals(1, store.createInstances(List.of(rollbackInstance)));
            throw new IllegalStateException("force rollback");
        }));
        assertFalse(store.findInstance(TENANT_ID, rollbackSlaId).isPresent());
    }

    private CalendarIdentity activateCalendarVersionOne() {
        store.createCalendar(calendarIdentity());
        store.saveCalendarVersion(calendarVersion(1, "calendar-hash-v1"), 1);
        store.publishCalendarVersion(
            TENANT_ID,
            CALENDAR_ID,
            1,
            "publisher-a",
            NOW.plusSeconds(10),
            2
        );
        return store.activateCalendarVersion(
            TENANT_ID,
            CALENDAR_ID,
            1,
            "activator-a",
            NOW.plusSeconds(20),
            3
        );
    }

    private void createPublishedNaturalPolicy() {
        SlaPolicyIdentity identity = store.createPolicy(policyIdentity(POLICY_ID, "policy-instance"));
        store.savePolicyVersion(naturalPolicy(), identity.version());
        store.publishPolicyVersion(
            TENANT_ID,
            POLICY_ID,
            1,
            "publisher-instance",
            NOW.plusSeconds(10),
            2
        );
        store.activatePolicyVersion(
            TENANT_ID,
            POLICY_ID,
            1,
            "activator-instance",
            NOW.plusSeconds(20),
            3
        );
    }

    private void seedApprovalEvidence() {
        jdbc.update(
            """
            insert into ap_definition_version (
                tenant_id, definition_key, definition_version,
                form_key, form_version, compiler_version, content_hash,
                deployment_id, engine_definition_id, engine_version,
                published_by, published_at
            ) values (?, 'purchasePayment', 1, 'purchasePayment', 1, 'compiler-v1',
                repeat('a', 64), 'deployment-sla', 'engine-definition-sla', 1,
                'test-publisher', ?)
            """,
            TENANT_ID,
            NOW
        );
        jdbc.update(
            """
            insert into ap_approval_instance (
                instance_id, tenant_id, business_key, engine_instance_id,
                definition_key, definition_version, form_key, form_version,
                compiler_version, content_hash, initiator_id, amount, supplier,
                purchase_order_reference, attachment_ids_json, assignee_snapshot_json,
                request_hash, status, version, created_at, updated_at
            ) values (?, ?, 'SLA-JDBC-1', 'engine-instance-sla',
                'purchasePayment', 1, 'purchasePayment', 1,
                'compiler-v1', repeat('a', 64), 'initiator-a', 100, 'supplier-a',
                'PO-SLA-1', '[]'::jsonb, '{}'::jsonb,
                repeat('b', 64), 'RUNNING', 1, ?, ?)
            """,
            APPROVAL_INSTANCE_ID,
            TENANT_ID,
            NOW,
            NOW
        );
        jdbc.update(
            """
            insert into ap_approval_task (
                task_id, instance_id, tenant_id, engine_task_id,
                task_definition_key, task_name, assignee_id, status,
                version, created_at, updated_at, completed_at
            ) values (?, ?, ?, 'engine-task-sla',
                'managerApproval', 'Manager approval', 'owner-a', 'PENDING',
                1, ?, ?, null)
            """,
            TASK_ID,
            APPROVAL_INSTANCE_ID,
            TENANT_ID,
            NOW,
            NOW
        );
    }

    private static CalendarIdentity calendarIdentity() {
        return new CalendarIdentity(
            CALENDAR_ID,
            TENANT_ID,
            "calendar-main",
            "Primary work calendar",
            "Asia/Shanghai",
            CalendarStatus.DRAFT,
            null,
            "designer-a",
            NOW,
            NOW,
            1
        );
    }

    private static CalendarVersion calendarVersion(int version, String contentHash) {
        CalendarSnapshot snapshot = CalendarSnapshot.of(
            CALENDAR_ID,
            TENANT_ID,
            version,
            "Asia/Shanghai",
            Map.of(
                DayOfWeek.MONDAY,
                List.of(
                    new WorkingInterval(LocalTime.of(9, 0), LocalTime.of(12, 0)),
                    new WorkingInterval(LocalTime.of(13, 0), LocalTime.of(18, 0))
                )
            ),
            Map.of(LocalDate.of(2026, 10, 1), DayOverride.holiday()),
            contentHash
        );
        return new CalendarVersion(
            CALENDAR_ID,
            TENANT_ID,
            version,
            NOW,
            NOW.plus(Duration.ofDays(365)),
            snapshot,
            CalendarStatus.DRAFT,
            false,
            null,
            null,
            NOW,
            NOW
        );
    }

    private static SlaPolicyIdentity policyIdentity(UUID policyId, String key) {
        return new SlaPolicyIdentity(
            policyId,
            TENANT_ID,
            key,
            "Policy " + key,
            PolicyStatus.DRAFT,
            null,
            "designer-a",
            NOW,
            NOW,
            1
        );
    }

    private static SlaPolicyVersion workingPolicy(UUID policyId, int version, String contentHash) {
        return new SlaPolicyVersion(
            policyId,
            TENANT_ID,
            version,
            "purchasePayment",
            null,
            "managerApproval",
            SlaTargetType.TASK,
            SlaDurationMode.WORKING_TIME,
            Duration.ofHours(8),
            CALENDAR_ID,
            1,
            Duration.ofHours(1),
            Duration.ofMinutes(30),
            3,
            Duration.ofMinutes(15),
            null,
            null,
            AutomaticAction.NONE,
            true,
            contentHash,
            PolicyStatus.DRAFT,
            false,
            null,
            null,
            NOW,
            NOW
        );
    }

    private static SlaPolicyVersion naturalPolicy() {
        return new SlaPolicyVersion(
            POLICY_ID,
            TENANT_ID,
            1,
            "purchasePayment",
            null,
            "managerApproval",
            SlaTargetType.TASK,
            SlaDurationMode.NATURAL_TIME,
            Duration.ofHours(2),
            null,
            null,
            Duration.ofMinutes(30),
            null,
            1,
            Duration.ofMinutes(15),
            null,
            null,
            AutomaticAction.NONE,
            true,
            "policy-instance-hash",
            PolicyStatus.DRAFT,
            false,
            null,
            null,
            NOW,
            NOW
        );
    }

    private static SlaInstance taskSla(UUID slaInstanceId, String requestId) {
        return new SlaInstance(
            slaInstanceId,
            TENANT_ID,
            APPROVAL_INSTANCE_ID,
            TASK_ID,
            null,
            "purchasePayment",
            "managerApproval",
            SlaTargetType.TASK,
            POLICY_ID,
            1,
            null,
            null,
            "UTC",
            "owner-a",
            "owner-a",
            NOW,
            NOW.plus(Duration.ofHours(2)),
            NOW.plus(Duration.ofMinutes(90)),
            NOW.plus(Duration.ofMinutes(135)),
            null,
            null,
            Duration.ZERO,
            null,
            null,
            SlaStatus.ACTIVE,
            0,
            requestId,
            "trace-create",
            1,
            NOW,
            NOW
        );
    }

    private static SlaInstance processSla(UUID slaInstanceId, String requestId) {
        return new SlaInstance(
            slaInstanceId,
            TENANT_ID,
            APPROVAL_INSTANCE_ID,
            null,
            null,
            "purchasePayment",
            null,
            SlaTargetType.PROCESS,
            POLICY_ID,
            1,
            null,
            null,
            "UTC",
            "initiator-a",
            "initiator-a",
            NOW,
            NOW.plus(Duration.ofHours(2)),
            NOW.plus(Duration.ofMinutes(90)),
            NOW.plus(Duration.ofMinutes(135)),
            null,
            null,
            Duration.ZERO,
            null,
            null,
            SlaStatus.ACTIVE,
            0,
            requestId,
            "trace-rollback",
            1,
            NOW,
            NOW
        );
    }
}
