package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalBusinessEventOutbox;
import io.github.akaryc1b.approval.application.port.ApprovalEffectiveReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.application.port.PurchasePaymentAssigneeResolver;
import io.github.akaryc1b.approval.application.port.PurchasePaymentAssigneeResolver.AssigneeRules;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalEffectiveRelease;
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
    private final PurchasePaymentAssigneeResolver assigneeResolver;
    private final ApprovalBusinessEventOutbox businessEventOutbox;
    private final ApprovalEffectiveReleaseStore effectiveReleases;
    private final ApprovalReleasePackageStore releasePackages;
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
        this(
            engine,
            compiler,
            idempotencyGuard,
            projections,
            auditEvents,
            (context, rules) -> {
                throw new PurchasePaymentAssigneeResolver.AssigneeResolutionException(
                    "ASSIGNEE_RESOLVER_UNAVAILABLE",
                    "connector-backed assignee resolver is not configured"
                );
            },
            ApprovalBusinessEventOutbox.noOp(),
            legacyEffectiveReleases(projections),
            clock,
            identifierGenerator
        );
    }

    public PurchasePaymentApplicationService(
        ApprovalEngine engine,
        ApprovalDslCompiler compiler,
        IdempotencyGuard idempotencyGuard,
        ApprovalProjectionStore projections,
        AuditEventSink auditEvents,
        PurchasePaymentAssigneeResolver assigneeResolver,
        ApprovalBusinessEventOutbox businessEventOutbox,
        Clock clock,
        Supplier<UUID> identifierGenerator
    ) {
        this(
            engine,
            compiler,
            idempotencyGuard,
            projections,
            auditEvents,
            assigneeResolver,
            businessEventOutbox,
            legacyEffectiveReleases(projections),
            clock,
            identifierGenerator
        );
    }

    public PurchasePaymentApplicationService(
        ApprovalEngine engine,
        ApprovalDslCompiler compiler,
        IdempotencyGuard idempotencyGuard,
        ApprovalProjectionStore projections,
        AuditEventSink auditEvents,
        PurchasePaymentAssigneeResolver assigneeResolver,
        ApprovalBusinessEventOutbox businessEventOutbox,
        ApprovalEffectiveReleaseStore effectiveReleases,
        Clock clock,
        Supplier<UUID> identifierGenerator
    ) {
        this(engine, compiler, idempotencyGuard, projections, auditEvents, assigneeResolver,
            businessEventOutbox, effectiveReleases, null, clock, identifierGenerator);
    }

    public PurchasePaymentApplicationService(ApprovalEngine engine, ApprovalDslCompiler compiler,
        IdempotencyGuard idempotencyGuard, ApprovalProjectionStore projections, AuditEventSink auditEvents,
        PurchasePaymentAssigneeResolver assigneeResolver, ApprovalBusinessEventOutbox businessEventOutbox,
        ApprovalEffectiveReleaseStore effectiveReleases, ApprovalReleasePackageStore releasePackages,
        Clock clock, Supplier<UUID> identifierGenerator) {
        this.releasePackages = releasePackages;
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.compiler = Objects.requireNonNull(compiler, "compiler must not be null");
        this.idempotencyGuard = Objects.requireNonNull(
            idempotencyGuard,
            "idempotencyGuard must not be null"
        );
        this.projections = Objects.requireNonNull(projections, "projections must not be null");
        this.auditEvents = Objects.requireNonNull(auditEvents, "auditEvents must not be null");
        this.assigneeResolver = Objects.requireNonNull(
            assigneeResolver,
            "assigneeResolver must not be null"
        );
        this.businessEventOutbox = Objects.requireNonNull(
            businessEventOutbox,
            "businessEventOutbox must not be null"
        );
        this.effectiveReleases = Objects.requireNonNull(
            effectiveReleases,
            "effectiveReleases must not be null"
        );
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

    ApprovalEffectiveRelease prepareFormRelease(RequestContext context) {
        return effectiveReleases.find(context.tenantId(), PurchasePaymentTemplate.DEFINITION_KEY)
            .orElseThrow(() -> new IllegalArgumentException("purchase payment has no effective deployed release"));
    }

    StartResult startBound(StartCommand command, ApprovalEffectiveRelease release) {
        Objects.requireNonNull(release, "release must not be null");
        if (!command.context().tenantId().equals(release.tenantId())
            || !PurchasePaymentTemplate.DEFINITION_KEY.equals(release.definitionKey())) {
            throw new IllegalArgumentException("bound release does not belong to purchase-payment tenant");
        }
        return start(command, release);
    }

    public StartResult start(StartCommand command) {
        return start(command, null);
    }

    private StartResult start(StartCommand command, ApprovalEffectiveRelease boundRelease) {
        Objects.requireNonNull(command, "command must not be null");
        validateStart(command);
        projections.findByBusinessKey(command.context().tenantId(), command.businessKey())
            .ifPresent(instance -> authorizeInitiator(command.context().operatorId(), instance));
        String requestHash = startRequestHash(command);
        StartResult result = idempotencyGuard.execute(
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
                    authorizeInitiator(command.context().operatorId(), existing.get());
                    requireBoundInstance(existing.get(), boundRelease);
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

                AssigneeSnapshot assignees = command.assignees() == null
                    ? assigneeResolver.resolve(command.context(), command.assigneeRules())
                    : command.assignees();
                validateResolvedAssignees(assignees);
                ApprovalEffectiveRelease effectiveRelease = boundRelease != null ? boundRelease : effectiveReleases.find(
                    command.context().tenantId(),
                    PurchasePaymentTemplate.DEFINITION_KEY
                ).orElseThrow(() -> new IllegalStateException(
                    "purchase payment does not have an effective deployed release"
                ));
                if (boundRelease != null) ensureBoundDefinitionProjection(boundRelease);
                boolean legacyStart = boundRelease == null && effectiveReleases instanceof LegacyEffectiveReleaseStore;
                UUID instanceId = identifierGenerator.get();
                Instant now = clock.instant();
                Map<String, Object> variables = new LinkedHashMap<>();
                variables.put("amount", command.amount());
                variables.put("supplier", command.supplier());
                variables.put("purchaseOrderReference", command.purchaseOrderReference());
                variables.put("attachmentIds", command.attachmentIds());
                variables.put(
                    PurchasePaymentTemplate.MANAGER_ASSIGNEE_VARIABLE,
                    assignees.managerAssignee()
                );
                variables.put(
                    PurchasePaymentTemplate.FINANCE_REVIEWER_VARIABLE,
                    assignees.financeReviewer()
                );
                variables.put(
                    PurchasePaymentTemplate.FINANCE_APPROVERS_VARIABLE,
                    assignees.financeApprovers()
                );
                variables.put("approvalInstanceId", instanceId.toString());
                variables.put("definitionVersion", effectiveRelease.definitionVersion());
                variables.put("formVersion", effectiveRelease.formSchemaVersion());
                variables.put("compilerVersion", effectiveRelease.compilerVersion());
                variables.put("contentHash", effectiveRelease.definitionHash());
                if (!legacyStart) {
                    variables.put("releaseVersion", effectiveRelease.effectiveReleaseVersion());
                    variables.put("releasePackageHash", effectiveRelease.releasePackageHash());
                    variables.put("formPackageVersion", effectiveRelease.formPackageVersion());
                    variables.put("uiSchemaVersion", effectiveRelease.uiSchemaVersion());
                }

                String engineInstanceId = legacyStart
                    ? engine.start(new ApprovalEngine.StartCommand(
                        command.context().tenantId(),
                        effectiveRelease.definitionKey(),
                        command.businessKey(),
                        command.context().operatorId(),
                        Map.copyOf(variables)
                    )).engineInstanceId()
                    : engine.startExact(new ApprovalEngine.ExactStartCommand(
                        command.context().tenantId(),
                        effectiveRelease.definitionKey(),
                        effectiveRelease.engineDeploymentId(),
                        effectiveRelease.engineDefinitionId(),
                        command.businessKey(),
                        command.context().operatorId(),
                        effectiveRelease.effectiveReleaseVersion(),
                        effectiveRelease.releasePackageHash(),
                        effectiveRelease.definitionVersion(),
                        effectiveRelease.formPackageVersion(),
                        effectiveRelease.compilerVersion(),
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
                    effectiveRelease.definitionKey(),
                    effectiveRelease.definitionVersion(),
                    effectiveRelease.definitionKey(),
                    effectiveRelease.formSchemaVersion(),
                    effectiveRelease.compilerVersion(),
                    effectiveRelease.definitionHash(),
                    legacyStart
                        ? null
                        : effectiveRelease.effectiveReleaseVersion(),
                    legacyStart
                        ? null
                        : effectiveRelease.releasePackageHash(),
                    legacyStart
                        ? null
                        : effectiveRelease.formPackageVersion(),
                    legacyStart
                        ? null
                        : effectiveRelease.formPackageHash(),
                    legacyStart
                        ? null
                        : effectiveRelease.uiSchemaVersion(),
                    legacyStart
                        ? null
                        : effectiveRelease.uiSchemaHash(),
                    legacyStart
                        ? null
                        : effectiveRelease.engineDefinitionId(),
                    command.context().operatorId(),
                    command.amount(),
                    command.supplier(),
                    command.purchaseOrderReference(),
                    command.attachmentIds(),
                    assignees,
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
                if (tasks.isEmpty()) {
                    enqueueCompletion(command.context(), instance, now);
                }
                return startResult(instance, tasks);
            }
        );
        // A concurrent start may become visible only after the idempotency lookup waits.
        InstanceProjection persisted = projections.findInstance(command.context().tenantId(), result.instanceId())
            .orElseThrow(ApprovalParticipantResourceNotFoundException::new);
        authorizeInitiator(command.context().operatorId(), persisted);
        requireBoundInstance(persisted, boundRelease);
        return result;
    }

    private static void requireBoundInstance(InstanceProjection instance, ApprovalEffectiveRelease release) {
        if (release != null && (!Objects.equals(instance.releaseVersion(), release.effectiveReleaseVersion())
            || !Objects.equals(instance.releasePackageHash(), release.releasePackageHash())
            || instance.formVersion() != release.formSchemaVersion()
            || !Objects.equals(instance.uiSchemaVersion(), release.uiSchemaVersion())
            || !Objects.equals(instance.uiSchemaHash(), release.uiSchemaHash()))) {
            throw new ApprovalProjectionStore.ProjectionConflictException("existing instance has a different immutable release binding");
        }
    }

    /** Same transaction as idempotent start; no publication, deployment, or projection overwrite. */
    private void ensureBoundDefinitionProjection(ApprovalEffectiveRelease release) {
        if (releasePackages == null) throw new IllegalStateException("release package store is required for bound starts");
        projections.lockDefinition(release.tenantId(), release.definitionKey(), release.definitionVersion());
        var artifact = releasePackages.find(release.tenantId(), release.definitionKey(), release.effectiveReleaseVersion())
            .orElseThrow(() -> new IllegalStateException("bound immutable release package is missing"));
        boolean exact = artifact.tenantId().equals(release.tenantId())
            && artifact.definitionKey().equals(release.definitionKey())
            && artifact.releaseVersion() == release.effectiveReleaseVersion()
            && artifact.definitionVersion() == release.definitionVersion()
            && artifact.definitionHash().equals(release.definitionHash())
            && artifact.packageHash().equals(release.releasePackageHash())
            && artifact.formPackageVersion() == release.formPackageVersion()
            && artifact.formPackageHash().equals(release.formPackageHash())
            && artifact.formVersion() == release.formSchemaVersion()
            && artifact.formHash().equals(release.formSchemaHash())
            && artifact.uiSchemaVersion() == release.uiSchemaVersion()
            && artifact.uiSchemaHash().equals(release.uiSchemaHash())
            && artifact.compilerVersion().equals(release.compilerVersion())
            && artifact.compiledArtifactHash().equals(release.compiledArtifactHash())
            && artifact.bpmnHash().equals(release.bpmnHash())
            && artifact.deploymentMetadataHash().equals(release.deploymentMetadataHash());
        if (!exact) throw new IllegalStateException("bound release package differs from captured effective release");
        PublishedDefinition expected = new PublishedDefinition(release.tenantId(), release.definitionKey(),
            release.definitionVersion(), release.definitionKey(), release.formSchemaVersion(), release.compilerVersion(),
            release.definitionHash(), release.engineDeploymentId(), release.engineDefinitionId(), release.engineVersion(),
            artifact.publishedBy(), artifact.publishedAt());
        var existing = projections.findDefinition(release.tenantId(), release.definitionKey(), release.definitionVersion());
        if (existing.isEmpty()) { projections.saveDefinition(expected); return; }
        PublishedDefinition actual = existing.get();
        if (!actual.tenantId().equals(expected.tenantId()) || !actual.definitionKey().equals(expected.definitionKey())
            || actual.definitionVersion() != expected.definitionVersion() || !actual.formKey().equals(expected.formKey())
            || actual.formVersion() != expected.formVersion() || !actual.compilerVersion().equals(expected.compilerVersion())
            || !actual.contentHash().equals(expected.contentHash()) || !actual.deploymentId().equals(expected.deploymentId())
            || !actual.engineDefinitionId().equals(expected.engineDefinitionId()) || actual.engineVersion() != expected.engineVersion()
            || !actual.publishedBy().equals(expected.publishedBy())
            || !actual.publishedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                .equals(expected.publishedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS))) {
            throw new ApprovalProjectionStore.ProjectionConflictException("existing definition projection differs from bound release");
        }
    }

    private static void authorizeInitiator(String operatorId, InstanceProjection instance) {
        if (!operatorId.equals(instance.initiatorId())) {
            throw new ApprovalParticipantResourceNotFoundException();
        }
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
                if (status == InstanceStatus.COMPLETED) {
                    enqueueCompletion(command.context(), instance, now);
                }
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
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("definitionKey", instance.definitionKey());
        attributes.put("definitionVersion", Integer.toString(instance.definitionVersion()));
        attributes.put("formVersion", Integer.toString(instance.formVersion()));
        attributes.put("compilerVersion", instance.compilerVersion());
        attributes.put("contentHash", instance.contentHash());
        if (instance.releaseVersion() != null) {
            attributes.put("releaseVersion", Integer.toString(instance.releaseVersion()));
            attributes.put("releasePackageHash", instance.releasePackageHash());
            attributes.put(
                "formPackageVersion",
                Integer.toString(instance.formPackageVersion())
            );
            attributes.put("formPackageHash", instance.formPackageHash());
            attributes.put("uiSchemaVersion", Integer.toString(instance.uiSchemaVersion()));
            attributes.put("uiSchemaHash", instance.uiSchemaHash());
            attributes.put("engineDefinitionId", instance.engineDefinitionId());
        }
        return Map.copyOf(attributes);
    }

    private static ApprovalEffectiveReleaseStore legacyEffectiveReleases(
        ApprovalProjectionStore projections
    ) {
        return new LegacyEffectiveReleaseStore(projections);
    }

    private static void validateStart(StartCommand command) {
        if (command.assigneeRules() != null
            && !command.context().operatorId().equals(command.assigneeRules().initiatorUserId().value())) {
            throw new PurchasePaymentAssigneeResolver.AssigneeResolutionException(
                "INITIATOR_OPERATOR_MISMATCH",
                "initiator external ID does not match the authenticated operator"
            );
        }
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
        if ((command.assignees() == null) == (command.assigneeRules() == null)) {
            throw new IllegalArgumentException(
                "exactly one of assignees or assigneeRules must be supplied"
            );
        }
        if (command.assignees() != null) {
            validateResolvedAssignees(command.assignees());
        }
    }

    private static void validateResolvedAssignees(AssigneeSnapshot assignees) {
        Objects.requireNonNull(assignees, "assignees must not be null");
        requireText(assignees.managerAssignee(), "managerAssignee");
        requireText(assignees.financeReviewer(), "financeReviewer");
        if (assignees.financeApprovers().isEmpty()) {
            throw new IllegalArgumentException("financeApprovers must not be empty");
        }
        assignees.financeApprovers().forEach(value -> requireText(value, "financeApprover"));
    }

    private static String startRequestHash(StartCommand command) {
        List<String> attachments = command.attachmentIds().stream().sorted().toList();
        List<String> values = new ArrayList<>();
        values.add(command.businessKey());
        values.add(command.amount().stripTrailingZeros().toPlainString());
        values.add(command.supplier());
        values.add(command.purchaseOrderReference());
        values.addAll(attachments);
        if (command.assigneeRules() != null) {
            values.add("RULES");
            values.add(command.assigneeRules().connectorKey());
            values.add(command.assigneeRules().initiatorUserId().canonicalValue());
            values.add(command.assigneeRules().financeReviewerRoleCode());
            values.add(command.assigneeRules().financeApproverPositionCode());
            values.add(Integer.toString(command.assigneeRules().maximumFinanceApprovers()));
        } else {
            values.add("SNAPSHOT");
            values.add(command.assignees().managerAssignee());
            values.add(command.assignees().financeReviewer());
            values.addAll(command.assignees().financeApprovers().stream()
                .sorted(Comparator.naturalOrder())
                .toList());
        }
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
        AssigneeSnapshot assignees,
        AssigneeRules assigneeRules
    ) {
        public StartCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
        }

        public StartCommand(
            RequestContext context,
            String businessKey,
            BigDecimal amount,
            String supplier,
            String purchaseOrderReference,
            List<String> attachmentIds,
            AssigneeSnapshot assignees
        ) {
            this(
                context,
                businessKey,
                amount,
                supplier,
                purchaseOrderReference,
                attachmentIds,
                assignees,
                null
            );
        }

        public StartCommand(
            RequestContext context,
            String businessKey,
            BigDecimal amount,
            String supplier,
            String purchaseOrderReference,
            List<String> attachmentIds,
            AssigneeRules assigneeRules
        ) {
            this(
                context,
                businessKey,
                amount,
                supplier,
                purchaseOrderReference,
                attachmentIds,
                null,
                assigneeRules
            );
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

    private static final class LegacyEffectiveReleaseStore
        implements ApprovalEffectiveReleaseStore {

        private final ApprovalProjectionStore projections;

        private LegacyEffectiveReleaseStore(ApprovalProjectionStore projections) {
            this.projections = projections;
        }

        @Override
        public void lock(String tenantId, String definitionKey) {
        }

        @Override
        public Optional<ApprovalEffectiveRelease> find(
            String tenantId,
            String definitionKey
        ) {
            return projections.findDefinition(
                tenantId,
                definitionKey,
                PurchasePaymentTemplate.PROCESS_VERSION
            ).map(value -> new ApprovalEffectiveRelease(
                value.tenantId(),
                value.definitionKey(),
                value.definitionVersion(),
                null,
                value.contentHash(),
                value.definitionVersion(),
                value.contentHash(),
                value.formVersion(),
                value.contentHash(),
                value.formVersion(),
                value.contentHash(),
                value.formVersion(),
                value.contentHash(),
                value.compilerVersion(),
                value.contentHash(),
                value.contentHash(),
                value.contentHash(),
                value.deploymentId(),
                value.engineDefinitionId(),
                value.engineVersion(),
                ApprovalEffectiveRelease.Status.ACTIVE,
                1,
                value.publishedBy(),
                value.publishedAt(),
                "legacy published definition",
                "legacy-" + value.definitionKey() + '-' + value.definitionVersion(),
                null
            ));
        }

        @Override
        public void save(
            ApprovalEffectiveRelease effectiveRelease,
            ApprovalEffectiveRelease.Activation activation
        ) {
            throw new UnsupportedOperationException("legacy effective release store is read-only");
        }

        @Override
        public boolean update(
            ApprovalEffectiveRelease effectiveRelease,
            long expectedRevision,
            ApprovalEffectiveRelease.Activation activation
        ) {
            throw new UnsupportedOperationException("legacy effective release store is read-only");
        }

        @Override
        public boolean wasActivated(String tenantId, String definitionKey, int releaseVersion) {
            return false;
        }

        @Override
        public ActivationPage findHistory(ActivationCriteria criteria) {
            return new ActivationPage(List.of(), 0, criteria.limit(), criteria.offset());
        }
    }
}
