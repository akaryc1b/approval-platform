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
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.CollaborationProgress;
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

/** Dynamic collaboration lifecycle with exact identities, deterministic voting and completion gating. */
public final class ApprovalTaskCollaborationService implements ApprovalTaskCompletionGuard {

    private static final String CREATE_OPERATION = "approval.task-collaboration.create.v2";
    private static final String ADD_OPERATION = "approval.task-collaboration.add.v1";
    private static final String REMOVE_OPERATION = "approval.task-collaboration.remove.v2";
    private static final String DECIDE_OPERATION = "approval.task-collaboration.decide.v2";
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
        List<ParticipantSpec> participants = normalizeParticipants(command.participants());
        String reason = requireText(command.reason(), "reason");
        CollaborationMode mode = Objects.requireNonNull(command.mode(), "mode must not be null");
        validateThresholds(
            mode,
            command.approvalThreshold(),
            command.approvalWeightThreshold(),
            participants
        );
        String requestHash = hashValues(
            command.taskId().toString(),
            command.connectorKey(),
            mode.name(),
            nullableNumber(command.approvalThreshold()),
            nullableNumber(command.approvalWeightThreshold()),
            reason,
            participantHash(participants)
        );
        return idempotencyGuard.execute(
            command.context(),
            CREATE_OPERATION,
            requestHash,
            TaskCollaboration.class,
            () -> executeCreate(command, participants, mode, reason)
        );
    }

    public TaskCollaboration add(AddParticipantsCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        List<ParticipantSpec> participants = normalizeParticipants(command.participants());
        String reason = requireText(command.reason(), "reason");
        String requestHash = hashValues(
            command.taskId().toString(),
            command.connectorKey(),
            reason,
            participantHash(participants)
        );
        return idempotencyGuard.execute(
            command.context(),
            ADD_OPERATION,
            requestHash,
            TaskCollaboration.class,
            () -> executeAdd(command, participants, reason)
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
                    throw conflict(
                        "APPROVAL_TASK_COLLABORATION_STATE_CONFLICT",
                        "all required collaboration decisions must finish before task approval"
                    );
                }
                if (collaboration.status() == CollaborationStatus.REJECTED) {
                    throw conflict(
                        "APPROVAL_TASK_COLLABORATION_THRESHOLD_UNREACHABLE",
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
        List<ParticipantSpec> participants,
        CollaborationMode mode,
        String reason
    ) {
        collaborations.lockTask(command.context().tenantId(), command.taskId());
        TaskProjection task = requirePendingOwnedTask(command.context(), command.taskId());
        if (PurchasePaymentTemplate.REVISION_TASK_KEY.equals(task.taskDefinitionKey())) {
            throw conflict(
                "APPROVAL_TASK_COLLABORATION_STATE_CONFLICT",
                "an initiator revision task cannot be add-signed"
            );
        }
        InstanceProjection instance = requireInstance(
            command.context().tenantId(),
            task.instanceId()
        );
        if (instance.status() != InstanceStatus.RUNNING) {
            throw conflict(
                "APPROVAL_TASK_COLLABORATION_STATE_CONFLICT",
                "only a running approval task can be add-signed"
            );
        }

        List<ResolvedParticipant> resolved = resolveCandidates(
            command.context(),
            command.connectorKey(),
            participants
        );
        validateResolvedUsers(task, resolved, Set.of());

        Instant now = clock.instant();
        UUID policyId = identifierGenerator.get();
        List<CollaborationParticipant> evidence = participantEvidence(
            policyId,
            command.context(),
            resolved,
            now
        );
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
            command.approvalThreshold(),
            command.approvalWeightThreshold(),
            CollaborationStatus.ACTIVE,
            reason,
            command.context().operatorId(),
            now,
            null,
            null,
            null,
            1,
            evidence
        ));
        appendAudit(
            command.context(),
            "TASK_COLLABORATION_CREATED",
            created,
            Map.of("reason", reason),
            now
        );
        return created;
    }

    private TaskCollaboration executeAdd(
        AddParticipantsCommand command,
        List<ParticipantSpec> participants,
        String reason
    ) {
        collaborations.lockTask(command.context().tenantId(), command.taskId());
        TaskProjection task = requirePendingOwnedTask(command.context(), command.taskId());
        TaskCollaboration current = collaborations.findByTask(
            command.context().tenantId(),
            command.taskId()
        ).orElseThrow(() -> new ApprovalTaskCollaborationStore.CollaborationNotFoundException(
            "task collaboration policy was not found"
        ));
        if (current.status() != CollaborationStatus.ACTIVE) {
            throw conflict(
                "APPROVAL_TASK_COLLABORATION_STATE_CONFLICT",
                "participants can only be added to an active collaboration policy"
            );
        }
        if (decisionsStarted(current)) {
            throw conflict(
                "APPROVAL_TASK_COLLABORATION_DECISIONS_STARTED",
                "participants cannot be added after collaboration decisions begin"
            );
        }
        int existing = (int) current.participants().stream()
            .filter(item -> item.status() != ParticipantStatus.REMOVED)
            .count();
        if (existing + participants.size() > MAXIMUM_PARTICIPANTS) {
            throw invalid(
                "APPROVAL_TASK_COLLABORATION_INVALID_PARTICIPANTS",
                "participants must not exceed 20 users"
            );
        }
        if (current.mode() != CollaborationMode.WEIGHTED
            && participants.stream().anyMatch(item -> item.weight() != 1)) {
            throw invalid(
                "APPROVAL_TASK_COLLABORATION_INVALID_THRESHOLD",
                "only WEIGHTED collaboration accepts participant weights"
            );
        }
        List<ResolvedParticipant> resolved = resolveCandidates(
            command.context(),
            command.connectorKey(),
            participants
        );
        Set<String> existingUsers = current.participants().stream()
            .map(CollaborationParticipant::participantUserId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        validateResolvedUsers(task, resolved, existingUsers);
        Instant now = clock.instant();
        List<CollaborationParticipant> evidence = participantEvidence(
            current.policyId(),
            command.context(),
            resolved,
            now
        );
        TaskCollaboration updated = collaborations.addParticipants(
            command.context().tenantId(),
            current.policyId(),
            evidence
        );
        for (CollaborationParticipant participant : evidence) {
            appendAudit(
                command.context(),
                "TASK_COLLABORATOR_ADDED",
                updated,
                Map.of(
                    "participantId", participant.participantId().toString(),
                    "participantUserId", participant.participantUserId(),
                    "participantWeight", Integer.toString(participant.weight()),
                    "reason", reason
                ),
                now
            );
        }
        return updated;
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
        CollaborationParticipant participant = participant(current, command.participantId());
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
            updated,
            Map.of(
                "participantId", participant.participantId().toString(),
                "participantUserId", participant.participantUserId(),
                "participantWeight", Integer.toString(participant.weight()),
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
            throw conflict(
                "APPROVAL_TASK_COLLABORATION_STATE_CONFLICT",
                "the parent approval task is no longer pending"
            );
        }
        CollaborationParticipant participant = participant(current, command.participantId());
        if (!participant.participantUserId().equals(command.context().operatorId())) {
            throw new ApprovalTaskCollaborationStore.CollaborationAuthorizationException(
                "only the assigned collaboration participant can decide"
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
            updated,
            Map.of(
                "participantId", command.participantId().toString(),
                "participantUserId", participant.participantUserId(),
                "participantWeight", Integer.toString(participant.weight()),
                "decision", decision.name(),
                "comment", comment
            ),
            now
        );
        if (current.status() == CollaborationStatus.ACTIVE
            && updated.status() == CollaborationStatus.SATISFIED) {
            appendAudit(
                command.context(),
                "TASK_COLLABORATION_THRESHOLD_REACHED",
                updated,
                Map.of("decision", decision.name()),
                now
            );
        }
        if (current.status() == CollaborationStatus.ACTIVE
            && updated.status() == CollaborationStatus.REJECTED) {
            appendAudit(
                command.context(),
                "TASK_COLLABORATION_THRESHOLD_IMPOSSIBLE",
                updated,
                Map.of("decision", decision.name()),
                now
            );
        }
        return updated;
    }

    private List<ResolvedParticipant> resolveCandidates(
        RequestContext context,
        String connectorKey,
        List<ParticipantSpec> participants
    ) {
        List<ResolvedParticipant> candidates = new ArrayList<>(participants.size());
        for (ParticipantSpec participant : participants) {
            IdentityCandidate candidate = identities.requireUser(new IdentityLookup(
                context.tenantId(),
                connectorKey,
                context.requestId(),
                context.traceId(),
                participant.identity(),
                true
            ));
            candidates.add(new ResolvedParticipant(candidate, participant.weight()));
        }
        candidates.sort(Comparator.comparing(
            item -> item.candidate().reference().canonicalValue()
        ));
        return List.copyOf(candidates);
    }

    private static void validateResolvedUsers(
        TaskProjection task,
        List<ResolvedParticipant> candidates,
        Set<String> existingUsers
    ) {
        Set<String> distinctUsers = new LinkedHashSet<>(existingUsers);
        for (ResolvedParticipant resolved : candidates) {
            IdentityCandidate candidate = resolved.candidate();
            if (candidate.userId().equals(task.assigneeId())) {
                throw invalid(
                    "APPROVAL_TASK_COLLABORATION_OWNER_PARTICIPANT",
                    "the current task assignee cannot be a collaboration participant"
                );
            }
            if (!distinctUsers.add(candidate.userId())) {
                throw conflict(
                    "APPROVAL_TASK_COLLABORATION_DUPLICATE_PARTICIPANT",
                    "collaboration participants must resolve to distinct users"
                );
            }
        }
    }

    private List<CollaborationParticipant> participantEvidence(
        UUID policyId,
        RequestContext context,
        List<ResolvedParticipant> participants,
        Instant addedAt
    ) {
        List<CollaborationParticipant> result = new ArrayList<>(participants.size());
        for (ResolvedParticipant resolved : participants) {
            result.add(new CollaborationParticipant(
                identifierGenerator.get(),
                policyId,
                context.tenantId(),
                resolved.candidate().userId(),
                resolved.candidate().reference(),
                resolved.weight(),
                ParticipantStatus.PENDING,
                context.operatorId(),
                addedAt,
                null,
                null,
                null,
                null,
                null,
                null,
                1
            ));
        }
        return List.copyOf(result);
    }

    private TaskProjection requirePendingOwnedTask(RequestContext context, UUID taskId) {
        TaskProjection task = requireTask(context.tenantId(), taskId);
        if (task.status() != TaskStatus.PENDING) {
            throw conflict(
                "APPROVAL_TASK_COLLABORATION_STATE_CONFLICT",
                "task is not pending"
            );
        }
        if (!task.assigneeId().equals(context.operatorId())) {
            throw new ApprovalTaskCollaborationStore.CollaborationAuthorizationException(
                "only the current task assignee can manage collaboration participants"
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
        TaskCollaboration collaboration,
        Map<String, String> additional,
        Instant occurredAt
    ) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("policyId", collaboration.policyId().toString());
        attributes.put("mode", collaboration.mode().name());
        putNullable(attributes, "approvalThreshold", collaboration.approvalThreshold());
        putNullable(
            attributes,
            "approvalWeightThreshold",
            collaboration.approvalWeightThreshold()
        );
        attributes.put("policyStatus", collaboration.status().name());
        attributes.put("ownerAssigneeId", collaboration.ownerAssigneeId());
        attributes.put("definitionKey", collaboration.definitionKey());
        attributes.put("taskDefinitionKey", collaboration.taskDefinitionKey());
        attributes.put("taskName", collaboration.taskName());
        attributes.put("engineTaskId", collaboration.engineTaskId());
        attributes.put("engineInstanceId", collaboration.engineInstanceId());
        attributes.put("participantWeights", participantWeights(collaboration));
        CollaborationProgress progress = collaboration.progress();
        attributes.put("approvedCount", Integer.toString(progress.approvedCount()));
        attributes.put("rejectedCount", Integer.toString(progress.rejectedCount()));
        attributes.put("pendingCount", Integer.toString(progress.pendingCount()));
        attributes.put("approvedWeight", Integer.toString(progress.approvedWeight()));
        attributes.put("rejectedWeight", Integer.toString(progress.rejectedWeight()));
        attributes.put("pendingWeight", Integer.toString(progress.pendingWeight()));
        attributes.put(
            "maximumReachableApprovalCount",
            Integer.toString(progress.maximumReachableApprovalCount())
        );
        attributes.put(
            "maximumReachableApprovalWeight",
            Integer.toString(progress.maximumReachableApprovalWeight())
        );
        attributes.putAll(additional);
        auditEvents.append(new AuditEvent(
            identifierGenerator.get(),
            context.tenantId(),
            context.operatorId(),
            action,
            "APPROVAL_TASK",
            collaboration.taskId().toString(),
            context.requestId(),
            context.traceId(),
            occurredAt,
            Map.copyOf(attributes)
        ));
    }

    private static String participantWeights(TaskCollaboration collaboration) {
        return collaboration.participants().stream()
            .map(item -> item.participantUserId() + ":" + item.weight())
            .sorted()
            .reduce((left, right) -> left + "," + right)
            .orElse("");
    }

    private static void putNullable(
        Map<String, String> attributes,
        String key,
        Integer value
    ) {
        attributes.put(key, value == null ? "" : Integer.toString(value));
    }

    private static boolean decisionsStarted(TaskCollaboration collaboration) {
        return collaboration.participants().stream().anyMatch(item ->
            item.status() == ParticipantStatus.APPROVED
                || item.status() == ParticipantStatus.REJECTED
        );
    }

    private static CollaborationParticipant participant(
        TaskCollaboration collaboration,
        UUID participantId
    ) {
        return collaboration.participants().stream()
            .filter(item -> item.participantId().equals(participantId))
            .findFirst()
            .orElseThrow(() -> new ApprovalTaskCollaborationStore.CollaborationNotFoundException(
                "task collaboration participant was not found"
            ));
    }

    private static List<ParticipantSpec> normalizeParticipants(
        List<ParticipantSpec> participants
    ) {
        if (participants == null || participants.isEmpty()) {
            throw invalid(
                "APPROVAL_TASK_COLLABORATION_INVALID_PARTICIPANTS",
                "participants must not be empty"
            );
        }
        if (participants.size() > MAXIMUM_PARTICIPANTS) {
            throw invalid(
                "APPROVAL_TASK_COLLABORATION_INVALID_PARTICIPANTS",
                "participants must not exceed 20 users"
            );
        }
        Map<String, ParticipantSpec> distinct = new LinkedHashMap<>();
        for (ParticipantSpec participant : participants) {
            ParticipantSpec normalized = Objects.requireNonNull(
                participant,
                "participant must not be null"
            );
            if (distinct.putIfAbsent(normalized.identity().canonicalValue(), normalized) != null) {
                throw conflict(
                    "APPROVAL_TASK_COLLABORATION_DUPLICATE_PARTICIPANT",
                    "participant identities must be distinct"
                );
            }
        }
        return distinct.values().stream()
            .sorted(Comparator.comparing(item -> item.identity().canonicalValue()))
            .toList();
    }

    private static void validateThresholds(
        CollaborationMode mode,
        Integer approvalThreshold,
        Integer approvalWeightThreshold,
        List<ParticipantSpec> participants
    ) {
        int totalWeight = participants.stream().mapToInt(ParticipantSpec::weight).sum();
        switch (mode) {
            case ALL, ANY -> {
                if (approvalThreshold != null || approvalWeightThreshold != null) {
                    throw invalid(
                        "APPROVAL_TASK_COLLABORATION_INVALID_THRESHOLD",
                        "ALL and ANY collaboration must not contain thresholds"
                    );
                }
                if (participants.stream().anyMatch(item -> item.weight() != 1)) {
                    throw invalid(
                        "APPROVAL_TASK_COLLABORATION_INVALID_THRESHOLD",
                        "ALL and ANY participants must use weight 1"
                    );
                }
            }
            case VOTE -> {
                if (approvalThreshold == null || approvalThreshold < 1
                    || approvalThreshold > participants.size()
                    || approvalWeightThreshold != null) {
                    throw invalid(
                        "APPROVAL_TASK_COLLABORATION_INVALID_THRESHOLD",
                        "VOTE requires approvalThreshold between 1 and participant count"
                    );
                }
                if (participants.stream().anyMatch(item -> item.weight() != 1)) {
                    throw invalid(
                        "APPROVAL_TASK_COLLABORATION_INVALID_THRESHOLD",
                        "VOTE participants must use weight 1"
                    );
                }
            }
            case WEIGHTED -> {
                if (approvalWeightThreshold == null || approvalWeightThreshold < 1
                    || approvalWeightThreshold > totalWeight || approvalThreshold != null) {
                    throw invalid(
                        "APPROVAL_TASK_COLLABORATION_INVALID_THRESHOLD",
                        "WEIGHTED requires approvalWeightThreshold between 1 and total weight"
                    );
                }
            }
        }
    }

    private static String participantHash(List<ParticipantSpec> participants) {
        return participants.stream()
            .map(item -> item.identity().canonicalValue() + ":" + item.weight())
            .reduce((left, right) -> left + "," + right)
            .orElse("");
    }

    private static String nullableNumber(Integer value) {
        return value == null ? "" : Integer.toString(value);
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

    private static ApprovalTaskCollaborationStore.CollaborationValidationException invalid(
        String code,
        String message
    ) {
        return new ApprovalTaskCollaborationStore.CollaborationValidationException(code, message);
    }

    private static ApprovalTaskCollaborationStore.CollaborationConflictException conflict(
        String code,
        String message
    ) {
        return new ApprovalTaskCollaborationStore.CollaborationConflictException(code, message);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    public record ParticipantSpec(IdentityReference identity, int weight) {
        public ParticipantSpec {
            identity = Objects.requireNonNull(identity, "identity must not be null");
            if (weight < 1) {
                throw invalid(
                    "APPROVAL_TASK_COLLABORATION_INVALID_WEIGHT",
                    "participant weight must be positive"
                );
            }
        }
    }

    private record ResolvedParticipant(IdentityCandidate candidate, int weight) {
    }

    public record CreateCollaborationCommand(
        RequestContext context,
        UUID taskId,
        String connectorKey,
        CollaborationMode mode,
        Integer approvalThreshold,
        Integer approvalWeightThreshold,
        List<ParticipantSpec> participants,
        String reason
    ) {
        public CreateCollaborationCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            taskId = Objects.requireNonNull(taskId, "taskId must not be null");
            connectorKey = requireText(connectorKey, "connectorKey");
            participants = participants == null ? List.of() : List.copyOf(participants);
        }
    }

    public record AddParticipantsCommand(
        RequestContext context,
        UUID taskId,
        String connectorKey,
        List<ParticipantSpec> participants,
        String reason
    ) {
        public AddParticipantsCommand {
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
