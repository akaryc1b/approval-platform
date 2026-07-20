package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityCandidate;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityLookup;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityReference;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.CollaborationMode;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.CollaborationParticipant;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.CollaborationStatus;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.ParticipantDecision;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.ParticipantStatus;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.PendingCollaborationTask;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.TaskCollaboration;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCompletionGuard;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Dynamic add-sign/remove-sign lifecycle with exact identities and completion gating. */
public final class ApprovalTaskCollaborationService implements ApprovalTaskCompletionGuard {

    private static final String CREATE_OPERATION = "approval.task-collaboration.create.v1";
    private static final String REMOVE_OPERATION = "approval.task-collaboration.remove.v1";
    private static final String DECIDE_OPERATION = "approval.task-collaboration.decide.v1";
    private static final int MAXIMUM_PARTICIPANTS = 20;

    private final IdempotencyGuard idempotencyGuard;
    private final ApprovalIdentityDirectory identities;
    private final ApprovalTaskCollaborationStore collaborations;
    private final ApprovalProjectionStore projections;
    private final AuditEventSink auditEvents;
    private final Clock clock;
    private final Supplier<UUID> identifierGenerator;

    public ApprovalTaskCollaborationService(
        IdempotencyGuard idempotencyGuard,
        ApprovalIdentityDirectory identities,
        ApprovalTaskCollaborationStore collaborations,
        ApprovalProjectionStore projections,
        AuditEventSink auditEvents,
        Clock clock,
        Supplier<UUID> identifierGenerator
    ) {
        this.idempotencyGuard = Objects.requireNonNull(
            idempotencyGuard,
            "idempotencyGuard must not be null"
        );
        this.identities = Objects.requireNonNull(identities, "identities must not be null");
        this.collaborations = Objects.requireNonNull(
            collaborations,
            "collaborations must not be null"
        );
        this.projections = Objects.requireNonNull(projections, "projections must not be null");
        this.auditEvents = Objects.requireNonNull(auditEvents, "auditEvents must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.identifierGenerator = Objects.requireNonNull(
            identifierGenerator,
            "identifierGenerator must not be null"
        );
    }

    public TaskCollaboration create(CreateCollaborationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        List<IdentityReference> references = normalizeReferences(command.participants());
        String reason = requireText(command.reason(), "reason");
        CollaborationMode mode = Objects.requireNonNull(command.mode(), "mode must not be null");
        String requestHash = hashValues(
            command.taskId().toString(),
            command.connectorKey(),
            mode.name(),
            reason,
            references.stream().map(IdentityReference::canonicalValue).toList().toString()
        );
        return idempotencyGuard.execute(
            command.context(),
            CREATE_OPERATION,
            requestHash,
            TaskCollaboration.class,
            () -> executeCreate(command, references, mode, reason)
        );
    }

    public TaskCollaboration remove(RemoveParticipantCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String reason = requireText(command.reason(), "reason");
        String requestHash = hashValues(command.participantId().toString(), reason);
        return idempotencyGuard.execute(
            command.context(),
            REMOVE_OPERATION,
            requestHash,
            TaskCollaboration.class,
            () -> executeRemove(command, reason)
        );
    }

    public TaskCollaboration decide(DecideParticipantCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        ParticipantDecision decision = Objects.requireNonNull(
            command.decision(),
            "decision must not be null"
        );
        String comment = requireText(command.comment(), "comment");
        String requestHash = hashValues(
            command.participantId().toString(),
            decision.name(),
            comment
        );
        return idempotencyGuard.execute(
            command.context(),
            DECIDE_OPERATION,
            requestHash,
            TaskCollaboration.class,
            () -> executeDecision(command, decision, comment)
        );
    }

