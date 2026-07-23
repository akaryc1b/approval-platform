package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ActionType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionIntent;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.IntentStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.AutomaticAction;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.PolicyStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaDurationMode;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstance;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaPolicyVersion;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTargetType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidatingApprovalSlaExecutionStoreTest {

    private static final String TENANT_ID = "tenant-validation";
    private static final String DEFINITION_KEY = "purchasePayment";
    private static final String TASK_KEY = "managerApproval";
    private static final UUID POLICY_ID = UUID.fromString(
        "82000000-0000-0000-0000-000000000001"
    );
    private static final UUID APPROVAL_ID = UUID.fromString(
        "82000000-0000-0000-0000-000000000002"
    );
    private static final UUID TASK_ID = UUID.fromString(
        "82000000-0000-0000-0000-000000000003"
    );
    private static final UUID SLA_ID = UUID.fromString(
        "82000000-0000-0000-0000-000000000004"
    );
    private static final Instant NOW = Instant.parse("2026-07-22T08:00:00Z");

    @Test
    void exactAndFallbackBindingsAreValidatedAndEnriched() {
        for (SlaTargetType instanceTarget : SlaTargetType.values()) {
            for (SlaTargetType policyTarget : allowedPolicyTargets(instanceTarget)) {
                SlaInstance instance = instance(instanceTarget, TASK_ID);
                AtomicReference<List<ExecutionIntent>> captured = new AtomicReference<>();
                ApprovalSlaExecutionStore store = validatingStore(
                    captured,
                    instance,
                    policy(policyTarget, DEFINITION_KEY, taskKey(policyTarget), true)
                );

                assertEquals(1, store.enqueue(List.of(intent(instance))));
                Map<String, Object> payload = captured.get().getFirst().payload();
                assertEquals(DEFINITION_KEY, payload.get("policyDefinitionKey"));
                assertEquals(policyTarget.name(), payload.get("policyTargetType"));
                assertEquals(7, payload.get("policyReleaseVersion"));
                assertEquals("d".repeat(64), payload.get("policyContentHash"));
                assertEquals(instance.dueAt().toString(), payload.get("authoritativeDueAt"));
            }
        }
    }

    @Test
    void mismatchedPolicyOrIntentIdentityFailsBeforeDelegate() {
        SlaInstance task = instance(SlaTargetType.TASK, TASK_ID);
        assertRejected(
            task,
            policy(SlaTargetType.TASK, "otherDefinition", TASK_KEY, true),
            intent(task)
        );
        assertRejected(
            task,
            policy(SlaTargetType.COLLABORATION_PARTICIPANT, DEFINITION_KEY, TASK_KEY, true),
            intent(task)
        );
        assertRejected(
            task,
            policy(SlaTargetType.TASK, DEFINITION_KEY, "otherTask", true),
            intent(task)
        );
        assertRejected(
            task,
            policy(SlaTargetType.TASK, DEFINITION_KEY, TASK_KEY, false),
            intent(task)
        );
        assertRejected(
            instance(SlaTargetType.TASK, UUID.randomUUID()),
            policy(SlaTargetType.TASK, DEFINITION_KEY, TASK_KEY, true),
            intent(task)
        );
    }

    private static void assertRejected(
        SlaInstance authoritative,
        SlaPolicyVersion policy,
        ExecutionIntent intent
    ) {
        AtomicReference<List<ExecutionIntent>> captured = new AtomicReference<>();
        ApprovalSlaExecutionStore store = validatingStore(
            captured,
            authoritative,
            policy
        );
        assertThrows(IllegalArgumentException.class, () -> store.enqueue(List.of(intent)));
        assertTrue(captured.get() == null);
    }

    private static ApprovalSlaExecutionStore validatingStore(
        AtomicReference<List<ExecutionIntent>> captured,
        SlaInstance instance,
        SlaPolicyVersion policy
    ) {
        ApprovalSlaExecutionStore executionDelegate = (ApprovalSlaExecutionStore)
            Proxy.newProxyInstance(
                ApprovalSlaExecutionStore.class.getClassLoader(),
                new Class<?>[]{ApprovalSlaExecutionStore.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("enqueue")) {
                        @SuppressWarnings("unchecked")
                        List<ExecutionIntent> values = (List<ExecutionIntent>) arguments[0];
                        captured.set(List.copyOf(values));
                        return values.size();
                    }
                    return defaultValue(proxy, method, arguments);
                }
            );
        ApprovalSlaStore slaDelegate = (ApprovalSlaStore) Proxy.newProxyInstance(
            ApprovalSlaStore.class.getClassLoader(),
            new Class<?>[]{ApprovalSlaStore.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "findInstance" -> Optional.of(instance);
                case "findPolicyVersion" -> Optional.of(policy);
                default -> defaultValue(proxy, method, arguments);
            }
        );
        return new ValidatingApprovalSlaExecutionStore(executionDelegate, slaDelegate);
    }

    private static Object defaultValue(Object proxy, Method method, Object[] arguments) {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == arguments[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "ValidationProxy";
                default -> null;
            };
        }
        if (method.getReturnType() == int.class) {
            return 0;
        }
        if (method.getReturnType() == long.class) {
            return 0L;
        }
        if (method.getReturnType() == boolean.class) {
            return false;
        }
        if (method.getReturnType() == Optional.class) {
            return Optional.empty();
        }
        if (method.getReturnType() == List.class) {
            return List.of();
        }
        return null;
    }

    private static List<SlaTargetType> allowedPolicyTargets(SlaTargetType targetType) {
        return switch (targetType) {
            case PROCESS -> List.of(SlaTargetType.PROCESS);
            case TASK -> List.of(SlaTargetType.TASK, SlaTargetType.PROCESS);
            case COLLABORATION_PARTICIPANT -> List.of(
                SlaTargetType.COLLABORATION_PARTICIPANT,
                SlaTargetType.TASK,
                SlaTargetType.PROCESS
            );
        };
    }

    private static String taskKey(SlaTargetType targetType) {
        return targetType == SlaTargetType.PROCESS ? null : TASK_KEY;
    }

    private static SlaInstance instance(SlaTargetType targetType, UUID taskId) {
        boolean process = targetType == SlaTargetType.PROCESS;
        return new SlaInstance(
            SLA_ID,
            TENANT_ID,
            APPROVAL_ID,
            process ? null : taskId,
            targetType == SlaTargetType.COLLABORATION_PARTICIPANT
                ? UUID.fromString("82000000-0000-0000-0000-000000000005")
                : null,
            DEFINITION_KEY,
            process ? null : TASK_KEY,
            targetType,
            POLICY_ID,
            1,
            null,
            null,
            "UTC",
            "owner-validation",
            "owner-validation",
            NOW,
            NOW.plus(Duration.ofHours(2)),
            null,
            NOW.plus(Duration.ofHours(2)),
            null,
            null,
            Duration.ZERO,
            null,
            null,
            SlaStatus.ACTIVE,
            0,
            "request-validation",
            "trace-validation",
            1,
            NOW,
            NOW
        );
    }

    private static SlaPolicyVersion policy(
        SlaTargetType targetType,
        String definitionKey,
        String taskKey,
        boolean immutable
    ) {
        return new SlaPolicyVersion(
            POLICY_ID,
            TENANT_ID,
            1,
            definitionKey,
            7,
            taskKey,
            targetType,
            SlaDurationMode.NATURAL_TIME,
            Duration.ofHours(2),
            null,
            null,
            null,
            null,
            0,
            Duration.ZERO,
            null,
            null,
            AutomaticAction.NONE,
            true,
            "d".repeat(64),
            immutable ? PolicyStatus.ACTIVE : PolicyStatus.DRAFT,
            immutable,
            immutable ? "publisher-validation" : null,
            immutable ? NOW.minus(Duration.ofHours(1)) : null,
            NOW.minus(Duration.ofHours(2)),
            NOW.minus(Duration.ofHours(1))
        );
    }

    private static ExecutionIntent intent(SlaInstance instance) {
        return new ExecutionIntent(
            UUID.fromString("82000000-0000-0000-0000-000000000006"),
            instance.tenantId(),
            instance.slaInstanceId(),
            instance.approvalInstanceId(),
            instance.taskId(),
            instance.collaborationParticipantId(),
            instance.policyId(),
            instance.policyVersion(),
            instance.calendarId(),
            instance.calendarVersion(),
            null,
            ActionType.OVERDUE,
            1,
            instance.overdueAt(),
            instance.overdueAt(),
            IntentStatus.READY,
            null,
            null,
            0,
            3,
            instance.overdueAt(),
            "validation-intent-1",
            Map.of("schemaVersion", 1),
            instance.responsibleUserId(),
            instance.requestId(),
            instance.traceId(),
            1,
            NOW,
            NOW,
            null,
            null,
            null,
            null,
            null
        );
    }
}
