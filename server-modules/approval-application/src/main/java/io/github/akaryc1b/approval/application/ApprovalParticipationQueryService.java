package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalParticipationQuery;
import io.github.akaryc1b.approval.application.port.ApprovalParticipationQuery.ProcessedTaskCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalParticipationQuery.ProcessedTaskPage;
import io.github.akaryc1b.approval.application.port.ApprovalParticipationQuery.StartedInstanceCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalParticipationQuery.StartedInstancePage;

import java.util.Objects;

/**
 * Application boundary for user participation views.
 */
public final class ApprovalParticipationQueryService {

    private final ApprovalParticipationQuery query;

    public ApprovalParticipationQueryService(ApprovalParticipationQuery query) {
        this.query = Objects.requireNonNull(query, "query must not be null");
    }

    public StartedInstancePage findStartedInstances(
        String tenantId,
        String operatorId,
        String keyword,
        int limit,
        int offset
    ) {
        return query.findStartedInstances(new StartedInstanceCriteria(
            tenantId,
            operatorId,
            keyword,
            limit,
            offset
        ));
    }

    public ProcessedTaskPage findProcessedTasks(
        String tenantId,
        String operatorId,
        String keyword,
        int limit,
        int offset
    ) {
        return query.findProcessedTasks(new ProcessedTaskCriteria(
            tenantId,
            operatorId,
            keyword,
            limit,
            offset
        ));
    }
}
