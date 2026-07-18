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
import java.util.UUID;

/**
 * PostgreSQL implementation for immutable approval comments.
 */
public final class JdbcApprovalCommentStore implements ApprovalCommentStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
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
    public void append(ApprovalComment comment) {
        int inserted = jdbc.update(
            """
            insert into ap_approval_comment (
                comment_id, tenant_id, instance_id, author_id, body,
                mention_ids_json, attachment_ids_json, created_at
            ) values (
                :commentId, :tenantId, :instanceId, :authorId, :body,
                cast(:mentionIdsJson as jsonb), cast(:attachmentIdsJson as jsonb), :createdAt
            )
            """,
            new MapSqlParameterSource()
                .addValue("commentId", comment.commentId())
                .addValue("tenantId", comment.tenantId())
                .addValue("instanceId", comment.instanceId())
                .addValue("authorId", comment.authorId())
                .addValue("body", comment.body())
                .addValue("mentionIdsJson", encode(comment.mentionIds()))
                .addValue("attachmentIdsJson", encode(comment.attachmentIds()))
                .addValue("createdAt", offset(comment.createdAt()))
        );
        if (inserted != 1) {
            throw new IllegalStateException("approval comment was not inserted");
        }
    }

    @Override
    public StoredCommentPage findComments(CommentCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", criteria.tenantId())
            .addValue("instanceId", criteria.instanceId())
            .addValue("limit", criteria.limit())
            .addValue("offset", criteria.offset());
        Long total = jdbc.queryForObject(
            """
            select count(*)
            from ap_approval_comment
            where tenant_id = :tenantId and instance_id = :instanceId
            """,
            parameters,
            Long.class
        );
        long matched = total == null ? 0 : total;
        if (matched == 0) {
            return new StoredCommentPage(List.of(), 0, criteria.limit(), criteria.offset());
        }
        List<StoredCommentItem> items = jdbc.query(
            """
            select
                comment_id, instance_id, author_id, body,
                mention_ids_json, attachment_ids_json, created_at
            from ap_approval_comment
            where tenant_id = :tenantId and instance_id = :instanceId
            order by created_at, comment_id
            limit :limit offset :offset
            """,
            parameters,
            (resultSet, rowNumber) -> item(resultSet)
        );
        return new StoredCommentPage(items, matched, criteria.limit(), criteria.offset());
    }

    private StoredCommentItem item(ResultSet resultSet) throws SQLException {
        return new StoredCommentItem(
            resultSet.getObject("comment_id", UUID.class),
            resultSet.getObject("instance_id", UUID.class),
            resultSet.getString("author_id"),
            resultSet.getString("body"),
            decode(resultSet.getString("mention_ids_json")),
            decode(resultSet.getString("attachment_ids_json")),
            instant(resultSet, "created_at")
        );
    }

    private String encode(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("unable to encode approval comment references", exception);
        }
    }

    private List<String> decode(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new SQLException("unable to decode approval comment references", exception);
        }
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
