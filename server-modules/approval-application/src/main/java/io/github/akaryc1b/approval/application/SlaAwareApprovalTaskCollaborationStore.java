package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalRequestEvidenceProvider;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Collaboration-store decorator that creates and terminates participant SLA evidence atomically. */
public final class SlaAwareApprovalTaskCollaborationStore implements ApprovalTaskCollaborationStore {

    private final ApprovalTaskCollaborationStore delegate;
    private final ApprovalSlaService sla;
    private final ApprovalRequestEvidenceProvider evidence;

    public SlaAwareApprovalTaskCollaborationStore(
        ApprovalTaskCollaborationStore delegate,
        ApprovalSlaService sla,
        ApprovalRequestEvidenceProvider evidence
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.sla = Objects.requireNonNull(sla, "sla must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
    }

    @Override
    public void lockTask(String tenantId, UUID taskId) {
        delegate.lockTask(tenantId, taskId);
    }

    @Override
    public TaskCollaboration create(TaskCollaboration collaboration) {
        TaskCollaboration created = delegate.create(collaboration);
        sla.synchronizeCollaboration(created, evidence.current());
        return created;
    }

    @Override
    public Optional<TaskCollaboration> findByTask(String tenantId, UUID taskId) {
        return delegate.findByTask(tenantId, taskId);
    }

    @Override
    public Optional<TaskCollaboration> findByParticipant(String tenantId, UUID participantId) {
        return delegate.findByParticipant(tenantId, participantId);
    }

    @Override
    public List<PendingCollaborationTask> findPendingByParticipant(
        String tenantId,
        String participantUserId,
        int limit
    ) {
        return delegate.findPendingByParticipant(tenantId, participantUserId, limit);
    }

    @Override
    public TaskCollaboration addParticipants(
        String tenantId,
        UUID policyId,
        List<CollaborationParticipant> participants
    ) {
        TaskCollaboration changed = delegate.addParticipants(tenantId, policyId, participants);
        sla.synchronizeCollaboration(changed, evidence.current());
        return changed;
    }

    @Override
    public TaskCollaboration removeParticipant(
        String tenantId,
        UUID participantId,
        String removedBy,
        String reason,
        Instant removedAt
    ) {
        TaskCollaboration changed = delegate.removeParticipant(
            tenantId,
            participantId,
            removedBy,
            reason,
            removedAt
        );
        sla.synchronizeCollaboration(changed, evidence.current());
        return changed;
    }

    @Override
    public TaskCollaboration decideParticipant(
        String tenantId,
        UUID participantId,
        String participantUserId,
        ParticipantDecision decision,
        String comment,
        Instant decidedAt
    ) {
        TaskCollaboration changed = delegate.decideParticipant(
            tenantId,
            participantId,
            participantUserId,
            decision,
            comment,
            decidedAt
        );
        sla.synchronizeCollaboration(changed, evidence.current());
        return changed;
    }

    @Override
    public Optional<TaskCollaboration> cancelActiveByTask(
        String tenantId,
        UUID taskId,
        String canceledBy,
        String reason,
        Instant canceledAt
    ) {
        Optional<TaskCollaboration> changed = delegate.cancelActiveByTask(
            tenantId,
            taskId,
            canceledBy,
            reason,
            canceledAt
        );
        changed.ifPresent(item -> sla.synchronizeCollaboration(item, evidence.current()));
        return changed;
    }
}
