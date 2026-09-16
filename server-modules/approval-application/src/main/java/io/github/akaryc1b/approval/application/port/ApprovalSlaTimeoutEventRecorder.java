package io.github.akaryc1b.approval.application.port;

/** Atomically records an authoritative, claimed SLA overdue action and its durable notification event. */
@FunctionalInterface
public interface ApprovalSlaTimeoutEventRecorder {

    ApprovalSlaActionStateRecorder.RecordResult record(
        ApprovalSlaExecutionStore.ExecutionIntent claimed
    );
}
