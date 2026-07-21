package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalDelegationStore;
import io.github.akaryc1b.approval.application.port.ApprovalDelegationStore.DelegationRule;
import io.github.akaryc1b.approval.application.port.ApprovalHandoverStore;
import io.github.akaryc1b.approval.application.port.ApprovalHandoverStore.HandoverTaskAssignment;
import io.github.akaryc1b.approval.application.port.ApprovalHandoverStore.PrincipalHandover;
import io.github.akaryc1b.approval.application.port.ApprovalTaskDelegationAssignmentStore;
import io.github.akaryc1b.approval.application.port.ApprovalTaskDelegationAssignmentStore.AssignmentStatus;
import io.github.akaryc1b.approval.application.port.ApprovalTaskDelegationAssignmentStore.DelegatedTaskAssignment;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import io.github.akaryc1b.approval.engine.ApprovalEngine;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Approval-engine decorator that applies durable employee handovers and active proxy rules
 * whenever a new user task becomes visible. The raw engine remains responsible only for
 * workflow mechanics; responsibility evidence remains platform-owned.
 */
public final class DelegatingApprovalEngine implements ApprovalEngine {

    private final ApprovalEngine delegate;
    private final ApprovalDelegationStore delegations;
    private final ApprovalTaskDelegationAssignmentStore assignments;
    private final ApprovalHandoverStore handovers;
    private final AuditEventSink auditEvents;
    private final Clock clock;
    private final Supplier<UUID> identifierGenerator;
    private final ConcurrentMap<String, String> startedDefinitions = new ConcurrentHashMap<>();

    public DelegatingApprovalEngine(
        ApprovalEngine delegate,
        ApprovalDelegationStore delegations,
        ApprovalTaskDelegationAssignmentStore assignments,
        AuditEventSink auditEvents,
        Clock clock,
        Supplier<UUID> identifierGenerator
    ) {
        this(
            delegate,
            delegations,
            assignments,
            null,
            auditEvents,
            clock,
            identifierGenerator
        );
    }

    public DelegatingApprovalEngine(
        ApprovalEngine delegate,
        ApprovalDelegationStore delegations,
        ApprovalTaskDelegationAssignmentStore assignments,
        ApprovalHandoverStore handovers,
        AuditEventSink auditEvents,
        Clock clock,
        Supplier<UUID> identifierGenerator
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.delegations = Objects.requireNonNull(
            delegations,
            "delegations must not be null"
        );
        this.assignments = Objects.requireNonNull(
            assignments,
            "assignments must not be null"
        );
        this.handovers = handovers;
        this.auditEvents = Objects.requireNonNull(
            auditEvents,
            "auditEvents must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.identifierGenerator = Objects.requireNonNull(
            identifierGenerator,
            "identifierGenerator must not be null"
        );
    }

    @Override
    public DeploymentResult deploy(DeployCommand command) {
        return delegate.deploy(command);
    }

    @Override
    public StartResult start(StartCommand command) {
        StartResult result = delegate.start(command);
        rememberDefinition(command.tenantId(), result.engineInstanceId(), command.definitionKey());
        return result;
    }

    @Override
    public StartResult startExact(ExactStartCommand command) {
        StartResult result = delegate.startExact(command);
        rememberDefinition(command.tenantId(), result.engineInstanceId(), command.definitionKey());
        return result;
    }

    @Override
    public List<TaskSnapshot> findActiveTasks(TaskQuery query) {
        List<TaskSnapshot> active = delegate.findActiveTasks(query);
        if (active.isEmpty()) {
            startedDefinitions.remove(instanceKey(query.tenantId(), query.processInstanceId()));
            return List.of();
        }
        String definitionKey = definitionKey(query).orElse(null);
        if (definitionKey == null) {
            return List.copyOf(active);
        }
        Instant now = clock.instant();
        List<TaskSnapshot> resolved = new ArrayList<>(active.size());
        for (TaskSnapshot task : active) {
            resolved.add(resolveTask(query.tenantId(), definitionKey, task, now));
        }
        return List.copyOf(resolved);
    }

    @Override
    public TaskResult complete(CompleteTaskCommand command) {
        TaskResult result = delegate.complete(command);
        Instant now = clock.instant();
        assignments.markCompleted(
            command.tenantId(),
            command.taskId(),
            command.operatorId(),
            now
        );
        if (handovers != null) {
            handovers.markCompleted(
                command.tenantId(),
                command.taskId(),
                command.operatorId(),
                now
            );
        }
        return result;
    }

