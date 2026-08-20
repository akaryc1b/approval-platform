package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** MySQL 8.4 implementation of the user-facing approval message center. */
public final class JdbcMySqlApprovalMessageStore implements ApprovalMessageStore {

    private static final TypeReference<Map<String, String>> STRING_MAP =
        new TypeReference<>() {
        };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final JdbcDatabaseValueAdapter values;
    private final TransactionTemplate transactions;

    public JdbcMySqlApprovalMessageStore(
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
                "JdbcMySqlApprovalMessageStore requires MySQL 8.4"
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

    @Override
    public int append(List<ApprovalMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int inserted = 0;
        for (ApprovalMessage message : messages) {
            inserted += insertMessageStrictly(
                Objects.requireNonNull(message, "message must not be null")
            );
        }
        return inserted;
    }

    private int insertMessageStrictly(ApprovalMessage message) {
        try {
            int inserted = jdbc.update(
                """
                insert into ap_approval_message (
                    message_id, tenant_id, recipient_id, sender_id,
                    instance_id, task_id, message_type, title, body,
                    metadata_json, dedup_key, created_at, read_at
                ) values (
                    :messageId, :tenantId, :recipientId, :senderId,
                    :instanceId, :taskId, :messageType, :title, :body,
                    cast(:metadataJson as json), :dedupKey, :createdAt, null
                )
                """,
                messageParameters(message)
            );
            if (inserted != 1) {
                throw new IllegalStateException(
                    "approval message insert changed an unexpected row count"
                );
            }
            return 1;
        } catch (DuplicateKeyException exception) {
            if (isLegalDeduplicationReplay(message)) {
                return 0;
            }
            throw exception;
        }
    }

    private boolean isLegalDeduplicationReplay(ApprovalMessage message) {
        Optional<UUID> dedupOwner = deduplicationOwner(
            message.tenantId(),
            message.dedupKey()
        );
        if (dedupOwner.isEmpty()) {
            return false;
        }
        Optional<UUID> messageOwner = messageIdOwner(message.messageId());
        return messageOwner.isEmpty() || messageOwner.equals(dedupOwner);
    }

    private Optional<UUID> deduplicationOwner(
        String tenantId,
        String dedupKey
    ) {
        return jdbc.query(
            """
            select message_id
            from ap_approval_message
            where tenant_id = :tenantId
              and dedup_key = :dedupKey
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("dedupKey", dedupKey),
            (resultSet, rowNumber) -> values.uuid(resultSet, "message_id")
        ).stream().findFirst();
    }

    private Optional<UUID> messageIdOwner(UUID messageId) {
        return jdbc.query(
            """
            select message_id
            from ap_approval_message
            where message_id = :messageId
            """,
            new MapSqlParameterSource().addValue(
                "messageId",
                values.bindUuid(
                    Objects.requireNonNull(
                        messageId,
                        "messageId must not be null"
                    )
                )
            ),
            (resultSet, rowNumber) -> values.uuid(resultSet, "message_id")
        ).stream().findFirst();
    }

    @Override
    public MessagePage findMessages(MessageCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", criteria.tenantId())
            .addValue("recipientId", criteria.recipientId())
            .addValue("unreadOnly", criteria.unreadOnly() ? 1 : 0)
            .addValue("limit", criteria.limit())
            .addValue("offset", criteria.offset());
        Long total = jdbc.queryForObject(
            """
            select count(*)
            from ap_approval_message message
            where message.tenant_id = :tenantId
              and message.recipient_id = :recipientId
              and (:unreadOnly = 0 or message.read_at is null)
            """,
            parameters,
            Long.class
        );
        long matched = total == null ? 0L : total;
        if (matched == 0L) {
            return new MessagePage(
                List.of(),
                0L,
                criteria.limit(),
                criteria.offset()
            );
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
              and (:unreadOnly = 0 or message.read_at is null)
            order by message.created_at desc, message.message_id desc
            limit :limit offset :offset
            """,
            parameters,
            messageItemMapper()
        );
        return new MessagePage(
            items,
            matched,
            criteria.limit(),
            criteria.offset()
        );
    }

    @Override
    public CopiedInstancePage findCopiedInstances(
        CopiedInstanceCriteria criteria
    ) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        MapSqlParameterSource parameters = copiedParameters(criteria);
        Long total = jdbc.queryForObject(
            """
            select count(*)
            from ap_approval_message message
            join ap_approval_instance instance
              on instance.tenant_id = message.tenant_id
             and instance.instance_id = message.instance_id
            where message.tenant_id = :tenantId
              and message.recipient_id = :recipientId
              and message.message_type = 'COPY'
              and (
                  :hasKeyword = 0
                  or locate(:keyword, lower(instance.business_key)) > 0
                  or locate(:keyword, lower(instance.supplier)) > 0
                  or locate(
                      :keyword,
                      lower(instance.purchase_order_reference)
                  ) > 0
              )
            """,
            parameters,
            Long.class
        );
        long matched = total == null ? 0L : total;
        if (matched == 0L) {
            return new CopiedInstancePage(
                List.of(),
                0L,
                criteria.limit(),
                criteria.offset()
            );
        }
        List<CopiedInstanceItem> items = jdbc.query(
            """
            select
                message.message_id as copy_message_id,
                instance.instance_id,
                instance.definition_key,
                instance.business_key,
                instance.initiator_id,
                instance.amount,
                instance.supplier,
                instance.purchase_order_reference,
                instance.status,
                instance.updated_at,
                message.sender_id as copied_by,
                message.created_at as copied_at,
                message.read_at as copy_read_at,
                (
                    select task.task_definition_key
                    from ap_approval_task task
                    where task.tenant_id = instance.tenant_id
                      and task.instance_id = instance.instance_id
                      and task.status = 'PENDING'
                    order by task.created_at, task.task_id
                    limit 1
                ) as current_task_definition_key,
                (
                    select task.task_name
                    from ap_approval_task task
                    where task.tenant_id = instance.tenant_id
                      and task.instance_id = instance.instance_id
                      and task.status = 'PENDING'
                    order by task.created_at, task.task_id
                    limit 1
                ) as current_task_name,
                (
                    select count(*)
                    from ap_approval_comment comment
                    where comment.tenant_id = instance.tenant_id
                      and comment.instance_id = instance.instance_id
                ) as comment_count
            from ap_approval_message message
            join ap_approval_instance instance
              on instance.tenant_id = message.tenant_id
             and instance.instance_id = message.instance_id
            where message.tenant_id = :tenantId
              and message.recipient_id = :recipientId
              and message.message_type = 'COPY'
              and (
                  :hasKeyword = 0
                  or locate(:keyword, lower(instance.business_key)) > 0
                  or locate(:keyword, lower(instance.supplier)) > 0
                  or locate(
                      :keyword,
                      lower(instance.purchase_order_reference)
                  ) > 0
              )
            order by message.created_at desc, message.message_id desc
            limit :limit offset :offset
            """,
            parameters,
            copiedItemMapper()
        );
        return new CopiedInstancePage(
            items,
            matched,
            criteria.limit(),
            criteria.offset()
        );
    }

