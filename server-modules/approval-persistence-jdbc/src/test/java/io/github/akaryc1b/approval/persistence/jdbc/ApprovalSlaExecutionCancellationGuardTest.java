package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstance;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTargetType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTerminalReason;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalSlaExecutionCancellationGuardTest {

    private static final String TENANT_ID = "tenant-cancellation";
    private static final UUID TASK_ID = UUID.fromString(
        "83000000-0000-0000-0000-000000000001"
    );
    private static final Instant NOW = Instant.parse("2026-07-22T08:00:00Z");

    @Test
    void collaborationTerminationCancelsIntentWithoutTaskSlaAnchor() {
        SlaInstance collaboration = instance(
            "83000000-0000-0000-0000-000000000002",
            SlaTargetType.COLLABORATION_PARTICIPANT
        );
        List<UUID> cancelled = new ArrayList<>();
        AtomicInteger delegateCalls = new AtomicInteger();
        ApprovalSlaStore store = guard(
            List.of(collaboration),
            delegateCalls,
            cancelled,
            false
        );

        assertEquals(1, store.terminalCollaborationParticipantsByTask(
            TENANT_ID,
            TASK_ID,
            SlaTerminalReason.COLLABORATION_CANCELED,
            NOW
        ));
        assertEquals(1, delegateCalls.get());
        assertEquals(List.of(collaboration.slaInstanceId()), cancelled);
    }

    @Test
    void taskTerminationCancelsEveryTaskAttachedSla() {
        SlaInstance task = instance(
            "83000000-0000-0000-0000-000000000003",
            SlaTargetType.TASK
        );
        SlaInstance collaboration = instance(
            "83000000-0000-0000-0000-000000000004",
            SlaTargetType.COLLABORATION_PARTICIPANT
        );
        List<UUID> cancelled = new ArrayList<>();
        ApprovalSlaStore store = guard(
            List.of(task, collaboration),
            new AtomicInteger(),
            cancelled,
            false
        );

        assertEquals(2, store.terminalTask(
            TENANT_ID,
            TASK_ID,
            SlaTerminalReason.TASK_COMPLETED,
            NOW
        ));
        assertEquals(
            List.of(task.slaInstanceId(), collaboration.slaInstanceId()),
            cancelled
        );
    }

    @Test
    void delegateFailureDoesNotProduceCancellationEvidence() {
        List<UUID> cancelled = new ArrayList<>();
        ApprovalSlaStore store = guard(
            List.of(instance(
                "83000000-0000-0000-0000-000000000005",
                SlaTargetType.COLLABORATION_PARTICIPANT
            )),
            new AtomicInteger(),
            cancelled,
            true
        );

        assertThrows(IllegalStateException.class, () -> store.terminalTask(
            TENANT_ID,
            TASK_ID,
            SlaTerminalReason.TASK_CANCELED,
            NOW
        ));
        assertEquals(List.of(), cancelled);
    }

    private static ApprovalSlaStore guard(
        List<SlaInstance> active,
        AtomicInteger delegateCalls,
        List<UUID> cancelled,
        boolean failDelegate
    ) {
        ApprovalSlaStore delegate = (ApprovalSlaStore) Proxy.newProxyInstance(
            ApprovalSlaStore.class.getClassLoader(),
            new Class<?>[]{ApprovalSlaStore.class},
            (proxy, method, arguments) -> {
                if (method.getName().startsWith("terminal")) {
                    delegateCalls.incrementAndGet();
                    if (failDelegate) {
                        throw new IllegalStateException("terminal store unavailable");
                    }
                    return active.size();
                }
                return defaultValue(proxy, method, arguments);
            }
        );
        ApprovalSlaExecutionStore executions = (ApprovalSlaExecutionStore)
            Proxy.newProxyInstance(
                ApprovalSlaExecutionStore.class.getClassLoader(),
                new Class<?>[]{ApprovalSlaExecutionStore.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("cancelActiveForSla")) {
                        cancelled.add((UUID) arguments[1]);
                        return 1;
                    }
                    return defaultValue(proxy, method, arguments);
                }
            );
        return ApprovalSlaExecutionCancellationGuard.wrap(
            delegate,
            (tenantId, taskId) -> active,
            executions,
            transactionManager()
        );
    }

    private static PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }

    private static Object defaultValue(Object proxy, Method method, Object[] arguments) {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == arguments[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "CancellationGuardProxy";
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

    private static SlaInstance instance(String id, SlaTargetType targetType) {
        return new SlaInstance(
            UUID.fromString(id),
            TENANT_ID,
            UUID.fromString("83000000-0000-0000-0000-000000000010"),
            TASK_ID,
            targetType == SlaTargetType.COLLABORATION_PARTICIPANT
                ? UUID.randomUUID()
                : null,
            "purchasePayment",
            "managerApproval",
            targetType,
            UUID.fromString("83000000-0000-0000-0000-000000000011"),
            1,
            null,
            null,
            "UTC",
            "owner-cancellation",
            "owner-cancellation",
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
            "request-cancellation",
            "trace-cancellation",
            1,
            NOW,
            NOW
        );
    }
}