    public TaskCollaboration findByTask(
        String tenantId,
        String operatorId,
        UUID taskId
    ) {
        String normalizedTenant = requireText(tenantId, "tenantId");
        String normalizedOperator = requireText(operatorId, "operatorId");
        TaskCollaboration collaboration = collaborations.findByTask(normalizedTenant, taskId)
            .orElseThrow(() -> new ApprovalTaskCollaborationStore.CollaborationNotFoundException(
                "task collaboration policy was not found"
            ));
        InstanceProjection instance = requireInstance(normalizedTenant, collaboration.instanceId());
        boolean participant = collaboration.participants().stream().anyMatch(item ->
            item.participantUserId().equals(normalizedOperator)
        );
        if (!normalizedOperator.equals(instance.initiatorId())
            && !normalizedOperator.equals(requireTask(normalizedTenant, taskId).assigneeId())
            && !participant) {
            throw new ApprovalTaskCollaborationStore.CollaborationNotFoundException(
                "task collaboration policy was not found"
            );
        }
        return collaboration;
    }

    public List<PendingCollaborationTask> findPending(
        String tenantId,
        String operatorId,
        int limit
    ) {
        return collaborations.findPendingByParticipant(
            requireText(tenantId, "tenantId"),
            requireText(operatorId, "operatorId"),
            limit
        );
    }

    @Override
    public void validate(String tenantId, UUID taskId, TaskOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (outcome != TaskOutcome.APPROVED) {
            return;
        }
        collaborations.findByTask(requireText(tenantId, "tenantId"), taskId)
            .ifPresent(collaboration -> {
                if (collaboration.status() == CollaborationStatus.ACTIVE) {
                    throw new ApprovalTaskCollaborationStore.CollaborationConflictException(
                        "all required add-sign decisions must finish before task approval"
                    );
                }
                if (collaboration.status() == CollaborationStatus.REJECTED) {
                    throw new ApprovalTaskCollaborationStore.CollaborationConflictException(
                        "task collaboration was rejected and cannot be approved"
                    );
                }
            });
    }

