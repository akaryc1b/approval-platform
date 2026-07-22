package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalSlaActiveTaskQuery;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstance;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTargetType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTerminalReason;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Adds authoritative task-wide execution cancellation around terminal SLA operations. */
public final class ApprovalSlaExecutionCancellationGuard implements InvocationHandler {

    private static final String TERMINAL_TASK = "terminalTask";
    private static final String TERMINAL_COLLABORATION =
        "terminalCollaborationParticipantsByTask";

    private final ApprovalSlaStore delegate;
    private final ApprovalSlaActiveTaskQuery activeTasks;
    private final ApprovalSlaExecutionStore executions;
    private final TransactionTemplate transactions;

    private ApprovalSlaExecutionCancellationGuard(
        ApprovalSlaStore delegate,
        ApprovalSlaActiveTaskQuery activeTasks,
        ApprovalSlaExecutionStore executions,
        PlatformTransactionManager transactionManager
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.activeTasks = Objects.requireNonNull(
            activeTasks,
            "activeTasks must not be null"
        );
        this.executions = Objects.requireNonNull(
            executions,
            "executions must not be null"
        );
        this.transactions = new TransactionTemplate(Objects.requireNonNull(
            transactionManager,
            "transactionManager must not be null"
        ));
    }

    public static ApprovalSlaStore wrap(
        ApprovalSlaStore delegate,
        ApprovalSlaActiveTaskQuery activeTasks,
        ApprovalSlaExecutionStore executions,
        PlatformTransactionManager transactionManager
    ) {
        ApprovalSlaExecutionCancellationGuard handler =
            new ApprovalSlaExecutionCancellationGuard(
                delegate,
                activeTasks,
                executions,
                transactionManager
            );
        return (ApprovalSlaStore) Proxy.newProxyInstance(
            ApprovalSlaStore.class.getClassLoader(),
            new Class<?>[]{ApprovalSlaStore.class},
            handler
        );
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) {
        if (method.getDeclaringClass() == Object.class) {
            return objectMethod(proxy, method, arguments);
        }
        if (method.getName().equals(TERMINAL_TASK)) {
            return terminal(method, arguments, false);
        }
        if (method.getName().equals(TERMINAL_COLLABORATION)) {
            return terminal(method, arguments, true);
        }
        return invokeDelegate(method, arguments);
    }

    private Object terminal(
        Method method,
        Object[] arguments,
        boolean collaborationOnly
    ) {
        return Objects.requireNonNull(transactions.execute(status -> {
            String tenantId = (String) arguments[0];
            UUID taskId = (UUID) arguments[1];
            SlaTerminalReason reason = (SlaTerminalReason) arguments[2];
            Instant terminalAt = (Instant) arguments[3];
            List<SlaInstance> affected = activeTasks.findActiveByTask(
                tenantId,
                taskId
            ).stream().filter(instance -> !collaborationOnly
                || instance.targetType() == SlaTargetType.COLLABORATION_PARTICIPANT)
                .toList();
            Object result = invokeDelegate(method, arguments);
            for (SlaInstance instance : affected) {
                executions.cancelActiveForSla(
                    instance.tenantId(),
                    instance.slaInstanceId(),
                    terminalAt,
                    "SLA_TERMINAL: " + reason.name()
                );
            }
            return result;
        }), "transaction result must not be null");
    }

    private Object invokeDelegate(Method method, Object[] arguments) {
        try {
            return method.invoke(delegate, arguments);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("SLA delegate invocation failed", cause);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("SLA delegate invocation failed", exception);
        }
    }

    private static Object objectMethod(
        Object proxy,
        Method method,
        Object[] arguments
    ) {
        return switch (method.getName()) {
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "ApprovalSlaExecutionCancellationGuard";
            default -> null;
        };
    }
}
