package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** JDBC binary attachment store with bounded PostgreSQL/MySQL value conversion. */
public final class JdbcApprovalAttachmentStore implements ApprovalAttachmentStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcDatabaseValueAdapter values;

    public JdbcApprovalAttachmentStore(DataSource dataSource) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        this.jdbc = new NamedParameterJdbcTemplate(source);
        this.values = JdbcDatabaseValueAdapter.resolve(source);
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
                .addValue("attachmentId", values.bindUuid(attachment.attachmentId()))
                .addValue("tenantId", attachment.tenantId())
                .addValue("uploaderId", attachment.uploaderId())
                .addValue("instanceId", values.bindNullableUuid(attachment.instanceId()))
                .addValue("fileName", attachment.fileName())
                .addValue("contentType", attachment.contentType())
                .addValue("sizeBytes", attachment.sizeBytes())
                .addValue("sha256", attachment.sha256())
                .addValue("content", attachment.content())
                .addValue("createdAt", values.bindInstant(attachment.createdAt()))
                .addValue("boundAt", values.bindNullableInstant(attachment.boundAt()))
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
                .addValue("attachmentId", values.bindUuid(attachmentId)),
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
                .addValue("attachmentIds", values.bindUuids(attachmentIds)),
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
            .addValue("instanceId", values.bindUuid(instanceId))
            .addValue("attachmentIds", values.bindUuids(attachmentIds))
            .addValue("boundAt", values.bindInstant(boundAt));
        Integer eligible = jdbc.queryForObject(
            """
            select count(*)
            from ap_approval_attachment
            where tenant_id = :tenantId
              and attachment_id in (:attachmentIds)
              and uploader_id = :uploaderId
              and (instance_id = :instanceId or instance_id is null)
            """,
            parameters,
            Integer.class
        );
        if (eligible == null || eligible != attachmentIds.size()) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "one or more attachments are missing, owned by another user "
                    + "or bound to another approval"
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

    private ApprovalAttachment attachment(ResultSet resultSet) throws SQLException {
        return new ApprovalAttachment(
            values.uuid(resultSet, "attachment_id"),
            resultSet.getString("tenant_id"),
            resultSet.getString("uploader_id"),
            values.nullableUuid(resultSet, "instance_id"),
            resultSet.getString("file_name"),
            resultSet.getString("content_type"),
            resultSet.getLong("size_bytes"),
            resultSet.getString("sha256"),
            resultSet.getBytes("content"),
            values.instant(resultSet, "created_at"),
            values.nullableInstant(resultSet, "bound_at")
        );
    }

    private AttachmentSummary summary(ResultSet resultSet) throws SQLException {
        return new AttachmentSummary(
            values.uuid(resultSet, "attachment_id"),
            values.nullableUuid(resultSet, "instance_id"),
            resultSet.getString("file_name"),
            resultSet.getString("content_type"),
            resultSet.getLong("size_bytes"),
            resultSet.getString("sha256"),
            resultSet.getString("uploader_id"),
            values.instant(resultSet, "created_at"),
            values.nullableInstant(resultSet, "bound_at")
        );
    }
}
