package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import io.github.akaryc1b.approval.engine.ApprovalEngine;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * First production-shaped approval use case with durable idempotency, audit and projections.
 */
public final class PurchasePaymentApplicationService {

    private static final String PUBLISH_OPERATION = "purchase-payment.publish.v1";
    private static final String START_OPERATION = "purchase-payment.start.v1";
    private static final String APPROVE_OPERATION = "purchase-payment.approve.v1";

    private final ApprovalEngine engine;
    private final ApprovalDslCompiler compiler;
    private final IdempotencyGuard idempotencyGuard;
    private final ApprovalProjectionStore projections;
    private final AuditEventSink auditEvents;
    private final Clock clock;
    private final Supplier<UUID> identifierGenerator;

    public PurchasePaymentApplicationService(
        ApprovalEngine engine,
        ApprovalDslCompiler compiler,
        IdempotencyGuard idempotencyGuard,
        ApprovalProjectionStore projections,
        AuditEventSink auditEvents,
        Clock clock,
        Supplier<UUID> identifierGenerator
    ) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.compiler = Objects.requireNonNull(compiler, "compiler must not be null");
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

    public PublishResult publish(PublishCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        var compiled = compiler.compile(
            PurchasePaymentTemplate.processDefinition(),
            PurchasePaymentTemplate.formDefinition()
        );
        String requestHash = hashValues(
            compiled.definitionKey(),
            Integer.toString(compiled.definitionVersion()),
            Integer.toString(compiled.formVersion()),
            compiled.compilerVersion(),
            compiled.contentHash()
        );
        return idempotencyGuard.execute(
            command.context(),
            PUBLISH_OPERATION,
            requestHash,
            PublishResult.class,
            () -> {
                projections.lockDefinition(
                    command.context().tenantId(),
                    compiled.definitionKey(),
                    compiled.definitionVersion()
                );
                Optional<PublishedDefinition> existing = projections.findDefinition(
                    command.context().tenantId(),
                    compiled.definitionKey(),
                    compiled.definitionVersion()
                );
                if (existing.isPresent()) {
                    if (!compiled.contentHash().equals(existing.get().contentHash())) {
                        throw new ApprovalProjectionStore.ProjectionConflictException(
                            "published definition version has a different content hash"
                        );
                    }
                    return publishResult(existing.get());
                }

                ApprovalEngine.DeploymentResult deployed = engine.deploy(
                    new ApprovalEngine.DeployCommand(
                        command.context().tenantId(),
                        compiled.definitionKey(),
                        compiled.definitionVersion(),
                        compiled.resourceName(),
                        compiled.bpmnXml(),
                        compiled.contentHash()
                    )
                );
                Instant now = clock.instant();
                PublishedDefinition definition = new PublishedDefinition(
                    command.context().tenantId(),
                    compiled.definitionKey(),
                    compiled.definitionVersion(),
                    compiled.formKey(),
                    compiled.formVersion(),
                    compiled.compilerVersion(),
                    compiled.contentHash(),
                    deployed.deploymentId(),
                    deployed.engineDefinitionId(),
                    deployed.engineVersion(),
                    command.context().operatorId(),
                    now
                );
                projections.saveDefinition(definition);
                appendAudit(
                    command.context(),
                    "DEFINITION_PUBLISHED",
                    "APPROVAL_DEFINITION",
                    compiled.definitionKey() + ":" + compiled.definitionVersion(),
                    Map.of(
                        "definitionVersion", Integer.toString(compiled.definitionVersion()),
                        "formVersion", Integer.toString(compiled.formVersion()),
                        "compilerVersion", compiled.compilerVersion(),
                        "contentHash", compiled.contentHash()
                    ),
                    now
                );
                return publishResult(definition);
            }
        );
    }

