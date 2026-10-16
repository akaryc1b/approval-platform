package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalConnectorNotificationSender;
import io.github.akaryc1b.approval.application.port.ApprovalEmailNotificationSender;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationEventType;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationIntent;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ApprovalNotificationRouteTest {

    private static final UUID INSTANCE = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID TASK = UUID.fromString("50000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");
    private static final String COLLABORATION = "/approval/collaboration?taskId=" + TASK;
    private static final String DISCUSSION = "/approval/discussion/detail?instanceId=" + INSTANCE;

    @Test
    void assignedAndResultNotificationsReceiveAuthoritativeTaskRoute() {
        Fixture fixture = new Fixture();
        for (String action : List.of("TASK_COLLABORATION_CREATED", "TASK_COLLABORATOR_ADDED")) {
            fixture.enqueue(action, TASK, Map.of(
                "participantWeights", "collaborator:1", "participantUserId", "collaborator", "route", DISCUSSION
            ));
            fixture.assertRoutes(NotificationEventType.TASK_COLLABORATION_ASSIGNED, COLLABORATION);
        }
        for (String action : List.of("TASK_COLLABORATION_THRESHOLD_REACHED", "TASK_COLLABORATION_THRESHOLD_IMPOSSIBLE")) {
            fixture.enqueue(action, TASK, Map.of("participantWeights", "undecided-member:1", "route", DISCUSSION));
            fixture.assertRoutes(NotificationEventType.TASK_COLLABORATION_RESULT, COLLABORATION);
        }
        fixture.taskPresent = false;
        fixture.enqueue("TASK_COLLABORATOR_ADDED", TASK, Map.of("participantUserId", "collaborator"));
        fixture.assertRoutes(NotificationEventType.TASK_COLLABORATION_ASSIGNED, COLLABORATION);
    }

    @Test
    void collaborationEventsWithoutTaskIdsUseListInsteadOfDiscussion() {
        Fixture fixture = new Fixture();
        fixture.enqueue("TASK_COLLABORATOR_ADDED", "unknown-task", Map.of("participantUserId", "collaborator", "route", DISCUSSION));
        fixture.assertRoutes(NotificationEventType.TASK_COLLABORATION_ASSIGNED, "/approval/collaboration");
        fixture.enqueue("TASK_COLLABORATION_THRESHOLD_REACHED", "unknown-task", Map.of("participantWeights", "collaborator:1", "route", DISCUSSION));
        fixture.assertRoutes(NotificationEventType.TASK_COLLABORATION_RESULT, "/approval/collaboration");
    }

    @Test
    void everyOtherEventKeepsItsExistingDefaultRoute() {
        Fixture fixture = new Fixture();
        fixture.enqueue("INSTANCE_STARTED", INSTANCE, Map.of());
        fixture.assertRoutes(NotificationEventType.TASK_ASSIGNED, DISCUSSION);
        for (Map.Entry<String, NotificationEventType> action : Map.of(
            "TASK_DELEGATED", NotificationEventType.AUTOMATIC_DELEGATION,
            "TASK_HANDOVER_ASSIGNED", NotificationEventType.EMPLOYEE_HANDOVER
        ).entrySet()) {
            fixture.enqueue(action.getKey(), TASK, Map.of(
                "delegateAssigneeId", "delegate", "successorAssigneeId", "successor", "participantWeights", "collaborator:1"
            ));
            fixture.assertRoutes(action.getValue(), DISCUSSION);
        }
        fixture.enqueue("INSTANCE_COMMENTED", INSTANCE, Map.of("mentions", "mentioned"));
        fixture.assertRoutes(NotificationEventType.COMMENT_MENTION, DISCUSSION);
        fixture.status = InstanceStatus.COMPLETED;
        fixture.enqueue("TASK_APPROVED", TASK, Map.of());
        fixture.assertRoutes(NotificationEventType.APPROVAL_COMPLETED, DISCUSSION);
        fixture.status = InstanceStatus.REJECTED;
        fixture.enqueue("TASK_REJECTED", TASK, Map.of());
        fixture.assertRoutes(NotificationEventType.APPROVAL_REJECTED, DISCUSSION);
    }

    @Test
    void nonCollaborationEventsPreserveExplicitMetadataRoutesAndIgnoreMetadataEventType() {
        Fixture fixture = new Fixture();
        for (String action : List.of("TASK_DELEGATED", "TASK_HANDOVER_ASSIGNED",
            "INSTANCE_COMMENTED")) {
            fixture.enqueue(action, action.equals("INSTANCE_COMMENTED") ? INSTANCE : TASK, Map.of(
                "delegateAssigneeId", "delegate", "successorAssigneeId", "successor",
                "participantWeights", "collaborator:1", "mentions", "mentioned",
                "route", "/existing-destination", "eventType", "TASK_COLLABORATION_ASSIGNED"
            ));
            assertFalse(fixture.intents.isEmpty());
            for (NotificationIntent intent : fixture.intents) {
                assertEquals("/existing-destination", intent.metadata().get("route"));
                assertEquals(intent.eventType().name(), intent.metadata().get("eventType"));
            }
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static final class Fixture {
        private final List<NotificationIntent> intents = new ArrayList<>();
        private InstanceStatus status = InstanceStatus.RUNNING;
        private boolean taskPresent = true;
        private final ApprovalNotificationService service = new ApprovalNotificationService(
            proxy(ApprovalNotificationStore.class, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "findPreferences" -> Optional.empty();
                    case "enqueue" -> {
                        for (Object item : (List<?>) args[0]) intents.add((NotificationIntent) item);
                        yield intents.size();
                    }
                    default -> throw new AssertionError("unexpected notification method: " + method.getName());
                };
            }),
            proxy(ApprovalProjectionStore.class, (proxy, method, args) -> switch (method.getName()) {
                case "findTask" -> taskPresent ? Optional.of(task()) : Optional.empty();
                case "findInstance" -> Optional.of(instance());
                case "findTasks" -> status == InstanceStatus.RUNNING ? List.of(task()) : List.of();
                default -> throw new AssertionError("unexpected projection method: " + method.getName());
            }),
            ApprovalConnectorNotificationSender.unavailable(), ApprovalEmailNotificationSender.unavailable(),
            Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID
        );

        private void enqueue(String action, UUID aggregateId, Map<String, String> attributes) {
            enqueue(action, aggregateId.toString(), attributes);
        }

        private void enqueue(String action, String aggregateId, Map<String, String> attributes) {
            intents.clear();
            service.enqueueFromAudit(new AuditEvent(UUID.randomUUID(), "tenant-a", "owner", action,
                "APPROVAL_TASK", aggregateId, "route-test", null, NOW, attributes));
        }

        private void assertRoutes(NotificationEventType expected, String route) {
            assertFalse(intents.isEmpty());
            for (NotificationIntent intent : intents) {
                assertEquals(expected, intent.eventType());
                assertEquals(route, intent.metadata().get("route"));
            }
        }

        private TaskProjection task() {
            return new TaskProjection(TASK, INSTANCE, "tenant-a", "engine-task", "approval", "Approval",
                "assignee", TaskStatus.PENDING, 1, NOW, NOW, null);
        }

        private InstanceProjection instance() {
            return new InstanceProjection(INSTANCE, "tenant-a", "business", "engine-instance", "definition", 1,
                "form", 1, "compiler", "hash", "initiator", BigDecimal.TEN, "supplier", "purchase-order", List.of(),
                new AssigneeSnapshot("assignee", "reviewer", List.of(), Map.of()), "request-hash", status, 1, NOW, NOW);
        }
    }
}
