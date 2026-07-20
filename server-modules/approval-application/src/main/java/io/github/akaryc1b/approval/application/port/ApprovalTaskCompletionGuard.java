package io.github.akaryc1b.approval.application.port;

import java.time.Instant;
import java.util.UUID;

/** Additional platform-owned preconditions attached to a pending approval task. */
public interface ApprovalTaskCompletionGuard {

    void validate(String tenantId, UUID taskId, TaskOutcome outcome);

    void completed(
        String tenantId,
        UUID taskId,
        String operatorId,
        TaskOutcome outcome,
        Instant completedAt
    );

    enum TaskOutcome {
        APPROVED,
        REJECTED,
        RESUBMITTED
    }

    static ApprovalTaskCompletionGuard none() {
        return NoOpApprovalTaskCompletionGuard.INSTANCE;
    }

    final class NoOpApprovalTaskCompletionGuard implements ApprovalTaskCompletionGuard {

        private static final NoOpApprovalTaskCompletionGuard INSTANCE =
            new NoOpApprovalTaskCompletionGuard();

        private NoOpApprovalTaskCompletionGuard() {
        }

        @Override
        public void validate(String tenantId, UUID taskId, TaskOutcome outcome) {
        }

        @Override
        public void completed(
            String tenantId,
            UUID taskId,
            String operatorId,
            TaskOutcome outcome,
            Instant completedAt
        ) {
        }
    }
}