    public StartResult start(StartCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validateStart(command);
        String requestHash = startRequestHash(command);
        return idempotencyGuard.execute(
            command.context(),
            START_OPERATION,
            requestHash,
            StartResult.class,
            () -> {
                projections.lockBusinessKey(command.context().tenantId(), command.businessKey());
                Optional<InstanceProjection> existing = projections.findByBusinessKey(
                    command.context().tenantId(),
                    command.businessKey()
                );
                if (existing.isPresent()) {
                    if (!requestHash.equals(existing.get().requestHash())) {
                        throw new ApprovalProjectionStore.ProjectionConflictException(
                            "business key is already used by a different purchase payment request"
                        );
                    }
                    return startResult(existing.get(), projections.findTasks(
                        command.context().tenantId(),
                        existing.get().instanceId()
                    ));
                }

                PublishedDefinition definition = projections.findDefinition(
                    command.context().tenantId(),
                    PurchasePaymentTemplate.DEFINITION_KEY,
                    PurchasePaymentTemplate.PROCESS_VERSION
                ).orElseThrow(() -> new IllegalStateException(
                    "purchase payment definition version 1 has not been published"
                ));
                UUID instanceId = identifierGenerator.get();
                Instant now = clock.instant();
                Map<String, Object> variables = new LinkedHashMap<>();
                variables.put("amount", command.amount());
                variables.put("supplier", command.supplier());
                variables.put("purchaseOrderReference", command.purchaseOrderReference());
                variables.put("attachmentIds", command.attachmentIds());
                variables.put(
                    PurchasePaymentTemplate.MANAGER_ASSIGNEE_VARIABLE,
                    command.assignees().managerAssignee()
                );
                variables.put(
                    PurchasePaymentTemplate.FINANCE_REVIEWER_VARIABLE,
                    command.assignees().financeReviewer()
                );
                variables.put(
                    PurchasePaymentTemplate.FINANCE_APPROVERS_VARIABLE,
                    command.assignees().financeApprovers()
                );
                variables.put("approvalInstanceId", instanceId.toString());
                variables.put("definitionVersion", definition.definitionVersion());
                variables.put("formVersion", definition.formVersion());
                variables.put("compilerVersion", definition.compilerVersion());
                variables.put("contentHash", definition.contentHash());

                String engineInstanceId = engine.start(new ApprovalEngine.StartCommand(
                    command.context().tenantId(),
                    definition.definitionKey(),
                    command.businessKey(),
                    command.context().operatorId(),
                    Map.copyOf(variables)
                )).engineInstanceId();
                List<TaskProjection> tasks = newTaskProjections(
                    instanceId,
                    command.context().tenantId(),
                    engine.findActiveTasks(new ApprovalEngine.TaskQuery(
                        command.context().tenantId(),
                        engineInstanceId,
                        null
                    )),
                    now,
                    Map.of()
                );
                InstanceProjection instance = new InstanceProjection(
                    instanceId,
                    command.context().tenantId(),
                    command.businessKey(),
                    engineInstanceId,
                    definition.definitionKey(),
                    definition.definitionVersion(),
                    definition.formKey(),
                    definition.formVersion(),
                    definition.compilerVersion(),
                    definition.contentHash(),
                    command.context().operatorId(),
                    command.amount(),
                    command.supplier(),
                    command.purchaseOrderReference(),
                    command.attachmentIds(),
                    command.assignees(),
                    requestHash,
                    tasks.isEmpty() ? InstanceStatus.COMPLETED : InstanceStatus.RUNNING,
                    1,
                    now,
                    now
                );
                projections.createInstance(instance, tasks);
                appendAudit(
                    command.context(),
                    "INSTANCE_STARTED",
                    "APPROVAL_INSTANCE",
                    instanceId.toString(),
                    versionAttributes(instance),
                    now
                );
                return startResult(instance, tasks);
            }
        );
    }

