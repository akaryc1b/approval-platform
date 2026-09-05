package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentUpdate;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentVisibility;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.RevisionType;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.StoredCommentItem;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.ApprovalMessage;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.MessageType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalCommentStoreMySqlAccessIntegrationTest
    extends JdbcApprovalCommentStoreMySqlIntegrationSupport {

    @Test
    void findsAdditionalParticipantsAndFencesAttachmentAudience() {
        comments.create(
            comment(
                COMMENT_ID,
                null,
                "Manager-A",
                "私密附件评论",
                List.of("Finance-A"),
                List.of(ATTACHMENT_A),
                CommentVisibility.MENTIONED_ONLY,
                COMMENT_AT
            ),
            revision(
                COMMENT_ID,
                1,
                RevisionType.CREATE,
                "私密附件评论",
                List.of("Finance-A"),
                List.of(ATTACHMENT_A),
                CommentVisibility.MENTIONED_ONLY,
                "Manager-A",
                null,
                COMMENT_AT
            )
        );
        assertEquals(1, messages.append(List.of(new ApprovalMessage(
            uuid(9331),
            TENANT,
            "Message-User",
            "Manager-A",
            INSTANCE_ID,
            TASK_ID,
            MessageType.COPY,
            "审批抄送",
            "参与评论候选",
            Map.of("source", "comment-test"),
            "comment-participant-message",
            COMMENT_AT
        ))));
        addCollaborationParticipant("Collab-User");

        var participants = comments.findAdditionalParticipants(
            TENANT,
            INSTANCE_ID
        );
        assertTrue(participants.stream().anyMatch(
            participant ->
                participant.userId().equals("Message-User")
                    && participant.identitySource()
                    .equals("APPROVAL_MESSAGE")
        ));
        assertTrue(participants.stream().anyMatch(
            participant ->
                participant.userId().equals("Collab-User")
                    && participant.identitySource()
                    .equals("CONNECTOR_DIRECTORY")
        ));
        assertTrue(comments.findAdditionalParticipants(
            OTHER_TENANT,
            INSTANCE_ID
        ).isEmpty());

        assertTrue(comments.findAttachmentAccess(
            TENANT,
            INSTANCE_ID,
            ATTACHMENT_A,
            "Manager-A"
        ).readable());
        assertTrue(comments.findAttachmentAccess(
            TENANT,
            INSTANCE_ID,
            ATTACHMENT_A,
            "Finance-A"
        ).readable());
        var outsider = comments.findAttachmentAccess(
            TENANT,
            INSTANCE_ID,
            ATTACHMENT_A,
            "Outside-User"
        );
        assertTrue(outsider.referenced());
        assertFalse(outsider.readable());

        var otherTenant = comments.findAttachmentAccess(
            OTHER_TENANT,
            INSTANCE_ID,
            ATTACHMENT_A,
            "Manager-A"
        );
        assertFalse(otherTenant.referenced());
        assertFalse(otherTenant.readable());

        StoredCommentItem current = comments.findComment(
            TENANT,
            INSTANCE_ID,
            COMMENT_ID
        ).orElseThrow();
        comments.update(
            new CommentUpdate(
                TENANT,
                INSTANCE_ID,
                COMMENT_ID,
                "移除当前附件但保留修订证据",
                List.of("Finance-A"),
                List.of(),
                CommentVisibility.MENTIONED_ONLY,
                EDIT_AT,
                current.version()
            ),
            revision(
                COMMENT_ID,
                2,
                RevisionType.EDIT,
                "移除当前附件但保留修订证据",
                List.of("Finance-A"),
                List.of(),
                CommentVisibility.MENTIONED_ONLY,
                "Manager-A",
                null,
                EDIT_AT
            )
        );
        var historicalReference = comments.findAttachmentAccess(
            TENANT,
            INSTANCE_ID,
            ATTACHMENT_A,
            "Finance-A"
        );
        assertTrue(historicalReference.referenced());
        assertTrue(historicalReference.readable());
    }

    @Test
    void participatesInOuterRollback() {
        commentTransactions.executeWithoutResult(status -> {
            comments.create(
                comment(
                    ROLLBACK_COMMENT_ID,
                    null,
                    "Manager-A",
                    "外层回滚",
                    List.of(),
                    List.of(),
                    CommentVisibility.PARTICIPANTS,
                    COMMENT_AT
                ),
                revision(
                    ROLLBACK_COMMENT_ID,
                    1,
                    RevisionType.CREATE,
                    "外层回滚",
                    List.of(),
                    List.of(),
                    CommentVisibility.PARTICIPANTS,
                    "Manager-A",
                    null,
                    COMMENT_AT
                )
            );
            status.setRollbackOnly();
        });

        assertTrue(comments.findComment(
            TENANT,
            INSTANCE_ID,
            ROLLBACK_COMMENT_ID
        ).isEmpty());
        assertEquals(
            0,
            count(
                """
                select count(*)
                from ap_approval_comment_revision
                where tenant_id = ? and comment_id = ?
                """,
                TENANT,
                values.bindUuid(ROLLBACK_COMMENT_ID)
            )
        );
    }
}
