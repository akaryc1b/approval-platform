package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalConsistencyService;
import io.github.akaryc1b.approval.application.ApprovalOperationalFailureService;
import io.github.akaryc1b.approval.application.ApprovalOperationalFailureService.ReplayCommand;
import io.github.akaryc1b.approval.application.ApprovalOperationalFailureService.ReplayItem;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationChannel;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationEventType;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationIntent;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationStatus;
import io.github.akaryc1b.approval.application.port.ApprovalOperationalFailureStore.FailureCategory;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.integration.jdbc.JdbcOutboxRepository;
import io.github.akaryc1b.approval.integration.outbox.OutboxMessage;
import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector.BusinessEvent;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalCrossModuleFaultIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-20T18:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_fault_matrix_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;
    private JdbcTransactionManager transactionManager;
    private JdbcIdempotencyGuard idempotency;
    private JdbcApprovalNotificationStore notificationStore;
    private JdbcOutboxRepository outboxRepository;
    private JdbcAuditEventSink realAudit;
    private ApprovalOperationalFailureService faultingReplayService;

    @BeforeAll
    static void migrateDatabase() {
        dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            truncate table
                ap_outbox_delivery_attempt,
                ap_outbox,
                ap_consistency_finding,
                ap_consistency_check,
                ap_notification_delivery_attempt,
                ap_notification_intent,
                ap_notification_preference,
                ap_notification_user_setting,
                ap_audit_event,
                ap_audit_chain_state,
                ap_command_idempotency
            cascade
            """);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        transactionManager = new JdbcTransactionManager(dataSource);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        idempotency = new JdbcIdempotencyGuard(
            dataSource,
            objectMapper,
            transactionManager,
            clock
        );
        notificationStore = new JdbcApprovalNotificationStore(
            dataSource,
            objectMapper,
            transactionManager
        );
        outboxRepository = new JdbcOutboxRepository(dataSource, objectMapper);
        realAudit = new JdbcAuditEventSink(dataSource, objectMapper, transactionManager);
        ApprovalConsistencyService consistency = new ApprovalConsistencyService(
            idempotency,
            new JdbcApprovalConsistencyStore(dataSource, objectMapper, transactionManager),
            realAudit,
            clock,
            UUID::randomUUID
        );
        faultingReplayService = new ApprovalOperationalFailureService(
            idempotency,
            new JdbcApprovalOperationalFailureStore(dataSource),
            notificationStore,
            consistency,
            event -> {
                throw new IllegalStateException("audit sink unavailable");
            },
            clock,
            UUID::randomUUID
        );
    }

    @Test
    void notificationReplayRollsBackWhenAuditAppendFails() {
        UUID sourceId = deadNotification("tenant-a");

        assertThrows(IllegalStateException.class, () -> faultingReplayService.replay(
            replayCommand(FailureCategory.NOTIFICATION_DELIVERY, sourceId, "notification")
        ));

        assertEquals("DEAD_LETTER", notificationValue(sourceId, "status"));
        assertEquals(1, notificationNumber(sourceId, "attempt_count"));
        assertEquals(0, count("ap_command_idempotency"));
        assertEquals(0, countAudit("OPERATIONAL_FAILURE_REPLAYED"));
    }

    @Test
    void outboxReplayRollsBackWhenAuditAppendFails() {
        UUID sourceId = deadOutbox("tenant-a");

        assertThrows(IllegalStateException.class, () -> faultingReplayService.replay(
            replayCommand(FailureCategory.BUSINESS_OUTBOX, sourceId, "outbox")
        ));

        assertEquals("DEAD", outboxValue(sourceId, "status"));
        assertEquals(1, outboxNumber(sourceId, "attempts"));
        assertEquals(0, outboxNumber(sourceId, "replay_count"));
        assertEquals(1, countAttempts(sourceId));
        assertEquals(0, count("ap_command_idempotency"));
    }

    @Test
    void consistencyReplayRollsBackNestedCheckAndAuditWhenOuterAuditFails() {
        UUID sourceId = failedConsistency("tenant-a");

        assertThrows(IllegalStateException.class, () -> faultingReplayService.replay(
            replayCommand(FailureCategory.CONSISTENCY_CHECK, sourceId, "consistency")
        ));

        assertEquals(1, count("ap_consistency_check"));
        assertEquals("FAILED", consistencyStatus(sourceId));
        assertEquals(0, count("ap_audit_event"));
        assertEquals(0, count("ap_audit_chain_state"));
        assertEquals(0, count("ap_command_idempotency"));
    }

    @Test
    void outboxAttemptEvidenceConflictDoesNotHalfCommitStateTransition() {
        UUID sourceId = deadOutbox("tenant-a");
        JdbcApprovalOperationalFailureStore failureStore =
            new JdbcApprovalOperationalFailureStore(dataSource);
        assertTrue(failureStore.replayOutboxDead(
            "tenant-a",
            sourceId,
            "operator-1",
            "fault-replay-request",
            NOW.plusSeconds(2)
        ));
        var claimed = outboxRepository.claimDue(
            NOW.plusSeconds(2),
            1,
            "worker-conflict",
            Duration.ofMinutes(5)
        ).getFirst();

        assertThrows(RuntimeException.class, () -> outboxRepository.markDead(
            sourceId,
            claimed.workerId(),
            1,
            "callback unavailable",
            NOW.plusSeconds(3)
        ));

        assertEquals("IN_FLIGHT", outboxValue(sourceId, "status"));
        assertEquals(0, outboxNumber(sourceId, "attempts"));
        assertEquals("worker-conflict", outboxValue(sourceId, "locked_by"));
        assertEquals(1, countAttempts(sourceId));
    }

    @Test
    void idempotencyResultEncodingFailureRollsBackCommandSideEffects() {
        RequestContext context = context("tenant-a", "serialization-fault");

        assertThrows(IllegalArgumentException.class, () -> idempotency.execute(
            context,
            "approval.fault.serialization.v1",
            sha256("payload"),
            FaultyResult.class,
            () -> {
                failedConsistency("tenant-a");
                return new FaultyResult();
            }
        ));

        assertEquals(0, count("ap_consistency_check"));
        assertEquals(0, count("ap_command_idempotency"));
    }

    @Test
    void duplicateAuditEventDoesNotAdvanceTenantChainState() {
        UUID eventId = UUID.randomUUID();
        AuditEvent event = new AuditEvent(
            eventId,
            "tenant-a",
            "operator-1",
            "FAULT_MATRIX_EVENT",
            "FAULT_MATRIX",
            "aggregate-1",
            "request-1",
            "trace-1",
            NOW,
            Map.of("mode", "baseline")
        );
        realAudit.append(event);

        assertThrows(RuntimeException.class, () -> realAudit.append(new AuditEvent(
            eventId,
            "tenant-a",
            "operator-1",
            "FAULT_MATRIX_EVENT",
            "FAULT_MATRIX",
            "aggregate-2",
            "request-2",
            "trace-2",
            NOW.plusSeconds(1),
            Map.of("mode", "duplicate")
        )));

        assertEquals(1, count("ap_audit_event"));
        assertEquals(1, chainSequence("tenant-a"));
        assertTrue(realAudit.verify(
            new io.github.akaryc1b.approval.application.port.ApprovalAuditStore.AuditIntegrityCriteria(
                "tenant-a",
                NOW.minusSeconds(1),
                NOW.plusSeconds(10)
            )
        ).valid());
    }

    private ReplayCommand replayCommand(
        FailureCategory category,
        UUID sourceId,
        String operation
    ) {
        return new ReplayCommand(
            context("tenant-a", operation),
            new ReplayItem(category, sourceId)
        );
    }

    private UUID deadNotification(String tenantId) {
        UUID intentId = UUID.randomUUID();
        NotificationIntent intent = new NotificationIntent(
            intentId,
            tenantId,
            NotificationEventType.TASK_ASSIGNED,
            NotificationChannel.CONNECTOR,
            "recipient-1",
            "approval-platform",
            null,
            null,
            "APPROVAL_INSTANCE",
            "instance-1",
            "task-assigned",
            1,
            "title",
            "body",
            Map.of("connectorKey", "connector-a"),
            "business-event-" + intentId,
            false,
            NotificationStatus.PENDING,
            0,
            5,
            NOW,
            null,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW,
            1
        );
        assertEquals(1, notificationStore.enqueue(List.of(intent)));
        NotificationIntent claimed = notificationStore.claimDue(
            NOW,
            1,
            "notification-worker",
            NOW.plusSeconds(30)
        ).getFirst();
        notificationStore.markFailed(
            tenantId,
            intentId,
            claimed.version(),
            UUID.randomUUID(),
            NOW,
            NOW.plusSeconds(1),
            false,
            NOW.plusSeconds(1),
            "DELIVERY_FAILED",
            "provider unavailable"
        );
        return intentId;
    }

    private UUID deadOutbox(String tenantId) {
        UUID sourceId = pendingOutbox(tenantId);
        var claimed = outboxRepository.claimDue(
            NOW,
            1,
            "outbox-worker",
            Duration.ofMinutes(5)
        ).getFirst();
        assertTrue(outboxRepository.markDead(
            sourceId,
            claimed.workerId(),
            1,
            "callback unavailable",
            NOW.plusSeconds(1)
        ));
        return sourceId;
    }

    private UUID pendingOutbox(String tenantId) {
        UUID sourceId = UUID.randomUUID();
        ConnectorContext connectorContext = new ConnectorContext(
            "erp-a",
            tenantId,
            "request-" + sourceId,
            "trace-" + sourceId,
            NOW
        );
        BusinessEvent event = new BusinessEvent(
            UUID.randomUUID(),
            "purchase-payment.completed.v1",
            "APPROVAL_INSTANCE",
            "instance-1",
            NOW,
            "idempotency-" + sourceId,
            Map.of("instanceId", "instance-1")
        );
        outboxRepository.append(new OutboxMessage(
            sourceId,
            connectorContext,
            event,
            NOW,
            NOW
        ));
        return sourceId;
    }

    private UUID failedConsistency(String tenantId) {
        UUID checkId = UUID.randomUUID();
        jdbc.update(
            """
            insert into ap_consistency_check (
                check_id, tenant_id, requested_by, request_id, trace_id,
                scope, status, started_at, completed_at, finding_count,
                error_code, error_message, version
            ) values (?, ?, ?, ?, ?, 'TENANT', 'FAILED', ?, ?, 0, ?, ?, 1)
            """,
            checkId,
            tenantId,
            "operator-1",
            "failed-check-" + checkId,
            "trace-failed-" + checkId,
            offset(NOW),
            offset(NOW.plusSeconds(1)),
            "CHECK_FAILED",
            "database timeout"
        );
        return checkId;
    }

    private RequestContext context(String tenantId, String operation) {
        return new RequestContext(
            tenantId,
            "operator-1",
            operation + "-request",
            operation + "-key",
            operation + "-trace"
        );
    }

    private String notificationValue(UUID sourceId, String column) {
        return jdbc.queryForObject(
            "select " + column + " from ap_notification_intent where intent_id = ?",
            String.class,
            sourceId
        );
    }

    private int notificationNumber(UUID sourceId, String column) {
        Integer value = jdbc.queryForObject(
            "select " + column + " from ap_notification_intent where intent_id = ?",
            Integer.class,
            sourceId
        );
        return value == null ? 0 : value;
    }

    private String outboxValue(UUID sourceId, String column) {
        return jdbc.queryForObject(
            "select " + column + " from ap_outbox where id = ?",
            String.class,
            sourceId
        );
    }

    private int outboxNumber(UUID sourceId, String column) {
        Integer value = jdbc.queryForObject(
            "select " + column + " from ap_outbox where id = ?",
            Integer.class,
            sourceId
        );
        return value == null ? 0 : value;
    }

    private String consistencyStatus(UUID sourceId) {
        return jdbc.queryForObject(
            "select status from ap_consistency_check where check_id = ?",
            String.class,
            sourceId
        );
    }

    private long chainSequence(String tenantId) {
        Long value = jdbc.queryForObject(
            "select last_sequence from ap_audit_chain_state where tenant_id = ?",
            Long.class,
            tenantId
        );
        return value == null ? 0 : value;
    }

    private int countAttempts(UUID sourceId) {
        Integer value = jdbc.queryForObject(
            "select count(*) from ap_outbox_delivery_attempt where outbox_id = ?",
            Integer.class,
            sourceId
        );
        return value == null ? 0 : value;
    }

    private int countAudit(String action) {
        Integer value = jdbc.queryForObject(
            "select count(*) from ap_audit_event where action = ?",
            Integer.class,
            action
        );
        return value == null ? 0 : value;
    }

    private int count(String table) {
        Integer value = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static final class FaultyResult {
        public FaultyResult getSelf() {
            return this;
        }
    }
}
