package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore;
import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore.ApprovalAttachment;
import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore.AttachmentSummary;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.context.RequestContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Uploads and serves approval attachments with participant-scoped authorization.
 */
public final class ApprovalAttachmentService {

    public static final long MAX_FILE_SIZE = 10L * 1024L * 1024L;
    private static final String UPLOAD_OPERATION = "approval.attachment.upload.v1";
    private static final int MAX_FILE_NAME_LENGTH = 255;
    private static final int MAX_CONTENT_TYPE_LENGTH = 160;

    private final IdempotencyGuard idempotencyGuard;
    private final ApprovalAttachmentStore attachments;
    private final ApprovalProjectionStore projections;
    private final ApprovalMessageStore messages;
    private final Clock clock;
    private final Supplier<UUID> identifierGenerator;

    public ApprovalAttachmentService(
        IdempotencyGuard idempotencyGuard,
        ApprovalAttachmentStore attachments,
        ApprovalProjectionStore projections,
        ApprovalMessageStore messages,
        Clock clock,
        Supplier<UUID> identifierGenerator
    ) {
        this.idempotencyGuard = Objects.requireNonNull(
            idempotencyGuard,
            "idempotencyGuard must not be null"
        );
        this.attachments = Objects.requireNonNull(attachments, "attachments must not be null");
        this.projections = Objects.requireNonNull(projections, "projections must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.identifierGenerator = Objects.requireNonNull(
            identifierGenerator,
            "identifierGenerator must not be null"
        );
    }

    public AttachmentSummary upload(UploadCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String fileName = normalizeFileName(command.fileName());
        String contentType = normalizeContentType(command.contentType());
        byte[] content = command.content();
        validateContent(content);
        String sha256 = sha256(content);
        String requestHash = hashValues(fileName, contentType, Long.toString(content.length), sha256);
        return idempotencyGuard.execute(
            command.context(),
            UPLOAD_OPERATION,
            requestHash,
            AttachmentSummary.class,
            () -> executeUpload(command.context(), fileName, contentType, content, sha256)
        );
    }

    public Optional<AttachmentSummary> findMetadata(
        String tenantId,
        String operatorId,
        UUID attachmentId
    ) {
        return readableAttachment(tenantId, operatorId, attachmentId).map(ApprovalAttachment::summary);
    }

    public Optional<DownloadPayload> download(
        String tenantId,
        String operatorId,
        UUID attachmentId
    ) {
        return readableAttachment(tenantId, operatorId, attachmentId)
            .map(attachment -> new DownloadPayload(attachment.summary(), attachment.content()));
    }

    private AttachmentSummary executeUpload(
        RequestContext context,
        String fileName,
        String contentType,
        byte[] content,
        String sha256
    ) {
        Instant now = clock.instant();
        ApprovalAttachment attachment = new ApprovalAttachment(
            identifierGenerator.get(),
            context.tenantId(),
            context.operatorId(),
            null,
            fileName,
            contentType,
            content.length,
            sha256,
            content,
            now,
            null
        );
        attachments.save(attachment);
        return attachment.summary();
    }

    private Optional<ApprovalAttachment> readableAttachment(
        String tenantId,
        String operatorId,
        UUID attachmentId
    ) {
        Objects.requireNonNull(attachmentId, "attachmentId must not be null");
        return attachments.find(tenantId, attachmentId).filter(attachment -> {
            if (attachment.instanceId() == null) {
                return attachment.uploaderId().equals(operatorId);
            }
            return isParticipant(tenantId, operatorId, attachment.instanceId());
        });
    }

    private boolean isParticipant(String tenantId, String operatorId, UUID instanceId) {
        InstanceProjection instance = projections.findInstance(tenantId, instanceId).orElse(null);
        if (instance == null) {
            return false;
        }
        if (instance.initiatorId().equals(operatorId)) {
            return true;
        }
        boolean taskParticipant = projections.findTasks(tenantId, instanceId).stream()
            .anyMatch(task -> task.assigneeId().equals(operatorId));
        return taskParticipant || messages.isRecipient(tenantId, operatorId, instanceId);
    }

    private static void validateContent(byte[] content) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("attachment content must not be empty");
        }
        if (content.length > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("attachment must not exceed 10 MiB");
        }
    }

    private static String normalizeFileName(String value) {
        String normalized = requireText(value, "fileName");
        normalized = normalized.replace('\\', '_').replace('/', '_');
        if (normalized.length() > MAX_FILE_NAME_LENGTH) {
            throw new IllegalArgumentException("fileName must not exceed 255 characters");
        }
        return normalized;
    }

    private static String normalizeContentType(String value) {
        String normalized = value == null || value.isBlank()
            ? "application/octet-stream"
            : value.trim().toLowerCase();
        if (normalized.length() > MAX_CONTENT_TYPE_LENGTH) {
            throw new IllegalArgumentException("contentType must not exceed 160 characters");
        }
        return normalized;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String hashValues(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    public record UploadCommand(
        RequestContext context,
        String fileName,
        String contentType,
        byte[] content
    ) {
        public UploadCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            content = content == null ? new byte[0] : content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    public record DownloadPayload(AttachmentSummary metadata, byte[] content) {
        public DownloadPayload {
            metadata = Objects.requireNonNull(metadata, "metadata must not be null");
            content = content == null ? new byte[0] : content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
