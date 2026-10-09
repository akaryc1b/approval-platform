package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityReference;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalRequestEvidenceProvider.RequestEvidence;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstance;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTargetType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTerminalReason;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.CollaborationMode;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.CollaborationParticipant;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.CollaborationStatus;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.ParticipantStatus;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.TaskCollaboration;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Shared executable checks; normal JUnit tests run these same production paths. */
final class ApprovalSlaTerminalTimestampChecks {
    private static final String TENANT = "timestamp-tenant";
    private static final UUID INSTANCE = new UUID(1, 1);
    private static final UUID TASK = new UUID(2, 1);
    private static final UUID OTHER_TASK = new UUID(2, 2);
    private static final UUID PARTICIPANT = new UUID(3, 1);
    private static final UUID POLICY = new UUID(4, 1);
    private static final Instant START = Instant.parse("2026-07-22T08:00:00Z");
    private static final Instant END = START.plusSeconds(3600).plusNanos(123456789);
    private static final Instant LATE = END.plus(Duration.ofDays(5));
    private static final RequestEvidence EVIDENCE = new RequestEvidence("actor", "request", "trace");

    private ApprovalSlaTerminalTimestampChecks() { }

    public static void main(String[] args) {
        serviceTimesAndCompatibility();
        projectionTimesAndFailureOrder();
        participantTimesAndOrdering();
        collaborationTerminalTimes();
        rejectsInvalidEvidenceBeforeWrites();
        collaborationDecoratorUsesPersistedEvidence();
        System.out.println("SLA_TERMINAL_TIMESTAMP_CHECK_GROUPS_PASSED=6");
    }

    static void serviceTimesAndCompatibility() {
        for (InstanceStatus status : List.of(InstanceStatus.RUNNING, InstanceStatus.COMPLETED,
            InstanceStatus.REJECTED)) {
            Harness h = new Harness();
            h.service.synchronizeTaskChange(instance(LATE), TASK, List.of(), status, false, END, EVIDENCE);
            equal(new Terminal(status == InstanceStatus.RUNNING ? "terminalTask" : "terminalApprovalInstance",
                status == InstanceStatus.RUNNING ? TASK : INSTANCE,
                status == InstanceStatus.RUNNING ? SlaTerminalReason.TASK_COMPLETED
                    : status == InstanceStatus.COMPLETED ? SlaTerminalReason.INSTANCE_COMPLETED
                    : SlaTerminalReason.INSTANCE_REJECTED, END), h.calls.getFirst());
        }
        Harness canceled = new Harness();
        canceled.active = List.of(sla(TASK, null), sla(OTHER_TASK, null));
        canceled.service.synchronizeTaskChange(instance(LATE), TASK, List.of(),
            InstanceStatus.RUNNING, true, END, EVIDENCE);
        equal(List.of(new Terminal("terminalTask", TASK, SlaTerminalReason.TASK_CANCELED, END),
            new Terminal("terminalTask", OTHER_TASK, SlaTerminalReason.TASK_CANCELED, END)), canceled.calls);
        Harness h = new Harness();
        h.service.terminalWithdrawnInstance(instance(LATE), END);
        equal(new Terminal("terminalApprovalInstance", INSTANCE, SlaTerminalReason.INSTANCE_WITHDRAWN,
            END), h.calls.getFirst());
        h.calls.clear();
        h.service.synchronizeTaskChange(instance(END), TASK, List.of(), InstanceStatus.COMPLETED,
            false, EVIDENCE);
        h.service.terminalWithdrawnInstance(instance(END));
        equal(List.of(END, END), h.calls.stream().map(Terminal::at).toList());
        h.calls.clear();
        // An observation clock behind the event must not clamp or replace authoritative evidence.
        service(h.store, Clock.fixed(START, ZoneOffset.UTC)).terminalWithdrawnInstance(instance(END), END);
        equal(END, h.calls.getFirst().at());
    }

