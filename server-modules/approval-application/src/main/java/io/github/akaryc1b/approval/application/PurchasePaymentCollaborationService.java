package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Idempotent collaboration actions that do not bypass the platform projection boundary.
 */
public final class PurchasePaymentCollaborationService {

    private static final String WITHDRAW_OPERATION = "purchase-payment.withdraw.v1";
    private static final String TRANSFER_OPERATION = "purchase-payment.transfer.v1";
    private static final String RETRIEVE_OPERATION = "purchase-payment.retrieve.v1";

    private final ApprovalEngine engine;
    private final IdempotencyGuard idempotencyGuard;
    private final ApprovalProjectionStore projections;
    private final AuditEventSink auditEvents;
    private final Clock clock;
    private final Supplier<UUID> identifierGenerator;

    public PurchasePaymentCollaborationService(
        ApprovalEngine engine,
        IdempotencyGuard idempotencyGuard,
        ApprovalProjectionStore projections,
        AuditEventSink auditEvents,
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
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.identifierGenerator = Objects.requireNonNull(
            identifierGenerator,
            "identifierGenerator must not be null"
        );
    }

    public WithdrawResult withdraw(WithdrawCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String comment = normalizeOptional(command.comment());
        String requestHash = hashValues(
            command.instanceId().toString(),
            "WITHDRAWN",
            comment
        );
        return idempotencyGuard.execute(
            command.context(),
            WITHDRAW_OPERATION,
            requestHash,
            WithdrawResult.class,
            () -> executeWithdraw(command, comment)
        );
    }

    public TransferResult transfer(TransferCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String targetUserId = requireText(command.targetUserId(), "targetUserId");
        String comment = requireText(command.comment(), "transfer comment");
        String requestHash = hashValues(
            command.taskId().toString(),
            targetUserId,
            comment
        );
        return idempotencyGuard.execute(
            command.context(),
            TRANSFER_OPERATION,
            requestHash,
            TransferResult.class,
            () -> executeTransfer(command, targetUserId, comment)
        );
    }

    public RetrieveResult retrieve(RetrieveCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String comment = normalizeOptional(command.comment());
        String requestHash = hashValues(
            command.completedTaskId().toString(),
            "RETRIEVED",
            comment
        );
        return idempotencyGuard.execute(
            command.context(),
            RETRIEVE_OPERATION,
            requestHash,
            RetrieveResult.class,
            () -> executeRetrieve(command, comment)
        );
    }

