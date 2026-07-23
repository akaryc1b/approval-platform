package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalRuntimeBindingStore;
import io.github.akaryc1b.approval.domain.definition.ApprovalRuntimeBinding;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Delegating projection store that fails closed when a release-bound instance is read without its
 * immutable runtime binding evidence.
 */
public final class RuntimeBindingEnforcingProjectionStore implements ApprovalProjectionStore {

    private final ApprovalProjectionStore delegate;
    private final ApprovalRuntimeBindingStore runtimeBindings;

    public RuntimeBindingEnforcingProjectionStore(
        ApprovalProjectionStore delegate,
        ApprovalRuntimeBindingStore runtimeBindings
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.runtimeBindings = Objects.requireNonNull(
            runtimeBindings,
            "runtimeBindings must not be null"
        );
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
        return requireBinding(delegate.findByBusinessKey(tenantId, businessKey));
    }

    @Override
    public void createInstance(InstanceProjection instance, List<TaskProjection> tasks) {
        delegate.createInstance(instance, tasks);
    }

    @Override
    public Optional<InstanceProjection> findInstance(String tenantId, UUID instanceId) {
        return requireBinding(delegate.findInstance(tenantId, instanceId));
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

    private Optional<InstanceProjection> requireBinding(Optional<InstanceProjection> candidate) {
        candidate.ifPresent(this::requireBinding);
        return candidate;
    }

    private void requireBinding(InstanceProjection instance) {
        if (instance.releaseVersion() == null) {
            return;
        }
        ApprovalRuntimeBinding binding = runtimeBindings.find(
            instance.tenantId(),
            instance.instanceId()
        ).orElseThrow(() -> new ProjectionConflictException(
            "release-bound instance is missing immutable runtime binding evidence"
        ));
        if (!binding.tenantId().equals(instance.tenantId())
            || !binding.approvalInstanceId().equals(instance.instanceId())
            || !binding.businessKey().equals(instance.businessKey())
            || !binding.engineInstanceId().equals(instance.engineInstanceId())
            || !binding.definitionKey().equals(instance.definitionKey())
            || binding.releaseVersion() != instance.releaseVersion()
            || !binding.releasePackageHash().equals(instance.releasePackageHash())
            || binding.definitionVersion() != instance.definitionVersion()
            || !binding.definitionHash().equals(instance.contentHash())
            || binding.formPackageVersion() != instance.formPackageVersion()
            || !binding.formPackageHash().equals(instance.formPackageHash())
            || binding.formVersion() != instance.formVersion()
            || binding.uiSchemaVersion() != instance.uiSchemaVersion()
            || !binding.uiSchemaHash().equals(instance.uiSchemaHash())
            || !binding.compilerVersion().equals(instance.compilerVersion())
            || !binding.engineDefinitionId().equals(instance.engineDefinitionId())) {
            throw new ProjectionConflictException(
                "runtime binding does not match release-bound instance projection"
            );
        }
    }
}