    static void projectionTimesAndFailureOrder() {
        for (InstanceStatus status : List.of(InstanceStatus.RUNNING, InstanceStatus.COMPLETED,
            InstanceStatus.REJECTED)) {
            Harness h = new Harness();
            List<String> order = new ArrayList<>();
            ApprovalProjectionStore delegate = projection(h, order, false);
            SlaAwareApprovalProjectionStore decorated = new SlaAwareApprovalProjectionStore(delegate,
                h.service, () -> EVIDENCE);
            decorated.completeTaskAndSynchronize(TENANT, INSTANCE, TASK, 2, List.of(), status, END);
            equal(List.of("completeTaskAndSynchronize", "findInstance"), order);
            equal(END, h.calls.getFirst().at());
        }
        Harness h = new Harness();
        SlaAwareApprovalProjectionStore decorated = new SlaAwareApprovalProjectionStore(
            projection(h, new ArrayList<>(), false), h.service, () -> EVIDENCE);
        decorated.cancelClaimedTaskAndSynchronize(TENANT, INSTANCE, TASK, 2, List.of(), END);
        equal(SlaTerminalReason.TASK_CANCELED, h.calls.getFirst().reason());
        equal(END, h.calls.getFirst().at());
        h.calls.clear();
        decorated.withdrawRunningInstance(TENANT, INSTANCE, "initiator", END);
        equal(END, h.calls.getFirst().at());
        h.calls.clear();
        SlaAwareApprovalProjectionStore failed = new SlaAwareApprovalProjectionStore(
            projection(h, new ArrayList<>(), true), h.service, () -> EVIDENCE);
        fails(IllegalStateException.class, () -> failed.completeTaskAndSynchronize(TENANT, INSTANCE,
            TASK, 2, List.of(), InstanceStatus.COMPLETED, END));
        equal(List.of(), h.calls);
    }

    static void participantTimesAndOrdering() {
        List<CollaborationParticipant> participants = new ArrayList<>();
        int i = 0;
        for (ParticipantStatus status : List.of(ParticipantStatus.APPROVED, ParticipantStatus.REJECTED,
            ParticipantStatus.REMOVED, ParticipantStatus.CANCELED)) {
            participants.add(participant(new UUID(3, ++i), status, END.plusSeconds(i)));
        }
        Harness h = new Harness();
        h.service.synchronizeCollaboration(collaboration(CollaborationStatus.ACTIVE, null, participants), EVIDENCE);
        equal(List.of(SlaTerminalReason.COLLABORATION_DECIDED, SlaTerminalReason.COLLABORATION_DECIDED,
            SlaTerminalReason.COLLABORATION_REMOVED, SlaTerminalReason.COLLABORATION_CANCELED),
            h.calls.stream().map(Terminal::reason).toList());
        equal(List.of(END.plusSeconds(1), END.plusSeconds(2), END.plusSeconds(3), END.plusSeconds(4)),
            h.calls.stream().map(Terminal::at).toList());
        List<Terminal> first = List.copyOf(h.calls);
        h.calls.clear();
        h.service.synchronizeCollaboration(collaboration(CollaborationStatus.ACTIVE, null,
            participants.reversed()), EVIDENCE);
        equal(first.reversed(), h.calls);
        h.calls.clear();
        h.service.synchronizeCollaboration(collaboration(CollaborationStatus.ACTIVE, null, participants), EVIDENCE);
        equal(first, h.calls); // Replays pass the original evidence; JDBC owns the active-only fence.
    }

    static void collaborationTerminalTimes() {
        for (CollaborationStatus status : List.of(CollaborationStatus.SATISFIED,
            CollaborationStatus.REJECTED, CollaborationStatus.CANCELED)) {
            Harness h = new Harness();
            h.service.synchronizeCollaboration(collaboration(status, END,
                List.of(participant(PARTICIPANT, ParticipantStatus.CANCELED, END))), EVIDENCE);
            // Retain the existing aggregate cancellation reason for the participants still active.
            equal(List.of(new Terminal("terminalCollaborationParticipantsByTask", TASK,
                SlaTerminalReason.COLLABORATION_CANCELED, END)), h.calls);
            equal(0, h.created);
        }
    }

