package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.engine.ApprovalEngine;

import java.util.Objects;

/**
 * Application boundary for approval use cases. Transaction, authorization, idempotency,
 * audit and outbox orchestration will be implemented here as the vertical slice grows.
 */
public final class ApprovalApplicationService {

    private final ApprovalEngine approvalEngine;

    public ApprovalApplicationService(ApprovalEngine approvalEngine) {
        this.approvalEngine = Objects.requireNonNull(approvalEngine, "approvalEngine");
    }

    public ApprovalEngine.StartResult start(ApprovalEngine.StartCommand command) {
        return approvalEngine.start(command);
    }

    public ApprovalEngine.TaskResult complete(ApprovalEngine.CompleteTaskCommand command) {
        return approvalEngine.complete(command);
    }
}
