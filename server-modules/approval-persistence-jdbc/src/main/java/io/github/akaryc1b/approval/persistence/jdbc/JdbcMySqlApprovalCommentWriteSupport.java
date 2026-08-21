package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.ApprovalComment;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentDeletion;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentRevision;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentUpdate;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.StoredCommentItem;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.util.Objects;

final class JdbcMySqlApprovalCommentWriteSupport {

    private final JdbcMySqlApprovalCommentStoreContext context;
    private final JdbcMySqlApprovalCommentReadSupport reads;

    JdbcMySqlApprovalCommentWriteSupport(
        JdbcMySqlApprovalCommentStoreContext context,
        JdbcMySqlApprovalCommentReadSupport reads
    ) {
        this.context = Objects.requireNonNull(
            context,
            "context must not be null"
        );
        this.reads = Objects.requireNonNull(
            reads,
            "reads must not be null"
        );
    }

    StoredCommentItem create(
        ApprovalComment comment,
        CommentRevision revision
    ) {
        ApprovalComment exactComment = Objects.requireNonNull(
            comment,
            "comment must not be null"
        );
        CommentRevision exactRevision = Objects.requireNonNull(
            revision,
            "revision must not be null"
        );
        return context.inTransaction(() -> {
            int inserted = context.jdbc.update(
                """
                insert into ap_approval_comment (
                    comment_id, tenant_id, instance_id, parent_comment_id,
                    author_id, body, mention_ids_json, attachment_ids_json,
                    status, visibility, current_revision, created_at, updated_at,
                    deleted_at, deleted_by, delete_reason, version
                ) values (
                    :commentId, :tenantId, :instanceId, :parentCommentId,
                    :authorId, :body, cast(:mentionIdsJson as json),
                    cast(:attachmentIdsJson as json), :status, :visibility,
                    :currentRevision, :createdAt, :updatedAt,
                    :deletedAt, :deletedBy, :deleteReason, :version
                )
                """,
                commentParameters(exactComment)
            );
            if (inserted != 1) {
                throw new IllegalStateException(
                    "approval comment was not inserted"
                );
            }
            appendRevision(exactRevision);
            return reads.requireComment(
                exactComment.tenantId(),
                exactComment.instanceId(),
                exactComment.commentId()
            );
        });
    }

    StoredCommentItem update(
        CommentUpdate update,
        CommentRevision revision
    ) {
        CommentUpdate exactUpdate = Objects.requireNonNull(
            update,
            "update must not be null"
        );
        CommentRevision exactRevision = Objects.requireNonNull(
            revision,
            "revision must not be null"
        );
        return context.inTransaction(() -> {
            int changed = context.jdbc.update(
                """
                update ap_approval_comment
                set body = :body,
                    mention_ids_json = cast(:mentionIdsJson as json),
                    attachment_ids_json = cast(:attachmentIdsJson as json),
                    visibility = :visibility,
                    current_revision = :revisionNumber,
                    updated_at = :updatedAt,
                    version = version + 1
                where tenant_id = :tenantId
                  and instance_id = :instanceId
                  and comment_id = :commentId
                  and status = 'ACTIVE'
                  and version = :expectedVersion
                """,
                new MapSqlParameterSource()
                    .addValue("tenantId", exactUpdate.tenantId())
                    .addValue(
                        "instanceId",
                        context.values.bindUuid(exactUpdate.instanceId())
                    )
                    .addValue(
                        "commentId",
                        context.values.bindUuid(exactUpdate.commentId())
                    )
                    .addValue("body", exactUpdate.body())
                    .addValue(
                        "mentionIdsJson",
                        context.encode(exactUpdate.mentionIds())
                    )
                    .addValue(
                        "attachmentIdsJson",
                        context.encode(exactUpdate.attachmentIds())
                    )
                    .addValue(
                        "visibility",
                        exactUpdate.visibility().name()
                    )
                    .addValue(
                        "revisionNumber",
                        exactRevision.revisionNumber()
                    )
                    .addValue(
                        "updatedAt",
                        context.values.bindInstant(
                            JdbcMySqlApprovalCommentStoreContext.canonicalInstant(
                                exactUpdate.updatedAt()
                            )
                        )
                    )
                    .addValue(
                        "expectedVersion",
                        exactUpdate.expectedVersion()
                    )
            );
            if (changed != 1) {
                throw concurrentConflict();
            }
            appendRevision(exactRevision);
            return reads.requireComment(
                exactUpdate.tenantId(),
                exactUpdate.instanceId(),
                exactUpdate.commentId()
            );
        });
    }

