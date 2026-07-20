package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.CollaborationStatus;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCompletionGuard.TaskOutcome;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Projection decorator that serializes task completion with dynamic add-sign creation. */
public final class CollaborationAwareApprovalProjectionStore implements ApprovalProjectionStore {

    private final ApprovalProjectionStore delegate;
    private final ApprovalTaskCollaborationStore collaborations;
    private final ApprovalTaskOutcomeContext outcomes;

    public CollaborationAwareApprovalProjectionStore(
        ApprovalProjectionStore delegate,
        ApprovalTaskCollaborationStore collaborations,
        ApprovalTaskOutcomeContext outcomes
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.collaborations = Objects.requireNonNull(
            collaborations,
            "collaborations must not be null"
        );
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes must not be null");
    }

    @Override
    public void lockDefinition(String tenantId, String definitionKey, int definitionVersion) {
        delegate.lockDefinition(tenantId, definitionKey, definitionVersion);
    }

    @Override
    public Optional<PublishedDefinition> findDefinition(
        String tenantId,
        String definitionKey,
        int definitionVersion
    ) {
        return delegate.findDefinition(tenantId, definitionKey, definitionVersion);
    }

    @Override
    public void saveDefinition(PublishedDefinition definition) {
        delegate.saveDefinition(definition);
    }

    @Override
    public void lockBusinessKey(String tenantId, String businessKey) {
        delegate.lockBusinessKey(tenantId, businessKey);
    }

    @Override
    public Optional<InstanceProjection> findByBusinessKey(String tenantId, String businessKey) {
        return delegate.findByBusinessKey(tenantId, businessKey);
    }

    @Override
    public void createInstance(InstanceProjection instance, List<TaskProjection> tasks) {
        delegate.createInstance(instance, tasks);
    }

    @Override
    public Optional<InstanceProjection> findInstance(String tenantId, UUID instanceId) {
        return delegate.findInstance(tenantId, instanceId);
    }

    @Override
    public List<TaskProjection> findTasks(String tenantId, UUID instanceId) {
        return delegate.findTasks(tenantId, instanceId);
    }

    @Override
    public Optional<TaskProjection> findTask(String tenantId, UUID taskId) {
        return delegate.findTask(tenantId, taskId);
    }

    @Override
    public TaskProjection claimPendingTask(
        String tenantId,
        UUID taskId,
        String operatorId,
        Instant claimedAt
    ) {
        collaborations.lockTask(tenantId, taskId);
        collaborations.findByTask(tenantId, taskId).ifPresent(collaboration -> {
            if (collaboration.status() == CollaborationStatus.ACTIVE) {
                throw new ApprovalTaskCollaborationStore.CollaborationConflictException(
                    "task collaboration decisions must finish before the parent task"
                );
            }
            TaskOutcome outcome = outcomes.current().orElse(null);
            if (collaboration.status() == CollaborationStatus.REJECTED
                && outcome != TaskOutcome.REJECTED) {
                throw new ApprovalTaskCollaborationStore.CollaborationConflictException(
                    "a rejected task collaboration requires the parent task to be rejected"
                );
            }
        });
        return delegate.claimPendingTask(tenantId, taskId, operatorId, claimedAt);
    }

    @Override
    public TaskProjection claimPendingTaskForControl(
        String tenantId,
        UUID taskId,
        Instant claimedAt
    ) {
        return delegate.claimPendingTaskForControl(tenantId, taskId, claimedAt);
    }

    @Override
    public TaskProjection transferPendingTask(
        String tenantId,
        UUID taskId,
        String currentAssigneeId,
        String targetAssigneeId,
        Instant transferredAt
    ) {
        return delegate.transferPendingTask(
            tenantId,
            taskId,
            currentAssigneeId,
            targetAssigneeId,
            transferredAt
        );
    }

    @Override
    public void completeTaskAndSynchronize(
        String tenantId,
        UUID instanceId,
        UUID completedTaskId,
        long claimedTaskVersion,
        List<TaskProjection> activeTasks,
        InstanceStatus instanceStatus,
        Instant completedAt
    ) {
        delegate.completeTaskAndSynchronize(
            tenantId,
            instanceId,
            completedTaskId,
            claimedTaskVersion,
            activeTasks,
            instanceStatus,
            completedAt
        );
    }

    @Override
    public void cancelClaimedTaskAndSynchronize(
        String tenantId,
        UUID instanceId,
        UUID canceledTaskId,
        long claimedTaskVersion,
        List<TaskProjection> activeTasks,
        Instant changedAt
    ) {
        delegate.cancelClaimedTaskAndSynchronize(
            tenantId,
            instanceId,
            canceledTaskId,
            claimedTaskVersion,
            activeTasks,
            changedAt
        );
    }

    @Override
    public void withdrawRunningInstance(
        String tenantId,
        UUID instanceId,
        String initiatorId,
        Instant withdrawnAt
    ) {
        delegate.withdrawRunningInstance(tenantId, instanceId, initiatorId, withdrawnAt);
    }
}
