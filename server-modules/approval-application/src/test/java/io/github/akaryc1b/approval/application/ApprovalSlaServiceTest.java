package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalSlaService.PauseCommand;
import io.github.akaryc1b.approval.application.ApprovalSlaService.ResumeCommand;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalRequestEvidenceProvider.RequestEvidence;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.AutomaticAction;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.PolicyStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.ResponsibilityChange;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.ResponsibilityChangeSource;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaDurationMode;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstance;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaPolicyVersion;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTargetType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTerminalReason;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalSlaServiceTest {

    private static final String TENANT_ID = "tenant-sla";
    private static final UUID INSTANCE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TASK_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID POLICY_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID SLA_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-07-22T10:00:00Z");
    private static final RequestEvidence EVIDENCE = new RequestEvidence(
        "operator-sla",
        "request-sla",
        "trace-sla"
    );

    @Test
    void createsNaturalTimeSlaWithServerOwnedEvidence() {
        StoreHarness harness = new StoreHarness();
        harness.effectivePolicy = Optional.of(naturalPolicy(false));
        ApprovalSlaService service = service(harness);

        service.synchronizeNewInstance(instanceProjection(), List.of(), EVIDENCE);

        assertEquals(1, harness.createdBatches.size());
        assertEquals(1, harness.createdBatches.getFirst().size());
        SlaInstance created = harness.createdBatches.getFirst().getFirst();
        assertEquals(SLA_ID, created.slaInstanceId());
        assertEquals(SlaTargetType.PROCESS, created.targetType());
        assertEquals(NOW.plus(Duration.ofHours(2)), created.dueAt());
        assertEquals(NOW.plus(Duration.ofMinutes(90)), created.nextReminderAt());
        assertEquals(NOW.plus(Duration.ofMinutes(135)), created.overdueAt());
        assertEquals("UTC", created.timeZone());
        assertEquals(EVIDENCE.requestId(), created.requestId());
        assertEquals(EVIDENCE.traceId(), created.traceId());
        assertEquals("initiator-sla", created.responsibleUserId());
        assertNull(created.calendarId());
    }

    @Test
    void duplicateLifecycleEventDoesNotCreateAnotherSla() {
        StoreHarness harness = new StoreHarness();
        harness.activeProcess = Optional.of(processSla());
        harness.effectivePolicy = Optional.of(naturalPolicy(false));

        service(harness).synchronizeNewInstance(instanceProjection(), List.of(), EVIDENCE);

        assertEquals(
            0,
            harness.createdBatches.stream().mapToInt(List::size).sum()
        );
    }

    @Test
    void completesRejectsAndWithdrawsProcessSlaWithExactReasons() {
        StoreHarness harness = new StoreHarness();
        ApprovalSlaService service = service(harness);
        InstanceProjection instance = instanceProjection();

        service.synchronizeTaskChange(
            instance,
            TASK_ID,
            List.of(),
            InstanceStatus.COMPLETED,
            false,
            EVIDENCE
        );
        service.synchronizeTaskChange(
            instance,
            TASK_ID,
            List.of(),
            InstanceStatus.REJECTED,
            false,
            EVIDENCE
        );
        service.terminalWithdrawnInstance(instance);

        assertEquals(
            List.of(
                SlaTerminalReason.INSTANCE_COMPLETED,
                SlaTerminalReason.INSTANCE_REJECTED,
                SlaTerminalReason.INSTANCE_WITHDRAWN
            ),
            harness.terminalCalls.stream().map(TerminalCall::reason).toList()
        );
        assertTrue(harness.terminalCalls.stream().allMatch(call -> call.targetId().equals(INSTANCE_ID)));
    }

    @Test
    void pausesAndResumesNaturalTimeSlaUsingOptimisticVersion() {
        StoreHarness harness = new StoreHarness();
        SlaInstance active = taskSla(
            SlaStatus.ACTIVE,
            NOW.plus(Duration.ofHours(1)),
            null,
            Duration.ofMinutes(30),
            3
        );
        SlaInstance paused = taskSla(
            SlaStatus.PAUSED,
            NOW.plus(Duration.ofHours(1)),
            NOW.minus(Duration.ofHours(1)),
            Duration.ofMinutes(30),
            4
        );
        harness.instance = Optional.of(active);
        harness.pauseResult = paused;
        ApprovalSlaService service = service(harness);

        SlaInstance pauseResult = service.pause(new PauseCommand(TENANT_ID, SLA_ID, 3, "planned outage"));

        assertSame(paused, pauseResult);
        assertEquals(TENANT_ID, harness.pauseArguments[0]);
        assertEquals(SLA_ID, harness.pauseArguments[1]);
        assertEquals(3L, harness.pauseArguments[2]);
        assertEquals(NOW, harness.pauseArguments[3]);
        assertEquals("planned outage", harness.pauseArguments[4]);

        harness.instance = Optional.of(paused);
        harness.policyVersion = Optional.of(naturalPolicy(true));
        SlaInstance resumed = service.resume(new ResumeCommand(TENANT_ID, SLA_ID, 4));

        assertNotNull(harness.resumeArguments);
        assertEquals(4L, harness.resumeArguments[2]);
        assertEquals(NOW.plus(Duration.ofHours(2)), harness.resumeArguments[3]);
        assertEquals(NOW.plus(Duration.ofMinutes(90)), harness.resumeArguments[4]);
        assertEquals(NOW.plus(Duration.ofMinutes(135)), harness.resumeArguments[5]);
        assertEquals(Duration.ofMinutes(90), harness.resumeArguments[6]);
        assertEquals(NOW, harness.resumeArguments[7]);
        assertEquals(SlaStatus.ACTIVE, resumed.status());
    }

    @Test
    void recordsBoundedResponsibilityEvidenceForTransfer() {
        StoreHarness harness = new StoreHarness();
        harness.activeTask = Optional.of(taskSla(
            SlaStatus.ACTIVE,
            NOW.plus(Duration.ofHours(1)),
            null,
            Duration.ZERO,
            7
        ));
        ApprovalSlaService service = service(harness);

        service.transferTaskResponsibility(
            TENANT_ID,
            TASK_ID,
            "owner-before",
            "owner-after",
            ResponsibilityChangeSource.MANUAL_TRANSFER,
            "manual workload transfer",
            EVIDENCE
        );

        ResponsibilityChange change = harness.responsibilityChange;
        assertNotNull(change);
        assertEquals(SLA_ID, change.slaInstanceId());
        assertEquals("owner-before", change.previousResponsibleUserId());
        assertEquals("owner-after", change.newResponsibleUserId());
        assertEquals(ResponsibilityChangeSource.MANUAL_TRANSFER, change.source());
        assertEquals("manual workload transfer", change.reason());
        assertEquals(EVIDENCE.actorId(), change.changedBy());
        assertEquals(EVIDENCE.requestId(), change.requestId());
        assertEquals(EVIDENCE.traceId(), change.traceId());
        assertEquals(7L, harness.responsibilityExpectedVersion);
    }

    @Test
    void missingTrustedEvidenceFailsClosedBeforePersistence() {
        StoreHarness harness = new StoreHarness();

        assertThrows(
            NullPointerException.class,
            () -> service(harness).synchronizeNewInstance(instanceProjection(), List.of(), null)
        );
        assertTrue(harness.createdBatches.isEmpty());
    }

    private static ApprovalSlaService service(StoreHarness harness) {
        return new ApprovalSlaService(
            harness.proxy(),
            new ApprovalWorkingTimeCalculator(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> SLA_ID
        );
    }

    private static InstanceProjection instanceProjection() {
        return new InstanceProjection(
            INSTANCE_ID,
            TENANT_ID,
            "business-sla",
            "engine-instance-sla",
            "purchasePayment",
            1,
            "purchasePayment",
            1,
            "compiler-v1",
            "definition-hash",
            "initiator-sla",
            BigDecimal.TEN,
            "supplier-sla",
            "PO-SLA",
            List.of(),
            new AssigneeSnapshot(
                "manager-sla",
                "finance-reviewer-sla",
                List.of("finance-approver-sla"),
                Map.of()
            ),
            "request-hash",
            InstanceStatus.RUNNING,
            1,
            NOW,
            NOW
        );
    }

    private static SlaPolicyVersion naturalPolicy(boolean naturalTimePauses) {
        return new SlaPolicyVersion(
            POLICY_ID,
            TENANT_ID,
            1,
            "purchasePayment",
            null,
            null,
            SlaTargetType.PROCESS,
            SlaDurationMode.NATURAL_TIME,
            Duration.ofHours(2),
            null,
            null,
            Duration.ofMinutes(30),
            null,
            1,
            Duration.ofMinutes(15),
            null,
            null,
            AutomaticAction.NONE,
            naturalTimePauses,
            "policy-content-hash",
            PolicyStatus.ACTIVE,
            true,
            "publisher-sla",
            NOW.minus(Duration.ofMinutes(5)),
            NOW.minus(Duration.ofMinutes(10)),
            NOW.minus(Duration.ofMinutes(5))
        );
    }

    private static SlaInstance processSla() {
        return new SlaInstance(
            SLA_ID,
            TENANT_ID,
            INSTANCE_ID,
            null,
            null,
            "purchasePayment",
            null,
            SlaTargetType.PROCESS,
            POLICY_ID,
            1,
            null,
            null,
            "UTC",
            "initiator-sla",
            "initiator-sla",
            NOW,
            NOW.plus(Duration.ofHours(2)),
            NOW.plus(Duration.ofMinutes(90)),
            NOW.plus(Duration.ofMinutes(135)),
            null,
            null,
            Duration.ZERO,
            null,
            null,
            SlaStatus.ACTIVE,
            0,
            "request-original",
            "trace-original",
            1,
            NOW,
            NOW
        );
    }

    private static SlaInstance taskSla(
        SlaStatus status,
        Instant dueAt,
        Instant pausedAt,
        Duration accumulated,
        long version
    ) {
        String pauseReason = status == SlaStatus.PAUSED ? "planned outage" : null;
        return new SlaInstance(
            SLA_ID,
            TENANT_ID,
            INSTANCE_ID,
            TASK_ID,
            null,
            "purchasePayment",
            "managerApproval",
            SlaTargetType.TASK,
            POLICY_ID,
            1,
            null,
            null,
            "UTC",
            "owner-before",
            "owner-before",
            NOW.minus(Duration.ofHours(2)),
            dueAt,
            dueAt.minus(Duration.ofMinutes(30)),
            dueAt.plus(Duration.ofMinutes(15)),
            pausedAt,
            pauseReason,
            accumulated,
            null,
            null,
            status,
            0,
            "request-original",
            "trace-original",
            version,
            NOW.minus(Duration.ofHours(2)),
            NOW
        );
    }

    private record TerminalCall(String method, UUID targetId, SlaTerminalReason reason) {
    }

    private static final class StoreHarness implements InvocationHandler {
        private final List<List<SlaInstance>> createdBatches = new ArrayList<>();
        private final List<TerminalCall> terminalCalls = new ArrayList<>();
        private Optional<SlaPolicyVersion> effectivePolicy = Optional.empty();
        private Optional<SlaPolicyVersion> policyVersion = Optional.empty();
        private Optional<SlaInstance> activeProcess = Optional.empty();
        private Optional<SlaInstance> activeTask = Optional.empty();
        private Optional<SlaInstance> instance = Optional.empty();
        private SlaInstance pauseResult;
        private Object[] pauseArguments;
        private Object[] resumeArguments;
        private ResponsibilityChange responsibilityChange;
        private long responsibilityExpectedVersion;

        private ApprovalSlaStore proxy() {
            return (ApprovalSlaStore) Proxy.newProxyInstance(
                ApprovalSlaStore.class.getClassLoader(),
                new Class<?>[]{ApprovalSlaStore.class},
                this
            );
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, name, arguments);
            }
            return switch (name) {
                case "findEffectivePolicy" -> effectivePolicy;
                case "findPolicyVersion" -> policyVersion;
                case "findActiveProcessInstance" -> activeProcess;
                case "findActiveTaskInstance" -> activeTask;
                case "findActiveCollaborationInstance", "findCalendar", "findCalendarByKey",
                    "findCalendarVersion", "findPolicy", "findPolicyByKey" -> Optional.empty();
                case "findInstance" -> instance;
                case "findActiveByApprovalInstance" -> List.of();
                case "createInstances" -> captureInstances(arguments);
                case "pause" -> capturePause(arguments);
                case "resume" -> captureResume(arguments);
                case "terminalTask", "terminalCollaborationParticipant",
                    "terminalCollaborationParticipantsByTask", "terminalApprovalInstance" ->
                    captureTerminal(name, arguments);
                case "changeResponsibility" -> captureResponsibility(arguments);
                default -> defaultValue(method.getReturnType());
            };
        }

        @SuppressWarnings("unchecked")
        private int captureInstances(Object[] arguments) {
            List<SlaInstance> values = List.copyOf((List<SlaInstance>) arguments[0]);
            createdBatches.add(values);
            return values.size();
        }

        private SlaInstance capturePause(Object[] arguments) {
            pauseArguments = arguments.clone();
            return pauseResult;
        }

        private SlaInstance captureResume(Object[] arguments) {
            resumeArguments = arguments.clone();
            SlaInstance paused = instance.orElseThrow();
            return new SlaInstance(
                paused.slaInstanceId(),
                paused.tenantId(),
                paused.approvalInstanceId(),
                paused.taskId(),
                paused.collaborationParticipantId(),
                paused.definitionKey(),
                paused.taskDefinitionKey(),
                paused.targetType(),
                paused.policyId(),
                paused.policyVersion(),
                paused.calendarId(),
                paused.calendarVersion(),
                paused.timeZone(),
                paused.responsibleUserId(),
                paused.originalResponsibleUserId(),
                paused.startedAt(),
                (Instant) arguments[3],
                (Instant) arguments[4],
                (Instant) arguments[5],
                null,
                null,
                (Duration) arguments[6],
                null,
                null,
                SlaStatus.ACTIVE,
                paused.lastActionSequence(),
                paused.requestId(),
                paused.traceId(),
                ((Number) arguments[2]).longValue() + 1,
                paused.createdAt(),
                (Instant) arguments[7]
            );
        }

        private int captureTerminal(String method, Object[] arguments) {
            terminalCalls.add(new TerminalCall(
                method,
                (UUID) arguments[1],
                (SlaTerminalReason) arguments[2]
            ));
            return 1;
        }

        private SlaInstance captureResponsibility(Object[] arguments) {
            responsibilityChange = (ResponsibilityChange) arguments[0];
            responsibilityExpectedVersion = ((Number) arguments[1]).longValue();
            return activeTask.orElseThrow();
        }

        private static Object objectMethod(Object proxy, String name, Object[] arguments) {
            return switch (name) {
                case "equals" -> proxy == arguments[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "ApprovalSlaStoreProxy";
                default -> null;
            };
        }

        private static Object defaultValue(Class<?> returnType) {
            if (returnType == int.class) {
                return 0;
            }
            if (returnType == long.class) {
                return 0L;
            }
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == Optional.class) {
                return Optional.empty();
            }
            if (returnType == List.class) {
                return List.of();
            }
            return null;
        }
    }
}
