package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentStatus;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentVisibility;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.RevisionType;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.StoredCommentItem;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.StoredCommentRevision;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

final class JdbcMySqlApprovalCommentStoreContext {

    private static final TypeReference<List<String>> STRING_LIST =
        new TypeReference<>() {
        };
    private static final TypeReference<List<UUID>> UUID_LIST =
        new TypeReference<>() {
        };

    final NamedParameterJdbcTemplate jdbc;
    final JdbcDatabaseValueAdapter values;

    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    JdbcMySqlApprovalCommentStoreContext(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager
    ) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        this.values = JdbcDatabaseValueAdapter.resolve(source);
        if (values.vendor() != ApprovalDatabaseVendor.MYSQL) {
            throw new IllegalArgumentException(
                "JdbcMySqlApprovalCommentStore requires MySQL 8.4"
            );
        }
        this.jdbc = new NamedParameterJdbcTemplate(source);
        this.objectMapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper must not be null"
        );
        this.transactions = new TransactionTemplate(
            Objects.requireNonNull(
                transactionManager,
                "transactionManager must not be null"
            )
        );
    }

    MapSqlParameterSource identityParameters(
        String tenantId,
        UUID instanceId,
        UUID commentId
    ) {
        return new MapSqlParameterSource()
            .addValue("tenantId", requireText(tenantId, "tenantId"))
            .addValue(
                "instanceId",
                values.bindUuid(
                    Objects.requireNonNull(
                        instanceId,
                        "instanceId must not be null"
                    )
                )
            )
            .addValue(
                "commentId",
                values.bindUuid(
                    Objects.requireNonNull(
                        commentId,
                        "commentId must not be null"
                    )
                )
            );
    }

    StoredCommentItem item(ResultSet resultSet) throws SQLException {
        return new StoredCommentItem(
            values.uuid(resultSet, "comment_id"),
            values.uuid(resultSet, "instance_id"),
            values.nullableUuid(resultSet, "parent_comment_id"),
            resultSet.getString("parent_author_id"),
            resultSet.getString("author_id"),
            resultSet.getString("body"),
            decodeStrings(resultSet.getString("mention_ids_json")),
            decodeUuids(resultSet.getString("attachment_ids_json")),
            CommentStatus.valueOf(resultSet.getString("status")),
            CommentVisibility.valueOf(resultSet.getString("visibility")),
            resultSet.getInt("current_revision"),
            values.instant(resultSet, "created_at"),
            values.instant(resultSet, "updated_at"),
            values.nullableInstant(resultSet, "deleted_at"),
            resultSet.getString("deleted_by"),
            resultSet.getString("delete_reason"),
            resultSet.getLong("version")
        );
    }

    StoredCommentRevision revision(ResultSet resultSet) throws SQLException {
        return new StoredCommentRevision(
            resultSet.getInt("revision_number"),
            RevisionType.valueOf(resultSet.getString("revision_type")),
            resultSet.getString("body"),
            decodeStrings(resultSet.getString("mention_ids_json")),
            decodeUuids(resultSet.getString("attachment_ids_json")),
            CommentVisibility.valueOf(resultSet.getString("visibility")),
            resultSet.getString("operator_id"),
            resultSet.getString("reason"),
            values.instant(resultSet, "occurred_at")
        );
    }

    String encode(List<?> references) {
        try {
            return objectMapper.writeValueAsString(references);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "unable to encode approval comment references",
                exception
            );
        }
    }

    <T> T inTransaction(Supplier<T> action) {
        T result = transactions.execute(status -> action.get());
        return Objects.requireNonNull(
            result,
            "approval comment transaction must return a result"
        );
    }

    static String selectComment(String suffix) {
        return """
            select
                comment.comment_id, comment.instance_id,
                comment.parent_comment_id,
                parent.author_id as parent_author_id,
                comment.author_id, comment.body,
                comment.mention_ids_json,
                comment.attachment_ids_json,
                comment.status, comment.visibility,
                comment.current_revision,
                comment.created_at, comment.updated_at,
                comment.deleted_at, comment.deleted_by,
                comment.delete_reason, comment.version
            from ap_approval_comment comment
            left join ap_approval_comment parent
              on parent.tenant_id = comment.tenant_id
             and parent.comment_id = comment.parent_comment_id
            """ + suffix;
    }

    static Instant canonicalInstant(Instant value) {
        return AuditHashCanonicalizer.canonicalInstant(
            Objects.requireNonNull(value, "instant must not be null")
        );
    }

    static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private List<String> decodeStrings(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new SQLException(
                "unable to decode approval comment mentions",
                exception
            );
        }
    }

    private List<UUID> decodeUuids(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, UUID_LIST);
        } catch (JsonProcessingException exception) {
            throw new SQLException(
                "unable to decode approval comment attachments",
                exception
            );
        }
    }
}
