package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.migration.ApprovalCommandOperation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Shared transaction-level serialization gate for every approval-instance command. */
public interface ApprovalInstanceCommandFence {

    void guardBusinessCommand(
        String tenantId,
        UUID approvalInstanceId,
        ApprovalCommandOperation operation,
        Instant happenedAt
    );

    final class InstanceCommandFencedException extends RuntimeException {
        public InstanceCommandFencedException(String message) {
            super(message);
        }
    }

    static void requireBusinessOperation(ApprovalCommandOperation operation) {
        Objects.requireNonNull(operation, "operation must not be null");
        if (operation == ApprovalCommandOperation.MIGRATION) {
            throw new IllegalArgumentException("business command operation cannot be MIGRATION");
        }
    }
}