    private WithdrawResult executeWithdraw(WithdrawCommand command, String comment) {
        InstanceProjection instance = requireInstance(
            command.context().tenantId(),
            command.instanceId()
        );
        if (!instance.initiatorId().equals(command.context().operatorId())) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "only the process initiator can withdraw the request"
            );
        }
        if (instance.status() != InstanceStatus.RUNNING) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "only a running request can be withdrawn"
            );
        }
        boolean waitingForRevision = projections.findTasks(
            command.context().tenantId(),
            instance.instanceId()
        ).stream().anyMatch(task ->
            PurchasePaymentTemplate.REVISION_TASK_KEY.equals(task.taskDefinitionKey())
                && (task.status() == TaskStatus.PENDING || task.status() == TaskStatus.COMPLETING)
        );
        if (waitingForRevision) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "a rejected request waiting for revision cannot be withdrawn"
            );
        }

        Instant now = clock.instant();
        projections.withdrawRunningInstance(
            command.context().tenantId(),
            instance.instanceId(),
            command.context().operatorId(),
            now
        );
        engine.terminate(new ApprovalEngine.TerminateInstanceCommand(
            command.context().tenantId(),
            instance.engineInstanceId(),
            comment == null ? "withdrawn by initiator" : comment
        ));

        Map<String, String> attributes = new LinkedHashMap<>(versionAttributes(instance));
        if (comment != null) {
            attributes.put("comment", comment);
        }
        appendAudit(
            command.context(),
            "INSTANCE_WITHDRAWN",
            "APPROVAL_INSTANCE",
            instance.instanceId().toString(),
            Map.copyOf(attributes),
            now
        );
        return new WithdrawResult(instance.instanceId(), InstanceStatus.WITHDRAWN, now);
    }

    private TransferResult executeTransfer(
        TransferCommand command,
        String targetUserId,
        String comment
    ) {
        TaskProjection task = requireTask(command.context().tenantId(), command.taskId());
        if (task.status() != TaskStatus.PENDING
            || !task.assigneeId().equals(command.context().operatorId())) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "task is not pending or is assigned to another operator"
            );
        }
        if (PurchasePaymentTemplate.REVISION_TASK_KEY.equals(task.taskDefinitionKey())) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "an initiator revision task cannot be transferred"
            );
        }
        if (task.assigneeId().equals(targetUserId)) {
            throw new IllegalArgumentException("targetUserId must differ from the current assignee");
        }
        InstanceProjection instance = requireInstance(
            command.context().tenantId(),
            task.instanceId()
        );
        requireRunning(instance);
        validateTransferTarget(instance, targetUserId);

        Instant now = clock.instant();
        TaskProjection transferred = projections.transferPendingTask(
            command.context().tenantId(),
            task.taskId(),
            command.context().operatorId(),
            targetUserId,
            now
        );
        ApprovalEngine.TaskSnapshot engineTask = engine.transfer(
            new ApprovalEngine.TransferTaskCommand(
                command.context().tenantId(),
                task.engineTaskId(),
                command.context().operatorId(),
                targetUserId
            )
        );
        if (!targetUserId.equals(engineTask.assigneeId())) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "engine task transfer did not preserve the target assignee"
            );
        }

        Map<String, String> attributes = new LinkedHashMap<>(versionAttributes(instance));
        attributes.put("taskDefinitionKey", task.taskDefinitionKey());
        attributes.put("previousAssigneeId", task.assigneeId());
        attributes.put("targetAssigneeId", targetUserId);
        attributes.put("comment", comment);
        appendAudit(
            command.context(),
            "TASK_TRANSFERRED",
            "APPROVAL_TASK",
            task.taskId().toString(),
            Map.copyOf(attributes),
            now
        );
        return new TransferResult(
            transferred.taskId(),
            transferred.instanceId(),
            task.assigneeId(),
            targetUserId,
            now
        );
    }

    private RetrieveResult executeRetrieve(RetrieveCommand command, String comment) {
        TaskProjection completedTask = requireTask(
            command.context().tenantId(),
            command.completedTaskId()
        );
        if (completedTask.status() != TaskStatus.COMPLETED
            || completedTask.completedAt() == null
            || !completedTask.assigneeId().equals(command.context().operatorId())) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "only the original assignee can retrieve a completed task"
            );
        }
        if (PurchasePaymentTemplate.REVISION_TASK_KEY.equals(completedTask.taskDefinitionKey())) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "an initiator revision task cannot be retrieved"
            );
        }
        InstanceProjection instance = requireInstance(
            command.context().tenantId(),
            completedTask.instanceId()
        );
        requireRunning(instance);

        List<TaskProjection> tasks = projections.findTasks(
            command.context().tenantId(),
            instance.instanceId()
        );
        TaskProjection latestCompleted = tasks.stream()
            .filter(task -> task.status() == TaskStatus.COMPLETED && task.completedAt() != null)
            .max(Comparator
                .comparing(TaskProjection::completedAt)
                .thenComparing(task -> task.taskId().toString()))
            .orElseThrow(() -> new ApprovalProjectionStore.ProjectionConflictException(
                "the completed task history is missing"
            ));
        if (!latestCompleted.taskId().equals(completedTask.taskId())) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "only the most recently completed task can be retrieved"
            );
        }
        if (tasks.stream().anyMatch(task -> task.status() == TaskStatus.COMPLETING)) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "the downstream task is already being processed"
            );
        }
        List<TaskProjection> pending = tasks.stream()
            .filter(task -> task.status() == TaskStatus.PENDING)
            .toList();
        if (pending.size() != 1) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "retrieve requires exactly one unprocessed downstream task"
            );
        }
        TaskProjection downstream = pending.getFirst();
        if (PurchasePaymentTemplate.REVISION_TASK_KEY.equals(downstream.taskDefinitionKey())) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "a rejected task cannot be retrieved from initiator revision"
            );
        }
        if (completedTask.taskDefinitionKey().equals(downstream.taskDefinitionKey())) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "multi-instance and same-activity retrieval are not supported"
            );
        }
        if (downstream.createdAt().isBefore(completedTask.completedAt())) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "the downstream task does not follow the completed task"
            );
        }

        Instant now = clock.instant();
        TaskProjection claimedDownstream = projections.claimPendingTaskForControl(
            command.context().tenantId(),
            downstream.taskId(),
            now
        );
        engine.retrieve(new ApprovalEngine.RetrieveTaskCommand(
            command.context().tenantId(),
            instance.engineInstanceId(),
            claimedDownstream.engineTaskId(),
            completedTask.taskDefinitionKey()
        ));

        List<ApprovalEngine.TaskSnapshot> engineTasks = engine.findActiveTasks(
            new ApprovalEngine.TaskQuery(
                command.context().tenantId(),
                instance.engineInstanceId(),
                null
            )
        );
        Map<String, TaskProjection> previousByEngineId = tasks.stream().collect(
            Collectors.toMap(TaskProjection::engineTaskId, task -> task, (left, right) -> left)
        );
        List<TaskProjection> activeTasks = newTaskProjections(
            instance.instanceId(),
            command.context().tenantId(),
            engineTasks,
            now,
            previousByEngineId
        );
        if (activeTasks.size() != 1
            || !completedTask.taskDefinitionKey().equals(activeTasks.getFirst().taskDefinitionKey())
            || !command.context().operatorId().equals(activeTasks.getFirst().assigneeId())) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "engine did not restore the expected task to the original assignee"
            );
        }
        projections.cancelClaimedTaskAndSynchronize(
            command.context().tenantId(),
            instance.instanceId(),
            claimedDownstream.taskId(),
            claimedDownstream.version(),
            activeTasks,
            now
        );

        Map<String, String> attributes = new LinkedHashMap<>(versionAttributes(instance));
        attributes.put("fromTaskDefinitionKey", downstream.taskDefinitionKey());
        attributes.put("toTaskDefinitionKey", completedTask.taskDefinitionKey());
        if (comment != null) {
            attributes.put("comment", comment);
        }
        appendAudit(
            command.context(),
            "TASK_RETRIEVED",
            "APPROVAL_TASK",
            completedTask.taskId().toString(),
            Map.copyOf(attributes),
            now
        );
        return new RetrieveResult(
            completedTask.taskId(),
            instance.instanceId(),
            List.copyOf(activeTasks),
            now
        );
    }

    private static void validateTransferTarget(
        InstanceProjection instance,
        String targetUserId
    ) {
        Set<String> knownUsers = new HashSet<>();
        knownUsers.add(instance.assigneeSnapshot().managerAssignee());
        knownUsers.add(instance.assigneeSnapshot().financeReviewer());
        knownUsers.addAll(instance.assigneeSnapshot().financeApprovers());
        knownUsers.addAll(instance.assigneeSnapshot().identities().keySet());
        instance.assigneeSnapshot().identities().values().forEach(identity -> {
            if (identity.externalId() != null && !identity.externalId().isBlank()) {
                knownUsers.add(identity.externalId());
            }
        });
        knownUsers.removeIf(value -> value == null || value.isBlank());
        if (!knownUsers.contains(targetUserId)) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "transfer target is not present in the immutable assignee snapshot"
            );
        }
    }

    private InstanceProjection requireInstance(String tenantId, UUID instanceId) {
        return projections.findInstance(tenantId, instanceId)
            .orElseThrow(() -> new ApprovalProjectionStore.ProjectionConflictException(
                "approval instance projection is missing"
            ));
    }

    private TaskProjection requireTask(String tenantId, UUID taskId) {
        return projections.findTask(tenantId, taskId)
            .orElseThrow(() -> new ApprovalProjectionStore.ProjectionConflictException(
                "approval task projection is missing"
            ));
    }

    private static void requireRunning(InstanceProjection instance) {
        if (instance.status() != InstanceStatus.RUNNING) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "collaboration actions require a running approval instance"
            );
        }
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
        String aggregateType,
        String aggregateId,
        Map<String, String> attributes,
        Instant occurredAt
    ) {
        auditEvents.append(new AuditEvent(
            identifierGenerator.get(),
            context.tenantId(),
            context.operatorId(),
            action,
            aggregateType,
            aggregateId,
            context.requestId(),
            context.traceId(),
            occurredAt,
            attributes
        ));
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

    public record WithdrawCommand(RequestContext context, UUID instanceId, String comment) {
        public WithdrawCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
        }
    }

    public record WithdrawResult(
        UUID instanceId,
        InstanceStatus instanceStatus,
        Instant withdrawnAt
    ) {
    }

    public record TransferCommand(
        RequestContext context,
        UUID taskId,
        String targetUserId,
        String comment
    ) {
        public TransferCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            taskId = Objects.requireNonNull(taskId, "taskId must not be null");
        }
    }

    public record TransferResult(
        UUID taskId,
        UUID instanceId,
        String previousAssigneeId,
        String targetAssigneeId,
        Instant transferredAt
    ) {
    }

    public record RetrieveCommand(
        RequestContext context,
        UUID completedTaskId,
        String comment
    ) {
        public RetrieveCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            completedTaskId = Objects.requireNonNull(
                completedTaskId,
                "completedTaskId must not be null"
            );
        }
    }

    public record RetrieveResult(
        UUID completedTaskId,
        UUID instanceId,
        List<TaskProjection> activeTasks,
        Instant retrievedAt
    ) {
        public RetrieveResult {
            activeTasks = activeTasks == null ? List.of() : List.copyOf(activeTasks);
        }
    }
}