    static void rejectsInvalidEvidenceBeforeWrites() {
        Harness h = new Harness();
        fails(NullPointerException.class, () -> h.service.synchronizeTaskChange(instance(END), TASK,
            List.of(), InstanceStatus.COMPLETED, false, null, EVIDENCE));
        fails(NullPointerException.class, () -> h.service.terminalWithdrawnInstance(instance(END), null));
        fails(IllegalArgumentException.class, () -> h.service.synchronizeTaskChange(instance(END), TASK,
            List.of(), InstanceStatus.RUNNING, false, START.minusNanos(1), EVIDENCE));
        fails(IllegalArgumentException.class, () -> h.service.terminalWithdrawnInstance(instance(END),
            START.minusNanos(1)));
        for (ParticipantStatus status : List.of(ParticipantStatus.APPROVED, ParticipantStatus.REJECTED,
            ParticipantStatus.REMOVED, ParticipantStatus.CANCELED)) {
            fails(IllegalArgumentException.class, () -> h.service.synchronizeCollaboration(
                collaboration(CollaborationStatus.ACTIVE, null, List.of(
                    participant(PARTICIPANT, ParticipantStatus.APPROVED, END),
                    participant(new UUID(3, 2), status, START.minusNanos(1)))), EVIDENCE));
            fails(IllegalArgumentException.class, () -> participant(PARTICIPANT, status, null));
        }
        fails(IllegalArgumentException.class, () -> h.service.synchronizeCollaboration(
            collaboration(CollaborationStatus.CANCELED, START.minusNanos(1),
                List.of(participant(PARTICIPANT, ParticipantStatus.CANCELED, END))), EVIDENCE));
        equal(List.of(), h.calls);
        equal(0, h.created);
        h.active = List.of(sla(TASK, new UUID(3, 99)));
        fails(ApprovalSlaService.ApprovalSlaException.class, () -> h.service.synchronizeCollaboration(
            collaboration(CollaborationStatus.ACTIVE, null,
                List.of(participant(PARTICIPANT, ParticipantStatus.APPROVED, END))), EVIDENCE));
        equal(List.of(), h.calls);
        equal(0, h.created);
        // The process can predate its tasks: validate every affected target before any write.
        h.active = List.of(sla(TASK, null, END.plusSeconds(1)));
        for (InstanceStatus status : List.of(InstanceStatus.RUNNING, InstanceStatus.COMPLETED,
            InstanceStatus.REJECTED)) {
            fails(IllegalArgumentException.class, () -> h.service.synchronizeTaskChange(instance(END),
                TASK, List.of(), status, false, END, EVIDENCE));
        }
        fails(IllegalArgumentException.class, () -> h.service.terminalWithdrawnInstance(instance(END), END));
        h.active = List.of(sla(TASK, PARTICIPANT, END.plusSeconds(1)));
        for (CollaborationStatus status : List.of(CollaborationStatus.ACTIVE, CollaborationStatus.CANCELED)) {
            fails(IllegalArgumentException.class, () -> h.service.synchronizeCollaboration(
                collaboration(status, status == CollaborationStatus.ACTIVE ? null : END,
                    List.of(participant(PARTICIPANT, ParticipantStatus.APPROVED, END))), EVIDENCE));
        }
        equal(List.of(), h.calls);
        h.active = List.of();
        // Zero elapsed is valid; it is not a missing timestamp.
        h.service.terminalWithdrawnInstance(instance(END), START);
        equal(START, h.calls.getFirst().at());
    }

    static void collaborationDecoratorUsesPersistedEvidence() {
        for (ParticipantStatus status : List.of(ParticipantStatus.APPROVED, ParticipantStatus.REMOVED,
            ParticipantStatus.CANCELED)) {
            Harness h = new Harness();
            TaskCollaboration persisted = collaboration(status == ParticipantStatus.CANCELED
                ? CollaborationStatus.CANCELED : CollaborationStatus.ACTIVE,
                status == ParticipantStatus.CANCELED ? END : null,
                List.of(participant(PARTICIPANT, status, END)));
            ApprovalTaskCollaborationStore delegate = (ApprovalTaskCollaborationStore) Proxy.newProxyInstance(
                ApprovalTaskCollaborationStore.class.getClassLoader(),
                new Class<?>[]{ApprovalTaskCollaborationStore.class}, (proxy, method, args) ->
                    method.getName().equals("cancelActiveByTask") ? Optional.of(persisted) : persisted);
            SlaAwareApprovalTaskCollaborationStore decorated = new SlaAwareApprovalTaskCollaborationStore(
                delegate, h.service, () -> EVIDENCE);
            if (status == ParticipantStatus.APPROVED) {
                decorated.decideParticipant(TENANT, PARTICIPANT, "user",
                    ApprovalTaskCollaborationStore.ParticipantDecision.APPROVED, "approved", LATE);
            } else if (status == ParticipantStatus.REMOVED) {
                decorated.removeParticipant(TENANT, PARTICIPANT, "actor", "removed", LATE);
            } else {
                decorated.cancelActiveByTask(TENANT, TASK, "actor", "canceled", LATE);
            }
            equal(END, h.calls.getFirst().at());
        }
    }