    @Override
    public void completed(
        String tenantId,
        UUID taskId,
        String operatorId,
        TaskOutcome outcome,
        Instant completedAt
    ) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (outcome == TaskOutcome.APPROVED) {
            return;
        }
        collaborations.cancelActiveByTask(
            requireText(tenantId, "tenantId"),
            Objects.requireNonNull(taskId, "taskId must not be null"),
            requireText(operatorId, "operatorId"),
            "parent task completed with outcome " + outcome.name(),
            Objects.requireNonNull(completedAt, "completedAt must not be null")
        );
    }

    private TaskCollaboration executeCreate(
        CreateCollaborationCommand command,
        List<IdentityReference> references,
        CollaborationMode mode,
        String reason
    ) {
        TaskProjection task = requirePendingOwnedTask(command.context(), command.taskId());
        if (PurchasePaymentTemplate.REVISION_TASK_KEY.equals(task.taskDefinitionKey())) {
            throw new ApprovalTaskCollaborationStore.CollaborationConflictException(
                "an initiator revision task cannot be add-signed"
            );
        }
        InstanceProjection instance = requireInstance(
            command.context().tenantId(),
            task.instanceId()
        );
        if (instance.status() != InstanceStatus.RUNNING) {
            throw new ApprovalTaskCollaborationStore.CollaborationConflictException(
                "only a running approval task can be add-signed"
            );
        }

        List<IdentityCandidate> candidates = resolveCandidates(command, references);
        Set<String> distinctUsers = new LinkedHashSet<>();
        for (IdentityCandidate candidate : candidates) {
            if (candidate.userId().equals(task.assigneeId())) {
                throw new IllegalArgumentException(
                    "the current task assignee cannot be an add-sign participant"
                );
            }
            if (!distinctUsers.add(candidate.userId())) {
                throw new IllegalArgumentException(
                    "add-sign participants must resolve to distinct users"
                );
            }
        }

        Instant now = clock.instant();
        UUID policyId = identifierGenerator.get();
        List<CollaborationParticipant> participants = new ArrayList<>(candidates.size());
        for (IdentityCandidate candidate : candidates) {
            participants.add(new CollaborationParticipant(
                identifierGenerator.get(),
                policyId,
                command.context().tenantId(),
                candidate.userId(),
                candidate.reference(),
                ParticipantStatus.PENDING,
                command.context().operatorId(),
                now,
                null,
                null,
                null,
                null,
                null,
                null,
                1
            ));
        }
        TaskCollaboration created = collaborations.create(new TaskCollaboration(
            policyId,
            command.context().tenantId(),
            task.taskId(),
            task.instanceId(),
            task.engineTaskId(),
            instance.engineInstanceId(),
            instance.definitionKey(),
            task.taskDefinitionKey(),
            task.name(),
            task.assigneeId(),
            mode,
            CollaborationStatus.ACTIVE,
            reason,
            command.context().operatorId(),
            now,
            null,
            null,
            null,
            1,
            participants
        ));
        appendAudit(
            command.context(),
            "TASK_COLLABORATION_CREATED",
            task.taskId(),
            Map.of(
                "policyId", created.policyId().toString(),
                "mode", created.mode().name(),
                "participantUserIds", String.join(",", distinctUsers),
                "reason", reason
            ),
            now
        );
        return created;
    }

    private TaskCollaboration executeRemove(
        RemoveParticipantCommand command,
        String reason
    ) {
        TaskCollaboration current = collaborations.findByParticipant(
            command.context().tenantId(),
            command.participantId()
        ).orElseThrow(() -> new ApprovalTaskCollaborationStore.CollaborationNotFoundException(
            "task collaboration participant was not found"
        ));
        requirePendingOwnedTask(command.context(), current.taskId());
        CollaborationParticipant participant = current.participants().stream()
            .filter(item -> item.participantId().equals(command.participantId()))
            .findFirst()
            .orElseThrow(() -> new ApprovalTaskCollaborationStore.CollaborationNotFoundException(
                "task collaboration participant was not found"
            ));
        Instant now = clock.instant();
        TaskCollaboration updated = collaborations.removeParticipant(
            command.context().tenantId(),
            command.participantId(),
            command.context().operatorId(),
            reason,
            now
        );
        appendAudit(
            command.context(),
            "TASK_COLLABORATOR_REMOVED",
            current.taskId(),
            Map.of(
                "policyId", current.policyId().toString(),
                "participantId", participant.participantId().toString(),
                "participantUserId", participant.participantUserId(),
                "reason", reason
            ),
            now
        );
        return updated;
    }

    private TaskCollaboration executeDecision(
        DecideParticipantCommand command,
        ParticipantDecision decision,
        String comment
    ) {
        TaskCollaboration current = collaborations.findByParticipant(
            command.context().tenantId(),
            command.participantId()
        ).orElseThrow(() -> new ApprovalTaskCollaborationStore.CollaborationNotFoundException(
            "task collaboration participant was not found"
        ));
        TaskProjection task = requireTask(command.context().tenantId(), current.taskId());
        if (task.status() != TaskStatus.PENDING) {
            throw new ApprovalTaskCollaborationStore.CollaborationConflictException(
                "the parent approval task is no longer pending"
            );
        }
        Instant now = clock.instant();
        TaskCollaboration updated = collaborations.decideParticipant(
            command.context().tenantId(),
            command.participantId(),
            command.context().operatorId(),
            decision,
            comment,
            now
        );
        appendAudit(
            command.context(),
            "TASK_COLLABORATION_PARTICIPANT_DECIDED",
            current.taskId(),
            Map.of(
                "policyId", current.policyId().toString(),
                "participantId", command.participantId().toString(),
                "decision", decision.name(),
                "policyStatus", updated.status().name(),
                "comment", comment
            ),
            now
        );
        return updated;
    }

    private List<IdentityCandidate> resolveCandidates(
        CreateCollaborationCommand command,
        List<IdentityReference> references
    ) {
        List<IdentityCandidate> candidates = new ArrayList<>(references.size());
        for (IdentityReference reference : references) {
            candidates.add(identities.requireUser(new IdentityLookup(
                command.context().tenantId(),
                command.connectorKey(),
                command.context().requestId(),
                command.context().traceId(),
                reference,
                true
            )));
        }
        candidates.sort(Comparator.comparing(candidate -> candidate.reference().canonicalValue()));
        return List.copyOf(candidates);
    }

    private TaskProjection requirePendingOwnedTask(RequestContext context, UUID taskId) {
        TaskProjection task = requireTask(context.tenantId(), taskId);
        if (task.status() != TaskStatus.PENDING
            || !task.assigneeId().equals(context.operatorId())) {
            throw new ApprovalTaskCollaborationStore.CollaborationConflictException(
                "task is not pending or is assigned to another operator"
            );
        }
        return task;
    }

    private TaskProjection requireTask(String tenantId, UUID taskId) {
        return projections.findTask(
            requireText(tenantId, "tenantId"),
            Objects.requireNonNull(taskId, "taskId must not be null")
        ).orElseThrow(() -> new ApprovalTaskCollaborationStore.CollaborationNotFoundException(
            "approval task was not found"
        ));
    }

    private InstanceProjection requireInstance(String tenantId, UUID instanceId) {
        return projections.findInstance(
            requireText(tenantId, "tenantId"),
            Objects.requireNonNull(instanceId, "instanceId must not be null")
        ).orElseThrow(() -> new ApprovalTaskCollaborationStore.CollaborationNotFoundException(
            "approval instance was not found"
        ));
    }

    private void appendAudit(
        RequestContext context,
        String action,
        UUID taskId,
        Map<String, String> attributes,
        Instant occurredAt
    ) {
        auditEvents.append(new AuditEvent(
            identifierGenerator.get(),
            context.tenantId(),
            context.operatorId(),
            action,
            "APPROVAL_TASK",
            taskId.toString(),
            context.requestId(),
            context.traceId(),
            occurredAt,
            attributes
        ));
    }

    private static List<IdentityReference> normalizeReferences(
        List<IdentityReference> references
    ) {
        if (references == null || references.isEmpty()) {
            throw new IllegalArgumentException("participants must not be empty");
        }
        if (references.size() > MAXIMUM_PARTICIPANTS) {
            throw new IllegalArgumentException("participants must not exceed 20 users");
        }
        Map<String, IdentityReference> distinct = new LinkedHashMap<>();
        for (IdentityReference reference : references) {
            IdentityReference normalized = Objects.requireNonNull(
                reference,
                "participant identity must not be null"
            );
            distinct.putIfAbsent(normalized.canonicalValue(), normalized);
        }
        if (distinct.size() != references.size()) {
            throw new IllegalArgumentException("participant identities must be distinct");
        }
        return distinct.values().stream()
            .sorted(Comparator.comparing(IdentityReference::canonicalValue))
            .toList();
    }

    private static String hashValues(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                String normalized = value == null ? "" : value;
                byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
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

    public record CreateCollaborationCommand(
        RequestContext context,
        UUID taskId,
        String connectorKey,
        CollaborationMode mode,
        List<IdentityReference> participants,
        String reason
    ) {
        public CreateCollaborationCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            taskId = Objects.requireNonNull(taskId, "taskId must not be null");
            connectorKey = requireText(connectorKey, "connectorKey");
            participants = participants == null ? List.of() : List.copyOf(participants);
        }
    }

    public record RemoveParticipantCommand(
        RequestContext context,
        UUID participantId,
        String reason
    ) {
        public RemoveParticipantCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            participantId = Objects.requireNonNull(
                participantId,
                "participantId must not be null"
            );
        }
    }

    public record DecideParticipantCommand(
        RequestContext context,
        UUID participantId,
        ParticipantDecision decision,
        String comment
    ) {
        public DecideParticipantCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            participantId = Objects.requireNonNull(
                participantId,
                "participantId must not be null"
            );
        }
    }
}
