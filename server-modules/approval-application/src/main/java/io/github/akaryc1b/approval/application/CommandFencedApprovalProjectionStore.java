package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalInstanceCommandFence;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.domain.migration.ApprovalCommandOperation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Applies one shared tenant/instance command fence before every runtime mutation. */
public final class CommandFencedApprovalProjectionStore implements ApprovalProjectionStore {

    private final ApprovalProjectionStore delegate;
    private final ApprovalInstanceCommandFence fence;

    public CommandFencedApprovalProjectionStore(
        ApprovalProjectionStore delegate,
        ApprovalInstanceCommandFence fence
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.fence = Objects.requireNonNull(fence, "fence must not be null");
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
        TaskProjection task = requireTask(tenantId, taskId);
        guard(tenantId, task.instanceId(), ApprovalCommandOperation.COMPLETE, claimedAt);
        return delegate.claimPendingTask(tenantId, taskId, operatorId, claimedAt);
    }

    @Override
    public TaskProjection claimPendingTaskForControl(
        String tenantId,
        UUID taskId,
        Instant claimedAt
    ) {
        TaskProjection task = requireTask(tenantId, taskId);
        guard(tenantId, task.instanceId(), ApprovalCommandOperation.RETURN, claimedAt);
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
        TaskProjection task = requireTask(tenantId, taskId);
        guard(tenantId, task.instanceId(), ApprovalCommandOperation.TRANSFER, transferredAt);
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
        guard(tenantId, instanceId, ApprovalCommandOperation.COMPLETE, completedAt);
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
        guard(tenantId, instanceId, ApprovalCommandOperation.RETRIEVE, changedAt);
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
        guard(tenantId, instanceId, ApprovalCommandOperation.WITHDRAW, withdrawnAt);
        delegate.withdrawRunningInstance(tenantId, instanceId, initiatorId, withdrawnAt);
    }

    private TaskProjection requireTask(String tenantId, UUID taskId) {
        return delegate.findTask(tenantId, taskId).orElseThrow(() ->
            new ProjectionConflictException("approval task was not found for command fencing")
        );
    }

    private void guard(
        String tenantId,
        UUID instanceId,
        ApprovalCommandOperation operation,
        Instant happenedAt
    ) {
        fence.guardBusinessCommand(tenantId, instanceId, operation, happenedAt);
    }
}
