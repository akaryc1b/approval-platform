package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityReference;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.CollaborationMode;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.CollaborationParticipant;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.CollaborationStatus;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.ParticipantStatus;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.TaskCollaboration;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCompletionGuard.TaskOutcome;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CollaborationAwareApprovalProjectionStoreTest {

    private static final Instant NOW = Instant.parse("2026-07-21T05:00:00Z");

    @Test
    void rejectedParentCompletionCancelsActiveCollaborationAfterProjectionCommit() {
        UUID instanceId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskCollaboration active = activeCollaboration(instanceId, taskId);
        List<String> events = new ArrayList<>();
        AtomicReference<String> canceledBy = new AtomicReference<>();
        AtomicReference<String> terminalReason = new AtomicReference<>();

        ApprovalProjectionStore delegate = proxy(ApprovalProjectionStore.class, (target, method, args) -> {
            if ("completeTaskAndSynchronize".equals(method.getName())) {
                events.add("projection-completed");
                return null;
            }
            throw new AssertionError("unexpected projection call: " + method.getName());
        });
        ApprovalTaskCollaborationStore collaborations = proxy(
            ApprovalTaskCollaborationStore.class,
            (target, method, args) -> {
                if ("findByTask".equals(method.getName())) {
                    return Optional.of(active);
                }
                if ("cancelActiveByTask".equals(method.getName())) {
                    events.add("collaboration-canceled");
                    canceledBy.set((String) args[2]);
                    terminalReason.set((String) args[3]);
                    return Optional.of(active);
                }
                throw new AssertionError("unexpected collaboration call: " + method.getName());
            }
        );
        ApprovalTaskOutcomeContext outcomes = new ApprovalTaskOutcomeContext();
        outcomes.set(TaskOutcome.REJECTED);
        CollaborationAwareApprovalProjectionStore guarded =
            new CollaborationAwareApprovalProjectionStore(delegate, collaborations, outcomes);

        guarded.completeTaskAndSynchronize(
            "tenant-a",
            instanceId,
            taskId,
            2,
            List.of(),
            InstanceStatus.RUNNING,
            NOW
        );

        assertEquals(
            List.of("projection-completed", "collaboration-canceled"),
            events
        );
        assertEquals("task-owner", canceledBy.get());
        assertEquals("parent task completed with outcome REJECTED", terminalReason.get());
    }

    @Test
    void approvedParentCompletionDoesNotCancelCollaboration() {
        List<String> events = new ArrayList<>();
        ApprovalProjectionStore delegate = proxy(ApprovalProjectionStore.class, (target, method, args) -> {
            if ("completeTaskAndSynchronize".equals(method.getName())) {
                events.add("projection-completed");
                return null;
            }
            throw new AssertionError("unexpected projection call: " + method.getName());
        });
        ApprovalTaskCollaborationStore collaborations = proxy(
            ApprovalTaskCollaborationStore.class,
            (target, method, args) -> {
                throw new AssertionError("collaboration must not be queried for approval");
            }
        );
        ApprovalTaskOutcomeContext outcomes = new ApprovalTaskOutcomeContext();
        outcomes.set(TaskOutcome.APPROVED);
        CollaborationAwareApprovalProjectionStore guarded =
            new CollaborationAwareApprovalProjectionStore(delegate, collaborations, outcomes);

        guarded.completeTaskAndSynchronize(
            "tenant-a",
            UUID.randomUUID(),
            UUID.randomUUID(),
            2,
            List.of(),
            InstanceStatus.COMPLETED,
            NOW
        );

        assertEquals(List.of("projection-completed"), events);
    }

    private static TaskCollaboration activeCollaboration(UUID instanceId, UUID taskId) {
        UUID policyId = UUID.randomUUID();
        CollaborationParticipant participant = new CollaborationParticipant(
            UUID.randomUUID(),
            policyId,
            "tenant-a",
            "participant-one",
            new IdentityReference("generic", "user", "participant-one"),
            1,
            ParticipantStatus.PENDING,
            "task-owner",
            NOW,
            null,
            null,
            null,
            null,
            null,
            null,
            1
        );
        return new TaskCollaboration(
            policyId,
            "tenant-a",
            taskId,
            instanceId,
            "engine-task",
            "engine-instance",
            "purchase-payment",
            "managerApproval",
            "Manager approval",
            "task-owner",
            CollaborationMode.ANY,
            null,
            null,
            CollaborationStatus.ACTIVE,
            "additional review required",
            "task-owner",
            NOW,
            null,
            null,
            null,
            1,
            List.of(participant)
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }
}
