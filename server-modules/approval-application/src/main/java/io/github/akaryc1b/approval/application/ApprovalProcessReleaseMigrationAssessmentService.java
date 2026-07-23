package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalReleaseStructuralDiff.Impact;
import io.github.akaryc1b.approval.application.port.ApprovalDefinitionVersionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalRuntimeBindingStore;
import io.github.akaryc1b.approval.application.port.ApprovalRuntimeBindingStore.BindingCriteria;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition.ProcessNode;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinitionVersion;
import io.github.akaryc1b.approval.domain.definition.ApprovalProcessRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.definition.ApprovalRuntimeBinding;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Evidence-producing, non-mutating assessment for in-flight release migration.
 * The service reads only public platform projections and immutable release evidence.
 */
public final class ApprovalProcessReleaseMigrationAssessmentService {

    private static final String OPERATION = "approval-process-release.migration-dry-run.v1";
    private static final String AUDIT_ACTION = "PROCESS_RELEASE_MIGRATION_DRY_RUN_EXECUTED";
    private static final int MIN_REASON_CODE_POINTS = 8;
    private static final int MAX_REASON_CODE_POINTS = 512;

    private final IdempotencyGuard idempotency;
    private final ApprovalProcessReleaseStore releases;
    private final ApprovalReleasePackageStore packages;
    private final ApprovalReleaseDeploymentStore deployments;
    private final ApprovalDefinitionVersionStore definitions;
    private final ApprovalRuntimeBindingStore runtimeBindings;
    private final ApprovalProjectionStore projections;
    private final AuditEventSink auditEvents;
    private final ApprovalReleaseStructuralDiff structuralDiff;
    private final ApprovalReleasePackageHasher hasher;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public ApprovalProcessReleaseMigrationAssessmentService(
        IdempotencyGuard idempotency,
        ApprovalProcessReleaseStore releases,
        ApprovalReleasePackageStore packages,
        ApprovalReleaseDeploymentStore deployments,
        ApprovalDefinitionVersionStore definitions,
        ApprovalRuntimeBindingStore runtimeBindings,
        ApprovalProjectionStore projections,
        AuditEventSink auditEvents,
        ApprovalReleaseStructuralDiff structuralDiff,
        ApprovalReleasePackageHasher hasher,
        Clock clock,
        Supplier<UUID> identifiers
    ) {
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency must not be null");
        this.releases = Objects.requireNonNull(releases, "releases must not be null");
        this.packages = Objects.requireNonNull(packages, "packages must not be null");
        this.deployments = Objects.requireNonNull(deployments, "deployments must not be null");
        this.definitions = Objects.requireNonNull(definitions, "definitions must not be null");
        this.runtimeBindings = Objects.requireNonNull(
            runtimeBindings,
            "runtimeBindings must not be null"
        );
        this.projections = Objects.requireNonNull(projections, "projections must not be null");
        this.auditEvents = Objects.requireNonNull(auditEvents, "auditEvents must not be null");
        this.structuralDiff = Objects.requireNonNull(
            structuralDiff,
            "structuralDiff must not be null"
        );
        this.hasher = Objects.requireNonNull(hasher, "hasher must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
    }

    public AssessmentResult assess(AssessmentCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.sourceReleaseVersion() == command.targetReleaseVersion()) {
            throw new IllegalArgumentException("source and target release versions must differ");
        }
        return idempotency.execute(
            command.context(),
            OPERATION,
            requestHash(command),
            AssessmentResult.class,
            () -> assessOnce(command)
        );
    }

