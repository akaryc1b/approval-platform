package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalFormRuntimeService.RevisionPlan;
import io.github.akaryc1b.approval.application.port.ApprovalBusinessEventOutbox;
import io.github.akaryc1b.approval.application.port.ApprovalHandoverStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import io.github.akaryc1b.approval.engine.ApprovalEngine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Idempotent task transitions for the purchase-payment vertical slice.
 */
public final class PurchasePaymentTaskActionService {

    private static final String APPROVE_OPERATION = "purchase-payment.approve.v1";
    private static final String REJECT_OPERATION = "purchase-payment.reject.v1";
    private static final String RESUBMIT_OPERATION = "purchase-payment.resubmit.v1";
    private static final FormSubmissionHasher ACTION_VALUES_HASHER = new FormSubmissionHasher();

    private final ApprovalEngine engine;
    private final IdempotencyGuard idempotencyGuard;
    private final ApprovalProjectionStore projections;
    private final AuditEventSink auditEvents;
    private final ApprovalBusinessEventOutbox businessEventOutbox;
    private final ApprovalFormRuntimeService formRuntimeService;
    private final ApprovalHandoverStore handovers;
    private final Clock clock;
    private final Supplier<UUID> identifierGenerator;

    public PurchasePaymentTaskActionService(
        ApprovalEngine engine,
        IdempotencyGuard idempotencyGuard,
        ApprovalProjectionStore projections,
        AuditEventSink auditEvents,
        ApprovalBusinessEventOutbox businessEventOutbox,
        Clock clock,
        Supplier<UUID> identifierGenerator
    ) {
        this(
            engine,
            idempotencyGuard,
            projections,
            auditEvents,
            businessEventOutbox,
            null,
            null,
            clock,
            identifierGenerator
        );
    }

    public PurchasePaymentTaskActionService(
        ApprovalEngine engine,
        IdempotencyGuard idempotencyGuard,
        ApprovalProjectionStore projections,
        AuditEventSink auditEvents,
        ApprovalBusinessEventOutbox businessEventOutbox,
        ApprovalFormRuntimeService formRuntimeService,
        Clock clock,
        Supplier<UUID> identifierGenerator
    ) {
        this(
            engine,
            idempotencyGuard,
            projections,
            auditEvents,
            businessEventOutbox,
            formRuntimeService,
            null,
            clock,
            identifierGenerator
        );
    }

