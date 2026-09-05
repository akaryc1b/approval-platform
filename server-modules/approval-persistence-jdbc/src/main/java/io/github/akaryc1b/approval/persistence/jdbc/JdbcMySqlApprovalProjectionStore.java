package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Complete MySQL 8.4 ApprovalProjectionStore delegating the accepted P3-D task CAS. */
public final class JdbcMySqlApprovalProjectionStore implements ApprovalProjectionStore {

    private final JdbcMySqlDefinitionInstanceProjectionStore definitions;
    private final JdbcMySqlApprovalTaskCasStore taskCas;
    private final JdbcMySqlTaskLifecycleProjectionStore lifecycle;

    public JdbcMySqlApprovalProjectionStore(
        DataSource dataSource,
        ObjectMapper objectMapper
    ) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        JdbcMySqlProjectionCodec codec = new JdbcMySqlProjectionCodec(
            source,
            objectMapper
        );
        JdbcMySqlTransactionLockManager locks = new JdbcMySqlTransactionLockManager(
            source
        );
        this.taskCas = new JdbcMySqlApprovalTaskCasStore(source);
        this.definitions = new JdbcMySqlDefinitionInstanceProjectionStore(
            source,
            codec,
            locks
        );
        this.lifecycle = new JdbcMySqlTaskLifecycleProjectionStore(
            source,
            codec,
            taskCas
        );
    }

    @Override
    public void lockDefinition(
        String tenantId,
        String definitionKey,
        int definitionVersion
    ) {
        definitions.lockDefinition(tenantId, definitionKey, definitionVersion);
    }

    @Override
    public Optional<PublishedDefinition> findDefinition(
        String tenantId,
        String definitionKey,
        int definitionVersion
    ) {
        return definitions.findDefinition(tenantId, definitionKey, definitionVersion);
    }

    @Override
    public void saveDefinition(PublishedDefinition definition) {
        definitions.saveDefinition(definition);
    }

    @Override
    public void lockBusinessKey(String tenantId, String businessKey) {
        definitions.lockBusinessKey(tenantId, businessKey);
    }

    @Override
    public Optional<InstanceProjection> findByBusinessKey(
        String tenantId,
        String businessKey
    ) {
        return definitions.findByBusinessKey(tenantId, businessKey);
    }

    @Override
    public void createInstance(
        InstanceProjection instance,
        List<TaskProjection> tasks
    ) {
        definitions.createInstance(instance, tasks);
    }

    @Override
    public Optional<InstanceProjection> findInstance(
        String tenantId,
        UUID instanceId
    ) {
        return definitions.findInstance(tenantId, instanceId);
    }

    @Override
    public List<TaskProjection> findTasks(String tenantId, UUID instanceId) {
        return taskCas.findTasks(tenantId, instanceId);
    }

    @Override
    public Optional<TaskProjection> findTask(String tenantId, UUID taskId) {
        return taskCas.findTask(tenantId, taskId);
    }

    @Override
    public TaskProjection claimPendingTask(
        String tenantId,
        UUID taskId,
        String operatorId,
        Instant claimedAt
    ) {
        return taskCas.claimPendingTask(
            tenantId,
            taskId,
            operatorId,
            JdbcMySqlProjectionCodec.canonicalInstant(claimedAt)
        );
    }

    @Override
    public TaskProjection claimPendingTaskForControl(
        String tenantId,
        UUID taskId,
        Instant claimedAt
    ) {
        return taskCas.claimPendingTaskForControl(
            tenantId,
            taskId,
            JdbcMySqlProjectionCodec.canonicalInstant(claimedAt)
        );
    }

    @Override
    public TaskProjection transferPendingTask(
        String tenantId,
        UUID taskId,
        String currentAssigneeId,
        String targetAssigneeId,
        Instant transferredAt
    ) {
        return taskCas.transferPendingTask(
            tenantId,
            taskId,
            currentAssigneeId,
            targetAssigneeId,
            JdbcMySqlProjectionCodec.canonicalInstant(transferredAt)
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
        lifecycle.completeTaskAndSynchronize(
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
        lifecycle.cancelClaimedTaskAndSynchronize(
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
        lifecycle.withdrawRunningInstance(
            tenantId,
            instanceId,
            initiatorId,
            withdrawnAt
        );
    }
}