    private AssessmentResult assessOnce(AssessmentCommand command) {
        RequestContext context = command.context();
        String definitionKey = command.definitionKey();
        ApprovalProcessRelease sourceLifecycle = release(
            context.tenantId(),
            definitionKey,
            command.sourceReleaseVersion(),
            "source"
        );
        ApprovalProcessRelease targetLifecycle = release(
            context.tenantId(),
            definitionKey,
            command.targetReleaseVersion(),
            "target"
        );
        ApprovalReleasePackage sourcePackage = releasePackage(
            context.tenantId(),
            definitionKey,
            command.sourceReleaseVersion(),
            "source"
        );
        ApprovalReleasePackage targetPackage = releasePackage(
            context.tenantId(),
            definitionKey,
            command.targetReleaseVersion(),
            "target"
        );
        requireLifecycleIdentity(sourceLifecycle, sourcePackage, "source");
        requireLifecycleIdentity(targetLifecycle, targetPackage, "target");

        ApprovalDefinitionVersion sourceDefinition = definition(
            context.tenantId(),
            definitionKey,
            sourcePackage.definitionVersion(),
            "source"
        );
        ApprovalDefinitionVersion targetDefinition = definition(
            context.tenantId(),
            definitionKey,
            targetPackage.definitionVersion(),
            "target"
        );
        ApprovalReleaseDeployment targetDeployment = deployments.find(
            context.tenantId(),
            definitionKey,
            command.targetReleaseVersion()
        ).orElse(null);

        List<Finding> globalFindings = globalFindings(
            sourceLifecycle,
            targetLifecycle,
            targetPackage,
            targetDeployment
        );
        int highImpactChangeCount = (int) structuralDiff.diff(
            sourceDefinition,
            targetDefinition,
            sourcePackage,
            targetPackage
        ).changes().stream().filter(change -> change.impact() == Impact.HIGH).count();

        var page = runtimeBindings.findByRelease(new BindingCriteria(
            context.tenantId(),
            definitionKey,
            command.sourceReleaseVersion(),
            command.limit(),
            command.offset()
        ));
        Map<String, ProcessNode> sourceNodes = nodes(sourceDefinition.definition());
        Map<String, ProcessNode> targetNodes = nodes(targetDefinition.definition());
        List<InstanceAssessment> instances = page.items().stream()
            .map(binding -> assessInstance(binding, sourcePackage, sourceNodes, targetNodes))
            .toList();

        int runningCount = (int) instances.stream()
            .filter(item -> item.instanceStatus() == InstanceStatus.RUNNING)
            .count();
        int eligibleCount = (int) instances.stream()
            .filter(item -> item.decision() == InstanceDecision.ELIGIBLE)
            .count();
        int blockedCount = (int) instances.stream()
            .filter(item -> item.decision() == InstanceDecision.BLOCKED)
            .count();
        int terminalCount = (int) instances.stream()
            .filter(item -> item.decision() == InstanceDecision.TERMINAL_SKIPPED)
            .count();
        boolean hasGlobalBlocker = globalFindings.stream()
            .anyMatch(finding -> finding.severity() == FindingSeverity.BLOCKER);
        boolean complete = command.offset() == 0 && !page.hasMore();
        AssessmentStatus status = status(
            complete,
            runningCount,
            blockedCount,
            hasGlobalBlocker
        );
        Instant assessedAt = clock.instant();
        UUID assessmentId = nextIdentifier("assessmentId");
        String reportHash = reportHash(
            command,
            sourceLifecycle,
            targetLifecycle,
            page.total(),
            highImpactChangeCount,
            globalFindings,
            instances
        );
        AssessmentResult result = new AssessmentResult(
            assessmentId,
            context.tenantId(),
            definitionKey,
            command.sourceReleaseVersion(),
            sourcePackage.packageHash(),
            sourceLifecycle.lifecycleState(),
            command.targetReleaseVersion(),
            targetPackage.packageHash(),
            targetLifecycle.lifecycleState(),
            status,
            true,
            complete,
            page.total(),
            command.limit(),
            command.offset(),
            page.hasMore(),
            runningCount,
            eligibleCount,
            blockedCount,
            terminalCount,
            highImpactChangeCount,
            globalFindings,
            instances,
            reportHash,
            assessedAt
        );
        auditEvents.append(new AuditEvent(
            nextIdentifier("auditEventId"),
            context.tenantId(),
            context.operatorId(),
            AUDIT_ACTION,
            "APPROVAL_PROCESS_RELEASE_MIGRATION",
            definitionKey + ':' + command.sourceReleaseVersion()
                + "->" + command.targetReleaseVersion(),
            context.requestId(),
            context.traceId(),
            assessedAt,
            auditAttributes(command, result)
        ));
        return result;
    }

