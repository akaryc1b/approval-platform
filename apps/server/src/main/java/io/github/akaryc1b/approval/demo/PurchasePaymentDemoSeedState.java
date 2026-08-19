package io.github.akaryc1b.approval.demo;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory startup evidence for the explicitly enabled local demo seed.
 */
public final class PurchasePaymentDemoSeedState {

    private final AtomicReference<SeedEvidence> evidence = new AtomicReference<>();

    public void record(SeedEvidence value) {
        Objects.requireNonNull(value, "value must not be null");
        SeedEvidence existing = evidence.get();
        if (existing != null && !existing.sameIdentity(value)) {
            throw new IllegalStateException("demo seed identity changed during one application run");
        }
        evidence.compareAndSet(null, value);
    }

    public Optional<SeedEvidence> evidence() {
        return Optional.ofNullable(evidence.get());
    }

    public SeedEvidence requireEvidence() {
        return evidence().orElseThrow(() ->
            new IllegalStateException("purchase-payment demo seed has not been applied")
        );
    }

    public record SeedEvidence(
        String tenantId,
        String businessKey,
        UUID instanceId,
        InstanceStatus status,
        String definitionKey,
        String engineDefinitionId,
        List<UUID> taskIds,
        List<AttachmentEvidence> attachments,
        Instant seededAt
    ) {
        public SeedEvidence {
            tenantId = requireText(tenantId, "tenantId");
            businessKey = requireText(businessKey, "businessKey");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            status = Objects.requireNonNull(status, "status must not be null");
            definitionKey = requireText(definitionKey, "definitionKey");
            engineDefinitionId = requireText(engineDefinitionId, "engineDefinitionId");
            taskIds = taskIds == null ? List.of() : List.copyOf(taskIds);
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
            seededAt = Objects.requireNonNull(seededAt, "seededAt must not be null");
        }

        boolean sameIdentity(SeedEvidence other) {
            return tenantId.equals(other.tenantId)
                && businessKey.equals(other.businessKey)
                && instanceId.equals(other.instanceId)
                && taskIds.equals(other.taskIds)
                && attachments.equals(other.attachments);
        }
    }

    public record AttachmentEvidence(
        String logicalId,
        UUID attachmentId,
        String fileName,
        String sha256,
        boolean bound
    ) {
        public AttachmentEvidence {
            logicalId = requireText(logicalId, "logicalId");
            attachmentId = Objects.requireNonNull(
                attachmentId,
                "attachmentId must not be null"
            );
            fileName = requireText(fileName, "fileName");
            sha256 = requireText(sha256, "sha256");
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
