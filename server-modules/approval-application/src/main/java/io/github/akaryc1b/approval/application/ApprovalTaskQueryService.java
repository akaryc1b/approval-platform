package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery.PendingTaskCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery.PendingTaskPage;

import java.util.Objects;

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
}