    private InstanceAssessment assessInstance(
        ApprovalRuntimeBinding binding,
        ApprovalReleasePackage sourcePackage,
        Map<String, ProcessNode> sourceNodes,
        Map<String, ProcessNode> targetNodes
    ) {
        List<Finding> findings = new ArrayList<>();
        if (!binding.binds(sourcePackage)) {
            findings.add(blocker(
                "SOURCE_BINDING_MISMATCH",
                "Runtime binding does not match the immutable source Release Package",
                null
            ));
        }
        Optional<InstanceProjection> projectionOptional = projections.findInstance(
            binding.tenantId(),
            binding.approvalInstanceId()
        );
        if (projectionOptional.isEmpty()) {
            findings.add(blocker(
                "INSTANCE_PROJECTION_MISSING",
                "Platform instance projection was not found for the runtime binding",
                null
            ));
            return assessment(binding, null, InstanceDecision.BLOCKED, List.of(), findings);
        }
        InstanceProjection projection = projectionOptional.orElseThrow();
        if (!matches(binding, projection)) {
            findings.add(blocker(
                "INSTANCE_BINDING_MISMATCH",
                "Platform instance projection does not match immutable runtime binding evidence",
                null
            ));
        }
        if (projection.status() != InstanceStatus.RUNNING) {
            InstanceDecision terminalDecision = findings.stream()
                .anyMatch(finding -> finding.severity() == FindingSeverity.BLOCKER)
                ? InstanceDecision.BLOCKED
                : InstanceDecision.TERMINAL_SKIPPED;
            return assessment(
                binding,
                projection.status(),
                terminalDecision,
                List.of(),
                findings
            );
        }

        List<TaskProjection> activeTasks = projections.findTasks(
            binding.tenantId(),
            binding.approvalInstanceId()
        ).stream().filter(task -> task.status() == TaskStatus.PENDING
            || task.status() == TaskStatus.COMPLETING).toList();
        List<String> taskKeys = activeTasks.stream()
            .map(TaskProjection::taskDefinitionKey)
            .sorted()
            .toList();
        if (activeTasks.isEmpty()) {
            findings.add(blocker(
                "ACTIVE_TASK_PROJECTION_MISSING",
                "Running instance has no active platform task projection",
                null
            ));
        }
        for (TaskProjection task : activeTasks) {
            if (task.status() == TaskStatus.COMPLETING) {
                findings.add(blocker(
                    "TASK_TRANSITION_IN_PROGRESS",
                    "Task is currently completing and cannot be assessed as migration-stable",
                    task.taskDefinitionKey()
                ));
                continue;
            }
            ProcessNode sourceNode = sourceNodes.get(task.taskDefinitionKey());
            ProcessNode targetNode = targetNodes.get(task.taskDefinitionKey());
            if (sourceNode == null) {
                findings.add(blocker(
                    "SOURCE_TASK_NODE_MISSING",
                    "Active task node is absent from the immutable source Approval DSL",
                    task.taskDefinitionKey()
                ));
            } else if (!isUserTaskNode(sourceNode)) {
                findings.add(blocker(
                    "SOURCE_TASK_NODE_KIND_UNSUPPORTED",
                    "Active task does not map to an approval or handling node",
                    task.taskDefinitionKey()
                ));
            }
            if (targetNode == null) {
                findings.add(blocker(
                    "TARGET_TASK_NODE_MISSING",
                    "Active task node is absent from the target Approval DSL",
                    task.taskDefinitionKey()
                ));
            } else if (sourceNode != null
                && !sourceNode.getClass().equals(targetNode.getClass())) {
                findings.add(blocker(
                    "TARGET_TASK_NODE_KIND_CHANGED",
                    "Target Approval DSL changes the active task node kind",
                    task.taskDefinitionKey()
                ));
            }
        }
        InstanceDecision decision = findings.stream()
            .anyMatch(finding -> finding.severity() == FindingSeverity.BLOCKER)
            ? InstanceDecision.BLOCKED
            : InstanceDecision.ELIGIBLE;
        return assessment(binding, projection.status(), decision, taskKeys, findings);
    }

    private static InstanceAssessment assessment(
        ApprovalRuntimeBinding binding,
        InstanceStatus status,
        InstanceDecision decision,
        List<String> taskKeys,
        List<Finding> findings
    ) {
        return new InstanceAssessment(
            binding.approvalInstanceId(),
            binding.businessKey(),
            binding.engineInstanceId(),
            status,
            decision,
            taskKeys,
            findings,
            binding.bindingEvidenceHash()
        );
    }

