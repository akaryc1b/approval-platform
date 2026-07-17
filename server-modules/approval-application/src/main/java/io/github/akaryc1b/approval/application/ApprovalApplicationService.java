package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.engine.ApprovalEngine;

import java.util.List;
import java.util.Objects;

/**
 * Application boundary for approval use cases. Transaction, authorization, idempotency,
 * audit and outbox orchestration are added here as vertical slices grow.
 */
public final class ApprovalApplicationService {

    private final ApprovalEngine approvalEngine;

    public ApprovalApplicationService(ApprovalEngine approvalEngine) {
        this.approvalEngine = Objects.requireNonNull(approvalEngine, "approvalEngine");
    }

    public ApprovalEngine.DeploymentResult deploy(ApprovalEngine.DeployCommand command) {
        return approvalEngine.deploy(command);
    }

    public ApprovalEngine.StartResult start(ApprovalEngine.StartCommand command) {
        return approvalEngine.start(command);
    }

    public List<ApprovalEngine.TaskSnapshot> findActiveTasks(ApprovalEngine.TaskQuery query) {
        return approvalEngine.findActiveTasks(query);
    }

    public ApprovalEngine.TaskResult complete(ApprovalEngine.CompleteTaskCommand command) {
        return approvalEngine.complete(command);
    }
}
