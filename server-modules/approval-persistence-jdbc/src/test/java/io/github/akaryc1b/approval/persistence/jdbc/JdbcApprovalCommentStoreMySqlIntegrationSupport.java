package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.ApprovalComment;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentRevision;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentStatus;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentUpdate;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentVisibility;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.RevisionType;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

abstract class JdbcApprovalCommentStoreMySqlIntegrationSupport
    extends MySqlApprovalProjectionStoreIntegrationSupport {

    protected static final UUID COMMENT_ID = uuid(9301);
    protected static final UUID REPLY_ID = uuid(9302);
    protected static final UUID CAS_COMMENT_ID = uuid(9303);
    protected static final UUID ROLLBACK_COMMENT_ID = uuid(9304);
    protected static final UUID ATTACHMENT_A = uuid(9311);
    protected static final UUID ATTACHMENT_B = uuid(9312);
    protected static final UUID POLICY_ID = uuid(9321);
    protected static final UUID PARTICIPANT_ID = uuid(9322);
    protected static final Instant COMMENT_AT = Instant.parse(
        "2026-08-20T10:11:12.123456499Z"
    );
    protected static final Instant EDIT_AT = Instant.parse(
        "2026-08-20T10:12:13.999999500Z"
    );
    protected static final Instant DELETE_AT = Instant.parse(
        "2026-08-20T10:13:14.111222500Z"
    );

    protected ApprovalCommentStore comments;
    protected ApprovalMessageStore messages;
    protected JdbcDatabaseValueAdapter values;
    protected TransactionTemplate commentTransactions;

    @BeforeEach
    void setUpComments() {
        JdbcTransactionManager transactionManager =
            new JdbcTransactionManager(dataSource);
        ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
        comments = JdbcApprovalCommentStoreFactory.create(
            dataSource,
            mapper,
            transactionManager
        );
        messages = JdbcApprovalMessageStoreFactory.create(
            dataSource,
            mapper,
            transactionManager
        );
        values = JdbcDatabaseValueAdapter.resolve(dataSource);
        commentTransactions = new TransactionTemplate(transactionManager);
        seedInstanceWithTasks(
            instance(
                TENANT,
                INSTANCE_ID,
                "engine-instance-comment",
                "business-comment-alpha"
            ),
            List.of(task(
                TENANT,
                TASK_ID,
                INSTANCE_ID,
                "engine-task-comment",
                "Manager-A",
                TaskStatus.PENDING,
                1,
                CREATED_AT
            ))
        );
    }

    @AfterEach
    void cleanComments() {
        jdbc.update("delete from ap_approval_comment_revision");
        jdbc.update(
            "delete from ap_approval_comment where parent_comment_id is not null"
        );
        jdbc.update(
            "delete from ap_approval_comment where parent_comment_id is null"
        );
        jdbc.update("delete from ap_approval_message");
        jdbc.update(
            "delete from ap_task_collaboration_participant"
        );
        jdbc.update("delete from ap_task_collaboration_policy");
    }

    protected ApprovalCommentStore.StoredCommentPage page(
        String viewerId
    ) {
        return comments.findComments(new CommentCriteria(
            TENANT,
            INSTANCE_ID,
            viewerId,
            20,
            0
        ));
    }

    protected String concurrentUpdate(
        String body,
        String operatorId,
        CountDownLatch ready,
        CountDownLatch start
    ) {
        ready.countDown();
        await(start);
        try {
            comments.update(
                new CommentUpdate(
                    TENANT,
                    INSTANCE_ID,
                    CAS_COMMENT_ID,
                    body,
                    List.of(),
                    List.of(),
                    CommentVisibility.PARTICIPANTS,
                    EDIT_AT,
                    1
                ),
                revision(
                    CAS_COMMENT_ID,
                    2,
                    RevisionType.EDIT,
                    body,
                    List.of(),
                    List.of(),
                    CommentVisibility.PARTICIPANTS,
                    operatorId,
                    null,
                    EDIT_AT
                )
            );
            return "UPDATED";
        } catch (ApprovalCommentStore.CommentConflictException exception) {
            return exception.code();
        }
    }

    protected void addCollaborationParticipant(String userId) {
        jdbc.update(
            """
            insert into ap_task_collaboration_policy (
                policy_id, tenant_id, task_id, instance_id,
                engine_task_id, engine_instance_id, definition_key,
                task_definition_key, task_name, owner_assignee_id,
                collaboration_mode, status, reason, created_by,
                created_at, terminal_by, terminal_at, terminal_reason,
                version, approval_threshold,
                approval_weight_threshold
            ) values (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                'ALL', 'ACTIVE', ?, ?, ?,
                null, null, null, 1, null, null
            )
            """,
            values.bindUuid(POLICY_ID),
            TENANT,
            values.bindUuid(TASK_ID),
            values.bindUuid(INSTANCE_ID),
            "engine-task-comment",
            "engine-instance-comment",
            DEFINITION_KEY,
            "managerApproval",
            "Manager approval",
            "Manager-A",
            "需要协作审批",
            "Manager-A",
            values.bindInstant(canonicalInstant(COMMENT_AT))
        );
        jdbc.update(
            """
            insert into ap_task_collaboration_participant (
                participant_id, tenant_id, policy_id,
                participant_user_id, identity_source,
                identity_object_type, identity_external_value,
                status, added_by, added_at, decision_comment,
                decided_at, removed_by, removed_at,
                removal_reason, canceled_at, version,
                participant_weight
            ) values (
                ?, ?, ?, ?, ?, ?, ?,
                'PENDING', ?, ?, null, null,
                null, null, null, null, 1, 1
            )
            """,
            values.bindUuid(PARTICIPANT_ID),
            TENANT,
            values.bindUuid(POLICY_ID),
            userId,
            "CONNECTOR_DIRECTORY",
            "USER",
            "ext-" + userId,
            "Manager-A",
            values.bindInstant(canonicalInstant(COMMENT_AT))
        );
    }

    protected static ApprovalComment comment(
        UUID commentId,
        UUID parentCommentId,
        String authorId,
        String body,
        List<String> mentionIds,
        List<UUID> attachmentIds,
        CommentVisibility visibility,
        Instant occurredAt
    ) {
        return new ApprovalComment(
            commentId,
            TENANT,
            INSTANCE_ID,
            parentCommentId,
            authorId,
            body,
            mentionIds,
            attachmentIds,
            CommentStatus.ACTIVE,
            visibility,
            1,
            occurredAt,
            occurredAt,
            null,
            null,
            null,
            1
        );
    }

    protected static CommentRevision revision(
        UUID commentId,
        int revisionNumber,
        RevisionType revisionType,
        String body,
        List<String> mentionIds,
        List<UUID> attachmentIds,
        CommentVisibility visibility,
        String operatorId,
        String reason,
        Instant occurredAt
    ) {
        return new CommentRevision(
            TENANT,
            commentId,
            revisionNumber,
            revisionType,
            body,
            mentionIds,
            attachmentIds,
            visibility,
            operatorId,
            reason,
            occurredAt
        );
    }

    protected static UUID uuid(long suffix) {
        return UUID.fromString(
            "00000000-0000-0000-0000-"
                + String.format("%012d", suffix)
        );
    }
}