    private static List<Finding> globalFindings(
        ApprovalProcessRelease source,
        ApprovalProcessRelease target,
        ApprovalReleasePackage targetPackage,
        ApprovalReleaseDeployment targetDeployment
    ) {
        List<Finding> findings = new ArrayList<>();
        if (source.lifecycleState() == State.ACTIVE) {
            findings.add(blocker(
                "SOURCE_RELEASE_STILL_ACTIVE",
                "Source release must stop receiving new instances before migration assessment",
                null
            ));
        } else if (source.lifecycleState() == State.PUBLISHED) {
            findings.add(blocker(
                "SOURCE_RELEASE_NOT_ACTIVATED",
                "Source release has never been active and cannot own in-flight instances",
                null
            ));
        }
        if (target.lifecycleState() != State.ACTIVE) {
            findings.add(blocker(
                "TARGET_RELEASE_NOT_ACTIVE",
                "Target release must be ACTIVE before any governed migration command exists",
                null
            ));
        }
        if (targetDeployment == null
            || targetDeployment.status() != ApprovalReleaseDeployment.Status.DEPLOYED
            || !targetPackage.tenantId().equals(targetDeployment.tenantId())
            || !targetPackage.definitionKey().equals(targetDeployment.definitionKey())
            || targetPackage.releaseVersion() != targetDeployment.releaseVersion()
            || !targetPackage.packageHash().equals(targetDeployment.releasePackageHash())) {
            findings.add(blocker(
                "TARGET_DEPLOYMENT_NOT_READY",
                "Target release does not have an exact DEPLOYED platform projection",
                null
            ));
        }
        return List.copyOf(findings);
    }

    private static boolean matches(
        ApprovalRuntimeBinding binding,
        InstanceProjection projection
    ) {
        return binding.approvalInstanceId().equals(projection.instanceId())
            && binding.tenantId().equals(projection.tenantId())
            && binding.businessKey().equals(projection.businessKey())
            && binding.engineInstanceId().equals(projection.engineInstanceId())
            && binding.definitionKey().equals(projection.definitionKey())
            && binding.definitionVersion() == projection.definitionVersion()
            && binding.definitionHash().equals(projection.contentHash())
            && Objects.equals(binding.releaseVersion(), projection.releaseVersion())
            && binding.releasePackageHash().equals(projection.releasePackageHash())
            && Objects.equals(binding.formPackageVersion(), projection.formPackageVersion())
            && binding.formPackageHash().equals(projection.formPackageHash())
            && binding.formVersion() == projection.formVersion()
            && Objects.equals(binding.uiSchemaVersion(), projection.uiSchemaVersion())
            && binding.uiSchemaHash().equals(projection.uiSchemaHash())
            && binding.compilerVersion().equals(projection.compilerVersion())
            && binding.engineDefinitionId().equals(projection.engineDefinitionId());
    }

    private static boolean isUserTaskNode(ProcessNode node) {
        return node instanceof ApprovalDefinition.ApprovalStep
            || node instanceof ApprovalDefinition.HandleStep;
    }

    private static Map<String, ProcessNode> nodes(ApprovalDefinition definition) {
        return definition.nodes().stream().collect(Collectors.toUnmodifiableMap(
            ProcessNode::id,
            node -> node
        ));
    }

    private ApprovalProcessRelease release(
        String tenantId,
        String definitionKey,
        int releaseVersion,
        String role
    ) {
        return releases.find(tenantId, definitionKey, releaseVersion)
            .orElseThrow(() -> new MigrationEvidenceNotFoundException(
                role + " release lifecycle was not found for the tenant"
            ));
    }

    private ApprovalReleasePackage releasePackage(
        String tenantId,
        String definitionKey,
        int releaseVersion,
        String role
    ) {
        return packages.find(tenantId, definitionKey, releaseVersion)
            .orElseThrow(() -> new MigrationEvidenceNotFoundException(
                role + " Release Package was not found for the tenant"
            ));
    }

    private ApprovalDefinitionVersion definition(
        String tenantId,
        String definitionKey,
        int definitionVersion,
        String role
    ) {
        return definitions.find(tenantId, definitionKey, definitionVersion)
            .orElseThrow(() -> new MigrationEvidenceNotFoundException(
                role + " Approval DSL version was not found for the tenant"
            ));
    }

    private static void requireLifecycleIdentity(
        ApprovalProcessRelease lifecycle,
        ApprovalReleasePackage releasePackage,
        String role
    ) {
        if (!lifecycle.tenantId().equals(releasePackage.tenantId())
            || !lifecycle.definitionKey().equals(releasePackage.definitionKey())
            || lifecycle.releaseVersion() != releasePackage.releaseVersion()
            || !lifecycle.releasePackageHash().equals(releasePackage.packageHash())) {
            throw new MigrationEvidenceConflictException(
                role + " lifecycle does not match its immutable Release Package"
            );
        }
    }

