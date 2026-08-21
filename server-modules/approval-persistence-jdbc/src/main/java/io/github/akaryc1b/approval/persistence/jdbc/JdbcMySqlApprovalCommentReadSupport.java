package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentAttachmentAccess;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentNotFoundException;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentParticipantIdentity;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.StoredCommentItem;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.StoredCommentPage;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.StoredCommentRevision;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class JdbcMySqlApprovalCommentReadSupport {

    private final JdbcMySqlApprovalCommentStoreContext context;

    JdbcMySqlApprovalCommentReadSupport(
        JdbcMySqlApprovalCommentStoreContext context
    ) {
        this.context = Objects.requireNonNull(
            context,
            "context must not be null"
        );
    }

    Optional<StoredCommentItem> findComment(
        String tenantId,
        UUID instanceId,
        UUID commentId
    ) {
        return context.jdbc.query(
            JdbcMySqlApprovalCommentStoreContext.selectComment("""
                where comment.tenant_id = :tenantId
                  and comment.instance_id = :instanceId
                  and comment.comment_id = :commentId
                """),
            context.identityParameters(tenantId, instanceId, commentId),
            (resultSet, rowNumber) -> context.item(resultSet)
        ).stream().findFirst();
    }

    StoredCommentPage findComments(CommentCriteria criteria) {
        CommentCriteria exact = Objects.requireNonNull(
            criteria,
            "criteria must not be null"
        );
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", exact.tenantId())
            .addValue(
                "instanceId",
                context.values.bindUuid(exact.instanceId())
            )
            .addValue("viewerId", exact.viewerId())
            .addValue("limit", exact.limit())
            .addValue("offset", exact.offset());
        String audience = """
            comment.tenant_id = :tenantId
            and comment.instance_id = :instanceId
            and (
                comment.visibility = 'PARTICIPANTS'
                or comment.author_id = :viewerId
                or json_contains(
                    comment.mention_ids_json,
                    json_quote(:viewerId)
                ) = 1
            )
            """;
        Long total = context.jdbc.queryForObject(
            "select count(*) from ap_approval_comment comment where "
                + audience,
            parameters,
            Long.class
        );
        long matched = total == null ? 0L : total;
        if (matched == 0L) {
            return new StoredCommentPage(
                List.of(),
                0L,
                exact.limit(),
                exact.offset()
            );
        }
        List<StoredCommentItem> items = context.jdbc.query(
            JdbcMySqlApprovalCommentStoreContext.selectComment(
                "where " + audience + """
                    order by comment.created_at, comment.comment_id
                    limit :limit offset :offset
                    """
            ),
            parameters,
            (resultSet, rowNumber) -> context.item(resultSet)
        );
        return new StoredCommentPage(
            items,
            matched,
            exact.limit(),
            exact.offset()
        );
    }

    List<StoredCommentRevision> findRevisions(
        String tenantId,
        UUID instanceId,
        UUID commentId
    ) {
        return List.copyOf(context.jdbc.query(
            """
            select
                revision.revision_number, revision.revision_type,
                revision.body, revision.mention_ids_json,
                revision.attachment_ids_json, revision.visibility,
                revision.operator_id, revision.reason,
                revision.occurred_at
            from ap_approval_comment_revision revision
            join ap_approval_comment comment
              on comment.tenant_id = revision.tenant_id
             and comment.comment_id = revision.comment_id
            where comment.tenant_id = :tenantId
              and comment.instance_id = :instanceId
              and comment.comment_id = :commentId
            order by revision.revision_number
            """,
            context.identityParameters(tenantId, instanceId, commentId),
            (resultSet, rowNumber) -> context.revision(resultSet)
        ));
    }

    List<CommentParticipantIdentity> findAdditionalParticipants(
        String tenantId,
        UUID instanceId
    ) {
        return List.copyOf(context.jdbc.query(
            """
            select participant.user_id,
                   participant.display_name,
                   participant.identity_source,
                   participant.object_type,
                   participant.external_identity_value
            from (
                select distinct
                    message.recipient_id as user_id,
                    message.recipient_id as display_name,
                    'APPROVAL_MESSAGE' as identity_source,
                    'USER' as object_type,
                    message.recipient_id as external_identity_value
                from ap_approval_message message
                where message.tenant_id = :tenantId
                  and message.instance_id = :instanceId
                union
                select distinct
                    collaboration_participant.participant_user_id as user_id,
                    collaboration_participant.participant_user_id as display_name,
                    collaboration_participant.identity_source,
                    collaboration_participant.identity_object_type as object_type,
                    collaboration_participant.identity_external_value
                        as external_identity_value
                from ap_task_collaboration_participant collaboration_participant
                join ap_task_collaboration_policy policy
                  on policy.tenant_id = collaboration_participant.tenant_id
                 and policy.policy_id = collaboration_participant.policy_id
                where policy.tenant_id = :tenantId
                  and policy.instance_id = :instanceId
            ) participant
            order by participant.display_name, participant.user_id
            """,
            new MapSqlParameterSource()
                .addValue(
                    "tenantId",
                    JdbcMySqlApprovalCommentStoreContext.requireText(
                        tenantId,
                        "tenantId"
                    )
                )
                .addValue(
                    "instanceId",
                    context.values.bindUuid(
                        Objects.requireNonNull(
                            instanceId,
                            "instanceId must not be null"
                        )
                    )
                ),
            (resultSet, rowNumber) -> new CommentParticipantIdentity(
                resultSet.getString("user_id"),
                resultSet.getString("display_name"),
                resultSet.getString("identity_source"),
                resultSet.getString("object_type"),
                resultSet.getString("external_identity_value")
            )
        ));
    }

    CommentAttachmentAccess findAttachmentAccess(
        String tenantId,
        UUID instanceId,
        UUID attachmentId,
        String viewerId
    ) {
        String exactTenant = JdbcMySqlApprovalCommentStoreContext.requireText(
            tenantId,
            "tenantId"
        );
        UUID exactInstance = Objects.requireNonNull(
            instanceId,
            "instanceId must not be null"
        );
        String exactAttachment = Objects.requireNonNull(
            attachmentId,
            "attachmentId must not be null"
        ).toString();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", exactTenant)
            .addValue(
                "instanceId",
                context.values.bindUuid(exactInstance)
            )
            .addValue("attachmentId", exactAttachment)
            .addValue(
                "viewerId",
                JdbcMySqlApprovalCommentStoreContext.requireText(
                    viewerId,
                    "viewerId"
                )
            );
        Integer referenced = context.jdbc.queryForObject(
            referenceExistsSql(false),
            parameters,
            Integer.class
        );
        if (!Integer.valueOf(1).equals(referenced)) {
            return new CommentAttachmentAccess(false, false);
        }
        Integer readable = context.jdbc.queryForObject(
            referenceExistsSql(true),
            parameters,
            Integer.class
        );
        return new CommentAttachmentAccess(
            true,
            Integer.valueOf(1).equals(readable)
        );
    }

    StoredCommentItem requireComment(
        String tenantId,
        UUID instanceId,
        UUID commentId
    ) {
        return findComment(tenantId, instanceId, commentId).orElseThrow(() ->
            new CommentNotFoundException("approval comment was not found")
        );
    }

    private static String referenceExistsSql(boolean enforceAudience) {
        String audience = enforceAudience ? """
              and (
                  comment.visibility = 'PARTICIPANTS'
                  or comment.author_id = :viewerId
                  or json_contains(
                      comment.mention_ids_json,
                      json_quote(:viewerId)
                  ) = 1
              )
            """ : "";
        return """
            select exists (
                select 1
                from ap_approval_comment comment
                where comment.tenant_id = :tenantId
                  and comment.instance_id = :instanceId
                  and (
                      json_contains(
                          comment.attachment_ids_json,
                          json_quote(:attachmentId)
                      ) = 1
                      or exists (
                          select 1
                          from ap_approval_comment_revision revision
                          where revision.tenant_id = comment.tenant_id
                            and revision.comment_id = comment.comment_id
                            and json_contains(
                                revision.attachment_ids_json,
                                json_quote(:attachmentId)
                            ) = 1
                      )
                  )
            """ + audience + ")";
    }
}