    @Override
    public void terminate(TerminateInstanceCommand command) {
        delegate.terminate(command);
        Instant now = clock.instant();
        assignments.cancelActiveByEngineInstance(
            command.tenantId(),
            command.processInstanceId(),
            now
        );
        if (handovers != null) {
            handovers.cancelActiveByEngineInstance(
                command.tenantId(),
                command.processInstanceId(),
                now
            );
        }
        startedDefinitions.remove(instanceKey(command.tenantId(), command.processInstanceId()));
    }

    @Override
    public TaskSnapshot transfer(TransferTaskCommand command) {
        TaskSnapshot transferred = delegate.transfer(command);
        Instant now = clock.instant();
        assignments.markSuperseded(
            command.tenantId(),
            command.taskId(),
            command.targetAssigneeId(),
            now
        );
        if (handovers != null) {
            handovers.markSuperseded(
                command.tenantId(),
                command.taskId(),
                command.targetAssigneeId(),
                now
            );
        }
        return transferred;
    }

    @Override
    public void retrieve(RetrieveTaskCommand command) {
        delegate.retrieve(command);
        Instant now = clock.instant();
        assignments.markCanceled(
            command.tenantId(),
            command.currentTaskId(),
            now
        );
        if (handovers != null) {
            handovers.markCanceled(
                command.tenantId(),
                command.currentTaskId(),
                now
            );
        }
    }

    private TaskSnapshot resolveTask(
        String tenantId,
        String definitionKey,
        TaskSnapshot task,
        Instant effectiveAt
    ) {
        String principalId = normalizeAssignee(task.assigneeId());
        if (principalId == null) {
            return task;
        }
        TaskSnapshot handoverResolved = resolveHandover(
            tenantId,
            definitionKey,
            task,
            principalId,
            effectiveAt
        );
        if (!Objects.equals(handoverResolved.assigneeId(), task.assigneeId())) {
            return handoverResolved;
        }
        if (PurchasePaymentTemplate.REVISION_TASK_KEY.equals(task.taskDefinitionKey())) {
            return task;
        }
        return resolveDelegation(tenantId, definitionKey, task, principalId, effectiveAt);
    }

    private TaskSnapshot resolveHandover(
        String tenantId,
        String definitionKey,
        TaskSnapshot task,
        String principalId,
        Instant effectiveAt
    ) {
        if (handovers == null) {
            return task;
        }
        handovers.lockEngineTask(tenantId, task.taskId());
        Optional<HandoverTaskAssignment> existing = handovers.findAssignmentByEngineTask(
            tenantId,
            task.taskId()
        );
        if (existing.isPresent()) {
            return reconcileExistingHandover(tenantId, task, existing.get());
        }
        Optional<PrincipalHandover> handover = handovers.findActiveByPrincipal(
            tenantId,
            principalId
        );
        if (handover.isEmpty()) {
            return task;
        }
        PrincipalHandover active = handover.get();
        TaskSnapshot transferred = delegate.transfer(new TransferTaskCommand(
            tenantId,
            task.taskId(),
            principalId,
            active.successorId()
        ));
        HandoverTaskAssignment assignment = handovers.createAssignment(
            new HandoverTaskAssignment(
                identifierGenerator.get(),
                tenantId,
                task.taskId(),
                task.processInstanceId(),
                definitionKey,
                task.taskDefinitionKey(),
                principalId,
                active.successorId(),
                active.handoverId(),
                ApprovalHandoverStore.AssignmentStatus.ACTIVE,
                effectiveAt,
                null,
                null,
                null,
                null,
                null,
                1
            )
        );
        appendHandoverAudit(assignment, effectiveAt);
        return transferred;
    }

    private TaskSnapshot reconcileExistingHandover(
        String tenantId,
        TaskSnapshot task,
        HandoverTaskAssignment existing
    ) {
        if (existing.status() != ApprovalHandoverStore.AssignmentStatus.ACTIVE) {
            return task;
        }
        if (existing.successorAssigneeId().equals(task.assigneeId())) {
            return task;
        }
        if (existing.principalAssigneeId().equals(task.assigneeId())) {
            return delegate.transfer(new TransferTaskCommand(
                tenantId,
                task.taskId(),
                existing.principalAssigneeId(),
                existing.successorAssigneeId()
            ));
        }
        handovers.markSuperseded(
            tenantId,
            task.taskId(),
            requireAssignee(task.assigneeId()),
            clock.instant()
        );
        return task;
    }

