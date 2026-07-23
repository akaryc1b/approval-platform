package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.AssessmentCommand;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.AssessmentStatus;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.InstanceDecision;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.MigrationEvidenceNotFoundException;
import io.github.akaryc1b.approval.application.port.ApprovalDefinitionVersionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalRuntimeBindingStore;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinitionVersion;
import io.github.akaryc1b.approval.domain.definition.ApprovalProcessRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.definition.ApprovalRuntimeBinding;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition.ApprovalMode;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition.ApprovalStep;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition.AssigneeResolver;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition.AssigneeRule;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition.EmptyAssigneePolicy;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition.EndNode;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition.StartNode;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalProcessReleaseMigrationAssessmentServiceTest {
    private static final String TENANT = "tenant-migration";
    private static final String KEY = "purchasePayment";
    private static final Instant NOW = Instant.parse("2026-07-23T01:00:00Z");

    @Test
    void readyReplayAndTerminalAssessmentRemainDetectOnly() {
        Harness ready = new Harness();
        ready.project(ready.primary(), ApprovalProjectionStore.InstanceStatus.RUNNING,
            "managerApproval");
        AssessmentCommand command = ready.command("ready", 100);

        var result = ready.service().assess(command);
        var replay = ready.service().assess(command);
        assertEquals(result, replay);
        assertEquals(AssessmentStatus.READY, result.status());
        assertEquals(InstanceDecision.ELIGIBLE, result.instances().getFirst().decision());
        assertTrue(result.detectOnly());
        assertEquals(1, ready.audit.size());
        assertFalse(ready.mutated);

        Harness terminal = new Harness();
        terminal.project(terminal.primary(), ApprovalProjectionStore.InstanceStatus.COMPLETED);
        var terminalResult = terminal.service().assess(terminal.command("terminal", 100));
        assertEquals(AssessmentStatus.NO_IN_FLIGHT, terminalResult.status());
        assertEquals(InstanceDecision.TERMINAL_SKIPPED,
            terminalResult.instances().getFirst().decision());
        assertFalse(terminal.mutated);
    }

    @Test
    void missingTargetNodeProjectionAndLifecycleEvidenceFailClosed() {
        Harness removed = new Harness();
        removed.targetDefinition = definition(2, false);
        removed.project(removed.primary(), ApprovalProjectionStore.InstanceStatus.RUNNING,
            "managerApproval");
        var removedResult = removed.service().assess(removed.command("removed", 100));
        assertEquals(AssessmentStatus.BLOCKED, removedResult.status());
        assertTrue(removedResult.highImpactChangeCount() > 0);
        assertTrue(hasFinding(removedResult.instances().getFirst().findings(),
            "TARGET_TASK_NODE_MISSING"));

        Harness blocked = new Harness();
        blocked.sourceLifecycle = lifecycle(1, State.ACTIVE);
        blocked.targetLifecycle = lifecycle(2, State.PUBLISHED);
        blocked.total = 2;
        var blockedResult = blocked.service().assess(blocked.command("partial", 1));
        assertEquals(AssessmentStatus.BLOCKED, blockedResult.status());
        assertFalse(blockedResult.complete());
        assertTrue(blockedResult.hasMore());
        assertTrue(hasFinding(blockedResult.globalFindings(), "SOURCE_RELEASE_STILL_ACTIVE"));
        assertTrue(hasFinding(blockedResult.globalFindings(), "TARGET_RELEASE_NOT_ACTIVE"));
        assertTrue(hasFinding(blockedResult.instances().getFirst().findings(),
            "INSTANCE_PROJECTION_MISSING"));
    }

    @Test
    void commandValidationIsBoundedNormalizedAndTenantScoped() {
        RequestContext context = context(TENANT, "validation");
        assertThrows(IllegalArgumentException.class,
            () -> new AssessmentCommand(context, " ", 1, 2, 100, 0, validReason()));
        assertThrows(IllegalArgumentException.class,
            () -> new AssessmentCommand(context, KEY, 0, 2, 100, 0, validReason()));
        assertThrows(IllegalArgumentException.class,
            () -> new AssessmentCommand(context, KEY, 1, 0, 100, 0, validReason()));
        assertThrows(IllegalArgumentException.class,
            () -> new AssessmentCommand(context, KEY, 1, 2, 0, 0, validReason()));
        assertThrows(IllegalArgumentException.class,
            () -> new AssessmentCommand(context, KEY, 1, 2, 101, 0, validReason()));
        assertThrows(IllegalArgumentException.class,
            () -> new AssessmentCommand(context, KEY, 1, 2, 100, -1, validReason()));
        assertThrows(IllegalArgumentException.class,
            () -> new AssessmentCommand(context, KEY, 1, 2, 100, 0, "short"));
        assertThrows(IllegalArgumentException.class,
            () -> new AssessmentCommand(context, KEY, 1, 2, 100, 0, "x".repeat(513)));
        assertThrows(IllegalArgumentException.class,
            () -> new AssessmentCommand(context, KEY, 1, 2, 100, 0,
                "Assess" + Character.toString(0) + "migration evidence"));

        AssessmentCommand normalized = new AssessmentCommand(
            context,
            KEY,
            1,
            2,
            100,
            0,
            "Ａｓｓｅｓｓ　ｒｅｌｅａｓｅ"
        );
        assertEquals("Assess release", normalized.reason());
        assertEquals(
            "12345678",
            new AssessmentCommand(context, KEY, 1, 2, 100, 0, "12345678").reason()
        );
        assertEquals(
            512,
            new AssessmentCommand(context, KEY, 1, 2, 100, 0, "x".repeat(512))
                .reason()
                .codePointCount(0, 512)
        );

        Harness sameVersion = new Harness();
        assertThrows(IllegalArgumentException.class,
            () -> sameVersion.service().assess(new AssessmentCommand(
                context,
                KEY,
                1,
                1,
                100,
                0,
                validReason()
            )));

        Harness crossTenant = new Harness();
        assertThrows(MigrationEvidenceNotFoundException.class,
            () -> crossTenant.service().assess(new AssessmentCommand(
                context("tenant-other", "cross-tenant"),
                KEY,
                1,
                2,
                100,
                0,
                validReason()
            )));

        Harness missingRelease = new Harness();
        missingRelease.targetReleaseMissing = true;
        assertThrows(MigrationEvidenceNotFoundException.class,
            () -> missingRelease.service().assess(missingRelease.command("missing-release", 100)));

        Harness missingPackage = new Harness();
        missingPackage.targetPackageMissing = true;
        assertThrows(MigrationEvidenceNotFoundException.class,
            () -> missingPackage.service().assess(missingPackage.command("missing-package", 100)));
    }

    @Test
    void completePartialBlockedAndMixedResultsAreCountedExactly() {
        Harness empty = new Harness();
        empty.bindings.clear();
        empty.total = 0;
        var emptyResult = empty.service().assess(empty.command("empty", 100));
        assertEquals(AssessmentStatus.NO_IN_FLIGHT, emptyResult.status());
        assertEquals(0, emptyResult.runningCount());
        assertEquals(0, emptyResult.instances().size());

        Harness partial = new Harness();
        partial.project(partial.primary(), ApprovalProjectionStore.InstanceStatus.RUNNING,
            "managerApproval");
        partial.total = 2;
        var partialResult = partial.service().assess(partial.command("partial-ready", 1));
        assertEquals(AssessmentStatus.PARTIAL, partialResult.status());
        assertFalse(partialResult.complete());
        assertTrue(partialResult.hasMore());

        Harness offset = new Harness();
        offset.project(offset.primary(), ApprovalProjectionStore.InstanceStatus.RUNNING,
            "managerApproval");
        var offsetResult = offset.service().assess(new AssessmentCommand(
            context(TENANT, "offset-page"),
            KEY,
            1,
            2,
            100,
            1,
            validReason()
        ));
        assertEquals(AssessmentStatus.PARTIAL, offsetResult.status());
        assertFalse(offsetResult.complete());
        assertTrue(offsetResult.instances().isEmpty());

        Harness mixed = new Harness();
        ApprovalRuntimeBinding eligible = mixed.primary();
        ApprovalRuntimeBinding terminal = binding(mixed.sourcePackage, 2);
        ApprovalRuntimeBinding mismatched = mismatchedBinding(mixed.sourcePackage, 3);
        mixed.bindings.clear();
        mixed.bindings.addAll(List.of(eligible, terminal, mismatched));
        mixed.total = 3;
        mixed.project(eligible, ApprovalProjectionStore.InstanceStatus.RUNNING,
            "managerApproval");
        mixed.project(terminal, ApprovalProjectionStore.InstanceStatus.COMPLETED);
        mixed.project(mismatched, ApprovalProjectionStore.InstanceStatus.RUNNING,
            "managerApproval");

        var mixedResult = mixed.service().assess(mixed.command("mixed", 100));
        assertEquals(AssessmentStatus.BLOCKED, mixedResult.status());
        assertEquals(2, mixedResult.runningCount());
        assertEquals(1, mixedResult.eligibleCount());
        assertEquals(1, mixedResult.blockedCount());
        assertEquals(1, mixedResult.terminalCount());
        assertTrue(hasFinding(mixedResult.instances().get(2).findings(),
            "SOURCE_BINDING_MISMATCH"));
    }

    @Test
    void targetDeploymentAndTaskTransitionEvidenceFailClosed() {
        Harness deploymentMissing = new Harness();
        deploymentMissing.targetDeployment = null;
        deploymentMissing.project(
            deploymentMissing.primary(),
            ApprovalProjectionStore.InstanceStatus.RUNNING,
            "managerApproval"
        );
        var deploymentResult = deploymentMissing.service().assess(
            deploymentMissing.command("deployment-missing", 100)
        );
        assertEquals(AssessmentStatus.BLOCKED, deploymentResult.status());
        assertTrue(hasFinding(deploymentResult.globalFindings(), "TARGET_DEPLOYMENT_NOT_READY"));

        Harness transition = new Harness();
        transition.projectWithStatus(
            transition.primary(),
            ApprovalProjectionStore.InstanceStatus.RUNNING,
            ApprovalProjectionStore.TaskStatus.COMPLETING,
            "managerApproval"
        );
        var transitionResult = transition.service().assess(
            transition.command("transition-in-progress", 100)
        );
        assertEquals(AssessmentStatus.BLOCKED, transitionResult.status());
        assertTrue(hasFinding(transitionResult.instances().getFirst().findings(),
            "TASK_TRANSITION_IN_PROGRESS"));
    }

    @Test
    void reportHashIsDeterministicAndChangesWithPlatformState() {
        Harness harness = new Harness();
        harness.project(harness.primary(), ApprovalProjectionStore.InstanceStatus.RUNNING,
            "managerApproval");

        var first = harness.service().assess(harness.command("hash-one", 100));
        var sameState = harness.service().assess(harness.command("hash-two", 100));
        assertNotEquals(first.assessmentId(), sameState.assessmentId());
        assertEquals(first.reportHash(), sameState.reportHash());

        harness.projectWithStatus(
            harness.primary(),
            ApprovalProjectionStore.InstanceStatus.RUNNING,
            ApprovalProjectionStore.TaskStatus.COMPLETING,
            "managerApproval"
        );
        var changedState = harness.service().assess(harness.command("hash-three", 100));
        assertNotEquals(first.reportHash(), changedState.reportHash());
        assertEquals(AssessmentStatus.BLOCKED, changedState.status());
    }

    private static boolean hasFinding(
        List<ApprovalProcessReleaseMigrationAssessmentService.Finding> findings,
        String code
    ) {
        return findings.stream().anyMatch(finding -> finding.code().equals(code));
    }

    private static RequestContext context(String tenant, String key) {
        return new RequestContext(tenant, "operator", "request-" + key, key, "trace");
    }

    private static String validReason() {
        return "Assess compatibility without runtime mutation";
    }

    private static final class Harness {
        private ApprovalReleasePackage sourcePackage = releasePackage(1, 1, "a");
        private ApprovalReleasePackage targetPackage = releasePackage(2, 2, "b");
        private ApprovalProcessRelease sourceLifecycle = lifecycle(1, State.DEPRECATED);
        private ApprovalProcessRelease targetLifecycle = lifecycle(2, State.ACTIVE);
        private ApprovalDefinitionVersion sourceDefinition = definition(1, true);
        private ApprovalDefinitionVersion targetDefinition = definition(2, true);
        private ApprovalReleaseDeployment targetDeployment = deployment(targetPackage);
        private final List<ApprovalRuntimeBinding> bindings = new ArrayList<>();
        private final Map<UUID, ApprovalProjectionStore.InstanceProjection> instances =
            new HashMap<>();
        private final Map<UUID, List<ApprovalProjectionStore.TaskProjection>> taskMap =
            new HashMap<>();
        private final Map<String, Object> replay = new HashMap<>();
        private final List<AuditEvent> audit = new ArrayList<>();
        private long total;
        private boolean mutated;
        private boolean targetReleaseMissing;
        private boolean targetPackageMissing;
        private int sequence;

        private Harness() {
            bindings.add(binding(sourcePackage, 1));
            total = 1;
        }

        private ApprovalRuntimeBinding primary() {
            return bindings.getFirst();
        }

        private void project(
            ApprovalRuntimeBinding binding,
            ApprovalProjectionStore.InstanceStatus status,
            String... taskKeys
        ) {
            projectWithStatus(
                binding,
                status,
                ApprovalProjectionStore.TaskStatus.PENDING,
                taskKeys
            );
        }

        private void projectWithStatus(
            ApprovalRuntimeBinding binding,
            ApprovalProjectionStore.InstanceStatus status,
            ApprovalProjectionStore.TaskStatus taskStatus,
            String... taskKeys
        ) {
            instances.put(binding.approvalInstanceId(), instance(binding, status));
            taskMap.put(
                binding.approvalInstanceId(),
                java.util.Arrays.stream(taskKeys)
                    .map(key -> task(binding, key, taskStatus))
                    .toList()
            );
        }

        private ApprovalProcessReleaseMigrationAssessmentService service() {
            return new ApprovalProcessReleaseMigrationAssessmentService(
                idempotency(), port(ApprovalProcessReleaseStore.class),
                port(ApprovalReleasePackageStore.class), port(ApprovalReleaseDeploymentStore.class),
                port(ApprovalDefinitionVersionStore.class), port(ApprovalRuntimeBindingStore.class),
                port(ApprovalProjectionStore.class), audit::add, new ApprovalReleaseStructuralDiff(),
                new ApprovalReleasePackageHasher(), Clock.fixed(NOW, ZoneOffset.UTC),
                () -> new UUID(0, ++sequence)
            );
        }

        private AssessmentCommand command(String key, int limit) {
            return new AssessmentCommand(
                context(TENANT, key),
                KEY,
                1,
                2,
                limit,
                0,
                validReason()
            );
        }

        private IdempotencyGuard idempotency() {
            return new IdempotencyGuard() {
                @SuppressWarnings("unchecked")
                public <T> T execute(
                    RequestContext context, String operation, String hash,
                    Class<T> type, Supplier<T> action
                ) {
                    return (T) replay.computeIfAbsent(
                        context.idempotencyKey() + ':' + operation,
                        ignored -> action.get()
                    );
                }
            };
        }

        @SuppressWarnings("unchecked")
        private <T> T port(Class<T> type) {
            return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> invoke(type, method.getName(), args)
            );
        }

        private Object invoke(Class<?> type, String method, Object[] supplied) {
            Object[] args = supplied == null ? new Object[0] : supplied;
            if (method.equals("toString")) return type.getSimpleName() + "Proxy";
            if (type == ApprovalProcessReleaseStore.class && method.equals("find")) {
                if (!TENANT.equals(args[0])) return Optional.empty();
                int version = (int) args[2];
                if (version == 2 && targetReleaseMissing) return Optional.empty();
                return Optional.of(version == 1 ? sourceLifecycle : targetLifecycle);
            }
            if (type == ApprovalReleasePackageStore.class && method.equals("find")) {
                if (!TENANT.equals(args[0])) return Optional.empty();
                int version = (int) args[2];
                if (version == 2 && targetPackageMissing) return Optional.empty();
                return Optional.of(version == 1 ? sourcePackage : targetPackage);
            }
            if (type == ApprovalReleaseDeploymentStore.class && method.equals("find")) {
                if (!TENANT.equals(args[0])) return Optional.empty();
                return Optional.ofNullable(targetDeployment);
            }
            if (type == ApprovalDefinitionVersionStore.class && method.equals("find")) {
                if (!TENANT.equals(args[0])) return Optional.empty();
                return Optional.of(((int) args[2]) == 1 ? sourceDefinition : targetDefinition);
            }
            if (type == ApprovalRuntimeBindingStore.class && method.equals("findByRelease")) {
                var criteria = (ApprovalRuntimeBindingStore.BindingCriteria) args[0];
                List<ApprovalRuntimeBinding> page = bindings.stream()
                    .skip(criteria.offset())
                    .limit(criteria.limit())
                    .toList();
                return new ApprovalRuntimeBindingStore.BindingPage(
                    page,
                    total,
                    criteria.limit(),
                    criteria.offset()
                );
            }
            if (type == ApprovalProjectionStore.class && method.equals("findInstance")) {
                return Optional.ofNullable(instances.get((UUID) args[1]));
            }
            if (type == ApprovalProjectionStore.class && method.equals("findTasks")) {
                return taskMap.getOrDefault((UUID) args[1], List.of());
            }
            if (method.startsWith("find")) return Optional.empty();
            mutated = true;
            throw new AssertionError("dry-run called mutation port: " + method);
        }
    }

    private static ApprovalProjectionStore.InstanceProjection instance(
        ApprovalRuntimeBinding binding,
        ApprovalProjectionStore.InstanceStatus status
    ) {
        return new ApprovalProjectionStore.InstanceProjection(
            binding.approvalInstanceId(), binding.tenantId(), binding.businessKey(),
            binding.engineInstanceId(), binding.definitionKey(), binding.definitionVersion(), KEY,
            binding.formVersion(), binding.compilerVersion(), binding.definitionHash(),
            binding.releaseVersion(), binding.releasePackageHash(),
            binding.formPackageVersion(), binding.formPackageHash(),
            binding.uiSchemaVersion(), binding.uiSchemaHash(),
            binding.engineDefinitionId(), "initiator", BigDecimal.ONE, "supplier", "po",
            List.of(), null, "request-hash", status, 1, NOW.minusSeconds(60), NOW
        );
    }

    private static ApprovalProjectionStore.TaskProjection task(
        ApprovalRuntimeBinding binding,
        String key,
        ApprovalProjectionStore.TaskStatus status
    ) {
        return new ApprovalProjectionStore.TaskProjection(
            UUID.nameUUIDFromBytes((binding.approvalInstanceId() + ":" + key).getBytes()),
            binding.approvalInstanceId(), binding.tenantId(),
            "engine-task-" + binding.approvalInstanceId(), key,
            "Manager approval", "manager", status,
            1, NOW.minusSeconds(30), NOW.minusSeconds(30), null
        );
    }

    private static ApprovalProcessRelease lifecycle(int version, State state) {
        Instant activated = state == State.PUBLISHED ? null : NOW.minusSeconds(120);
        Instant deprecated = state == State.DEPRECATED ? NOW.minusSeconds(60) : null;
        return new ApprovalProcessRelease(
            TENANT, KEY, version, (version == 1 ? "a" : "b").repeat(64), state, 2,
            "publisher", NOW.minusSeconds(300), activated, deprecated, null, "operator", NOW,
            "Lifecycle fixture reason", "key-" + version, "request", "trace", "audit"
        );
    }

    private static ApprovalDefinitionVersion definition(int version, boolean task) {
        List<ApprovalDefinition.ProcessNode> nodes = new ArrayList<>();
        nodes.add(new StartNode("start", "Start", task ? "managerApproval" : "end"));
        if (task) {
            nodes.add(new ApprovalStep(
                "managerApproval", "Manager approval",
                new AssigneeRule(AssigneeResolver.VARIABLE_USER, "manager", EmptyAssigneePolicy.FAIL),
                ApprovalMode.single(), "end"
            ));
        }
        nodes.add(new EndNode("end", "End"));
        ApprovalDefinition value = new ApprovalDefinition(
            ApprovalDefinition.CURRENT_SCHEMA_VERSION, KEY, version, "Purchase", "start", nodes
        );
        return new ApprovalDefinitionVersion(
            TENANT, KEY, version, hash(version), version, hash(version + 2), value,
            UUID.nameUUIDFromBytes(("definition-" + version).getBytes()), "publisher", NOW
        );
    }

    private static ApprovalReleasePackage releasePackage(int release, int definition, String id) {
        return new ApprovalReleasePackage(
            TENANT, KEY, release, definition, hash(definition), definition,
            hash(definition + 2), definition, hash(definition + 4), definition,
            hash(definition + 6), "compiler-v1", "process.bpmn20.xml", "<definitions/>",
            hash(definition + 8), hash(definition + 9), null, null,
            hash(definition + 1), id.repeat(64),
            UUID.nameUUIDFromBytes(("release-" + release).getBytes()), "publisher", NOW
        );
    }

    private static ApprovalRuntimeBinding binding(ApprovalReleasePackage release, int sequence) {
        return new ApprovalRuntimeBinding(
            TENANT,
            new UUID(0, sequence),
            "business-" + sequence,
            "engine-instance-" + sequence,
            KEY,
            release.releaseVersion(),
            release.packageHash(),
            release.definitionVersion(),
            release.definitionHash(),
            release.formPackageVersion(),
            release.formPackageHash(),
            release.formVersion(),
            release.formHash(),
            release.uiSchemaVersion(),
            release.uiSchemaHash(),
            release.compilerVersion(),
            release.compiledArtifactHash(),
            release.bpmnHash(),
            release.deploymentMetadataHash(),
            "engine-deployment-1",
            "engine-definition-1",
            1,
            hash(sequence + 11),
            "initiator",
            NOW,
            "request",
            "trace",
            "audit"
        );
    }

    private static ApprovalRuntimeBinding mismatchedBinding(
        ApprovalReleasePackage release,
        int sequence
    ) {
        ApprovalRuntimeBinding binding = binding(release, sequence);
        return new ApprovalRuntimeBinding(
            binding.tenantId(),
            binding.approvalInstanceId(),
            binding.businessKey(),
            binding.engineInstanceId(),
            binding.definitionKey(),
            binding.releaseVersion(),
            "c".repeat(64),
            binding.definitionVersion(),
            binding.definitionHash(),
            binding.formPackageVersion(),
            binding.formPackageHash(),
            binding.formVersion(),
            binding.formHash(),
            binding.uiSchemaVersion(),
            binding.uiSchemaHash(),
            binding.compilerVersion(),
            binding.compiledArtifactHash(),
            binding.bpmnHash(),
            binding.deploymentMetadataHash(),
            binding.engineDeploymentId(),
            binding.engineDefinitionId(),
            binding.engineVersion(),
            binding.bindingEvidenceHash(),
            binding.boundBy(),
            binding.boundAt(),
            binding.requestId(),
            binding.traceId(),
            binding.auditChainReference()
        );
    }

    private static ApprovalReleaseDeployment deployment(ApprovalReleasePackage release) {
        return new ApprovalReleaseDeployment(
            UUID.nameUUIDFromBytes(("deployment-" + release.releaseVersion()).getBytes()),
            TENANT, KEY, release.releaseVersion(), release.packageHash(),
            ApprovalReleaseDeployment.Status.DEPLOYED, 1, "engine-deployment-2",
            "engine-definition-2", 2, null, null, "operator", NOW, NOW, NOW
        );
    }

    private static String hash(int value) {
        return Integer.toHexString(Math.floorMod(value, 16)).repeat(64);
    }
}
