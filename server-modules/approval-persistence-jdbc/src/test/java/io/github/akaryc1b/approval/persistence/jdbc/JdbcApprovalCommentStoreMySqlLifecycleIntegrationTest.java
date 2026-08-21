package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentDeletion;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentStatus;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentUpdate;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentVisibility;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.RevisionType;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.StoredCommentItem;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalCommentStoreMySqlLifecycleIntegrationTest
    extends JdbcApprovalCommentStoreMySqlIntegrationSupport {

    @Test
    void roundTripsLifecycleAudienceRevisionsAndCanonicalTime() {
        assertInstanceOf(
            JdbcMySqlApprovalCommentStore.class,
            comments
        );

        StoredCommentItem parent = comments.create(
            comment(
                COMMENT_ID,
                null,
                "Manager-A",
                "公开评论",
                List.of("Finance-A"),
                List.of(ATTACHMENT_A),
                CommentVisibility.PARTICIPANTS,
                COMMENT_AT
            ),
            revision(
                COMMENT_ID,
                1,
                RevisionType.CREATE,
                "公开评论",
                List.of("Finance-A"),
                List.of(ATTACHMENT_A),
                CommentVisibility.PARTICIPANTS,
                "Manager-A",
                null,
                COMMENT_AT
            )
        );
        assertEquals(canonicalInstant(COMMENT_AT), parent.createdAt());
        assertEquals(canonicalInstant(COMMENT_AT), parent.updatedAt());
        assertEquals(List.of("Finance-A"), parent.mentionIds());
        assertEquals(List.of(ATTACHMENT_A), parent.attachmentIds());

        StoredCommentItem reply = comments.create(
            comment(
                REPLY_ID,
                COMMENT_ID,
                "Finance-A",
                "仅经理可见",
                List.of("Manager-A"),
                List.of(ATTACHMENT_B),
                CommentVisibility.MENTIONED_ONLY,
                COMMENT_AT.plusSeconds(1)
            ),
            revision(
                REPLY_ID,
                1,
                RevisionType.CREATE,
                "仅经理可见",
                List.of("Manager-A"),
                List.of(ATTACHMENT_B),
                CommentVisibility.MENTIONED_ONLY,
                "Finance-A",
                null,
                COMMENT_AT.plusSeconds(1)
            )
        );
        assertEquals("Manager-A", reply.parentAuthorId());

        assertEquals(2, page("Manager-A").total());
        assertEquals(2, page("Finance-A").total());
        assertEquals(1, page("Outside-User").total());
        assertEquals(0, comments.findComments(new CommentCriteria(
            TENANT.toLowerCase(),
            INSTANCE_ID,
            "Manager-A",
            20,
            0
        )).total());

        StoredCommentItem edited = comments.update(
            new CommentUpdate(
                TENANT,
                INSTANCE_ID,
                REPLY_ID,
                "改为仅财务复核员可见",
                List.of("Finance-Reviewer"),
                List.of(ATTACHMENT_B),
                CommentVisibility.MENTIONED_ONLY,
                EDIT_AT,
                reply.version()
            ),
            revision(
                REPLY_ID,
                2,
                RevisionType.EDIT,
                "改为仅财务复核员可见",
                List.of("Finance-Reviewer"),
                List.of(ATTACHMENT_B),
                CommentVisibility.MENTIONED_ONLY,
                "Finance-A",
                "调整可见范围",
                EDIT_AT
            )
        );
        assertEquals(2, edited.version());
        assertEquals(2, edited.currentRevision());
        assertEquals(canonicalInstant(EDIT_AT), edited.updatedAt());
        assertEquals(List.of("Finance-Reviewer"), edited.mentionIds());
        assertEquals(1, page("Manager-A").total());
        assertEquals(2, page("Finance-Reviewer").total());

        var revisions = comments.findRevisions(
            TENANT,
            INSTANCE_ID,
            REPLY_ID
        );
        assertEquals(
            List.of(RevisionType.CREATE, RevisionType.EDIT),
            revisions.stream()
                .map(item -> item.revisionType())
                .toList()
        );
        assertEquals(
            canonicalInstant(EDIT_AT),
            revisions.getLast().occurredAt()
        );

        StoredCommentItem deleted = comments.delete(
            new CommentDeletion(
                TENANT,
                INSTANCE_ID,
                REPLY_ID,
                "Finance-A",
                "内容已失效",
                "评论已删除",
                DELETE_AT,
                edited.version()
            ),
            revision(
                REPLY_ID,
                3,
                RevisionType.DELETE,
                edited.body(),
                edited.mentionIds(),
                edited.attachmentIds(),
                edited.visibility(),
                "Finance-A",
                "内容已失效",
                DELETE_AT
            )
        );
        assertEquals(CommentStatus.DELETED, deleted.status());
        assertEquals("评论已删除", deleted.body());
        assertEquals(3, deleted.version());
        assertEquals(3, deleted.currentRevision());
        assertEquals(canonicalInstant(DELETE_AT), deleted.deletedAt());
        assertEquals(3, comments.findRevisions(
            TENANT,
            INSTANCE_ID,
            REPLY_ID
        ).size());
    }

    @Test
    void onlyOneConcurrentCasWinsAndFailedRevisionRollsBack()
        throws Exception {
        comments.create(
            comment(
                CAS_COMMENT_ID,
                null,
                "Manager-A",
                "并发原文",
                List.of(),
                List.of(),
                CommentVisibility.PARTICIPANTS,
                COMMENT_AT
            ),
            revision(
                CAS_COMMENT_ID,
                1,
                RevisionType.CREATE,
                "并发原文",
                List.of(),
                List.of(),
                CommentVisibility.PARTICIPANTS,
                "Manager-A",
                null,
                COMMENT_AT
            )
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<String> left = executor.submit(
                () -> concurrentUpdate(
                    "并发正文甲",
                    "Manager-A",
                    ready,
                    start
                )
            );
            Future<String> right = executor.submit(
                () -> concurrentUpdate(
                    "并发正文乙",
                    "Finance-A",
                    ready,
                    start
                )
            );
            await(ready);
            start.countDown();
            List<String> outcomes = List.of(
                left.get(20, TimeUnit.SECONDS),
                right.get(20, TimeUnit.SECONDS)
            );
            assertEquals(
                1,
                outcomes.stream()
                    .filter("UPDATED"::equals)
                    .count()
            );
            assertEquals(
                1,
                outcomes.stream()
                    .filter(
                        "APPROVAL_COMMENT_CONCURRENT_MODIFICATION"::equals
                    )
                    .count()
            );
        }

        StoredCommentItem winner = comments.findComment(
            TENANT,
            INSTANCE_ID,
            CAS_COMMENT_ID
        ).orElseThrow();
        assertEquals(2, winner.version());
        assertTrue(Set.of("并发正文甲", "并发正文乙").contains(
            winner.body()
        ));
        assertEquals(2, comments.findRevisions(
            TENANT,
            INSTANCE_ID,
            CAS_COMMENT_ID
        ).size());

        String beforeBody = winner.body();
        assertThrows(
            DuplicateKeyException.class,
            () -> comments.update(
                new CommentUpdate(
                    TENANT,
                    INSTANCE_ID,
                    CAS_COMMENT_ID,
                    "必须回滚",
                    List.of(),
                    List.of(),
                    CommentVisibility.PARTICIPANTS,
                    EDIT_AT.plusSeconds(1),
                    winner.version()
                ),
                revision(
                    CAS_COMMENT_ID,
                    2,
                    RevisionType.EDIT,
                    "重复修订",
                    List.of(),
                    List.of(),
                    CommentVisibility.PARTICIPANTS,
                    "Manager-A",
                    null,
                    EDIT_AT.plusSeconds(1)
                )
            )
        );
        StoredCommentItem restored = comments.findComment(
            TENANT,
            INSTANCE_ID,
            CAS_COMMENT_ID
        ).orElseThrow();
        assertEquals(beforeBody, restored.body());
        assertEquals(2, restored.version());
        assertEquals(2, comments.findRevisions(
            TENANT,
            INSTANCE_ID,
            CAS_COMMENT_ID
        ).size());
    }
}
