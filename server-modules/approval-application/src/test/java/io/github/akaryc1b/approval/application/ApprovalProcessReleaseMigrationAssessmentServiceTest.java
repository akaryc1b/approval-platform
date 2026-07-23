package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.AssessmentCommand;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.AssessmentStatus;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.InstanceDecision;
import io.github.akaryc1b.approval.application.port.*;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.*;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition.*;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalProcessReleaseMigrationAssessmentServiceTest {
    private static final String TENANT = "tenant-migration";
    private static final String KEY = "purchasePayment";
    private static final Instant NOW = Instant.parse("2026-07-23T01:00:00Z");

    @Test
    void readyReplayAndTerminalAssessmentRemainDetectOnly() {
        Harness ready = new Harness();
        ready.status = ApprovalProjectionStore.InstanceStatus.RUNNING;
        ready.tasks = List.of(ready.task("managerApproval"));
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
        terminal.status = ApprovalProjectionStore.InstanceStatus.COMPLETED;
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
        removed.status = ApprovalProjectionStore.InstanceStatus.RUNNING;
        removed.tasks = List.of(removed.task("managerApproval"));
        var removedResult = removed.service().assess(removed.command("removed", 100));
        assertEquals(AssessmentStatus.BLOCKED, removedResult.status());
        assertTrue(hasFinding(removedResult.instances().getFirst().findings(),
            "TARGET_TASK_NODE_MISSING"));

        Harness blocked = new Harness();
        blocked.sourceLifecycle = lifecycle(1, State.ACTIVE);
        blocked.targetLifecycle = lifecycle(2, State.PUBLISHED);
        blocked.status = null;
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

    private static boolean hasFinding(
        List<ApprovalProcessReleaseMigrationAssessmentService.Finding> findings,
        String code
    ) {
        return findings.stream().anyMatch(finding -> finding.code().equals(code));
    }

    private static final class Harness {
        private final ApprovalReleasePackage sourcePackage = releasePackage(1, 1, "a");
        private final ApprovalReleasePackage targetPackage = releasePackage(2, 2, "b");
        private ApprovalProcessRelease sourceLifecycle = lifecycle(1, State.DEPRECATED);
        private ApprovalProcessRelease targetLifecycle = lifecycle(2, State.ACTIVE);
        private final ApprovalDefinitionVersion sourceDefinition = definition(1, true);
        private ApprovalDefinitionVersion targetDefinition = definition(2, true);
        private final ApprovalRuntimeBinding binding = binding(sourcePackage);
        private final Map<String, Object> replay = new HashMap<>();
        private final List<AuditEvent> audit = new ArrayList<>();
        private ApprovalProjectionStore.InstanceStatus status;
        private List<ApprovalProjectionStore.TaskProjection> tasks = List.of();
        private long total = 1;
        private boolean mutated;
        private int sequence;

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
                new RequestContext(TENANT, "operator", "request-" + key, key, "trace"),
                KEY, 1, 2, limit, 0,
                "Assess compatibility without runtime mutation"
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
                return Optional.of(((int) args[2]) == 1 ? sourceLifecycle : targetLifecycle);
            }
            if (type == ApprovalReleasePackageStore.class && method.equals("find")) {
                return Optional.of(((int) args[2]) == 1 ? sourcePackage : targetPackage);
            }
            if (type == ApprovalReleaseDeploymentStore.class && method.equals("find")) {
                return Optional.of(deployment(targetPackage));
            }
            if (type == ApprovalDefinitionVersionStore.class && method.equals("find")) {
                return Optional.of(((int) args[2]) == 1 ? sourceDefinition : targetDefinition);
            }
            if (type == ApprovalRuntimeBindingStore.class && method.equals("findByRelease")) {
                var criteria = (ApprovalRuntimeBindingStore.BindingCriteria) args[0];
                return new ApprovalRuntimeBindingStore.BindingPage(
                    List.of(binding), total, criteria.limit(), criteria.offset()
                );
            }
            if (type == ApprovalProjectionStore.class && method.equals("findInstance")) {
                return status == null ? Optional.empty() : Optional.of(instance(status));
            }
            if (type == ApprovalProjectionStore.class && method.equals("findTasks")) return tasks;
            if (method.startsWith("find")) return Optional.empty();
            mutated = true;
            throw new AssertionError("dry-run called mutation port: " + method);
        }

        private ApprovalProjectionStore.InstanceProjection instance(
            ApprovalProjectionStore.InstanceStatus value
        ) {
            return new ApprovalProjectionStore.InstanceProjection(
                binding.approvalInstanceId(), TENANT, binding.businessKey(),
                binding.engineInstanceId(), KEY, binding.definitionVersion(), KEY,
                binding.formVersion(), binding.compilerVersion(), binding.definitionHash(),
                binding.releaseVersion(), binding.releasePackageHash(),
                binding.formPackageVersion(), binding.formPackageHash(),
                binding.uiSchemaVersion(), binding.uiSchemaHash(),
                binding.engineDefinitionId(), "initiator", BigDecimal.ONE, "supplier", "po",
                List.of(), null, "request-hash", value, 1, NOW.minusSeconds(60), NOW
            );
        }

        private ApprovalProjectionStore.TaskProjection task(String key) {
            return new ApprovalProjectionStore.TaskProjection(
                UUID.randomUUID(), binding.approvalInstanceId(), TENANT, "engine-task", key,
                "Manager approval", "manager", ApprovalProjectionStore.TaskStatus.PENDING,
                1, NOW.minusSeconds(30), NOW.minusSeconds(30), null
            );
        }
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
            UUID.randomUUID(), "publisher", NOW
        );
    }

    private static ApprovalReleasePackage releasePackage(int release, int definition, String id) {
        return new ApprovalReleasePackage(
            TENANT, KEY, release, definition, hash(definition), definition,
            hash(definition + 2), definition, hash(definition + 4), definition,
            hash(definition + 6), "compiler-v1", "process.bpmn20.xml", "<definitions/>",
            hash(definition + 8), hash(definition + 9), null, null,
            hash(definition + 1), id.repeat(64), UUID.randomUUID(), "publisher", NOW
        );
    }

    private static ApprovalRuntimeBinding binding(ApprovalReleasePackage release) {
        return new ApprovalRuntimeBinding(
            TENANT, UUID.randomUUID(), "business", "engine-instance", KEY,
            release.releaseVersion(), release.packageHash(), release.definitionVersion(),
            release.definitionHash(), release.formPackageVersion(), release.formPackageHash(),
            release.formVersion(), release.formHash(), release.uiSchemaVersion(),
            release.uiSchemaHash(), release.compilerVersion(), release.compiledArtifactHash(),
            release.bpmnHash(), release.deploymentMetadataHash(), "engine-deployment-1",
            "engine-definition-1", 1, "f".repeat(64), "initiator", NOW,
            "request", "trace", "audit"
        );
    }

    private static ApprovalReleaseDeployment deployment(ApprovalReleasePackage release) {
        return new ApprovalReleaseDeployment(
            UUID.randomUUID(), TENANT, KEY, release.releaseVersion(), release.packageHash(),
            ApprovalReleaseDeployment.Status.DEPLOYED, 1, "engine-deployment-2",
            "engine-definition-2", 2, null, null, "operator", NOW, NOW, NOW
        );
    }

    private static String hash(int value) {
        return Integer.toHexString(Math.floorMod(value, 16)).repeat(64);
    }
}
