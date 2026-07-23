package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalRequestEvidenceProvider;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.ResponsibilityChangeSource;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Projection decorator that keeps task and SLA evidence in the same transaction boundary. */
public final class SlaAwareApprovalProjectionStore implements ApprovalProjectionStore {

    private final ApprovalProjectionStore delegate;
    private final ApprovalSlaService sla;
    private final ApprovalRequestEvidenceProvider evidence;

    public SlaAwareApprovalProjectionStore(
        ApprovalProjectionStore delegate,
        ApprovalSlaService sla,
        ApprovalRequestEvidenceProvider evidence
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.sla = Objects.requireNonNull(sla, "sla must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
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
        sla.synchronizeNewInstance(instance, tasks, evidence.current());
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
        TaskProjection transferred = delegate.transferPendingTask(
            tenantId,
            taskId,
            currentAssigneeId,
            targetAssigneeId,
            transferredAt
        );
        sla.transferTaskResponsibility(
            tenantId,
            taskId,
            currentAssigneeId,
            targetAssigneeId,
            ResponsibilityChangeSource.MANUAL_TRANSFER,
            "pending task responsibility transferred",
            evidence.current()
        );
        return transferred;
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
        InstanceProjection instance = delegate.findInstance(tenantId, instanceId)
            .orElseThrow(() -> new ProjectionConflictException(
                "approval instance disappeared after task synchronization"
            ));
        sla.synchronizeTaskChange(
            instance,
            completedTaskId,
            activeTasks,
            instanceStatus,
            false,
            evidence.current()
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
        InstanceProjection instance = delegate.findInstance(tenantId, instanceId)
            .orElseThrow(() -> new ProjectionConflictException(
                "approval instance disappeared after controlled task replacement"
            ));
        sla.synchronizeTaskChange(
            instance,
            canceledTaskId,
            activeTasks,
            InstanceStatus.RUNNING,
            true,
            evidence.current()
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
        InstanceProjection instance = delegate.findInstance(tenantId, instanceId)
            .orElseThrow(() -> new ProjectionConflictException(
                "approval instance disappeared after withdrawal"
            ));
        sla.terminalWithdrawnInstance(instance);
    }
}