    @Override
    public long countUnread(MessageIdentity identity) {
        Objects.requireNonNull(identity, "identity must not be null");
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
        return count == null ? 0L : count;
    }

    @Override
    public Optional<MessageReadResult> markRead(
        String tenantId,
        String recipientId,
        UUID messageId,
        Instant readAt
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", requireText(tenantId, "tenantId"))
            .addValue("recipientId", requireText(recipientId, "recipientId"))
            .addValue(
                "messageId",
                values.bindUuid(
                    Objects.requireNonNull(
                        messageId,
                        "messageId must not be null"
                    )
                )
            )
            .addValue(
                "readAt",
                values.bindInstant(canonicalInstant(readAt))
            );
        Optional<MessageReadResult> result = transactions.execute(
            status -> markReadOnce(parameters)
        );
        return Objects.requireNonNull(
            result,
            "approval message read transaction must return a result"
        );
    }

    private Optional<MessageReadResult> markReadOnce(
        MapSqlParameterSource parameters
    ) {
        int changed = jdbc.update(
            """
            update ap_approval_message
            set read_at = :readAt
            where tenant_id = :tenantId
              and recipient_id = :recipientId
              and message_id = :messageId
              and read_at is null
            """,
            parameters
        );
        if (changed == 1) {
            return readResult(parameters, true);
        }
        if (changed != 0) {
            throw new IllegalStateException(
                "approval message read update changed an unexpected row count"
            );
        }
        return readResult(parameters, false);
    }

    private Optional<MessageReadResult> readResult(
        MapSqlParameterSource parameters,
        boolean firstRead
    ) {
        return jdbc.query(
            """
            select message_id, instance_id, message_type, sender_id, read_at
            from ap_approval_message
            where tenant_id = :tenantId
              and recipient_id = :recipientId
              and message_id = :messageId
            """,
            parameters,
            readResultMapper(firstRead)
        ).stream().findFirst();
    }

