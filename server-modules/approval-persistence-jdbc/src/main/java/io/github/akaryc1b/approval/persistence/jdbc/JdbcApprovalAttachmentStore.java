package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL binary attachment store used by the standalone reference deployment.
 */
public final class JdbcApprovalAttachmentStore implements ApprovalAttachmentStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcApprovalAttachmentStore(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
    }

    @Override
    public void save(ApprovalAttachment attachment) {
        int inserted = jdbc.update(
            """
            insert into ap_approval_attachment (
                attachment_id, tenant_id, uploader_id, instance_id,
                file_name, content_type, size_bytes, sha256, content,
                created_at, bound_at
            ) values (
                :attachmentId, :tenantId, :uploaderId, :instanceId,
                :fileName, :contentType, :sizeBytes, :sha256, :content,
                :createdAt, :boundAt
            )
            """,
            new MapSqlParameterSource()
                .addValue("attachmentId", attachment.attachmentId())
                .addValue("tenantId", attachment.tenantId())
                .addValue("uploaderId", attachment.uploaderId())
                .addValue("instanceId", attachment.instanceId())
                .addValue("fileName", attachment.fileName())
                .addValue("contentType", attachment.contentType())
                .addValue("sizeBytes", attachment.sizeBytes())
                .addValue("sha256", attachment.sha256())
                .addValue("content", attachment.content())
                .addValue("createdAt", offset(attachment.createdAt()))
                .addValue("boundAt", nullableOffset(attachment.boundAt()))
        );
        if (inserted != 1) {
            throw new IllegalStateException("approval attachment was not inserted");
        }
    }

    @Override
    public Optional<ApprovalAttachment> find(String tenantId, UUID attachmentId) {
        return jdbc.query(
            """
            select
                attachment_id, tenant_id, uploader_id, instance_id,
                file_name, content_type, size_bytes, sha256, content,
                created_at, bound_at
            from ap_approval_attachment
            where tenant_id = :tenantId and attachment_id = :attachmentId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("attachmentId", attachmentId),
            (resultSet, rowNumber) -> attachment(resultSet)
        ).stream().findFirst();
    }

    @Override
    public List<AttachmentSummary> findSummaries(String tenantId, List<UUID> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return List.of();
        }
        List<AttachmentSummary> found = jdbc.query(
            """
            select
                attachment_id, uploader_id, instance_id, file_name,
                content_type, size_bytes, sha256, created_at, bound_at
            from ap_approval_attachment
            where tenant_id = :tenantId and attachment_id in (:attachmentIds)
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("attachmentIds", attachmentIds),
            (resultSet, rowNumber) -> summary(resultSet)
        );
        Map<UUID, AttachmentSummary> byId = new LinkedHashMap<>();
        found.forEach(item -> byId.put(item.attachmentId(), item));
        List<AttachmentSummary> ordered = new ArrayList<>();
        for (UUID attachmentId : attachmentIds) {
            AttachmentSummary item = byId.get(attachmentId);
            if (item != null) {
                ordered.add(item);
            }
        }
        return List.copyOf(ordered);
    }

    @Override
    public void bindToInstance(
        String tenantId,
        String uploaderId,
        UUID instanceId,
        List<UUID> attachmentIds,
        Instant boundAt
    ) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("uploaderId", uploaderId)
            .addValue("instanceId", instanceId)
            .addValue("attachmentIds", attachmentIds)
            .addValue("boundAt", offset(boundAt));
        Integer eligible = jdbc.queryForObject(
            """
            select count(*)
            from ap_approval_attachment
            where tenant_id = :tenantId
              and attachment_id in (:attachmentIds)
              and (
                  instance_id = :instanceId
                  or (instance_id is null and uploader_id = :uploaderId)
              )
            """,
            parameters,
            Integer.class
        );
        if (eligible == null || eligible != attachmentIds.size()) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "one or more attachments are missing, owned by another user or bound to another approval"
            );
        }
        jdbc.update(
            """
            update ap_approval_attachment
            set instance_id = :instanceId,
                bound_at = coalesce(bound_at, :boundAt)
            where tenant_id = :tenantId
              and attachment_id in (:attachmentIds)
              and instance_id is null
              and uploader_id = :uploaderId
            """,
            parameters
        );
    }

    private static ApprovalAttachment attachment(ResultSet resultSet) throws SQLException {
        return new ApprovalAttachment(
            resultSet.getObject("attachment_id", UUID.class),
            resultSet.getString("tenant_id"),
            resultSet.getString("uploader_id"),
            resultSet.getObject("instance_id", UUID.class),
            resultSet.getString("file_name"),
            resultSet.getString("content_type"),
            resultSet.getLong("size_bytes"),
            resultSet.getString("sha256"),
            resultSet.getBytes("content"),
            instant(resultSet, "created_at"),
            nullableInstant(resultSet, "bound_at")
        );
    }

    private static AttachmentSummary summary(ResultSet resultSet) throws SQLException {
        return new AttachmentSummary(
            resultSet.getObject("attachment_id", UUID.class),
            resultSet.getObject("instance_id", UUID.class),
            resultSet.getString("file_name"),
            resultSet.getString("content_type"),
            resultSet.getLong("size_bytes"),
            resultSet.getString("sha256"),
            resultSet.getString("uploader_id"),
            instant(resultSet, "created_at"),
            nullableInstant(resultSet, "bound_at")
        );
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static OffsetDateTime nullableOffset(Instant value) {
        return value == null ? null : offset(value);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