    private static ApprovalProjectionStore projection(Harness h, List<String> order, boolean fail) {
        return (ApprovalProjectionStore) Proxy.newProxyInstance(ApprovalProjectionStore.class.getClassLoader(),
            new Class<?>[]{ApprovalProjectionStore.class}, (proxy, method, args) -> {
                order.add(method.getName());
                if (method.getName().equals("findInstance")) return Optional.of(instance(LATE));
                if (fail) throw new IllegalStateException("projection write failed");
                equal(END, args[args.length - 1]);
                equal(List.of(), h.calls); // SLA side effects must follow the business write.
                return null;
            });
    }

    private static ApprovalSlaService service(ApprovalSlaStore store, Clock clock) {
        return new ApprovalSlaService(store, new ApprovalWorkingTimeCalculator(), clock,
            () -> new UUID(9, 1));
    }

    private static InstanceProjection instance(Instant updatedAt) {
        return new InstanceProjection(INSTANCE, TENANT, "business", "engine", "definition", 1,
            "form", 1, "compiler", "hash", "initiator", BigDecimal.TEN, "supplier", "po", List.of(),
            new AssigneeSnapshot("manager", "reviewer", List.of("approver"), Map.of()), "request-hash",
            InstanceStatus.RUNNING, 2, START, updatedAt);
    }

    private static CollaborationParticipant participant(UUID id, ParticipantStatus status, Instant end) {
        boolean decided = status == ParticipantStatus.APPROVED || status == ParticipantStatus.REJECTED;
        boolean removed = status == ParticipantStatus.REMOVED;
        return new CollaborationParticipant(id, POLICY, TENANT, "user-" + id,
            new IdentityReference("local", "user", "user-" + id), 1, status, "actor", START,
            decided ? "decision" : null, decided ? end : null, removed ? "actor" : null,
            removed ? end : null, removed ? "removed" : null,
            status == ParticipantStatus.CANCELED ? end : null, 2);
    }

    private static TaskCollaboration collaboration(CollaborationStatus status, Instant end,
        List<CollaborationParticipant> participants) {
        boolean terminal = status != CollaborationStatus.ACTIVE;
        return new TaskCollaboration(POLICY, TENANT, TASK, INSTANCE, "engine-task", "engine-instance",
            "definition", "task", "Task", "owner", CollaborationMode.ALL, null, null, status,
            "collaboration", "actor", START, terminal ? "actor" : null, end,
            terminal ? "terminal" : null, 2, participants);
    }

    private static SlaInstance sla(UUID taskId, UUID participantId) {
        return sla(taskId, participantId, START);
    }

    private static SlaInstance sla(UUID taskId, UUID participantId, Instant startedAt) {
        return new SlaInstance(new UUID(5, 1), TENANT, INSTANCE, taskId, participantId, "definition", "task",
            participantId == null ? SlaTargetType.TASK : SlaTargetType.COLLABORATION_PARTICIPANT,
            POLICY, 1, null, null, "UTC", "owner", "owner", startedAt, LATE, null, LATE,
            null, null, Duration.ZERO, null, null, SlaStatus.ACTIVE, 0, "request", "trace", 1, START, START);
    }

    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }

    private static void fails(Class<? extends Throwable> expected, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (expected.isInstance(failure)) return;
            throw new AssertionError("Expected " + expected.getName(), failure);
        }
        throw new AssertionError("Expected " + expected.getName());
    }

    private record Terminal(String method, UUID target, SlaTerminalReason reason, Instant at) { }

    private static final class Harness {
        private final List<Terminal> calls = new ArrayList<>();
        private List<SlaInstance> active = List.of();
        private int created;
        private final ApprovalSlaStore store = (ApprovalSlaStore) Proxy.newProxyInstance(
            ApprovalSlaStore.class.getClassLoader(), new Class<?>[]{ApprovalSlaStore.class},
            (proxy, method, args) -> {
                if (method.getName().startsWith("terminal")) {
                    calls.add(new Terminal(method.getName(), (UUID) args[1], (SlaTerminalReason) args[2],
                        (Instant) args[3]));
                    return 1;
                }
                if (method.getName().equals("findActiveByApprovalInstance")) return active;
                if (method.getName().equals("createInstances")) {
                    created += ((List<?>) args[0]).size();
                    return 0;
                }
                if (method.getReturnType() == Optional.class) return Optional.empty();
                throw new AssertionError("Unexpected store call: " + method.getName());
            });
        private final ApprovalSlaService service = service(store, Clock.fixed(LATE, ZoneOffset.UTC));
    }
}
