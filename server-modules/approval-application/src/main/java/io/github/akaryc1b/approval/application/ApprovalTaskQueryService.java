package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery.PendingTaskCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery.PendingTaskDetails;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery.PendingTaskIdentity;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery.PendingTaskPage;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Application boundary for task-center reads.
 */
public final class ApprovalTaskQueryService {

    private final ApprovalTaskQuery taskQuery;

    public ApprovalTaskQueryService(ApprovalTaskQuery taskQuery) {
        this.taskQuery = Objects.requireNonNull(taskQuery, "taskQuery must not be null");
    }

    public PendingTaskPage findPendingTasks(
        String tenantId,
        String operatorId,
        String keyword,
        int limit,
        int offset
    ) {
        return taskQuery.findPendingTasks(new PendingTaskCriteria(
            tenantId,
            operatorId,
            keyword,
            limit,
            offset
        ));
    }

    public Optional<PendingTaskDetails> findPendingTask(
        String tenantId,
        String operatorId,
        UUID taskId
    ) {
        return taskQuery.findPendingTask(new PendingTaskIdentity(
            tenantId,
            operatorId,
            taskId
        ));
    }
}