    StoredCommentItem delete(
        CommentDeletion deletion,
        CommentRevision revision
    ) {
        CommentDeletion exactDeletion = Objects.requireNonNull(
            deletion,
            "deletion must not be null"
        );
        CommentRevision exactRevision = Objects.requireNonNull(
            revision,
            "revision must not be null"
        );
        return context.inTransaction(() -> {
            int changed = context.jdbc.update(
                """
                update ap_approval_comment
                set body = :tombstoneBody,
                    status = 'DELETED',
                    current_revision = :revisionNumber,
                    updated_at = :deletedAt,
                    deleted_at = :deletedAt,
                    deleted_by = :deletedBy,
                    delete_reason = :deleteReason,
                    version = version + 1
                where tenant_id = :tenantId
                  and instance_id = :instanceId
                  and comment_id = :commentId
                  and status = 'ACTIVE'
                  and version = :expectedVersion
                """,
                new MapSqlParameterSource()
                    .addValue("tenantId", exactDeletion.tenantId())
                    .addValue(
                        "instanceId",
                        context.values.bindUuid(exactDeletion.instanceId())
                    )
                    .addValue(
                        "commentId",
                        context.values.bindUuid(exactDeletion.commentId())
                    )
                    .addValue(
                        "tombstoneBody",
                        exactDeletion.tombstoneBody()
                    )
                    .addValue(
                        "revisionNumber",
                        exactRevision.revisionNumber()
                    )
                    .addValue(
                        "deletedAt",
                        context.values.bindInstant(
                            JdbcMySqlApprovalCommentStoreContext.canonicalInstant(
                                exactDeletion.deletedAt()
                            )
                        )
                    )
                    .addValue("deletedBy", exactDeletion.deletedBy())
                    .addValue(
                        "deleteReason",
                        exactDeletion.deleteReason()
                    )
                    .addValue(
                        "expectedVersion",
                        exactDeletion.expectedVersion()
                    )
            );
            if (changed != 1) {
                throw concurrentConflict();
            }
            appendRevision(exactRevision);
            return reads.requireComment(
                exactDeletion.tenantId(),
                exactDeletion.instanceId(),
                exactDeletion.commentId()
            );
        });
    }

    private void appendRevision(CommentRevision revision) {
        int inserted = context.jdbc.update(
            """
            insert into ap_approval_comment_revision (
                tenant_id, comment_id, revision_number, revision_type,
                body, mention_ids_json, attachment_ids_json, visibility,
                operator_id, reason, occurred_at
            ) values (
                :tenantId, :commentId, :revisionNumber, :revisionType,
                :body, cast(:mentionIdsJson as json),
                cast(:attachmentIdsJson as json),
                :visibility, :operatorId, :reason, :occurredAt
            )
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", revision.tenantId())
                .addValue(
                    "commentId",
                    context.values.bindUuid(revision.commentId())
                )
                .addValue("revisionNumber", revision.revisionNumber())
                .addValue(
                    "revisionType",
                    revision.revisionType().name()
                )
                .addValue("body", revision.body())
                .addValue(
                    "mentionIdsJson",
                    context.encode(revision.mentionIds())
                )
                .addValue(
                    "attachmentIdsJson",
                    context.encode(revision.attachmentIds())
                )
                .addValue(
                    "visibility",
                    revision.visibility().name()
                )
                .addValue("operatorId", revision.operatorId())
                .addValue("reason", revision.reason())
                .addValue(
                    "occurredAt",
                    context.values.bindInstant(
                        JdbcMySqlApprovalCommentStoreContext.canonicalInstant(
                            revision.occurredAt()
                        )
                    )
                )
        );
        if (inserted != 1) {
            throw new IllegalStateException(
                "approval comment revision was not inserted"
            );
        }
    }

    private MapSqlParameterSource commentParameters(
        ApprovalComment comment
    ) {
        return new MapSqlParameterSource()
            .addValue(
                "commentId",
                context.values.bindUuid(comment.commentId())
            )
            .addValue("tenantId", comment.tenantId())
            .addValue(
                "instanceId",
                context.values.bindUuid(comment.instanceId())
            )
            .addValue(
                "parentCommentId",
                context.values.bindNullableUuid(comment.parentCommentId())
            )
            .addValue("authorId", comment.authorId())
            .addValue("body", comment.body())
            .addValue(
                "mentionIdsJson",
                context.encode(comment.mentionIds())
            )
            .addValue(
                "attachmentIdsJson",
                context.encode(comment.attachmentIds())
            )
            .addValue("status", comment.status().name())
            .addValue("visibility", comment.visibility().name())
            .addValue(
                "currentRevision",
                comment.currentRevision()
            )
            .addValue(
                "createdAt",
                context.values.bindInstant(
                    JdbcMySqlApprovalCommentStoreContext.canonicalInstant(
                        comment.createdAt()
                    )
                )
            )
            .addValue(
                "updatedAt",
                context.values.bindInstant(
                    JdbcMySqlApprovalCommentStoreContext.canonicalInstant(
                        comment.updatedAt()
                    )
                )
            )
            .addValue(
                "deletedAt",
                context.values.bindNullableInstant(
                    comment.deletedAt() == null
                        ? null
                        : JdbcMySqlApprovalCommentStoreContext.canonicalInstant(
                            comment.deletedAt()
                        )
                )
            )
            .addValue("deletedBy", comment.deletedBy())
            .addValue("deleteReason", comment.deleteReason())
            .addValue("version", comment.version());
    }

    private static CommentConflictException concurrentConflict() {
        return new CommentConflictException(
            "APPROVAL_COMMENT_CONCURRENT_MODIFICATION",
            "approval comment changed concurrently"
        );
    }
}
