package io.github.akaryc1b.approval.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Platform-owned attachment metadata and binary store.
 *
 * <p>The first production-capable adapter stores bytes in PostgreSQL. The port keeps the
 * application layer independent so an object-storage adapter can replace it later.</p>
 */
public interface ApprovalAttachmentStore {

    void save(ApprovalAttachment attachment);

    Optional<ApprovalAttachment> find(String tenantId, UUID attachmentId);

    List<AttachmentSummary> findSummaries(String tenantId, List<UUID> attachmentIds);

    void bindToInstance(
        String tenantId,
        String uploaderId,
        UUID instanceId,
        List<UUID> attachmentIds,
        Instant boundAt
    );

    record ApprovalAttachment(
        UUID attachmentId,
        String tenantId,
        String uploaderId,
        UUID instanceId,
        String fileName,
        String contentType,
        long sizeBytes,
        String sha256,
        byte[] content,
        Instant createdAt,
        Instant boundAt
    ) {
        public ApprovalAttachment {
            attachmentId = Objects.requireNonNull(attachmentId, "attachmentId must not be null");
            tenantId = requireText(tenantId, "tenantId");
            uploaderId = requireText(uploaderId, "uploaderId");
            fileName = requireText(fileName, "fileName");
            contentType = requireText(contentType, "contentType");
            if (sizeBytes < 1) {
                throw new IllegalArgumentException("sizeBytes must be positive");
            }
            sha256 = requireText(sha256, "sha256");
            content = content == null ? new byte[0] : content.clone();
            if (content.length != sizeBytes) {
                throw new IllegalArgumentException("content length must equal sizeBytes");
            }
            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
            if (instanceId == null && boundAt != null) {
                throw new IllegalArgumentException("unbound attachment cannot have boundAt");
            }
            if (instanceId != null && boundAt == null) {
                throw new IllegalArgumentException("bound attachment must have boundAt");
            }
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        public AttachmentSummary summary() {
            return new AttachmentSummary(
                attachmentId,
                instanceId,
                fileName,
                contentType,
                sizeBytes,
                sha256,
                uploaderId,
                createdAt,
                boundAt
            );
        }
    }

    record AttachmentSummary(
        UUID attachmentId,
        UUID instanceId,
        String fileName,
        String contentType,
        long sizeBytes,
        String sha256,
        String uploaderId,
        Instant createdAt,
        Instant boundAt
    ) {
        public AttachmentSummary {
            attachmentId = Objects.requireNonNull(attachmentId, "attachmentId must not be null");
            fileName = requireText(fileName, "fileName");
            contentType = requireText(contentType, "contentType");
            if (sizeBytes < 1) {
                throw new IllegalArgumentException("sizeBytes must be positive");
            }
            sha256 = requireText(sha256, "sha256");
            uploaderId = requireText(uploaderId, "uploaderId");
            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        }

        public boolean bound() {
            return instanceId != null;
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