    private TaskSnapshot resolveDelegation(
        String tenantId,
        String definitionKey,
        TaskSnapshot task,
        String principalId,
        Instant effectiveAt
    ) {
        assignments.lockEngineTask(tenantId, task.taskId());
        Optional<DelegatedTaskAssignment> existing = assignments.findByEngineTask(
            tenantId,
            task.taskId()
        );
        if (existing.isPresent()) {
            return reconcileExistingDelegation(tenantId, task, existing.get());
        }
        Optional<DelegationRule> rule = delegations.resolveEffective(
            tenantId,
            principalId,
            definitionKey,
            effectiveAt
        );
        if (rule.isEmpty()) {
            return task;
        }
        DelegationRule effectiveRule = rule.get();
        TaskSnapshot transferred = delegate.transfer(new TransferTaskCommand(
            tenantId,
            task.taskId(),
            principalId,
            effectiveRule.delegateId()
        ));
        DelegatedTaskAssignment assignment = assignments.create(new DelegatedTaskAssignment(
            identifierGenerator.get(),
            tenantId,
            task.taskId(),
            task.processInstanceId(),
            definitionKey,
            task.taskDefinitionKey(),
            principalId,
            effectiveRule.delegateId(),
            effectiveRule.ruleId(),
            effectiveRule.scope(),
            AssignmentStatus.ACTIVE,
            effectiveAt,
            null,
            null,
            null,
            null,
            null,
            1
        ));
        appendDelegationAudit(assignment, effectiveAt);
        return transferred;
    }

    private TaskSnapshot reconcileExistingDelegation(
        String tenantId,
        TaskSnapshot task,
        DelegatedTaskAssignment existing
    ) {
        if (existing.status() != AssignmentStatus.ACTIVE) {
            return task;
        }
        if (existing.delegateAssigneeId().equals(task.assigneeId())) {
            return task;
        }
        if (existing.principalAssigneeId().equals(task.assigneeId())) {
            return delegate.transfer(new TransferTaskCommand(
                tenantId,
                task.taskId(),
                existing.principalAssigneeId(),
                existing.delegateAssigneeId()
            ));
        }
        assignments.markSuperseded(
            tenantId,
            task.taskId(),
            requireAssignee(task.assigneeId()),
            clock.instant()
        );
        return task;
    }

    private Optional<String> definitionKey(TaskQuery query) {
        String remembered = startedDefinitions.get(instanceKey(
            query.tenantId(),
            query.processInstanceId()
        ));
        if (remembered != null) {
            return Optional.of(remembered);
        }
        return assignments.findDefinitionKeyByEngineInstance(
            query.tenantId(),
            query.processInstanceId()
        );
    }

    private void appendDelegationAudit(
        DelegatedTaskAssignment assignment,
        Instant occurredAt
    ) {
        auditEvents.append(new AuditEvent(
            identifierGenerator.get(),
            assignment.tenantId(),
            assignment.principalAssigneeId(),
            "TASK_DELEGATED",
            "APPROVAL_TASK",
            assignment.engineTaskId(),
            "delegation-assignment-" + assignment.assignmentId(),
            null,
            occurredAt,
            Map.of(
                "definitionKey", assignment.definitionKey(),
                "taskDefinitionKey", assignment.taskDefinitionKey(),
                "principalAssigneeId", assignment.principalAssigneeId(),
                "delegateAssigneeId", assignment.delegateAssigneeId(),
                "delegationRuleId", assignment.delegationRuleId().toString(),
                "delegationScope", assignment.delegationScope().name()
            )
        ));
    }

    private void appendHandoverAudit(
        HandoverTaskAssignment assignment,
        Instant occurredAt
    ) {
        auditEvents.append(new AuditEvent(
            identifierGenerator.get(),
            assignment.tenantId(),
            assignment.principalAssigneeId(),
            "TASK_HANDOVER_ASSIGNED",
            "APPROVAL_TASK",
            assignment.engineTaskId(),
            "handover-assignment-" + assignment.assignmentId(),
            null,
            occurredAt,
            Map.of(
                "definitionKey", assignment.definitionKey(),
                "taskDefinitionKey", assignment.taskDefinitionKey(),
                "principalAssigneeId", assignment.principalAssigneeId(),
                "successorAssigneeId", assignment.successorAssigneeId(),
                "handoverId", assignment.handoverId().toString()
            )
        ));
    }

    private void rememberDefinition(
        String tenantId,
        String engineInstanceId,
        String definitionKey
    ) {
        startedDefinitions.put(
            instanceKey(tenantId, engineInstanceId),
            requireText(definitionKey, "definitionKey")
        );
    }

    private static String instanceKey(String tenantId, String engineInstanceId) {
        return requireText(tenantId, "tenantId")
            + '\u001f'
            + requireText(engineInstanceId, "engineInstanceId");
    }

    private static String normalizeAssignee(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireAssignee(String value) {
        String normalized = normalizeAssignee(value);
        if (normalized == null) {
            throw new IllegalStateException("active task assignee must not be blank");
        }
        return normalized;
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
