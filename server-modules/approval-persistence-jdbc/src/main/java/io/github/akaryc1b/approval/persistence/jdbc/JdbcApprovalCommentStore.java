package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL comment lifecycle, revision evidence and database-enforced audience filtering. */
public final class JdbcApprovalCommentStore implements ApprovalCommentStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<UUID>> UUID_LIST = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcApprovalCommentStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public StoredCommentItem create(ApprovalComment comment, CommentRevision revision) {
        Objects.requireNonNull(comment, "comment must not be null");
        Objects.requireNonNull(revision, "revision must not be null");
        int inserted = jdbc.update(
            """
            insert into ap_approval_comment (
                comment_id, tenant_id, instance_id, parent_comment_id,
                author_id, body, mention_ids_json, attachment_ids_json,
                status, visibility, current_revision, created_at, updated_at,
                deleted_at, deleted_by, delete_reason, version
            ) values (
                :commentId, :tenantId, :instanceId, :parentCommentId,
                :authorId, :body, cast(:mentionIdsJson as jsonb),
                cast(:attachmentIdsJson as jsonb), :status, :visibility,
                :currentRevision, :createdAt, :updatedAt,
                :deletedAt, :deletedBy, :deleteReason, :version
            )
            """,
            commentParameters(comment)
        );
        if (inserted != 1) {
            throw new IllegalStateException("approval comment was not inserted");
        }
        appendRevision(revision);
        return requireComment(comment.tenantId(), comment.instanceId(), comment.commentId());
    }

    @Override
    public Optional<StoredCommentItem> findComment(
        String tenantId,
        UUID instanceId,
        UUID commentId
    ) {
        return jdbc.query(
            selectComment("""
                where comment.tenant_id = :tenantId
                  and comment.instance_id = :instanceId
                  and comment.comment_id = :commentId
                """),
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue("instanceId", Objects.requireNonNull(instanceId, "instanceId must not be null"))
                .addValue("commentId", Objects.requireNonNull(commentId, "commentId must not be null")),
            (resultSet, rowNumber) -> item(resultSet)
        ).stream().findFirst();
    }

    @Override
    public StoredCommentPage findComments(CommentCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", criteria.tenantId())
            .addValue("instanceId", criteria.instanceId())
            .addValue("viewerId", criteria.viewerId())
            .addValue("limit", criteria.limit())
            .addValue("offset", criteria.offset());
        String audience = """
            comment.tenant_id = :tenantId
            and comment.instance_id = :instanceId
            and (
                comment.visibility = 'PARTICIPANTS'
                or comment.author_id = :viewerId
                or jsonb_exists(comment.mention_ids_json, :viewerId)
            )
            """;
        Long total = jdbc.queryForObject(
            "select count(*) from ap_approval_comment comment where " + audience,
            parameters,
            Long.class
        );
        long matched = total == null ? 0 : total;
        if (matched == 0) {
            return new StoredCommentPage(List.of(), 0, criteria.limit(), criteria.offset());
        }
        List<StoredCommentItem> items = jdbc.query(
            selectComment("where " + audience + """
                order by comment.created_at, comment.comment_id
                limit :limit offset :offset
                """),
            parameters,
            (resultSet, rowNumber) -> item(resultSet)
        );
        return new StoredCommentPage(items, matched, criteria.limit(), criteria.offset());
    }

    @Override
    public List<StoredCommentRevision> findRevisions(
        String tenantId,
        UUID instanceId,
        UUID commentId
    ) {
        return List.copyOf(jdbc.query(
            """
            select
                revision.revision_number, revision.revision_type, revision.body,
                revision.mention_ids_json, revision.attachment_ids_json,
                revision.visibility, revision.operator_id, revision.reason,
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
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue("instanceId", Objects.requireNonNull(instanceId, "instanceId must not be null"))
                .addValue("commentId", Objects.requireNonNull(commentId, "commentId must not be null")),
            (resultSet, rowNumber) -> revision(resultSet)
        ));
    }

    @Override
    public StoredCommentItem update(CommentUpdate update, CommentRevision revision) {
        Objects.requireNonNull(update, "update must not be null");
        Objects.requireNonNull(revision, "revision must not be null");
        int changed = jdbc.update(
            """
            update ap_approval_comment
            set body = :body,
                mention_ids_json = cast(:mentionIdsJson as jsonb),
                attachment_ids_json = cast(:attachmentIdsJson as jsonb),
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
                .addValue("tenantId", update.tenantId())
                .addValue("instanceId", update.instanceId())
                .addValue("commentId", update.commentId())
                .addValue("body", update.body())
                .addValue("mentionIdsJson", encode(update.mentionIds()))
                .addValue("attachmentIdsJson", encode(update.attachmentIds()))
                .addValue("visibility", update.visibility().name())
                .addValue("revisionNumber", revision.revisionNumber())
                .addValue("updatedAt", offset(update.updatedAt()))
                .addValue("expectedVersion", update.expectedVersion())
        );
        if (changed != 1) {
            throw concurrentConflict();
        }
        appendRevision(revision);
        return requireComment(update.tenantId(), update.instanceId(), update.commentId());
    }

    @Override
    public StoredCommentItem delete(CommentDeletion deletion, CommentRevision revision) {
        Objects.requireNonNull(deletion, "deletion must not be null");
        Objects.requireNonNull(revision, "revision must not be null");
        int changed = jdbc.update(
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
                .addValue("tenantId", deletion.tenantId())
                .addValue("instanceId", deletion.instanceId())
                .addValue("commentId", deletion.commentId())
                .addValue("tombstoneBody", deletion.tombstoneBody())
                .addValue("revisionNumber", revision.revisionNumber())
                .addValue("deletedAt", offset(deletion.deletedAt()))
                .addValue("deletedBy", deletion.deletedBy())
                .addValue("deleteReason", deletion.deleteReason())
                .addValue("expectedVersion", deletion.expectedVersion())
        );
        if (changed != 1) {
            throw concurrentConflict();
        }
        appendRevision(revision);
        return requireComment(deletion.tenantId(), deletion.instanceId(), deletion.commentId());
    }

    @Override
    public List<CommentParticipantIdentity> findAdditionalParticipants(
        String tenantId,
        UUID instanceId
    ) {
        return List.copyOf(jdbc.query(
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
                    collaboration_participant.identity_external_value as external_identity_value
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
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue("instanceId", Objects.requireNonNull(instanceId, "instanceId must not be null")),
            (resultSet, rowNumber) -> new CommentParticipantIdentity(
                resultSet.getString("user_id"),
                resultSet.getString("display_name"),
                resultSet.getString("identity_source"),
                resultSet.getString("object_type"),
                resultSet.getString("external_identity_value")
            )
        ));
    }

    @Override
    public CommentAttachmentAccess findAttachmentAccess(
        String tenantId,
        UUID instanceId,
        UUID attachmentId,
        String viewerId
    ) {
        Boolean referenced = jdbc.queryForObject(
            """
            select exists (
                select 1
                from ap_approval_comment comment
                where comment.tenant_id = :tenantId
                  and comment.instance_id = :instanceId
                  and (
                      jsonb_exists(comment.attachment_ids_json, :attachmentId)
                      or exists (
                          select 1
                          from ap_approval_comment_revision revision
                          where revision.tenant_id = comment.tenant_id
                            and revision.comment_id = comment.comment_id
                            and jsonb_exists(revision.attachment_ids_json, :attachmentId)
                      )
                  )
            )
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue("instanceId", Objects.requireNonNull(instanceId, "instanceId must not be null"))
                .addValue("attachmentId", Objects.requireNonNull(attachmentId, "attachmentId must not be null").toString()),
            Boolean.class
        );
        if (!Boolean.TRUE.equals(referenced)) {
            return new CommentAttachmentAccess(false, false);
        }
        Boolean readable = jdbc.queryForObject(
            """
            select exists (
                select 1
                from ap_approval_comment comment
                where comment.tenant_id = :tenantId
                  and comment.instance_id = :instanceId
                  and (
                      jsonb_exists(comment.attachment_ids_json, :attachmentId)
                      or exists (
                          select 1
                          from ap_approval_comment_revision revision
                          where revision.tenant_id = comment.tenant_id
                            and revision.comment_id = comment.comment_id
                            and jsonb_exists(revision.attachment_ids_json, :attachmentId)
                      )
                  )
                  and (
                      comment.visibility = 'PARTICIPANTS'
                      or comment.author_id = :viewerId
                      or jsonb_exists(comment.mention_ids_json, :viewerId)
                  )
            )
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("instanceId", instanceId)
                .addValue("attachmentId", attachmentId.toString())
                .addValue("viewerId", requireText(viewerId, "viewerId")),
            Boolean.class
        );
        return new CommentAttachmentAccess(true, Boolean.TRUE.equals(readable));
    }

    private StoredCommentItem requireComment(String tenantId, UUID instanceId, UUID commentId) {
        return findComment(tenantId, instanceId, commentId).orElseThrow(() ->
            new CommentNotFoundException("approval comment was not found")
        );
    }

    private void appendRevision(CommentRevision revision) {
        int inserted = jdbc.update(
            """
            insert into ap_approval_comment_revision (
                tenant_id, comment_id, revision_number, revision_type,
                body, mention_ids_json, attachment_ids_json, visibility,
                operator_id, reason, occurred_at
            ) values (
                :tenantId, :commentId, :revisionNumber, :revisionType,
                :body, cast(:mentionIdsJson as jsonb), cast(:attachmentIdsJson as jsonb),
                :visibility, :operatorId, :reason, :occurredAt
            )
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", revision.tenantId())
                .addValue("commentId", revision.commentId())
                .addValue("revisionNumber", revision.revisionNumber())
                .addValue("revisionType", revision.revisionType().name())
                .addValue("body", revision.body())
                .addValue("mentionIdsJson", encode(revision.mentionIds()))
                .addValue("attachmentIdsJson", encode(revision.attachmentIds()))
                .addValue("visibility", revision.visibility().name())
                .addValue("operatorId", revision.operatorId())
                .addValue("reason", revision.reason())
                .addValue("occurredAt", offset(revision.occurredAt()))
        );
        if (inserted != 1) {
            throw new IllegalStateException("approval comment revision was not inserted");
        }
    }

    private MapSqlParameterSource commentParameters(ApprovalComment comment) {
        return new MapSqlParameterSource()
            .addValue("commentId", comment.commentId())
            .addValue("tenantId", comment.tenantId())
            .addValue("instanceId", comment.instanceId())
            .addValue("parentCommentId", comment.parentCommentId())
            .addValue("authorId", comment.authorId())
            .addValue("body", comment.body())
            .addValue("mentionIdsJson", encode(comment.mentionIds()))
            .addValue("attachmentIdsJson", encode(comment.attachmentIds()))
            .addValue("status", comment.status().name())
            .addValue("visibility", comment.visibility().name())
            .addValue("currentRevision", comment.currentRevision())
            .addValue("createdAt", offset(comment.createdAt()))
            .addValue("updatedAt", offset(comment.updatedAt()))
            .addValue("deletedAt", comment.deletedAt() == null ? null : offset(comment.deletedAt()))
            .addValue("deletedBy", comment.deletedBy())
            .addValue("deleteReason", comment.deleteReason())
            .addValue("version", comment.version());
    }

    private static String selectComment(String suffix) {
        return """
            select
                comment.comment_id, comment.instance_id, comment.parent_comment_id,
                parent.author_id as parent_author_id,
                comment.author_id, comment.body,
                comment.mention_ids_json, comment.attachment_ids_json,
                comment.status, comment.visibility, comment.current_revision,
                comment.created_at, comment.updated_at,
                comment.deleted_at, comment.deleted_by, comment.delete_reason,
                comment.version
            from ap_approval_comment comment
            left join ap_approval_comment parent
              on parent.tenant_id = comment.tenant_id
             and parent.comment_id = comment.parent_comment_id
            """ + suffix;
    }

    private StoredCommentItem item(ResultSet resultSet) throws SQLException {
        return new StoredCommentItem(
            resultSet.getObject("comment_id", UUID.class),
            resultSet.getObject("instance_id", UUID.class),
            resultSet.getObject("parent_comment_id", UUID.class),
            resultSet.getString("parent_author_id"),
            resultSet.getString("author_id"),
            resultSet.getString("body"),
            decodeStrings(resultSet.getString("mention_ids_json")),
            decodeUuids(resultSet.getString("attachment_ids_json")),
            CommentStatus.valueOf(resultSet.getString("status")),
            CommentVisibility.valueOf(resultSet.getString("visibility")),
            resultSet.getInt("current_revision"),
            instant(resultSet, "created_at"),
            instant(resultSet, "updated_at"),
            nullableInstant(resultSet, "deleted_at"),
            resultSet.getString("deleted_by"),
            resultSet.getString("delete_reason"),
            resultSet.getLong("version")
        );
    }

    private StoredCommentRevision revision(ResultSet resultSet) throws SQLException {
        return new StoredCommentRevision(
            resultSet.getInt("revision_number"),
            RevisionType.valueOf(resultSet.getString("revision_type")),
            resultSet.getString("body"),
            decodeStrings(resultSet.getString("mention_ids_json")),
            decodeUuids(resultSet.getString("attachment_ids_json")),
            CommentVisibility.valueOf(resultSet.getString("visibility")),
            resultSet.getString("operator_id"),
            resultSet.getString("reason"),
            instant(resultSet, "occurred_at")
        );
    }

    private String encode(List<?> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("unable to encode approval comment references", exception);
        }
    }

    private List<String> decodeStrings(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new SQLException("unable to decode approval comment mentions", exception);
        }
    }

    private List<UUID> decodeUuids(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, UUID_LIST);
        } catch (JsonProcessingException exception) {
            throw new SQLException("unable to decode approval comment attachments", exception);
        }
    }

    private static CommentConflictException concurrentConflict() {
        return new CommentConflictException(
            "APPROVAL_COMMENT_CONCURRENT_MODIFICATION",
            "approval comment changed concurrently"
        );
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