    @Override
    public int markAllRead(
        String tenantId,
        String recipientId,
        Instant readAt
    ) {
        return jdbc.update(
            """
            update ap_approval_message
            set read_at = :readAt
            where tenant_id = :tenantId
              and recipient_id = :recipientId
              and read_at is null
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue(
                    "recipientId",
                    requireText(recipientId, "recipientId")
                )
                .addValue(
                    "readAt",
                    values.bindInstant(canonicalInstant(readAt))
                )
        );
    }

    @Override
    public List<MessageReceipt> findReceipts(
        String tenantId,
        UUID instanceId
    ) {
        return List.copyOf(jdbc.query(
            """
            select message_id, message_type, recipient_id, sender_id,
                   created_at, read_at
            from ap_approval_message
            where tenant_id = :tenantId and instance_id = :instanceId
            order by created_at, message_id
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue(
                    "instanceId",
                    values.bindUuid(
                        Objects.requireNonNull(
                            instanceId,
                            "instanceId must not be null"
                        )
                    )
                ),
            (resultSet, rowNumber) -> new MessageReceipt(
                values.uuid(resultSet, "message_id"),
                MessageType.valueOf(resultSet.getString("message_type")),
                resultSet.getString("recipient_id"),
                resultSet.getString("sender_id"),
                values.instant(resultSet, "created_at"),
                values.nullableInstant(resultSet, "read_at")
            )
        ));
    }

    @Override
    public boolean isRecipient(
        String tenantId,
        String recipientId,
        UUID instanceId
    ) {
        Integer exists = jdbc.queryForObject(
            """
            select exists (
                select 1
                from ap_approval_message
                where tenant_id = :tenantId
                  and recipient_id = :recipientId
                  and instance_id = :instanceId
            )
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue(
                    "recipientId",
                    requireText(recipientId, "recipientId")
                )
                .addValue(
                    "instanceId",
                    values.bindUuid(
                        Objects.requireNonNull(
                            instanceId,
                            "instanceId must not be null"
                        )
                    )
                ),
            Integer.class
        );
        return exists != null && exists == 1;
    }

    private MapSqlParameterSource messageParameters(ApprovalMessage message) {
        return new MapSqlParameterSource()
            .addValue("messageId", values.bindUuid(message.messageId()))
            .addValue("tenantId", message.tenantId())
            .addValue("recipientId", message.recipientId())
            .addValue("senderId", message.senderId())
            .addValue("instanceId", values.bindUuid(message.instanceId()))
            .addValue("taskId", values.bindNullableUuid(message.taskId()))
            .addValue("messageType", message.messageType().name())
            .addValue("title", message.title())
            .addValue("body", message.body())
            .addValue("metadataJson", encode(message.metadata()))
            .addValue("dedupKey", message.dedupKey())
            .addValue(
                "createdAt",
                values.bindInstant(canonicalInstant(message.createdAt()))
            );
    }

    private static MapSqlParameterSource copiedParameters(
        CopiedInstanceCriteria criteria
    ) {
        return new MapSqlParameterSource()
            .addValue("tenantId", criteria.tenantId())
            .addValue("recipientId", criteria.recipientId())
            .addValue("hasKeyword", criteria.keyword() == null ? 0 : 1)
            .addValue("keyword", criteria.normalizedKeyword())
            .addValue("limit", criteria.limit())
            .addValue("offset", criteria.offset());
    }

    private RowMapper<MessageItem> messageItemMapper() {
        return (resultSet, rowNumber) -> new MessageItem(
            values.uuid(resultSet, "message_id"),
            MessageType.valueOf(resultSet.getString("message_type")),
            values.uuid(resultSet, "instance_id"),
            values.nullableUuid(resultSet, "task_id"),
            resultSet.getString("sender_id"),
            resultSet.getString("title"),
            resultSet.getString("body"),
            decode(resultSet.getString("metadata_json")),
            resultSet.getObject("read_at") != null,
            values.nullableInstant(resultSet, "read_at"),
            values.instant(resultSet, "created_at"),
            resultSet.getString("business_key"),
            resultSet.getBigDecimal("amount"),
            resultSet.getString("supplier"),
            resultSet.getString("purchase_order_reference"),
            InstanceStatus.valueOf(resultSet.getString("instance_status"))
        );
    }

    private RowMapper<CopiedInstanceItem> copiedItemMapper() {
        return (resultSet, rowNumber) -> new CopiedInstanceItem(
            values.uuid(resultSet, "copy_message_id"),
            values.uuid(resultSet, "instance_id"),
            resultSet.getString("definition_key"),
            resultSet.getString("business_key"),
            resultSet.getString("initiator_id"),
            resultSet.getBigDecimal("amount"),
            resultSet.getString("supplier"),
            resultSet.getString("purchase_order_reference"),
            InstanceStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("current_task_definition_key"),
            resultSet.getString("current_task_name"),
            resultSet.getString("copied_by"),
            values.instant(resultSet, "copied_at"),
            values.nullableInstant(resultSet, "copy_read_at"),
            resultSet.getLong("comment_count"),
            values.instant(resultSet, "updated_at")
        );
    }

    private RowMapper<MessageReadResult> readResultMapper(boolean firstRead) {
        return (resultSet, rowNumber) -> new MessageReadResult(
            values.uuid(resultSet, "message_id"),
            values.uuid(resultSet, "instance_id"),
            MessageType.valueOf(resultSet.getString("message_type")),
            resultSet.getString("sender_id"),
            firstRead,
            values.instant(resultSet, "read_at")
        );
    }

    private String encode(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "unable to encode message metadata",
                exception
            );
        }
    }

    private Map<String, String> decode(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, STRING_MAP);
        } catch (JsonProcessingException exception) {
            throw new SQLException(
                "unable to decode message metadata",
                exception
            );
        }
    }

    private static Instant canonicalInstant(Instant value) {
        return AuditHashCanonicalizer.canonicalInstant(
            Objects.requireNonNull(value, "instant must not be null")
        );
    }

    private static String requireText(String value, String name) {
        String exact = Objects.requireNonNull(
            value,
            name + " must not be null"
        );
        if (exact.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return exact.trim();
    }
}