    private static AssessmentStatus status(
        boolean complete,
        int runningCount,
        int blockedCount,
        boolean globalBlocker
    ) {
        if (globalBlocker || blockedCount > 0) {
            return AssessmentStatus.BLOCKED;
        }
        if (!complete) {
            return AssessmentStatus.PARTIAL;
        }
        return runningCount == 0 ? AssessmentStatus.NO_IN_FLIGHT : AssessmentStatus.READY;
    }

    private String requestHash(AssessmentCommand command) {
        return hasher.hashValues(
            command.definitionKey(),
            command.sourceReleaseVersion(),
            command.targetReleaseVersion(),
            command.limit(),
            command.offset(),
            command.reason()
        );
    }

    private String reportHash(
        AssessmentCommand command,
        ApprovalProcessRelease source,
        ApprovalProcessRelease target,
        long totalBindings,
        int highImpactChangeCount,
        List<Finding> globalFindings,
        List<InstanceAssessment> instances
    ) {
        String global = globalFindings.stream()
            .map(Finding::canonical)
            .collect(Collectors.joining("|"));
        String perInstance = instances.stream()
            .map(InstanceAssessment::canonical)
            .collect(Collectors.joining("|"));
        return hasher.hashValues(
            command.context().tenantId(),
            command.definitionKey(),
            command.sourceReleaseVersion(),
            source.releasePackageHash(),
            source.lifecycleState(),
            command.targetReleaseVersion(),
            target.releasePackageHash(),
            target.lifecycleState(),
            totalBindings,
            command.limit(),
            command.offset(),
            highImpactChangeCount,
            global,
            perInstance
        );
    }

    private static Map<String, String> auditAttributes(
        AssessmentCommand command,
        AssessmentResult result
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("operation", "DRY_RUN");
        values.put("definitionKey", command.definitionKey());
        values.put("sourceReleaseVersion", Integer.toString(result.sourceReleaseVersion()));
        values.put("sourceReleasePackageHash", result.sourceReleasePackageHash());
        values.put("targetReleaseVersion", Integer.toString(result.targetReleaseVersion()));
        values.put("targetReleasePackageHash", result.targetReleasePackageHash());
        values.put("status", result.status().name());
        values.put("totalBindingCount", Long.toString(result.totalBindingCount()));
        values.put("evaluatedCount", Integer.toString(result.instances().size()));
        values.put("eligibleCount", Integer.toString(result.eligibleCount()));
        values.put("blockedCount", Integer.toString(result.blockedCount()));
        values.put("terminalCount", Integer.toString(result.terminalCount()));
        values.put("highImpactChangeCount", Integer.toString(result.highImpactChangeCount()));
        values.put("reportHash", result.reportHash());
        values.put("detectOnly", Boolean.toString(result.detectOnly()));
        values.put("reason", command.reason());
        return Map.copyOf(values);
    }

    private UUID nextIdentifier(String name) {
        return Objects.requireNonNull(identifiers.get(), "generated " + name + " must not be null");
    }

    private static Finding blocker(String code, String message, String taskDefinitionKey) {
        return new Finding(code, FindingSeverity.BLOCKER, message, taskDefinitionKey);
    }