    public ApproveResult approve(ApproveCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String comment = normalizeOptional(command.comment());
        String requestHash = hashValues(command.taskId().toString(), "APPROVED", comment);
        return idempotencyGuard.execute(
            command.context(),
            APPROVE_OPERATION,
            requestHash,
            ApproveResult.class,
            () -> {
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
                engine.complete(new ApprovalEngine.CompleteTaskCommand(
                    command.context().tenantId(),
                    claimed.engineTaskId(),
                    command.context().operatorId(),
                    Map.of(
                        "decision", "APPROVED",
                        "comment", comment == null ? "" : comment,
                        "operatorId", command.context().operatorId(),
                        "requestId", command.context().requestId()
                    )
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
                attributes.put("decision", "APPROVED");
                appendAudit(
                    command.context(),
                    "TASK_APPROVED",
                    "APPROVAL_TASK",
                    claimed.taskId().toString(),
                    Map.copyOf(attributes),
                    now
                );
                return new ApproveResult(
                    claimed.taskId(),
                    instance.instanceId(),
                    status,
                    List.copyOf(activeTasks),
                    now
                );
            }
        );
    }

    public Optional<InstanceDetails> findInstance(String tenantId, UUID instanceId) {
        return projections.findInstance(tenantId, instanceId)
            .map(instance -> new InstanceDetails(
                instance,
                projections.findTasks(tenantId, instanceId)
            ));
    }

    public List<TaskProjection> findTasks(String tenantId, UUID instanceId) {
        return projections.findTasks(tenantId, instanceId);
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

    private static PublishResult publishResult(PublishedDefinition definition) {
        return new PublishResult(
            definition.definitionKey(),
            definition.definitionVersion(),
            definition.formVersion(),
            definition.compilerVersion(),
            definition.contentHash(),
            definition.engineDefinitionId(),
            definition.publishedAt()
        );
    }

    private static StartResult startResult(
        InstanceProjection instance,
        List<TaskProjection> tasks
    ) {
        return new StartResult(instance.instanceId(), instance.status(), tasks, instance.createdAt());
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

    private static void validateStart(StartCommand command) {
        requireText(command.businessKey(), "businessKey");
        if (command.amount() == null || command.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
        requireText(command.supplier(), "supplier");
        requireText(command.purchaseOrderReference(), "purchaseOrderReference");
        if (command.attachmentIds() == null || command.attachmentIds().isEmpty()) {
            throw new IllegalArgumentException("at least one attachment is required");
        }
        command.attachmentIds().forEach(value -> requireText(value, "attachmentId"));
        Objects.requireNonNull(command.assignees(), "assignees must not be null");
        requireText(command.assignees().managerAssignee(), "managerAssignee");
        requireText(command.assignees().financeReviewer(), "financeReviewer");
        if (command.assignees().financeApprovers().isEmpty()) {
            throw new IllegalArgumentException("financeApprovers must not be empty");
        }
        command.assignees().financeApprovers()
            .forEach(value -> requireText(value, "financeApprover"));
    }

    private static String startRequestHash(StartCommand command) {
        List<String> attachments = command.attachmentIds().stream().sorted().toList();
        List<String> financeApprovers = command.assignees().financeApprovers().stream()
            .sorted(Comparator.naturalOrder())
            .toList();
        List<String> values = new ArrayList<>();
        values.add(command.businessKey());
        values.add(command.amount().stripTrailingZeros().toPlainString());
        values.add(command.supplier());
        values.add(command.purchaseOrderReference());
        values.addAll(attachments);
        values.add(command.assignees().managerAssignee());
        values.add(command.assignees().financeReviewer());
        values.addAll(financeApprovers);
        return hashValues(values.toArray(String[]::new));
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
        return value;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record PublishCommand(RequestContext context) {
        public PublishCommand {
            context = Objects.requireNonNull(context, "context must not be null");
        }
    }

    public record PublishResult(
        String definitionKey,
        int definitionVersion,
        int formVersion,
        String compilerVersion,
        String contentHash,
        String engineDefinitionId,
        Instant publishedAt
    ) {
    }

    public record StartCommand(
        RequestContext context,
        String businessKey,
        BigDecimal amount,
        String supplier,
        String purchaseOrderReference,
        List<String> attachmentIds,
        AssigneeSnapshot assignees
    ) {
        public StartCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
        }
    }

    public record StartResult(
        UUID instanceId,
        InstanceStatus status,
        List<TaskProjection> activeTasks,
        Instant startedAt
    ) {
        public StartResult {
            activeTasks = activeTasks == null ? List.of() : List.copyOf(activeTasks);
        }
    }

    public record ApproveCommand(RequestContext context, UUID taskId, String comment) {
        public ApproveCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            taskId = Objects.requireNonNull(taskId, "taskId must not be null");
        }
    }

    public record ApproveResult(
        UUID completedTaskId,
        UUID instanceId,
        InstanceStatus instanceStatus,
        List<TaskProjection> activeTasks,
        Instant completedAt
    ) {
        public ApproveResult {
            activeTasks = activeTasks == null ? List.of() : List.copyOf(activeTasks);
        }
    }

    public record InstanceDetails(
        InstanceProjection instance,
        List<TaskProjection> tasks
    ) {
        public InstanceDetails {
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
        }
    }
}
