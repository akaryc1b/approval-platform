package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalConsistencyService;
import io.github.akaryc1b.approval.application.ApprovalOperationalFailureService;
import io.github.akaryc1b.approval.application.ApprovalOperationalFailureService.BatchReplayCommand;
import io.github.akaryc1b.approval.application.ApprovalOperationalFailureService.ReplayCommand;
import io.github.akaryc1b.approval.application.ApprovalOperationalFailureService.ReplayItem;
import io.github.akaryc1b.approval.application.ApprovalOperationalFailureService.ReplayOutcome;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationChannel;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationEventType;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationIntent;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationStatus;
import io.github.akaryc1b.approval.application.port.ApprovalOperationalFailureStore.FailureCategory;
import io.github.akaryc1b.approval.application.port.ApprovalOperationalFailureStore.FailureKind;
import io.github.akaryc1b.approval.application.port.ApprovalOperationalFailureStore.OperationalFailureNotFoundException;
import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector.BusinessEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.integration.jdbc.JdbcOutboxRepository;
import io.github.akaryc1b.approval.integration.outbox.OutboxMessage;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalOperationalFailureIntegrationTest {

    private static final Instant START = Instant.parse("2026-07-20T16:00:00Z");
    private static final Instant REPLAY_TIME = START.plusSeconds(60);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_operational_failure_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private JdbcApprovalNotificationStore notificationStore;
    private JdbcOutboxRepository outboxRepository;
    private ApprovalOperationalFailureService service;

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
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        JdbcTransactionManager transactionManager = new JdbcTransactionManager(dataSource);
        Clock clock = Clock.fixed(REPLAY_TIME, ZoneOffset.UTC);
        notificationStore = new JdbcApprovalNotificationStore(
            dataSource,
            objectMapper,
            transactionManager
        );
        outboxRepository = new JdbcOutboxRepository(dataSource, objectMapper);
        JdbcApprovalOperationalFailureStore failureStore =
            new JdbcApprovalOperationalFailureStore(dataSource);
        JdbcIdempotencyGuard idempotency = new JdbcIdempotencyGuard(
            dataSource,
            objectMapper,
            transactionManager,
            clock
        );
        JdbcAuditEventSink audit = new JdbcAuditEventSink(
            dataSource,
            objectMapper,
            transactionManager
        );
        ApprovalConsistencyService consistency = new ApprovalConsistencyService(
            idempotency,
            new JdbcApprovalConsistencyStore(dataSource, objectMapper, transactionManager),
            audit,
            clock,
            UUID::randomUUID
        );
        service = new ApprovalOperationalFailureService(
            idempotency,
            failureStore,
            notificationStore,
            consistency,
            audit,
            clock,
            UUID::randomUUID
        );
    }

    @Test
    void unifiedQueryPreservesOwnershipFiltersAndTenantIsolation() {
        UUID notificationId = deadNotification(
            "tenant-a",
            NotificationChannel.CONNECTOR,
            "connector-a"
        );
        UUID outboxId = deadOutbox("tenant-a", "erp-a");
        UUID checkId = failedConsistency("tenant-a");
        deadNotification("tenant-b", NotificationChannel.EMAIL, null);

        var page = service.findFailures("tenant-a", null, null, null, 20, 0);
        assertEquals(3, page.total());
        assertTrue(page.items().stream().anyMatch(item -> item.sourceId().equals(notificationId)));
        assertTrue(page.items().stream().anyMatch(item -> item.sourceId().equals(outboxId)));
        assertTrue(page.items().stream().anyMatch(item -> item.sourceId().equals(checkId)));

        var connector = service.findFailures(
            "tenant-a",
            null,
            FailureKind.CONNECTOR,
            "connector-a",
            20,
            0
        );
        assertEquals(1, connector.total());
        assertEquals(FailureCategory.NOTIFICATION_DELIVERY, connector.items().getFirst().category());

        var business = service.findFailures(
            "tenant-a",
            FailureCategory.BUSINESS_OUTBOX,
            null,
            "erp-a",
            20,
            0
        );
        assertEquals(1, business.total());
        assertEquals(outboxId, business.items().getFirst().sourceId());
    }

    @Test
    void outboxTriggerRecordsAttemptsAndReplayIsConditionalIdempotentAndAudited() {
        UUID sourceId = deadOutbox("tenant-a", "erp-a");
        var attempts = service.findAttempts(
            "tenant-a",
            FailureCategory.BUSINESS_OUTBOX,
            sourceId
        );
        assertEquals(1, attempts.size());
        assertEquals(1, attempts.getFirst().attemptNumber());
        assertEquals("OUTBOX_DEAD", attempts.getFirst().errorCode());

        ReplayCommand command = new ReplayCommand(
            context("tenant-a", "operator-1", "outbox-replay", "outbox-replay-key"),
            new ReplayItem(FailureCategory.BUSINESS_OUTBOX, sourceId)
        );
        var first = service.replay(command);
        var replay = service.replay(command);

        assertEquals(first, replay);
        assertEquals(ReplayOutcome.REPLAYED, first.outcome());
        assertEquals("PENDING", outboxValue(sourceId, "status"));
        assertEquals(0, outboxNumber(sourceId, "attempts"));
        assertEquals(1, outboxNumber(sourceId, "replay_count"));
        assertEquals(1, service.findAttempts(
            "tenant-a",
            FailureCategory.BUSINESS_OUTBOX,
            sourceId
        ).size());
        assertEquals(1, auditCount("OPERATIONAL_FAILURE_REPLAYED"));

        assertThrows(OperationalFailureNotFoundException.class, () -> service.replay(
            new ReplayCommand(
                context("tenant-a", "operator-1", "outbox-again", "outbox-again-key"),
                new ReplayItem(FailureCategory.BUSINESS_OUTBOX, sourceId)
            )
        ));
    }

    @Test
    void notificationReplayUsesExistingStateMachineAndPreservesAttemptEvidence() {
        UUID sourceId = deadNotification(
            "tenant-a",
            NotificationChannel.CONNECTOR,
            "connector-a"
        );
        var result = service.replay(new ReplayCommand(
            context("tenant-a", "operator-1", "notification-replay", "notification-key"),
            new ReplayItem(FailureCategory.NOTIFICATION_DELIVERY, sourceId)
        ));

        assertEquals(ReplayOutcome.REPLAYED, result.outcome());
        assertEquals("PENDING", notificationValue(sourceId, "status"));
        assertEquals(0, notificationNumber(sourceId, "attempt_count"));
        assertEquals(1, service.findAttempts(
            "tenant-a",
            FailureCategory.NOTIFICATION_DELIVERY,
            sourceId
        ).size());
        assertEquals(1, auditCount("OPERATIONAL_FAILURE_REPLAYED"));
    }

    @Test
    void consistencyReplayStartsANewDetectOnlyCheckAndRetainsOriginalFailure() {
        UUID sourceId = failedConsistency("tenant-a");
        var result = service.replay(new ReplayCommand(
            context("tenant-a", "operator-1", "consistency-replay", "consistency-key"),
            new ReplayItem(FailureCategory.CONSISTENCY_CHECK, sourceId)
        ));

        assertEquals(ReplayOutcome.REPLAYED, result.outcome());
        assertNotNull(result.replacementSourceId());
        assertEquals("FAILED", consistencyStatus(sourceId));
        assertEquals("COMPLETED", consistencyStatus(result.replacementSourceId()));
        assertEquals(2, countRows("ap_consistency_check"));
        assertEquals(1, auditCount("OPERATIONAL_FAILURE_REPLAYED"));
        assertEquals(1, auditCount("CONSISTENCY_CHECK_EXECUTED"));
    }

    @Test
    void batchReplayIsBoundedIdempotentAndReportsRejectedItems() {
        UUID notificationId = deadNotification(
            "tenant-a",
            NotificationChannel.EMAIL,
            null
        );
        UUID outboxId = deadOutbox("tenant-a", "erp-a");
        UUID missingId = UUID.randomUUID();
        List<ReplayItem> items = List.of(
            new ReplayItem(FailureCategory.NOTIFICATION_DELIVERY, notificationId),
            new ReplayItem(FailureCategory.BUSINESS_OUTBOX, outboxId),
            new ReplayItem(FailureCategory.CONSISTENCY_CHECK, missingId)
        );
        BatchReplayCommand command = new BatchReplayCommand(
            context("tenant-a", "operator-1", "batch-replay", "batch-replay-key"),
            items
        );

        var first = service.replayBatch(command);
        var replay = service.replayBatch(command);

        assertEquals(first, replay);
        assertEquals(2, first.replayed());
        assertEquals(1, first.rejected());
        assertEquals(1, outboxNumber(outboxId, "replay_count"));
        assertEquals(2, auditCount("OPERATIONAL_FAILURE_REPLAYED"));

        assertThrows(IllegalArgumentException.class, () -> service.replayBatch(
            new BatchReplayCommand(
                context("tenant-a", "operator-1", "duplicate", "duplicate-key"),
                List.of(items.getFirst(), items.getFirst())
            )
        ));
        List<ReplayItem> tooMany = new ArrayList<>();
        for (int index = 0; index < 51; index++) {
            tooMany.add(new ReplayItem(FailureCategory.CONSISTENCY_CHECK, UUID.randomUUID()));
        }
        assertThrows(IllegalArgumentException.class, () -> service.replayBatch(
            new BatchReplayCommand(
                context("tenant-a", "operator-1", "too-many", "too-many-key"),
                tooMany
            )
        ));
    }

    @Test
    void concurrentOutboxReplayAllowsOnlyOneOwningTransition() throws Exception {
        UUID sourceId = deadOutbox("tenant-a", "erp-a");
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Boolean>> calls = List.of(
                () -> replayOutboxSafely(sourceId, "concurrent-a"),
                () -> replayOutboxSafely(sourceId, "concurrent-b")
            );
            long accepted = executor.invokeAll(calls).stream().filter(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }).count();
            assertEquals(1, accepted);
        }
        assertEquals(1, outboxNumber(sourceId, "replay_count"));
        assertEquals(1, auditCount("OPERATIONAL_FAILURE_REPLAYED"));
    }

    @Test
    void deliveredOutboxTransitionAlsoCreatesSuccessfulAttemptEvidence() {
        UUID sourceId = pendingOutbox("tenant-a", "erp-a");
        var claimed = outboxRepository.claimDue(
            START,
            1,
            "worker-success",
            Duration.ofMinutes(5)
        ).getFirst();
        assertTrue(outboxRepository.markDelivered(
            sourceId,
            claimed.workerId(),
            "provider-1",
            200,
            START.plusSeconds(1)
        ));

        var attempts = service.findAttempts(
            "tenant-a",
            FailureCategory.BUSINESS_OUTBOX,
            sourceId
        );
        assertEquals(1, attempts.size());
        assertTrue(attempts.getFirst().successful());
        assertEquals("provider-1", attempts.getFirst().providerReference());
        assertEquals(200, attempts.getFirst().responseCode());
        assertEquals(0, service.findFailures(
            "tenant-a",
            FailureCategory.BUSINESS_OUTBOX,
            null,
            null,
            20,
            0
        ).total());
    }

    private boolean replayOutboxSafely(UUID sourceId, String operation) {
        try {
            service.replay(new ReplayCommand(
                context("tenant-a", "operator-1", operation, operation + "-key"),
                new ReplayItem(FailureCategory.BUSINESS_OUTBOX, sourceId)
            ));
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private UUID deadNotification(
        String tenantId,
        NotificationChannel channel,
        String connectorKey
    ) {
        UUID intentId = UUID.randomUUID();
        NotificationIntent intent = new NotificationIntent(
            intentId,
            tenantId,
            NotificationEventType.TASK_ASSIGNED,
            channel,
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
            connectorKey == null ? Map.of() : Map.of("connectorKey", connectorKey),
            "business-event-" + intentId,
            false,
            NotificationStatus.PENDING,
            0,
            5,
            START,
            null,
            null,
            null,
            null,
            null,
            null,
            START,
            START,
            1
        );
        assertEquals(1, notificationStore.enqueue(List.of(intent)));
        NotificationIntent claimed = notificationStore.claimDue(
            START,
            1,
            "notification-worker",
            START.plusSeconds(30)
        ).getFirst();
        notificationStore.markFailed(
            tenantId,
            intentId,
            claimed.version(),
            UUID.randomUUID(),
            START,
            START.plusSeconds(1),
            false,
            START.plusSeconds(1),
            "DELIVERY_FAILED",
            "provider unavailable"
        );
        return intentId;
    }

    private UUID deadOutbox(String tenantId, String connectorKey) {
        UUID sourceId = pendingOutbox(tenantId, connectorKey);
        var claimed = outboxRepository.claimDue(
            START,
            1,
            "outbox-worker",
            Duration.ofMinutes(5)
        ).getFirst();
        assertTrue(outboxRepository.markDead(
            sourceId,
            claimed.workerId(),
            1,
            "callback unavailable",
            START.plusSeconds(1)
        ));
        return sourceId;
    }

    private UUID pendingOutbox(String tenantId, String connectorKey) {
        UUID sourceId = UUID.randomUUID();
        ConnectorContext connectorContext = new ConnectorContext(
            connectorKey,
            tenantId,
            "request-" + sourceId,
            "trace-" + sourceId,
            START
        );
        BusinessEvent event = new BusinessEvent(
            UUID.randomUUID(),
            "purchase-payment.completed.v1",
            "APPROVAL_INSTANCE",
            "instance-1",
            START,
            "idempotency-" + sourceId,
            Map.of("instanceId", "instance-1")
        );
        outboxRepository.append(new OutboxMessage(
            sourceId,
            connectorContext,
            event,
            START,
            START
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
            offset(START),
            offset(START.plusSeconds(1)),
            "CHECK_FAILED",
            "database timeout"
        );
        return checkId;
    }

    private String consistencyStatus(UUID checkId) {
        return jdbc.queryForObject(
            "select status from ap_consistency_check where check_id = ?",
            String.class,
            checkId
        );
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

    private int auditCount(String action) {
        Integer count = jdbc.queryForObject(
            "select count(*) from ap_audit_event where action = ?",
            Integer.class,
            action
        );
        return count == null ? 0 : count;
    }

    private int countRows(String table) {
        Integer count = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private static RequestContext context(
        String tenantId,
        String operatorId,
        String requestId,
        String idempotencyKey
    ) {
        return new RequestContext(
            tenantId,
            operatorId,
            requestId,
            idempotencyKey,
            "trace-" + requestId
        );
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
