package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalAttachmentService;
import io.github.akaryc1b.approval.application.ApprovalAttachmentService.UploadCommand;
import io.github.akaryc1b.approval.application.ApprovalCommentService;
import io.github.akaryc1b.approval.application.ApprovalCommentService.CommentCommand;
import io.github.akaryc1b.approval.application.ApprovalCommentService.CommentOperationException;
import io.github.akaryc1b.approval.application.ApprovalCommentService.DeleteCommentCommand;
import io.github.akaryc1b.approval.application.ApprovalCommentService.EditCommentCommand;
import io.github.akaryc1b.approval.application.ApprovalNotificationService;
import io.github.akaryc1b.approval.application.NotificationAwareAuditEventSink;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentStatus;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentVisibility;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.RevisionType;
import io.github.akaryc1b.approval.application.port.ApprovalConnectorNotificationSender;
import io.github.akaryc1b.approval.application.port.ApprovalEmailNotificationSender;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.UserIdentitySnapshot;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
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
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalCommentIntegrationTest {

    private static final Instant START = Instant.parse("2026-07-20T12:00:00Z");
    private static final UUID INSTANCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000801");
    private static final UUID MANAGER_TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000802");
    private static final UUID REVIEWER_TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000803");
    private static final UUID FINANCE_TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000804");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_comment_governance_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private MutableClock clock;
    private JdbcApprovalProjectionStore projections;
    private JdbcApprovalMessageStore messageStore;
    private JdbcApprovalAttachmentStore attachmentStore;
    private JdbcApprovalCommentStore commentStore;
    private ApprovalAttachmentService attachmentService;
    private ApprovalCommentService commentService;
    private ApprovalNotificationService notificationService;

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
                ap_notification_delivery_attempt,
                ap_notification_intent,
                ap_notification_preference,
                ap_notification_user_setting,
                ap_approval_comment_revision,
                ap_approval_attachment,
                ap_approval_comment,
                ap_approval_message,
                ap_task_collaboration_participant,
                ap_task_collaboration_policy,
                ap_audit_event,
                ap_approval_task,
                ap_approval_instance,
                ap_definition_version,
                ap_command_idempotency
            cascade
            """);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        clock = new MutableClock(START);
        JdbcTransactionManager transactionManager = new JdbcTransactionManager(dataSource);
        JdbcIdempotencyGuard idempotency = new JdbcIdempotencyGuard(
            dataSource,
            objectMapper,
            transactionManager,
            clock
        );
        projections = new JdbcApprovalProjectionStore(dataSource, objectMapper);
        messageStore = new JdbcApprovalMessageStore(dataSource, objectMapper);
        attachmentStore = new JdbcApprovalAttachmentStore(dataSource);
        commentStore = new JdbcApprovalCommentStore(dataSource, objectMapper);
        JdbcApprovalNotificationStore notificationStore = new JdbcApprovalNotificationStore(
            dataSource,
            objectMapper,
            transactionManager
        );
        notificationService = new ApprovalNotificationService(
            notificationStore,
            projections,
            ApprovalConnectorNotificationSender.unavailable(),
            ApprovalEmailNotificationSender.unavailable(),
            clock,
            UUID::randomUUID
        );
        var audit = new NotificationAwareAuditEventSink(
            new JdbcAuditEventSink(dataSource, objectMapper),
            notificationService
        );
        attachmentService = new ApprovalAttachmentService(
            idempotency,
            attachmentStore,
            projections,
            messageStore,
            commentStore,
            clock,
            UUID::randomUUID
        );
        commentService = new ApprovalCommentService(
            idempotency,
            projections,
            messageStore,
            commentStore,
            attachmentStore,
            audit,
            clock,
            UUID::randomUUID
        );
        createInstance("tenant-a", INSTANCE_ID, "PO-COMMENT-001");
    }

    @Test
    void createEditRevisionAndNewMentionNotification() {
        var created = create(
            "tenant-a",
            "manager-1",
            INSTANCE_ID,
            null,
            "请财务复核合同",
            List.of("finance-reviewer"),
            List.of(),
            CommentVisibility.PARTICIPANTS,
            "create-edit"
        );
        assertEquals(1, created.currentRevision());
        assertEquals(1, notificationCount("tenant-a", "finance-reviewer"));

        var edited = commentService.edit(new EditCommentCommand(
            context("tenant-a", "manager-1", "edit-request", "edit-key"),
            INSTANCE_ID,
            created.commentId(),
            "请财务与会签人共同复核合同",
            List.of("finance-reviewer", "finance-a"),
            List.of(),
            CommentVisibility.PARTICIPANTS,
            created.version(),
            "补充会签人"
        ));

        assertEquals(2, edited.currentRevision());
        assertTrue(edited.edited());
        assertEquals(1, notificationCount("tenant-a", "finance-reviewer"));
        assertEquals(1, notificationCount("tenant-a", "finance-a"));
        var revisions = commentService.findRevisions(
            "tenant-a",
            "manager-1",
            INSTANCE_ID,
            created.commentId()
        );
        assertEquals(List.of(RevisionType.CREATE, RevisionType.EDIT), revisions.stream()
            .map(ApprovalCommentService.CommentRevisionItem::revisionType)
            .toList());
        assertEquals("补充会签人", revisions.getLast().reason());
    }

    @Test
    void privateVisibilityAndAttachmentCannotBeBypassed() {
        byte[] content = "private-contract".getBytes(StandardCharsets.UTF_8);
        var attachment = upload("tenant-a", "manager-1", "private-file", content);
        var comment = create(
            "tenant-a",
            "manager-1",
            INSTANCE_ID,
            null,
            "仅财务审核员可见",
            List.of("finance-reviewer"),
            List.of(attachment.attachmentId()),
            CommentVisibility.MENTIONED_ONLY,
            "private-comment"
        );

        assertTrue(comment.privateComment());
        assertEquals(1, commentService.findComments(
            "tenant-a", "finance-reviewer", INSTANCE_ID, 20, 0
        ).total());
        assertEquals(0, commentService.findComments(
            "tenant-a", "finance-a", INSTANCE_ID, 20, 0
        ).total());
        assertArrayEquals(
            content,
            attachmentService.download(
                "tenant-a", "finance-reviewer", attachment.attachmentId()
            ).orElseThrow().content()
        );
        assertTrue(attachmentService.download(
            "tenant-a", "finance-a", attachment.attachmentId()
        ).isEmpty());
        assertTrue(attachmentService.download(
            "tenant-a", "manager-1", attachment.attachmentId()
        ).isPresent());
    }

    @Test
    void privateReplyInheritsAudienceAndCannotExpandIt() {
        var parent = create(
            "tenant-a",
            "manager-1",
            INSTANCE_ID,
            null,
            "仅审核员可见的讨论",
            List.of("finance-reviewer"),
            List.of(),
            CommentVisibility.MENTIONED_ONLY,
            "private-parent"
        );
        var reply = create(
            "tenant-a",
            "finance-reviewer",
            INSTANCE_ID,
            parent.commentId(),
            "已收到",
            List.of("manager-1"),
            List.of(),
            CommentVisibility.MENTIONED_ONLY,
            "private-reply"
        );
        assertTrue(reply.reply());
        assertEquals(CommentVisibility.MENTIONED_ONLY, reply.visibility());

        CommentOperationException expansion = assertThrows(
            CommentOperationException.class,
            () -> create(
                "tenant-a",
                "finance-reviewer",
                INSTANCE_ID,
                parent.commentId(),
                "尝试扩大私密范围",
                List.of("manager-1", "finance-a"),
                List.of(),
                CommentVisibility.MENTIONED_ONLY,
                "private-expansion"
            )
        );
        assertEquals("APPROVAL_COMMENT_PRIVATE_AUDIENCE_EXPANSION", expansion.code());
    }

    @Test
    void nonAuthorCannotEditOrDelete() {
        var comment = create(
            "tenant-a", "manager-1", INSTANCE_ID, null, "作者内容",
            List.of(), List.of(), CommentVisibility.PARTICIPANTS, "author-only"
        );
        CommentOperationException editError = assertThrows(
            CommentOperationException.class,
            () -> commentService.edit(new EditCommentCommand(
                context("tenant-a", "finance-reviewer", "non-author-edit", "non-author-edit-key"),
                INSTANCE_ID,
                comment.commentId(),
                "越权修改",
                List.of(),
                List.of(),
                CommentVisibility.PARTICIPANTS,
                comment.version(),
                null
            ))
        );
        assertEquals("APPROVAL_COMMENT_UNAUTHORIZED", editError.code());
        CommentOperationException deleteError = assertThrows(
            CommentOperationException.class,
            () -> commentService.delete(new DeleteCommentCommand(
                context("tenant-a", "finance-reviewer", "non-author-delete", "non-author-delete-key"),
                INSTANCE_ID,
                comment.commentId(),
                comment.version(),
                "越权删除"
            ))
        );
        assertEquals("APPROVAL_COMMENT_UNAUTHORIZED", deleteError.code());
    }

    @Test
    void editWindowExpiresAfterFifteenMinutes() {
        var comment = create(
            "tenant-a", "manager-1", INSTANCE_ID, null, "窗口测试",
            List.of(), List.of(), CommentVisibility.PARTICIPANTS, "window"
        );
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        CommentOperationException error = assertThrows(
            CommentOperationException.class,
            () -> commentService.edit(new EditCommentCommand(
                context("tenant-a", "manager-1", "expired-edit", "expired-edit-key"),
                INSTANCE_ID,
                comment.commentId(),
                "超时修改",
                List.of(),
                List.of(),
                CommentVisibility.PARTICIPANTS,
                comment.version(),
                null
            ))
        );
        assertEquals("APPROVAL_COMMENT_EDIT_WINDOW_EXPIRED", error.code());
    }

    @Test
    void expectedVersionConflictIsStable() {
        var comment = create(
            "tenant-a", "manager-1", INSTANCE_ID, null, "并发测试",
            List.of(), List.of(), CommentVisibility.PARTICIPANTS, "version"
        );
        CommentOperationException error = assertThrows(
            CommentOperationException.class,
            () -> commentService.edit(new EditCommentCommand(
                context("tenant-a", "manager-1", "stale-edit", "stale-edit-key"),
                INSTANCE_ID,
                comment.commentId(),
                "过期版本",
                List.of(),
                List.of(),
                CommentVisibility.PARTICIPANTS,
                comment.version() + 1,
                null
            ))
        );
        assertEquals("APPROVAL_COMMENT_CONCURRENT_MODIFICATION", error.code());
    }

    @Test
    void tombstoneDeletePreservesRevisionEvidence() {
        var comment = create(
            "tenant-a", "manager-1", INSTANCE_ID, null, "需要保留的原正文",
            List.of("finance-reviewer"), List.of(), CommentVisibility.PARTICIPANTS, "delete"
        );
        var deleted = commentService.delete(new DeleteCommentCommand(
            context("tenant-a", "manager-1", "delete-request", "delete-key"),
            INSTANCE_ID,
            comment.commentId(),
            comment.version(),
            "内容已失效"
        ));
        assertEquals(CommentStatus.DELETED, deleted.status());
        assertEquals(ApprovalCommentService.TOMBSTONE_BODY, deleted.body());
        assertEquals("内容已失效", deleted.deleteReason());
        var revisions = commentService.findRevisions(
            "tenant-a", "manager-1", INSTANCE_ID, comment.commentId()
        );
        assertEquals(2, revisions.size());
        assertEquals(RevisionType.DELETE, revisions.getLast().revisionType());
        assertEquals("需要保留的原正文", revisions.getLast().body());
        assertEquals("内容已失效", revisions.getLast().reason());
        assertEquals(2, countRows("ap_approval_comment_revision"));
    }

    @Test
    void terminalInstanceKeepsHistoryButMakesCommentsReadOnly() {
        var comment = create(
            "tenant-a", "manager-1", INSTANCE_ID, null, "结束前评论",
            List.of(), List.of(), CommentVisibility.PARTICIPANTS, "terminal"
        );
        jdbc.update(
            "update ap_approval_instance set status = 'COMPLETED', updated_at = ?, version = version + 1 where tenant_id = ? and instance_id = ?",
            OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
            "tenant-a",
            INSTANCE_ID
        );
        assertTrue(commentService.findComments(
            "tenant-a", "manager-1", INSTANCE_ID, 20, 0
        ).readOnly());
        CommentOperationException error = assertThrows(
            CommentOperationException.class,
            () -> commentService.edit(new EditCommentCommand(
                context("tenant-a", "manager-1", "terminal-edit", "terminal-edit-key"),
                INSTANCE_ID,
                comment.commentId(),
                "结束后修改",
                List.of(),
                List.of(),
                CommentVisibility.PARTICIPANTS,
                comment.version(),
                null
            ))
        );
        assertEquals("APPROVAL_COMMENT_INSTANCE_READ_ONLY", error.code());
    }

    @Test
    void collaborationParticipantCanCommentAndBePreciselyMentioned() {
        addCollaborationParticipant("collab-user");
        var options = commentService.findOptions("tenant-a", "manager-1", INSTANCE_ID);
        var candidate = options.mentionCandidates().stream()
            .filter(item -> "collab-user".equals(item.userId()))
            .findFirst()
            .orElseThrow();
        assertEquals("CONNECTOR_DIRECTORY", candidate.identitySource());
        assertEquals("USER", candidate.objectType());
        assertEquals("ext-collab-user", candidate.externalIdentityValue());

        var comment = create(
            "tenant-a",
            "collab-user",
            INSTANCE_ID,
            null,
            "加签参与人评论",
            List.of("manager-1"),
            List.of(),
            CommentVisibility.PARTICIPANTS,
            "collab-comment"
        );
        assertEquals("collab-user", comment.authorId());
        assertEquals(1, commentService.findComments(
            "tenant-a", "collab-user", INSTANCE_ID, 20, 0
        ).total());
    }

    @Test
    void attachmentOwnershipAndInstanceIsolationAreEnforced() {
        var foreignOwner = upload(
            "tenant-a",
            "finance-a",
            "foreign-owner",
            "foreign".getBytes(StandardCharsets.UTF_8)
        );
        CommentOperationException ownerError = assertThrows(
            CommentOperationException.class,
            () -> create(
                "tenant-a", "manager-1", INSTANCE_ID, null, "引用他人附件",
                List.of(), List.of(foreignOwner.attachmentId()),
                CommentVisibility.PARTICIPANTS, "foreign-owner-comment"
            )
        );
        assertEquals("APPROVAL_COMMENT_ATTACHMENT_OWNERSHIP_CONFLICT", ownerError.code());

        UUID otherInstance = UUID.fromString("00000000-0000-0000-0000-000000000811");
        createInstance("tenant-a", otherInstance, "PO-COMMENT-OTHER");
        var otherInstanceAttachment = upload(
            "tenant-a",
            "manager-1",
            "other-instance-file",
            "other-instance".getBytes(StandardCharsets.UTF_8)
        );
        attachmentStore.bindToInstance(
            "tenant-a",
            "manager-1",
            otherInstance,
            List.of(otherInstanceAttachment.attachmentId()),
            clock.instant()
        );
        CommentOperationException instanceError = assertThrows(
            CommentOperationException.class,
            () -> create(
                "tenant-a", "manager-1", INSTANCE_ID, null, "跨审批引用附件",
                List.of(), List.of(otherInstanceAttachment.attachmentId()),
                CommentVisibility.PARTICIPANTS, "other-instance-comment"
            )
        );
        assertEquals("APPROVAL_COMMENT_ATTACHMENT_OWNERSHIP_CONFLICT", instanceError.code());
    }

    @Test
    void revisionsAreRestrictedToAuthorAndInitiator() {
        var comment = create(
            "tenant-a", "manager-1", INSTANCE_ID, null, "修订权限",
            List.of(), List.of(), CommentVisibility.PARTICIPANTS, "revision-auth"
        );
        assertEquals(1, commentService.findRevisions(
            "tenant-a", "manager-1", INSTANCE_ID, comment.commentId()
        ).size());
        assertEquals(1, commentService.findRevisions(
            "tenant-a", "initiator-1", INSTANCE_ID, comment.commentId()
        ).size());
        CommentOperationException error = assertThrows(
            CommentOperationException.class,
            () -> commentService.findRevisions(
                "tenant-a", "finance-reviewer", INSTANCE_ID, comment.commentId()
            )
        );
        assertEquals("APPROVAL_COMMENT_UNAUTHORIZED", error.code());
    }

    @Test
    void tenantIsolationPreventsCrossTenantCommentAccess() {
        UUID tenantBInstance = UUID.fromString("00000000-0000-0000-0000-000000000821");
        createInstance("tenant-b", tenantBInstance, "PO-COMMENT-001");
        create(
            "tenant-a", "manager-1", INSTANCE_ID, null, "租户 A",
            List.of(), List.of(), CommentVisibility.PARTICIPANTS, "tenant-a-comment"
        );
        create(
            "tenant-b", "manager-1", tenantBInstance, null, "租户 B",
            List.of(), List.of(), CommentVisibility.PARTICIPANTS, "tenant-b-comment"
        );
        assertEquals(1, commentService.findComments(
            "tenant-a", "manager-1", INSTANCE_ID, 20, 0
        ).total());
        assertEquals(1, commentService.findComments(
            "tenant-b", "manager-1", tenantBInstance, 20, 0
        ).total());
        CommentOperationException error = assertThrows(
            CommentOperationException.class,
            () -> commentService.findComments(
                "tenant-b", "manager-1", INSTANCE_ID, 20, 0
            )
        );
        assertEquals("APPROVAL_COMMENT_NOT_FOUND", error.code());
    }

    @Test
    void createEditAndDeleteAreIdempotent() {
        CommentCommand createCommand = new CommentCommand(
            context("tenant-a", "manager-1", "idempotent-create", "idempotent-create-key"),
            INSTANCE_ID,
            null,
            "幂等创建",
            List.of(),
            List.of(),
            CommentVisibility.PARTICIPANTS
        );
        var created = commentService.comment(createCommand);
        assertEquals(created, commentService.comment(createCommand));

        EditCommentCommand editCommand = new EditCommentCommand(
            context("tenant-a", "manager-1", "idempotent-edit", "idempotent-edit-key"),
            INSTANCE_ID,
            created.commentId(),
            "幂等编辑",
            List.of(),
            List.of(),
            CommentVisibility.PARTICIPANTS,
            created.version(),
            null
        );
        var edited = commentService.edit(editCommand);
        assertEquals(edited, commentService.edit(editCommand));

        DeleteCommentCommand deleteCommand = new DeleteCommentCommand(
            context("tenant-a", "manager-1", "idempotent-delete", "idempotent-delete-key"),
            INSTANCE_ID,
            created.commentId(),
            edited.version(),
            "幂等删除"
        );
        var deleted = commentService.delete(deleteCommand);
        assertEquals(deleted, commentService.delete(deleteCommand));
        assertEquals(1, countRows("ap_approval_comment"));
        assertEquals(3, countRows("ap_approval_comment_revision"));
    }

    @Test
    void auditEventsContainLifecycleAndRevisionEvidence() {
        var created = create(
            "tenant-a", "manager-1", INSTANCE_ID, null, "审计创建",
            List.of("finance-reviewer"), List.of(),
            CommentVisibility.PARTICIPANTS, "audit-create"
        );
        var edited = commentService.edit(new EditCommentCommand(
            context("tenant-a", "manager-1", "audit-edit", "audit-edit-key"),
            INSTANCE_ID,
            created.commentId(),
            "审计编辑",
            List.of("finance-reviewer", "finance-a"),
            List.of(),
            CommentVisibility.PARTICIPANTS,
            created.version(),
            "审计原因"
        ));
        commentService.delete(new DeleteCommentCommand(
            context("tenant-a", "manager-1", "audit-delete", "audit-delete-key"),
            INSTANCE_ID,
            created.commentId(),
            edited.version(),
            "审计删除原因"
        ));

        assertEquals(1, countAudit("INSTANCE_COMMENT_CREATED"));
        assertEquals(1, countAudit("INSTANCE_COMMENT_EDITED"));
        assertEquals(1, countAudit("INSTANCE_COMMENT_DELETED"));
        String editRevision = jdbc.queryForObject(
            "select attributes_json ->> 'commentRevision' from ap_audit_event where action = 'INSTANCE_COMMENT_EDITED'",
            String.class
        );
        String deleteReason = jdbc.queryForObject(
            "select attributes_json ->> 'reason' from ap_audit_event where action = 'INSTANCE_COMMENT_DELETED'",
            String.class
        );
        assertEquals("2", editRevision);
        assertEquals("审计删除原因", deleteReason);
    }

    @Test
    void unchangedMentionDoesNotCreateDuplicateNotification() {
        var created = create(
            "tenant-a", "manager-1", INSTANCE_ID, null, "首次提及",
            List.of("finance-reviewer"), List.of(),
            CommentVisibility.PARTICIPANTS, "mention-dedupe-create"
        );
        assertEquals(1, notificationCount("tenant-a", "finance-reviewer"));
        commentService.edit(new EditCommentCommand(
            context("tenant-a", "manager-1", "mention-dedupe-edit", "mention-dedupe-edit-key"),
            INSTANCE_ID,
            created.commentId(),
            "仅修改正文，不新增提及",
            List.of("finance-reviewer"),
            List.of(),
            CommentVisibility.PARTICIPANTS,
            created.version(),
            null
        ));
        assertEquals(1, notificationCount("tenant-a", "finance-reviewer"));
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from ap_approval_message where tenant_id = ? and recipient_id = ? and message_type = 'MENTION'",
            Integer.class,
            "tenant-a",
            "finance-reviewer"
        ));
    }

    private ApprovalCommentService.CommentItem create(
        String tenantId,
        String authorId,
        UUID instanceId,
        UUID parentCommentId,
        String body,
        List<String> mentionIds,
        List<UUID> attachmentIds,
        CommentVisibility visibility,
        String operation
    ) {
        return commentService.comment(new CommentCommand(
            context(tenantId, authorId, operation + "-request", operation + "-key"),
            instanceId,
            parentCommentId,
            body,
            mentionIds,
            attachmentIds,
            visibility
        ));
    }

    private io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore.AttachmentSummary upload(
        String tenantId,
        String uploaderId,
        String operation,
        byte[] content
    ) {
        return attachmentService.upload(new UploadCommand(
            context(tenantId, uploaderId, operation + "-request", operation + "-key"),
            operation + ".txt",
            "text/plain",
            content
        ));
    }

    private void createInstance(String tenantId, UUID instanceId, String businessKey) {
        projections.saveDefinition(new PublishedDefinition(
            tenantId,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.FORM_VERSION,
            ApprovalDslCompiler.COMPILER_VERSION,
            "a".repeat(64),
            "deployment-comment-" + tenantId + '-' + instanceId,
            "engine-definition-comment-" + tenantId + '-' + instanceId,
            1,
            "publisher",
            clock.instant()
        ));
        Map<String, UserIdentitySnapshot> identities = Map.of(
            "initiator-1", identity("initiator-1", "申请人"),
            "manager-1", identity("manager-1", "部门负责人"),
            "finance-reviewer", identity("finance-reviewer", "财务审核员"),
            "finance-a", identity("finance-a", "财务会签甲")
        );
        InstanceProjection instance = new InstanceProjection(
            instanceId,
            tenantId,
            businessKey,
            "engine-instance-" + instanceId,
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
            clock.instant(),
            clock.instant()
        );
        UUID managerTaskId = instanceId.equals(INSTANCE_ID) ? MANAGER_TASK_ID : UUID.randomUUID();
        UUID reviewerTaskId = instanceId.equals(INSTANCE_ID) ? REVIEWER_TASK_ID : UUID.randomUUID();
        UUID financeTaskId = instanceId.equals(INSTANCE_ID) ? FINANCE_TASK_ID : UUID.randomUUID();
        projections.createInstance(instance, List.of(
            task(tenantId, instanceId, managerTaskId, "manager-1", "managerApproval"),
            task(tenantId, instanceId, reviewerTaskId, "finance-reviewer", "financeReview"),
            task(tenantId, instanceId, financeTaskId, "finance-a", "financeApproval")
        ));
    }

    private TaskProjection task(
        String tenantId,
        UUID instanceId,
        UUID taskId,
        String assigneeId,
        String definitionKey
    ) {
        return new TaskProjection(
            taskId,
            instanceId,
            tenantId,
            "engine-task-" + taskId,
            definitionKey,
            definitionKey,
            assigneeId,
            TaskStatus.PENDING,
            1,
            clock.instant(),
            clock.instant(),
            null
        );
    }

    private void addCollaborationParticipant(String userId) {
        UUID policyId = UUID.fromString("00000000-0000-0000-0000-000000000831");
        jdbc.update("""
            insert into ap_task_collaboration_policy (
                policy_id, tenant_id, task_id, instance_id,
                engine_task_id, engine_instance_id, definition_key,
                task_definition_key, task_name, owner_assignee_id,
                collaboration_mode, status, reason, created_by,
                created_at, terminal_by, terminal_at, terminal_reason,
                version, approval_threshold, approval_weight_threshold
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ALL', 'ACTIVE', ?, ?, ?, null, null, null, 1, null, null)
            """,
            policyId,
            "tenant-a",
            MANAGER_TASK_ID,
            INSTANCE_ID,
            "engine-task-" + MANAGER_TASK_ID,
            "engine-instance-" + INSTANCE_ID,
            PurchasePaymentTemplate.DEFINITION_KEY,
            "managerApproval",
            "Manager approval",
            "manager-1",
            "需要协作审批",
            "manager-1",
            OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        );
        jdbc.update("""
            insert into ap_task_collaboration_participant (
                participant_id, tenant_id, policy_id, participant_user_id,
                identity_source, identity_object_type, identity_external_value,
                status, added_by, added_at, decision_comment, decided_at,
                removed_by, removed_at, removal_reason, canceled_at,
                version, participant_weight
            ) values (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, null, null, null, null, null, null, 1, 1)
            """,
            UUID.fromString("00000000-0000-0000-0000-000000000832"),
            "tenant-a",
            policyId,
            userId,
            "CONNECTOR_DIRECTORY",
            "USER",
            "ext-" + userId,
            "manager-1",
            OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        );
    }

    private int notificationCount(String tenantId, String recipientId) {
        Integer count = jdbc.queryForObject(
            "select count(*) from ap_notification_intent where tenant_id = ? and recipient_id = ? and event_type = 'COMMENT_MENTION'",
            Integer.class,
            tenantId,
            recipientId
        );
        return count == null ? 0 : count;
    }

    private int countAudit(String action) {
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

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            assertNotNull(zone);
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
