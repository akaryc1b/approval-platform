package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalMessageService;
import io.github.akaryc1b.approval.application.ApprovalMessageService.CopyCommand;
import io.github.akaryc1b.approval.application.ApprovalMessageService.UrgeCommand;
import io.github.akaryc1b.approval.application.port.ApprovalBusinessEventOutbox;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.MessageIdentity;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.UserIdentitySnapshot;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.context.RequestContext;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalMessageIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-18T04:00:00Z");
    private static final UUID INSTANCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000701");
    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000702");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_message_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private JdbcApprovalProjectionStore projections;
    private JdbcApprovalMessageStore messageStore;
    private ApprovalMessageService service;

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
                ap_approval_message,
                ap_audit_event,
                ap_approval_task,
                ap_approval_instance,
                ap_definition_version,
                ap_command_idempotency
            cascade
            """);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        projections = new JdbcApprovalProjectionStore(dataSource, objectMapper);
        messageStore = new JdbcApprovalMessageStore(dataSource, objectMapper);
        service = new ApprovalMessageService(
            new JdbcIdempotencyGuard(
                dataSource,
                objectMapper,
                new JdbcTransactionManager(dataSource),
                clock
            ),
            projections,
            messageStore,
            new JdbcAuditEventSink(dataSource, objectMapper),
            clock,
            UUID::randomUUID
        );
        createRunningInstance();
    }

    @Test
    void urgeCopyReadAndReceiptsAreDurableAuthorizedAndIdempotent() {
        UrgeCommand urge = new UrgeCommand(
            context("initiator-1", "urge-request", "urge-key"),
            INSTANCE_ID,
            "请尽快处理付款审批"
        );

        var urged = service.urge(urge);
        var replayedUrge = service.urge(urge);

        assertEquals(urged, replayedUrge);
        assertEquals(1, urged.createdMessages());
        assertEquals(List.of("manager-1"), urged.recipients());
        assertEquals(1, messageStore.countUnread(new MessageIdentity("tenant-a", "manager-1")));
        assertEquals(1, countAudit("INSTANCE_URGED"));

        assertThrows(
            RuntimeException.class,
            () -> service.urge(new UrgeCommand(
                context("initiator-1", "urge-request-2", "urge-key-2"),
                INSTANCE_ID,
                "再次催办"
            ))
        );

        var options = service.findOptions("tenant-a", "initiator-1", INSTANCE_ID);
        assertTrue(options.canUrge());
        assertTrue(options.copyCandidates().stream()
            .anyMatch(option -> "finance-reviewer".equals(option.userId())
                && "财务审核员".equals(option.displayName())));

        var copied = service.copy(new CopyCommand(
            context("initiator-1", "copy-request", "copy-key"),
            INSTANCE_ID,
            List.of("finance-reviewer"),
            "请关注该付款审批"
        ));
        assertEquals(1, copied.createdMessages());
        assertEquals(1, messageStore.countUnread(new MessageIdentity("tenant-a", "finance-reviewer")));
        assertEquals(1, countAudit("INSTANCE_COPIED"));

        assertThrows(
            RuntimeException.class,
            () -> service.copy(new CopyCommand(
                context("initiator-1", "copy-invalid", "copy-invalid-key"),
                INSTANCE_ID,
                List.of("outsider"),
                null
            ))
        );

        var managerMessages = service.findMessages("tenant-a", "manager-1", false, 20, 0);
        assertEquals(1, managerMessages.total());
        assertFalse(managerMessages.items().getFirst().read());
        UUID managerMessageId = managerMessages.items().getFirst().messageId();

        var firstRead = service.markRead("tenant-a", "manager-1", managerMessageId).orElseThrow();
        var repeatedRead = service.markRead("tenant-a", "manager-1", managerMessageId).orElseThrow();

        assertTrue(firstRead.firstRead());
        assertFalse(repeatedRead.firstRead());
        assertEquals(0, messageStore.countUnread(new MessageIdentity("tenant-a", "manager-1")));
        assertEquals(1, countAudit("MESSAGE_READ"));

        var receipts = service.findReceipts("tenant-a", "initiator-1", INSTANCE_ID);
        assertEquals(2, receipts.size());
        assertEquals(1, receipts.stream().filter(receipt -> receipt.read()).count());

        var started = new JdbcApprovalParticipationQuery(
            dataSource,
            new ObjectMapper().findAndRegisterModules()
        ).findStartedInstances(new io.github.akaryc1b.approval.application.port
            .ApprovalParticipationQuery.StartedInstanceCriteria(
                "tenant-a",
                "initiator-1",
                null,
                20,
                0
            ));
        assertEquals(2, started.items().getFirst().messageCount());
        assertEquals(1, started.items().getFirst().readCount());

        var timeline = new JdbcApprovalTimelineQuery(
            dataSource,
            new ObjectMapper().findAndRegisterModules()
        ).findTimeline(new io.github.akaryc1b.approval.application.port
            .ApprovalTimelineQuery.TimelineIdentity(
                "tenant-a",
                "finance-reviewer",
                INSTANCE_ID
            )).orElseThrow();
        assertTrue(timeline.items().stream()
            .anyMatch(item -> "INSTANCE_COPIED".equals(item.action())));
    }

    private void createRunningInstance() {
        projections.saveDefinition(new PublishedDefinition(
            "tenant-a",
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.FORM_VERSION,
            ApprovalDslCompiler.COMPILER_VERSION,
            "a".repeat(64),
            "deployment-message",
            "engine-definition-message",
            1,
            "publisher",
            NOW
        ));
        Map<String, UserIdentitySnapshot> identities = Map.of(
            "manager-1",
            identity("manager-1", "部门负责人"),
            "finance-reviewer",
            identity("finance-reviewer", "财务审核员"),
            "finance-a",
            identity("finance-a", "财务会签甲")
        );
        InstanceProjection instance = new InstanceProjection(
            INSTANCE_ID,
            "tenant-a",
            "PO-MESSAGE-001",
            "engine-instance-message",
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.FORM_VERSION,
            ApprovalDslCompiler.COMPILER_VERSION,
            "a".repeat(64),
            "initiator-1",
            new BigDecimal("8800.00"),
            "消息测试供应商",
            "PURCHASE-MESSAGE-001",
            List.of("attachment-message"),
            new AssigneeSnapshot(
                "manager-1",
                "finance-reviewer",
                List.of("finance-a"),
                Map.of("connectorKey", "test"),
                identities
            ),
            "b".repeat(64),
            InstanceStatus.RUNNING,
            1,
            NOW,
            NOW
        );
        TaskProjection managerTask = new TaskProjection(
            TASK_ID,
            INSTANCE_ID,
            "tenant-a",
            "engine-task-message",
            "managerApproval",
            "Manager approval",
            "manager-1",
            TaskStatus.PENDING,
            1,
            NOW,
            NOW,
            null
        );
        projections.createInstance(instance, List.of(managerTask));
    }

    private static UserIdentitySnapshot identity(String id, String displayName) {
        return new UserIdentitySnapshot(
            id,
            id,
            displayName,
            null,
            null,
            List.of(),
            Set.of(),
            Set.of(),
            Map.of()
        );
    }

    private static RequestContext context(
        String operatorId,
        String requestId,
        String idempotencyKey
    ) {
        return new RequestContext(
            "tenant-a",
            operatorId,
            requestId,
            idempotencyKey,
            "trace-message"
        );
    }

    private int countAudit(String action) {
        return jdbc.queryForObject(
            "select count(*) from ap_audit_event where action = ?",
            Integer.class,
            action
        );
    }
}
