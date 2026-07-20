package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalNotificationService;
import io.github.akaryc1b.approval.application.NotificationAwareAuditEventSink;
import io.github.akaryc1b.approval.application.port.ApprovalConnectorNotificationSender;
import io.github.akaryc1b.approval.application.port.ApprovalEmailNotificationSender;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationChannel;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationEventType;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationPreference;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
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
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalNotificationIntegrationTest {

    private static final Instant START = Instant.parse("2026-07-20T23:30:00Z");
    private static final UUID INSTANCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000902");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_notification_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private JdbcApprovalNotificationStore store;
    private JdbcApprovalProjectionStore projections;
    private MutableClock clock;
    private List<String> connectorDeliveries;
    private ApprovalNotificationService service;

    @BeforeAll
    static void migrateDatabase() {
        dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .load().migrate();
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            truncate table
                ap_notification_delivery_attempt,
                ap_notification_intent,
                ap_notification_preference,
                ap_notification_user_setting,
                ap_audit_event,
                ap_approval_task,
                ap_approval_instance,
                ap_definition_version
            cascade
            """);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        JdbcTransactionManager transactionManager = new JdbcTransactionManager(dataSource);
        store = new JdbcApprovalNotificationStore(dataSource, objectMapper, transactionManager);
        projections = new JdbcApprovalProjectionStore(dataSource, objectMapper);
        clock = new MutableClock(START);
        connectorDeliveries = new ArrayList<>();
        service = new ApprovalNotificationService(
            store,
            projections,
            intent -> {
                connectorDeliveries.add(intent.businessEventKey());
                return ApprovalConnectorNotificationSender.DeliveryResult.delivered(
                    "connector-" + intent.intentId()
                );
            },
            ApprovalEmailNotificationSender.unavailable(),
            clock,
            UUID::randomUUID
        );
        projections.saveDefinition(definition());
        projections.createInstance(instance("tenant-a", INSTANCE_ID), List.of(task(
            "tenant-a",
            INSTANCE_ID,
            TASK_ID,
            "manager-1"
        )));
    }

    @Test
    void defaultsEnableOnlyInAppAndPreferenceDisableSuppressesIntent() {
        var defaults = service.findPreferences("tenant-a", "manager-1");
        assertEquals(0, defaults.version());
        assertTrue(enabled(defaults.preferences(), NotificationEventType.TASK_ASSIGNED, NotificationChannel.IN_APP));
        assertFalse(enabled(
            defaults.preferences(),
            NotificationEventType.TASK_ASSIGNED,
            NotificationChannel.CONNECTOR
        ));

        assertEquals(1, service.enqueueFromAudit(started("tenant-a", INSTANCE_ID)));
        assertEquals(1, service.findHistory("tenant-a", "manager-1", false, 20, 0).total());

        List<NotificationPreference> preferences = new ArrayList<>(defaults.preferences());
        replace(preferences, NotificationEventType.TASK_ASSIGNED, NotificationChannel.IN_APP, false);
        var saved = service.updatePreferences(new ApprovalNotificationService.UpdatePreferencesCommand(
            "tenant-a",
            "manager-1",
            "UTC",
            false,
            null,
            null,
            true,
            false,
            0,
            preferences
        ));
        assertEquals(1, saved.version());

        UUID secondInstance = UUID.fromString("00000000-0000-0000-0000-000000000903");
        UUID secondTask = UUID.fromString("00000000-0000-0000-0000-000000000904");
        projections.createInstance(
            instance("tenant-a", secondInstance),
            List.of(task("tenant-a", secondInstance, secondTask, "manager-1"))
        );
        assertEquals(0, service.enqueueFromAudit(started("tenant-a", secondInstance)));
    }

    @Test
    void quietHoursDeferNormalEventsButEmergencyAssignmentsBypass() {
        var defaults = service.findPreferences("tenant-a", "mentioned-user");
        service.updatePreferences(new ApprovalNotificationService.UpdatePreferencesCommand(
            "tenant-a",
            "mentioned-user",
            "UTC",
            true,
            LocalTime.of(22, 0),
            LocalTime.of(7, 0),
            true,
            false,
            defaults.version(),
            defaults.preferences()
        ));

        AuditEvent mention = new AuditEvent(
            UUID.randomUUID(),
            "tenant-a",
            "author",
            "INSTANCE_COMMENTED",
            "APPROVAL_INSTANCE",
            INSTANCE_ID.toString(),
            "mention-request",
            null,
            START,
            Map.of("commentId", UUID.randomUUID().toString(), "mentions", "mentioned-user")
        );
        assertEquals(1, service.enqueueFromAudit(mention));
        var notification = service.findHistory(
            "tenant-a",
            "mentioned-user",
            false,
            20,
            0
        ).items().getFirst();
        assertEquals(Instant.parse("2026-07-21T07:00:00Z"), notification.nextAttemptAt());

        assertEquals(1, service.enqueueFromAudit(started("tenant-a", INSTANCE_ID)));
        var assignment = service.findHistory("tenant-a", "manager-1", false, 20, 0)
            .items().getFirst();
        assertEquals(START, assignment.nextAttemptAt());
    }

    @Test
    void inAppDeliveryCreatesUnreadHistoryAndReadLifecycle() {
        service.enqueueFromAudit(started("tenant-a", INSTANCE_ID));
        assertEquals(1, service.processDue(20, "worker-a"));
        var delivered = service.findHistory("tenant-a", "manager-1", false, 20, 0)
            .items().getFirst();
        assertEquals(NotificationStatus.DELIVERED, delivered.status());
        assertEquals(1, service.unreadCount("tenant-a", "manager-1").unread());
        assertTrue(service.markRead("tenant-a", "manager-1", delivered.intentId()).isPresent());
        assertEquals(0, service.unreadCount("tenant-a", "manager-1").unread());
    }

    @Test
    void businessEventRecipientAndChannelAreDeduplicatedUnderConcurrency() throws Exception {
        AuditEvent event = started("tenant-a", INSTANCE_ID);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Integer>> calls = List.of(
                () -> service.enqueueFromAudit(event),
                () -> service.enqueueFromAudit(event)
            );
            int inserted = executor.invokeAll(calls).stream().mapToInt(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }).sum();
            assertEquals(1, inserted);
        }
        assertEquals(1, service.findHistory("tenant-a", "manager-1", false, 20, 0).total());
    }

    @Test
    void connectorDeliveryUsesConfiguredChannelAndRecordsAttempt() {
        enable("manager-1", NotificationEventType.TASK_ASSIGNED, NotificationChannel.CONNECTOR);
        service.enqueueFromAudit(started("tenant-a", INSTANCE_ID));
        assertEquals(2, service.processDue(20, "worker-connector"));
        var connector = service.findHistory("tenant-a", "manager-1", false, 20, 0)
            .items().stream()
            .filter(item -> item.channel() == NotificationChannel.CONNECTOR)
            .findFirst().orElseThrow();
        assertEquals(NotificationStatus.DELIVERED, connector.status());
        assertEquals(1, connectorDeliveries.size());
        assertEquals(1, service.findAttempts(
            "tenant-a",
            "manager-1",
            connector.intentId()
        ).size());
    }

    @Test
    void transientFailureRetriesThenMovesToDeadLetterAndCanReplay() {
        service = new ApprovalNotificationService(
            store,
            projections,
            intent -> ApprovalConnectorNotificationSender.DeliveryResult.failed(
                true,
                "CONNECTOR_TEMPORARY_FAILURE",
                "connector temporarily unavailable"
            ),
            ApprovalEmailNotificationSender.unavailable(),
            clock,
            UUID::randomUUID
        );
        enable("manager-1", NotificationEventType.TASK_ASSIGNED, NotificationChannel.CONNECTOR);
        disable("manager-1", NotificationEventType.TASK_ASSIGNED, NotificationChannel.IN_APP);
        service.enqueueFromAudit(started("tenant-a", INSTANCE_ID));

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertEquals(1, service.processDue(20, "worker-failure"));
            if (attempt < 5) {
                clock.advanceSeconds(3600);
            }
        }
        var dead = service.findHistory("tenant-a", "manager-1", false, 20, 0)
            .items().getFirst();
        assertEquals(NotificationStatus.DEAD_LETTER, dead.status());
        assertEquals(5, dead.attemptCount());
        assertEquals(5, service.findAttempts("tenant-a", "manager-1", dead.intentId()).size());

        var replayed = service.replay("tenant-a", "manager-1", dead.intentId());
        assertEquals(NotificationStatus.PENDING, replayed.status());
        assertEquals(0, replayed.attemptCount());
    }

    @Test
    void unavailableEmailNeverPretendsSuccess() {
        enable("manager-1", NotificationEventType.TASK_ASSIGNED, NotificationChannel.EMAIL);
        service.enqueueFromAudit(started("tenant-a", INSTANCE_ID));
        service.processDue(20, "worker-email");
        var email = service.findHistory("tenant-a", "manager-1", false, 20, 0)
            .items().stream()
            .filter(item -> item.channel() == NotificationChannel.EMAIL)
            .findFirst().orElseThrow();
        assertEquals(NotificationStatus.DEAD_LETTER, email.status());
        assertEquals("EMAIL_PROVIDER_NOT_CONFIGURED", email.lastErrorCode());
    }

    @Test
    void deliveryFailureDoesNotRemoveCommittedApprovalAudit() {
        ApprovalNotificationService failingService = new ApprovalNotificationService(
            store,
            projections,
            intent -> ApprovalConnectorNotificationSender.DeliveryResult.failed(
                true,
                "CONNECTOR_DOWN",
                "connector is down"
            ),
            ApprovalEmailNotificationSender.unavailable(),
            clock,
            UUID::randomUUID
        );
        setPreference(
            failingService,
            "manager-1",
            NotificationEventType.TASK_ASSIGNED,
            NotificationChannel.CONNECTOR,
            true
        );
        setPreference(
            failingService,
            "manager-1",
            NotificationEventType.TASK_ASSIGNED,
            NotificationChannel.IN_APP,
            false
        );
        NotificationAwareAuditEventSink sink = new NotificationAwareAuditEventSink(
            new JdbcAuditEventSink(dataSource, new ObjectMapper().findAndRegisterModules()),
            failingService
        );
        AuditEvent event = started("tenant-a", INSTANCE_ID);
        sink.append(event);
        assertEquals(1, countAudit(event.eventId()));
        assertEquals(1, failingService.processDue(20, "worker-audit"));
        assertEquals(1, countAudit(event.eventId()));
        assertEquals(NotificationStatus.RETRY, failingService.findHistory(
            "tenant-a",
            "manager-1",
            false,
            20,
            0
        ).items().getFirst().status());
    }

    @Test
    void tenantIsolationProtectsPreferencesHistoryReadsAndReplay() {
        service.enqueueFromAudit(started("tenant-a", INSTANCE_ID));
        var intent = service.findHistory("tenant-a", "manager-1", false, 20, 0)
            .items().getFirst();
        assertEquals(0, service.findHistory("tenant-b", "manager-1", false, 20, 0).total());
        assertTrue(service.markRead("tenant-b", "manager-1", intent.intentId()).isEmpty());
        assertThrows(
            RuntimeException.class,
            () -> service.replay("tenant-b", "manager-1", intent.intentId())
        );
    }

    @Test
    void collaborationAndMentionAuditEventsCreateExpectedNotificationTypes() {
        AuditEvent collaboration = new AuditEvent(
            UUID.randomUUID(),
            "tenant-a",
            "manager-1",
            "TASK_COLLABORATION_CREATED",
            "APPROVAL_TASK",
            TASK_ID.toString(),
            "collaboration-request",
            null,
            START,
            Map.of(
                "policyId", UUID.randomUUID().toString(),
                "participantWeights", "collaborator-one:1,collaborator-two:2"
            )
        );
        assertEquals(2, service.enqueueFromAudit(collaboration));
        assertEquals(
            NotificationEventType.TASK_COLLABORATION_ASSIGNED,
            service.findHistory("tenant-a", "collaborator-one", false, 20, 0)
                .items().getFirst().eventType()
        );

        AuditEvent mention = new AuditEvent(
            UUID.randomUUID(),
            "tenant-a",
            "author",
            "INSTANCE_COMMENTED",
            "APPROVAL_INSTANCE",
            INSTANCE_ID.toString(),
            "mention-request",
            null,
            START,
            Map.of("commentId", UUID.randomUUID().toString(), "mentions", "collaborator-one")
        );
        assertEquals(1, service.enqueueFromAudit(mention));
        assertTrue(service.findHistory("tenant-a", "collaborator-one", false, 20, 0)
            .items().stream().anyMatch(item -> item.eventType() == NotificationEventType.COMMENT_MENTION));
    }

    private void enable(
        String userId,
        NotificationEventType eventType,
        NotificationChannel channel
    ) {
        setPreference(service, userId, eventType, channel, true);
    }

    private void disable(
        String userId,
        NotificationEventType eventType,
        NotificationChannel channel
    ) {
        setPreference(service, userId, eventType, channel, false);
    }

    private void setPreference(
        ApprovalNotificationService target,
        String userId,
        NotificationEventType eventType,
        NotificationChannel channel,
        boolean enabled
    ) {
        var current = target.findPreferences("tenant-a", userId);
        List<NotificationPreference> preferences = new ArrayList<>(current.preferences());
        replace(preferences, eventType, channel, enabled);
        target.updatePreferences(new ApprovalNotificationService.UpdatePreferencesCommand(
            "tenant-a",
            userId,
            current.timezone(),
            current.quietHoursEnabled(),
            current.quietHoursStart(),
            current.quietHoursEnd(),
            current.emergencyBypass(),
            current.digestEnabled(),
            current.version(),
            preferences
        ));
    }

    private static boolean enabled(
        List<NotificationPreference> preferences,
        NotificationEventType eventType,
        NotificationChannel channel
    ) {
        return preferences.stream().filter(item -> item.eventType() == eventType
            && item.channel() == channel).findFirst().orElseThrow().enabled();
    }

    private static void replace(
        List<NotificationPreference> preferences,
        NotificationEventType eventType,
        NotificationChannel channel,
        boolean enabled
    ) {
        for (int index = 0; index < preferences.size(); index++) {
            NotificationPreference item = preferences.get(index);
            if (item.eventType() == eventType && item.channel() == channel) {
                preferences.set(index, new NotificationPreference(eventType, channel, enabled));
                return;
            }
        }
        throw new IllegalStateException("notification preference was not found");
    }

    private int countAudit(UUID eventId) {
        Integer count = jdbc.queryForObject(
            "select count(*) from ap_audit_event where event_id = ?",
            Integer.class,
            eventId
        );
        return count == null ? 0 : count;
    }

    private static AuditEvent started(String tenantId, UUID instanceId) {
        return new AuditEvent(
            UUID.randomUUID(),
            tenantId,
            "initiator-1",
            "INSTANCE_STARTED",
            "APPROVAL_INSTANCE",
            instanceId.toString(),
            "start-request-" + instanceId,
            null,
            START,
            Map.of()
        );
    }

    private static PublishedDefinition definition() {
        return new PublishedDefinition(
            "tenant-a",
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.FORM_VERSION,
            "notification-test",
            "a".repeat(64),
            "deployment-notification",
            "definition-notification",
            1,
            "publisher",
            START
        );
    }

    private static InstanceProjection instance(String tenantId, UUID instanceId) {
        return new InstanceProjection(
            instanceId,
            tenantId,
            "business-" + instanceId,
            "engine-instance-" + instanceId,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.FORM_VERSION,
            "notification-test",
            "a".repeat(64),
            "initiator-1",
            new BigDecimal("100.00"),
            "Notification Supplier",
            "PO-NOTIFICATION",
            List.of(),
            new AssigneeSnapshot(
                "manager-1",
                "finance-reviewer",
                List.of("finance-a"),
                Map.of("connectorKey", "generic-rest")
            ),
            "request-hash-" + instanceId,
            InstanceStatus.RUNNING,
            1,
            START,
            START
        );
    }

    private static TaskProjection task(
        String tenantId,
        UUID instanceId,
        UUID taskId,
        String assigneeId
    ) {
        return new TaskProjection(
            taskId,
            instanceId,
            tenantId,
            "engine-task-" + taskId,
            "managerApproval",
            "Manager approval",
            assigneeId,
            TaskStatus.PENDING,
            1,
            START,
            START,
            null
        );
    }

    private static final class MutableClock extends Clock {

        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advanceSeconds(long seconds) {
            current = current.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
