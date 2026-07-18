package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL implementation of the user-facing approval message center.
 */
public final class JdbcApprovalMessageStore implements ApprovalMessageStore {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcApprovalMessageStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public int append(List<ApprovalMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int inserted = 0;
        for (ApprovalMessage message : messages) {
            inserted += jdbc.update(
                """
                insert into ap_approval_message (
                    message_id, tenant_id, recipient_id, sender_id,
                    instance_id, task_id, message_type, title, body,
                    metadata_json, dedup_key, created_at, read_at
                ) values (
                    :messageId, :tenantId, :recipientId, :senderId,
                    :instanceId, :taskId, :messageType, :title, :body,
                    cast(:metadataJson as jsonb), :dedupKey, :createdAt, null
                )
                on conflict (tenant_id, dedup_key) do nothing
                """,
                new MapSqlParameterSource()
                    .addValue("messageId", message.messageId())
                    .addValue("tenantId", message.tenantId())
                    .addValue("recipientId", message.recipientId())
                    .addValue("senderId", message.senderId())
                    .addValue("instanceId", message.instanceId())
                    .addValue("taskId", message.taskId())
                    .addValue("messageType", message.messageType().name())
                    .addValue("title", message.title())
                    .addValue("body", message.body())
                    .addValue("metadataJson", encode(message.metadata()))
                    .addValue("dedupKey", message.dedupKey())
                    .addValue("createdAt", offset(message.createdAt()))
            );
        }
        return inserted;
    }

    @Override
    public MessagePage findMessages(MessageCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", criteria.tenantId())
            .addValue("recipientId", criteria.recipientId())
            .addValue("unreadOnly", criteria.unreadOnly())
            .addValue("limit", criteria.limit())
            .addValue("offset", criteria.offset());
        Long total = jdbc.queryForObject(
            """
            select count(*)
            from ap_approval_message message
            where message.tenant_id = :tenantId
              and message.recipient_id = :recipientId
              and (:unreadOnly = false or message.read_at is null)
            """,
            parameters,
            Long.class
        );
        long matched = total == null ? 0 : total;
        if (matched == 0) {
            return new MessagePage(List.of(), 0, criteria.limit(), criteria.offset());
        }
        List<MessageItem> items = jdbc.query(
            """
            select
                message.message_id,
                message.message_type,
                message.instance_id,
                message.task_id,
                message.sender_id,
                message.title,
                message.body,
                message.metadata_json,
                message.read_at,
                message.created_at,
                instance.business_key,
                instance.amount,
                instance.supplier,
                instance.purchase_order_reference,
                instance.status as instance_status
            from ap_approval_message message
            join ap_approval_instance instance
              on instance.instance_id = message.instance_id
             and instance.tenant_id = message.tenant_id
            where message.tenant_id = :tenantId
              and message.recipient_id = :recipientId
              and (:unreadOnly = false or message.read_at is null)
            order by message.created_at desc, message.message_id desc
            limit :limit offset :offset
            """,
            parameters,
            messageItemMapper()
        );
        return new MessagePage(items, matched, criteria.limit(), criteria.offset());
    }

    @Override
    public long countUnread(MessageIdentity identity) {
        Long count = jdbc.queryForObject(
            """
            select count(*)
            from ap_approval_message
            where tenant_id = :tenantId
              and recipient_id = :recipientId
              and read_at is null
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", identity.tenantId())
                .addValue("recipientId", identity.recipientId()),
            Long.class
        );
        return count == null ? 0 : count;
    }

    @Override
    public Optional<MessageReadResult> markRead(
        String tenantId,
        String recipientId,
        UUID messageId,
        Instant readAt
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("recipientId", recipientId)
            .addValue("messageId", messageId)
            .addValue("readAt", offset(readAt));
        List<MessageReadResult> updated = jdbc.query(
            """
            update ap_approval_message
            set read_at = :readAt
            where tenant_id = :tenantId
              and recipient_id = :recipientId
              and message_id = :messageId
              and read_at is null
            returning message_id, instance_id, message_type, sender_id, read_at
            """,
            parameters,
            readResultMapper(true)
        );
        if (!updated.isEmpty()) {
            return Optional.of(updated.getFirst());
        }
        return jdbc.query(
            """
            select message_id, instance_id, message_type, sender_id, read_at
            from ap_approval_message
            where tenant_id = :tenantId
              and recipient_id = :recipientId
              and message_id = :messageId
            """,
            parameters,
            readResultMapper(false)
        ).stream().findFirst();
    }

    @Override
    public int markAllRead(String tenantId, String recipientId, Instant readAt) {
        return jdbc.update(
            """
            update ap_approval_message
            set read_at = :readAt
            where tenant_id = :tenantId
              and recipient_id = :recipientId
              and read_at is null
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("recipientId", recipientId)
                .addValue("readAt", offset(readAt))
        );
    }

    @Override
    public List<MessageReceipt> findReceipts(String tenantId, UUID instanceId) {
        return jdbc.query(
            """
            select message_id, message_type, recipient_id, sender_id, created_at, read_at
            from ap_approval_message
            where tenant_id = :tenantId and instance_id = :instanceId
            order by created_at, message_id
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("instanceId", instanceId),
            (resultSet, rowNumber) -> new MessageReceipt(
                resultSet.getObject("message_id", UUID.class),
                MessageType.valueOf(resultSet.getString("message_type")),
                resultSet.getString("recipient_id"),
                resultSet.getString("sender_id"),
                instant(resultSet, "created_at"),
                nullableInstant(resultSet, "read_at")
            )
        );
    }

    @Override
    public boolean isRecipient(String tenantId, String recipientId, UUID instanceId) {
        Boolean exists = jdbc.queryForObject(
            """
            select exists (
                select 1 from ap_approval_message
                where tenant_id = :tenantId
                  and recipient_id = :recipientId
                  and instance_id = :instanceId
            )
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("recipientId", recipientId)
                .addValue("instanceId", instanceId),
            Boolean.class
        );
        return Boolean.TRUE.equals(exists);
    }

    private RowMapper<MessageItem> messageItemMapper() {
        return (resultSet, rowNumber) -> new MessageItem(
            resultSet.getObject("message_id", UUID.class),
            MessageType.valueOf(resultSet.getString("message_type")),
            resultSet.getObject("instance_id", UUID.class),
            resultSet.getObject("task_id", UUID.class),
            resultSet.getString("sender_id"),
            resultSet.getString("title"),
            resultSet.getString("body"),
            decode(resultSet.getString("metadata_json")),
            resultSet.getObject("read_at") != null,
            nullableInstant(resultSet, "read_at"),
            instant(resultSet, "created_at"),
            resultSet.getString("business_key"),
            resultSet.getBigDecimal("amount"),
            resultSet.getString("supplier"),
            resultSet.getString("purchase_order_reference"),
            InstanceStatus.valueOf(resultSet.getString("instance_status"))
        );
    }

    private static RowMapper<MessageReadResult> readResultMapper(boolean firstRead) {
        return (resultSet, rowNumber) -> new MessageReadResult(
            resultSet.getObject("message_id", UUID.class),
            resultSet.getObject("instance_id", UUID.class),
            MessageType.valueOf(resultSet.getString("message_type")),
            resultSet.getString("sender_id"),
            firstRead,
            instant(resultSet, "read_at")
        );
    }

    private String encode(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("unable to encode message metadata", exception);
        }
    }

    private Map<String, String> decode(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, STRING_MAP);
        } catch (JsonProcessingException exception) {
            throw new SQLException("unable to decode message metadata", exception);
        }
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
}
