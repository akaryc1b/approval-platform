package io.github.akaryc1b.approval.observability;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Observe successful state mutations without additional queries or altered concurrency rules. */
public final class ObservedApprovalProjectionStore implements ApprovalProjectionStore {

    private final ApprovalProjectionStore delegate;
    private final ApprovalBusinessMetrics metrics;

    public ObservedApprovalProjectionStore(ApprovalProjectionStore delegate, ApprovalBusinessMetrics metrics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    @Override
    public void createInstance(InstanceProjection instance, List<TaskProjection> tasks) {
        delegate.createInstance(instance, tasks);
        metrics.processStarted(instance.status());
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
        delegate.completeTaskAndSynchronize(tenantId, instanceId, completedTaskId,
            claimedTaskVersion, activeTasks, instanceStatus, completedAt);
        metrics.taskCompleted(instanceStatus);
    }

    @Override
    public void withdrawRunningInstance(String tenantId, UUID instanceId, String initiatorId, Instant withdrawnAt) {
        delegate.withdrawRunningInstance(tenantId, instanceId, initiatorId, withdrawnAt);
        metrics.processWithdrawn();
    }

    @Override
    public void lockDefinition(String tenantId, String definitionKey, int definitionVersion) {
        delegate.lockDefinition(tenantId, definitionKey, definitionVersion);
    }

    @Override
    public Optional<PublishedDefinition> findDefinition(String tenantId, String definitionKey, int definitionVersion) {
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
    public TaskProjection claimPendingTask(String tenantId, UUID taskId, String operatorId, Instant claimedAt) {
        TaskProjection claimed = delegate.claimPendingTask(tenantId, taskId, operatorId, claimedAt);
        metrics.taskClaimed(claimed);
        return claimed;
    }

    @Override
    public TaskProjection claimPendingTaskForControl(String tenantId, UUID taskId, Instant claimedAt) {
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
        return delegate.transferPendingTask(tenantId, taskId, currentAssigneeId, targetAssigneeId, transferredAt);
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
        delegate.cancelClaimedTaskAndSynchronize(tenantId, instanceId, canceledTaskId,
            claimedTaskVersion, activeTasks, changedAt);
    }
}