    public record AssessmentCommand(
        RequestContext context,
        String definitionKey,
        int sourceReleaseVersion,
        int targetReleaseVersion,
        int limit,
        int offset,
        String reason
    ) {
        public AssessmentCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            definitionKey = requireText(definitionKey, "definitionKey");
            if (sourceReleaseVersion < 1 || targetReleaseVersion < 1) {
                throw new IllegalArgumentException("source and target release versions must be positive");
            }
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("limit must be between 1 and 100");
            }
            if (offset < 0) {
                throw new IllegalArgumentException("offset must not be negative");
            }
            reason = normalizeReason(reason);
        }
    }

    public record AssessmentResult(
        UUID assessmentId,
        String tenantId,
        String definitionKey,
        int sourceReleaseVersion,
        String sourceReleasePackageHash,
        State sourceLifecycleState,
        int targetReleaseVersion,
        String targetReleasePackageHash,
        State targetLifecycleState,
        AssessmentStatus status,
        boolean detectOnly,
        boolean complete,
        long totalBindingCount,
        int limit,
        int offset,
        boolean hasMore,
        int runningCount,
        int eligibleCount,
        int blockedCount,
        int terminalCount,
        int highImpactChangeCount,
        List<Finding> globalFindings,
        List<InstanceAssessment> instances,
        String reportHash,
        Instant assessedAt
    ) {
        public AssessmentResult {
            assessmentId = Objects.requireNonNull(assessmentId, "assessmentId must not be null");
            tenantId = requireText(tenantId, "tenantId");
            definitionKey = requireText(definitionKey, "definitionKey");
            sourceReleasePackageHash = requireHash(
                sourceReleasePackageHash,
                "sourceReleasePackageHash"
            );
            sourceLifecycleState = Objects.requireNonNull(
                sourceLifecycleState,
                "sourceLifecycleState must not be null"
            );
            targetReleasePackageHash = requireHash(
                targetReleasePackageHash,
                "targetReleasePackageHash"
            );
            targetLifecycleState = Objects.requireNonNull(
                targetLifecycleState,
                "targetLifecycleState must not be null"
            );
            status = Objects.requireNonNull(status, "status must not be null");
            if (!detectOnly) {
                throw new IllegalArgumentException("migration assessment must remain detect-only");
            }
            if (totalBindingCount < 0 || limit < 1 || offset < 0) {
                throw new IllegalArgumentException("assessment paging values are invalid");
            }
            globalFindings = globalFindings == null ? List.of() : List.copyOf(globalFindings);
            instances = instances == null ? List.of() : List.copyOf(instances);
            reportHash = requireHash(reportHash, "reportHash");
            assessedAt = Objects.requireNonNull(assessedAt, "assessedAt must not be null");
        }
    }

    public record InstanceAssessment(
        UUID approvalInstanceId,
        String businessKey,
        String engineInstanceId,
        InstanceStatus instanceStatus,
        InstanceDecision decision,
        List<String> activeTaskDefinitionKeys,
        List<Finding> findings,
        String bindingEvidenceHash
    ) {
        public InstanceAssessment {
            approvalInstanceId = Objects.requireNonNull(
                approvalInstanceId,
                "approvalInstanceId must not be null"
            );
            businessKey = requireText(businessKey, "businessKey");
            engineInstanceId = requireText(engineInstanceId, "engineInstanceId");
            decision = Objects.requireNonNull(decision, "decision must not be null");
            activeTaskDefinitionKeys = activeTaskDefinitionKeys == null
                ? List.of()
                : List.copyOf(activeTaskDefinitionKeys);
            findings = findings == null ? List.of() : List.copyOf(findings);
            bindingEvidenceHash = requireHash(bindingEvidenceHash, "bindingEvidenceHash");
        }

        String canonical() {
            return approvalInstanceId + ":" + instanceStatus + ":" + decision + ":"
                + String.join(",", activeTaskDefinitionKeys) + ":"
                + findings.stream().map(Finding::canonical).collect(Collectors.joining(","))
                + ":" + bindingEvidenceHash;
        }
    }

    public record Finding(
        String code,
        FindingSeverity severity,
        String message,
        String taskDefinitionKey
    ) {
        public Finding {
            code = requireText(code, "code");
            severity = Objects.requireNonNull(severity, "severity must not be null");
            message = requireText(message, "message");
            taskDefinitionKey = normalizeOptional(taskDefinitionKey);
        }

        String canonical() {
            return code + ":" + severity + ":" + taskDefinitionKey;
        }
    }

    public enum AssessmentStatus {
        READY,
        BLOCKED,
        NO_IN_FLIGHT,
        PARTIAL
    }

    public enum InstanceDecision {
        ELIGIBLE,
        BLOCKED,
        TERMINAL_SKIPPED
    }

    public enum FindingSeverity {
        BLOCKER,
        WARNING
    }

    public static final class MigrationEvidenceNotFoundException extends RuntimeException {
        public MigrationEvidenceNotFoundException(String message) {
            super(message);
        }
    }

    public static final class MigrationEvidenceConflictException extends RuntimeException {
        public MigrationEvidenceConflictException(String message) {
            super(message);
        }
    }

    private static String normalizeReason(String supplied) {
        Objects.requireNonNull(supplied, "reason must not be null");
        String normalized = Normalizer.normalize(supplied.trim(), Normalizer.Form.NFKC);
        int length = normalized.codePointCount(0, normalized.length());
        if (length < MIN_REASON_CODE_POINTS || length > MAX_REASON_CODE_POINTS) {
            throw new IllegalArgumentException("reason must contain between 8 and 512 characters");
        }
        for (int index = 0; index < normalized.length(); index++) {
            char value = normalized.charAt(index);
            int type = Character.getType(value);
            if (Character.isISOControl(value)
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE) {
                throw new IllegalArgumentException("reason contains unsupported characters");
            }
        }
        return normalized;
    }

    private static String requireHash(String value, String name) {
        String normalized = requireText(value, name);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
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

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
