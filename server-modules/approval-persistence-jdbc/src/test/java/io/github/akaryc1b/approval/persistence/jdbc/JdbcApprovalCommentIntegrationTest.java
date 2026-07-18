package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalAttachmentService;
import io.github.akaryc1b.approval.application.ApprovalAttachmentService.UploadCommand;
import io.github.akaryc1b.approval.application.ApprovalCommentService;
import io.github.akaryc1b.approval.application.ApprovalCommentService.CommentCommand;
import io.github.akaryc1b.approval.application.ApprovalMessageService;
import io.github.akaryc1b.approval.application.ApprovalMessageService.CopyCommand;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.CopiedInstanceCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.MessageType;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.UserIdentitySnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalTimelineQuery.TimelineIdentity;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalCommentIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-18T05:00:00Z");
    private static final UUID INSTANCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000801");
    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000802");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_comment_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private JdbcApprovalProjectionStore projections;
    private JdbcApprovalMessageStore messageStore;
    private ApprovalMessageService messageService;
    private ApprovalAttachmentService attachmentService;
    private ApprovalCommentService commentService;

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
                ap_approval_attachment,
                ap_approval_comment,
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
        JdbcIdempotencyGuard idempotency = new JdbcIdempotencyGuard(
            dataSource,
            objectMapper,
            new JdbcTransactionManager(dataSource),
            clock
        );
        projections = new JdbcApprovalProjectionStore(dataSource, objectMapper);
        messageStore = new JdbcApprovalMessageStore(dataSource, objectMapper);
        JdbcApprovalAttachmentStore attachmentStore = new JdbcApprovalAttachmentStore(dataSource);
        JdbcAuditEventSink audit = new JdbcAuditEventSink(dataSource, objectMapper);
        messageService = new ApprovalMessageService(
            idempotency,
            projections,
            messageStore,
            audit,
            clock,
            UUID::randomUUID
        );
        attachmentService = new ApprovalAttachmentService(
            idempotency,
            attachmentStore,
            projections,
            messageStore,
            clock,
            UUID::randomUUID
        );
        commentService = new ApprovalCommentService(
            idempotency,
            projections,
            messageStore,
            new JdbcApprovalCommentStore(dataSource, objectMapper),
            attachmentStore,
            audit,
            clock,
            UUID::randomUUID
        );
        createRunningInstance();
    }

    @Test
    void commentsRepliesAttachmentsAndDeepLinksAreAuthorizedAndIdempotent() {
        messageService.copy(new CopyCommand(
            context("initiator-1", "copy-request", "copy-key"),
            INSTANCE_ID,
            List.of("finance-reviewer"),
            "请关注该审批"
        ));

        byte[] contractBytes = "signed-contract".getBytes(StandardCharsets.UTF_8);
        var attachment = attachmentService.upload(new UploadCommand(
            context("manager-1", "upload-request", "upload-key"),
            "合同.pdf",
            "application/pdf",
            contractBytes
        ));
        assertFalse(attachment.bound());
        assertArrayEquals(
            contractBytes,
            attachmentService.download("tenant-a", "manager-1", attachment.attachmentId())
                .orElseThrow()
                .content()
        );
        assertTrue(attachmentService.download(
            "tenant-a",
            "finance-reviewer",
            attachment.attachmentId()
        ).isEmpty());

        CommentCommand command = new CommentCommand(
            context("manager-1", "comment-request", "comment-key"),
            INSTANCE_ID,
            null,
            "请财务确认合同附件",
            List.of("finance-reviewer"),
            List.of(attachment.attachmentId())
        );
        var created = commentService.comment(command);
        var replayed = commentService.comment(command);

        assertEquals(created, replayed);
        assertEquals(1, countRows("ap_approval_comment"));
        assertEquals(1, countRows("ap_approval_attachment"));
        assertEquals(1, countAudit("INSTANCE_COMMENTED"));
        assertEquals("部门负责人", created.authorDisplayName());
        assertEquals("合同.pdf", created.attachments().getFirst().fileName());
        assertTrue(created.attachments().getFirst().bound());
        assertEquals("财务审核员", created.mentionedUsers().getFirst().displayName());
        assertArrayEquals(
            contractBytes,
            attachmentService.download("tenant-a", "finance-reviewer", attachment.attachmentId())
                .orElseThrow()
                .content()
        );
        assertTrue(attachmentService.download(
            "tenant-a",
            "outsider",
            attachment.attachmentId()
        ).isEmpty());

        var reply = commentService.comment(new CommentCommand(
            context("finance-reviewer", "reply-request", "reply-key"),
            INSTANCE_ID,
            created.commentId(),
            "已核对合同，金额一致。",
            List.of("manager-1"),
            List.of()
        ));
        assertTrue(reply.reply());
        assertEquals(created.commentId(), reply.parentCommentId());
        assertEquals("部门负责人", reply.replyToAuthorDisplayName());

        assertThrows(
            RuntimeException.class,
            () -> commentService.comment(new CommentCommand(
                context("manager-1", "nested-reply", "nested-reply-key"),
                INSTANCE_ID,
                reply.commentId(),
                "不允许回复二级回复",
                List.of(),
                List.of()
            ))
        );

        var outsiderAttachment = attachmentService.upload(new UploadCommand(
            context("finance-a", "other-upload", "other-upload-key"),
            "内部.txt",
            "text/plain",
            "private".getBytes(StandardCharsets.UTF_8)
        ));
        assertThrows(
            RuntimeException.class,
            () -> commentService.comment(new CommentCommand(
                context("manager-1", "invalid-attachment", "invalid-attachment-key"),
                INSTANCE_ID,
                null,
                "尝试引用他人未绑定附件",
                List.of(),
                List.of(outsiderAttachment.attachmentId())
            ))
        );
        assertEquals(2, countRows("ap_approval_comment"));

        var copiedComments = commentService.findComments(
            "tenant-a",
            "finance-reviewer",
            INSTANCE_ID,
            20,
            0
        );
        assertEquals(2, copiedComments.total());
        assertEquals(created.commentId(), copiedComments.items().getFirst().commentId());

        var messages = messageService.findMessages(
            "tenant-a",
            "finance-reviewer",
            false,
            20,
            0
        );
        assertTrue(messages.items().stream()
            .anyMatch(item -> item.messageType() == MessageType.MENTION
                && created.commentId().toString().equals(item.metadata().get("commentId"))));

        UUID copyMessageId = messages.items().stream()
            .filter(item -> item.messageType() == MessageType.COPY)
            .findFirst()
            .orElseThrow()
            .messageId();
        messageService.markRead("tenant-a", "finance-reviewer", copyMessageId).orElseThrow();
        var copied = messageStore.findCopiedInstances(new CopiedInstanceCriteria(
            "tenant-a",
            "finance-reviewer",
            "消息测试",
            20,
            0
        ));
        assertEquals(1, copied.total());
        assertTrue(copied.items().getFirst().read());
        assertEquals(2, copied.items().getFirst().commentCount());

        assertThrows(
            RuntimeException.class,
            () -> commentService.findComments("tenant-a", "outsider", INSTANCE_ID, 20, 0)
        );
        assertFalse(messageStore.isRecipient("tenant-a", "outsider", INSTANCE_ID));

        var timeline = new JdbcApprovalTimelineQuery(
            dataSource,
            new ObjectMapper().findAndRegisterModules()
        ).findTimeline(new TimelineIdentity(
            "tenant-a",
            "finance-reviewer",
            INSTANCE_ID
        )).orElseThrow();
        assertTrue(timeline.items().stream()
            .anyMatch(item -> "INSTANCE_COMMENTED".equals(item.action())));
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
            "deployment-comment",
            "engine-definition-comment",
            1,
            "publisher",
            NOW
        ));
        Map<String, UserIdentitySnapshot> identities = Map.of(
            "initiator-1",
            identity("initiator-1", "申请人"),
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
            "PO-COMMENT-001",
            "engine-instance-comment",
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.FORM_VERSION,
            ApprovalDslCompiler.COMPILER_VERSION,
            "a".repeat(64),
            "initiator-1",
            new BigDecimal("8800.00"),
            "消息测试供应商",
            "PURCHASE-COMMENT-001",
            List.of("attachment-comment"),
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
            "engine-task-comment",
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
            "trace-comment"
        );
    }

    private int countRows(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private int countAudit(String action) {
        return jdbc.queryForObject(
            "select count(*) from ap_audit_event where action = ?",
            Integer.class,
            action
        );
    }
}