    public PurchasePaymentTaskActionService(
        ApprovalEngine engine,
        IdempotencyGuard idempotencyGuard,
        ApprovalProjectionStore projections,
        AuditEventSink auditEvents,
        ApprovalBusinessEventOutbox businessEventOutbox,
        ApprovalFormRuntimeService formRuntimeService,
        ApprovalHandoverStore handovers,
        Clock clock,
        Supplier<UUID> identifierGenerator
    ) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.idempotencyGuard = Objects.requireNonNull(
            idempotencyGuard,
            "idempotencyGuard must not be null"
        );
        this.projections = Objects.requireNonNull(projections, "projections must not be null");
        this.auditEvents = Objects.requireNonNull(auditEvents, "auditEvents must not be null");
        this.businessEventOutbox = Objects.requireNonNull(
            businessEventOutbox,
            "businessEventOutbox must not be null"
        );
        this.formRuntimeService = formRuntimeService;
        this.handovers = handovers;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.identifierGenerator = Objects.requireNonNull(
            identifierGenerator,
            "identifierGenerator must not be null"
        );
    }

    public PurchasePaymentApplicationService.ApproveResult approve(TaskActionCommand command) {
        return transition(
            command,
            APPROVE_OPERATION,
            Decision.APPROVED,
            false,
            "TASK_APPROVED"
        );
    }

    public PurchasePaymentApplicationService.ApproveResult reject(TaskActionCommand command) {
        requireText(command.comment(), "reject comment");
        return transition(
            command,
            REJECT_OPERATION,
            Decision.REJECTED,
            false,
            "TASK_REJECTED"
        );
    }

    public PurchasePaymentApplicationService.ApproveResult resubmit(TaskActionCommand command) {
        return transition(
            command,
            RESUBMIT_OPERATION,
            Decision.RESUBMITTED,
            true,
            "TASK_RESUBMITTED"
        );
    }

    private PurchasePaymentApplicationService.ApproveResult transition(
        TaskActionCommand command,
        String operation,
        Decision decision,
        boolean revisionRequired,
        String auditAction
    ) {
        Objects.requireNonNull(command, "command must not be null");
        String comment = normalizeOptional(command.comment());
        String valuesHash = ACTION_VALUES_HASHER.hash(
            "task-action",
            1,
            command.taskId().toString(),
            decision.name(),
            command.values(),
            Map.of()
        );
        String requestHash = hashValues(
            command.taskId().toString(),
            decision.name(),
            comment,
            valuesHash
        );
        return idempotencyGuard.execute(
            command.context(),
            operation,
            requestHash,
            PurchasePaymentApplicationService.ApproveResult.class,
            () -> executeTransition(
                command,
                decision,
                revisionRequired,
                auditAction,
                comment,
                requestHash
            )
        );
    }

    private PurchasePaymentApplicationService.ApproveResult executeTransition(
        TaskActionCommand command,
        Decision decision,
        boolean revisionRequired,
        String auditAction,
        String comment,
        String requestHash
    ) {
        RevisionPlan revisionPlan = revisionRequired && formRuntimeService != null
            ? formRuntimeService.planRevision(
                command.context().tenantId(),
                command.context().operatorId(),
                command.taskId(),
                command.values()
            )
            : null;
        Instant now = clock.instant();
        TaskProjection claimed = projections.claimPendingTask(
            command.context().tenantId(),
            command.taskId(),
            command.context().operatorId(),
            now
        );
        InstanceProjection instance = projections.findInstance(
            command.context().tenantId(),
            claimed.instanceId()
        ).orElseThrow(() -> new ApprovalProjectionStore.ProjectionConflictException(
            "task instance projection is missing"
        ));
        validateAction(command.context(), instance, claimed, decision, revisionRequired);
        if (revisionPlan != null) {
            formRuntimeService.saveRevision(revisionPlan, command.context(), requestHash, now);
        }

        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(ApprovalDslCompiler.DECISION_VARIABLE, decision.name());
        variables.put("decision", decision.name());
        variables.put("comment", comment == null ? "" : comment);
        variables.put("operatorId", command.context().operatorId());
        variables.put("requestId", command.context().requestId());
        engine.complete(new ApprovalEngine.CompleteTaskCommand(
            command.context().tenantId(),
            claimed.engineTaskId(),
            command.context().operatorId(),
            Map.copyOf(variables)
        ));

        List<ApprovalEngine.TaskSnapshot> engineTasks = engine.findActiveTasks(
            new ApprovalEngine.TaskQuery(
                command.context().tenantId(),
                instance.engineInstanceId(),
                null
            )
        );
        Map<String, TaskProjection> previousByEngineId = projections.findTasks(
            command.context().tenantId(),
            instance.instanceId()
        ).stream().collect(Collectors.toMap(
            TaskProjection::engineTaskId,
            task -> task,
            (left, right) -> left
        ));
        List<TaskProjection> activeTasks = newTaskProjections(
            instance.instanceId(),
            command.context().tenantId(),
            engineTasks,
            now,
            previousByEngineId
        );
        InstanceStatus status = activeTasks.isEmpty()
            ? InstanceStatus.COMPLETED
            : InstanceStatus.RUNNING;
        projections.completeTaskAndSynchronize(
            command.context().tenantId(),
            instance.instanceId(),
            claimed.taskId(),
            claimed.version(),
            activeTasks,
            status,
            now
        );

        Map<String, String> attributes = new LinkedHashMap<>(versionAttributes(instance));
        attributes.put("taskDefinitionKey", claimed.taskDefinitionKey());
        attributes.put("decision", decision.name());
        if (comment != null) {
            attributes.put("comment", comment);
        }
        if (revisionPlan != null) {
            attributes.put("formRevision", Integer.toString(revisionPlan.revisionNumber()));
            attributes.put("formDataHash", revisionPlan.contentHash());
        }
        appendAudit(
            command.context(),
            auditAction,
            claimed.taskId().toString(),
            Map.copyOf(attributes),
            now
        );
        if (status == InstanceStatus.COMPLETED) {
            enqueueCompletion(command.context(), instance, now);
        }
        return new PurchasePaymentApplicationService.ApproveResult(
            claimed.taskId(),
            instance.instanceId(),
            status,
            List.copyOf(activeTasks),
            now
        );
    }

    private void validateAction(
        RequestContext context,
        InstanceProjection instance,
        TaskProjection task,
        Decision decision,
        boolean revisionRequired
    ) {
        boolean revisionTask = PurchasePaymentTemplate.REVISION_TASK_KEY.equals(
            task.taskDefinitionKey()
        );
        if (revisionRequired && !revisionTask) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "only an initiator revision task can be resubmitted"
            );
        }
        if (!revisionRequired && revisionTask) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "an initiator revision task must be resubmitted"
            );
        }
        if (decision == Decision.REJECTED
            && instance.definitionVersion() < PurchasePaymentTemplate.PROCESS_VERSION) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "the running definition does not support rejection"
            );
        }
        if (revisionRequired && !canResubmit(context, instance, task)) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "only the process initiator or assigned handover successor can resubmit"
            );
        }
    }

    private boolean canResubmit(
        RequestContext context,
        InstanceProjection instance,
        TaskProjection task
    ) {
        if (instance.initiatorId().equals(context.operatorId())) {
            return true;
        }
        if (handovers == null) {
            return false;
        }
        return handovers.findAssignmentByEngineTask(
            context.tenantId(),
            task.engineTaskId()
        ).filter(assignment ->
            assignment.status() == ApprovalHandoverStore.AssignmentStatus.ACTIVE
                && instance.initiatorId().equals(assignment.principalAssigneeId())
                && context.operatorId().equals(assignment.successorAssigneeId())
        ).isPresent();
    }

    private List<TaskProjection> newTaskProjections(
        UUID instanceId,
        String tenantId,
        List<ApprovalEngine.TaskSnapshot> engineTasks,
        Instant now,
        Map<String, TaskProjection> existingByEngineId
    ) {
        List<TaskProjection> result = new ArrayList<>();
        for (ApprovalEngine.TaskSnapshot engineTask : engineTasks) {
            TaskProjection existing = existingByEngineId.get(engineTask.taskId());
            result.add(new TaskProjection(
                existing == null ? identifierGenerator.get() : existing.taskId(),
                instanceId,
                tenantId,
                engineTask.taskId(),
                engineTask.taskDefinitionKey(),
                engineTask.name(),
                requireText(engineTask.assigneeId(), "engine task assignee"),
                TaskStatus.PENDING,
                existing == null ? 1 : existing.version(),
                engineTask.createdAt(),
                now,
                null
            ));
        }
        return List.copyOf(result);
    }

    private void appendAudit(
        RequestContext context,
        String action,
        String aggregateId,
        Map<String, String> attributes,
        Instant occurredAt
    ) {
        auditEvents.append(new AuditEvent(
            identifierGenerator.get(),
            context.tenantId(),
            context.operatorId(),
            action,
            "APPROVAL_TASK",
            aggregateId,
            context.requestId(),
            context.traceId(),
            occurredAt,
            attributes
        ));
    }

    private void enqueueCompletion(
        RequestContext context,
        InstanceProjection instance,
        Instant occurredAt
    ) {
        String connectorKey = instance.assigneeSnapshot().attributes().get("connectorKey");
        if (connectorKey != null && !connectorKey.isBlank()) {
            businessEventOutbox.enqueueCompleted(context, connectorKey, instance, occurredAt);
        }
    }

    private static Map<String, String> versionAttributes(InstanceProjection instance) {
        return Map.of(
            "definitionKey", instance.definitionKey(),
            "definitionVersion", Integer.toString(instance.definitionVersion()),
            "formVersion", Integer.toString(instance.formVersion()),
            "compilerVersion", instance.compilerVersion(),
            "contentHash", instance.contentHash()
        );
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
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private enum Decision {
        APPROVED,
        REJECTED,
        RESUBMITTED
    }

    public record TaskActionCommand(
        RequestContext context,
        UUID taskId,
        String comment,
        Map<String, Object> values
    ) {
        public TaskActionCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            taskId = Objects.requireNonNull(taskId, "taskId must not be null");
            values = values == null ? Map.of() : Map.copyOf(values);
        }

        public TaskActionCommand(RequestContext context, UUID taskId, String comment) {
            this(context, taskId, comment, Map.of());
        }
    }
}
