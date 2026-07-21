package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalTaskDelegationAssignmentStore;
import io.github.akaryc1b.approval.application.port.ApprovalTaskDelegationAssignmentStore.DelegatedTaskAssignment;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Participant-authorized view of original and delegated task responsibility.
 */
public final class ApprovalTaskDelegationQueryService {

    private final ApprovalProjectionStore projections;
    private final ApprovalTaskDelegationAssignmentStore assignments;

    public ApprovalTaskDelegationQueryService(
        ApprovalProjectionStore projections,
        ApprovalTaskDelegationAssignmentStore assignments
    ) {
        this.projections = Objects.requireNonNull(
            projections,
            "projections must not be null"
        );
        this.assignments = Objects.requireNonNull(
            assignments,
            "assignments must not be null"
        );
    }

    public Optional<DelegatedTaskAssignment> findForTask(
        String tenantId,
        String operatorId,
        UUID taskId
    ) {
        String normalizedTenant = requireText(tenantId, "tenantId");
        String normalizedOperator = requireText(operatorId, "operatorId");
        ApprovalProjectionStore.TaskProjection task = projections.findTask(
            normalizedTenant,
            Objects.requireNonNull(taskId, "taskId must not be null")
        ).orElseThrow(() -> new ApprovalProjectionStore.ProjectionConflictException(
            "approval task projection was not found"
        ));
        Optional<DelegatedTaskAssignment> assignment = assignments.findByEngineTask(
            normalizedTenant,
            task.engineTaskId()
        );
        if (assignment.isEmpty()) {
            requireTaskParticipant(normalizedTenant, normalizedOperator, task);
            return Optional.empty();
        }
        DelegatedTaskAssignment evidence = assignment.get();
        boolean directlyAuthorized = normalizedOperator.equals(task.assigneeId())
            || normalizedOperator.equals(evidence.principalAssigneeId())
            || normalizedOperator.equals(evidence.delegateAssigneeId());
        if (!directlyAuthorized) {
            requireTaskParticipant(normalizedTenant, normalizedOperator, task);
        }
        return assignment;
    }

    private void requireTaskParticipant(
        String tenantId,
        String operatorId,
        ApprovalProjectionStore.TaskProjection task
    ) {
        ApprovalProjectionStore.InstanceProjection instance = projections.findInstance(
            tenantId,
            task.instanceId()
        ).orElseThrow(() -> new ApprovalProjectionStore.ProjectionConflictException(
            "approval instance projection was not found"
        ));
        boolean historicalParticipant = projections.findTasks(
            tenantId,
            task.instanceId()
        ).stream().anyMatch(candidate -> operatorId.equals(candidate.assigneeId()));
        if (!operatorId.equals(instance.initiatorId()) && !historicalParticipant) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "operator is not allowed to view delegated task responsibility"
            );
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
