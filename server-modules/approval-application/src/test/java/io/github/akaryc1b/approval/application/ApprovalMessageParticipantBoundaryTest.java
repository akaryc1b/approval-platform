package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalMessageService.CopyCommand;
import io.github.akaryc1b.approval.application.ApprovalMessageService.MessageStateConflictException;
import io.github.akaryc1b.approval.application.ApprovalMessageService.UrgeCommand;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.ApprovalMessage;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalMessageParticipantBoundaryTest {

    private static final UUID INSTANCE = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID MISSING = UUID.fromString("30000000-0000-0000-0000-000000000099");
    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");

    @Test
    void missingOtherTenantAndUnrelatedIdentitiesShareNonDisclosingFailure() {
        Fixture fixture = new Fixture();
        for (Identity identity : List.of(
            new Identity("tenant-a", "initiator", MISSING),
            new Identity("tenant-b", "initiator", INSTANCE),
            new Identity("tenant-a", "outsider", INSTANCE),
            new Identity("tenant-a", "pure-collaborator", INSTANCE),
            new Identity("tenant-a", "copy-recipient", INSTANCE),
            new Identity("tenant-a", "snapshot-only", INSTANCE)
        )) {
            assertHidden(() -> fixture.service.findOptions(identity.tenant(), identity.actor(), identity.instance()));
            assertHidden(() -> fixture.service.urge(new UrgeCommand(
                context(identity.tenant(), identity.actor(), "urge"), identity.instance(), null
            )));
            assertHidden(() -> fixture.service.copy(new CopyCommand(
                context(identity.tenant(), identity.actor(), "copy"), identity.instance(), List.of("snapshot-only"), null
            )));
        }
        assertEquals(0, fixture.appendCalls);
        assertEquals(0, fixture.auditCalls);
    }

    @Test
    void optionsKeepInitiatorCurrentAndHistoricalProjectedAssignees() {
        Fixture fixture = new Fixture();
        for (String actor : List.of("initiator", "current", "historical")) {
            var options = fixture.service.findOptions("tenant-a", actor, INSTANCE);
            assertEquals(INSTANCE, options.instanceId());
            assertEquals(List.of("current"), options.activeAssignees().stream()
                .map(ApprovalMessageService.UserOption::userId).toList());
        }
        assertTrue(fixture.service.findOptions("tenant-a", "historical", INSTANCE).canUrge());
    }

    @Test
    void receiptsRemainInitiatorOnlyAndDoNotQueryStoreForDeniedReads() {
        Fixture fixture = new Fixture();
        for (String actor : List.of("current", "historical", "copy-recipient", "pure-collaborator", "outsider")) {
            assertHidden(() -> fixture.service.findReceipts("tenant-a", actor, INSTANCE));
        }
        assertHidden(() -> fixture.service.findReceipts("tenant-b", "initiator", INSTANCE));
        assertHidden(() -> fixture.service.findReceipts("tenant-a", "initiator", MISSING));
        assertEquals(0, fixture.receiptCalls);
        assertEquals(List.of(), fixture.service.findReceipts("tenant-a", "initiator", INSTANCE));
        assertEquals(1, fixture.receiptCalls);
    }

    @Test
    void recognizedUrgeAndCopyBusinessFailuresAreTypedConflicts() {
        Fixture fixture = new Fixture();
        fixture.status = InstanceStatus.COMPLETED;
        assertThrows(MessageStateConflictException.class, () -> fixture.service.urge(urge("initiator", "closed")));
        fixture.status = InstanceStatus.RUNNING;
        assertThrows(MessageStateConflictException.class, () -> fixture.service.urge(urge("current", "self-only")));
        fixture.service.urge(urge("initiator", "first-urge"));
        assertThrows(MessageStateConflictException.class, () -> fixture.service.urge(urge("initiator", "duplicate-urge")));
        assertThrows(MessageStateConflictException.class, () -> fixture.service.copy(new CopyCommand(
            context("tenant-a", "initiator", "invalid-recipient"), INSTANCE, List.of("outsider"), null
        )));
        fixture.service.copy(copy("initiator", "first-copy"));
        assertThrows(MessageStateConflictException.class, () -> fixture.service.copy(copy("initiator", "duplicate-copy")));
        assertEquals(2, fixture.auditCalls);
    }

    @Test
    void guardReplayPrecedesMutableStateChecksAndRejectsOtherActorWithoutCachedResult() {
        Fixture fixture = new Fixture();
        var urged = fixture.service.urge(urge("initiator", "urge-key"));
        var copied = fixture.service.copy(copy("initiator", "copy-key"));
        int reads = fixture.projectionReads;
        fixture.status = InstanceStatus.COMPLETED;
        fixture.rejectProjectionReads = true;
        assertEquals(urged, fixture.service.urge(urge("initiator", "urge-key")));
        assertEquals(copied, fixture.service.copy(copy("initiator", "copy-key")));
        for (String actor : List.of("current", "outsider")) {
            assertThrows(IdempotencyGuard.IdempotencyConflictException.class,
                () -> fixture.service.urge(urge(actor, "urge-key")));
            assertThrows(IdempotencyGuard.IdempotencyConflictException.class,
                () -> fixture.service.copy(copy(actor, "copy-key")));
        }
        assertEquals(reads, fixture.projectionReads);
        assertEquals(2, fixture.appendCalls);
        assertEquals(2, fixture.auditCalls);
    }

    private static void assertHidden(Runnable action) {
        var exception = assertThrows(ApprovalParticipantResourceNotFoundException.class, action::run);
        assertEquals("approval resource was not found", exception.getMessage());
    }

    private static UrgeCommand urge(String actor, String key) {
        return new UrgeCommand(context("tenant-a", actor, key), INSTANCE, null);
    }

    private static CopyCommand copy(String actor, String key) {
        return new CopyCommand(context("tenant-a", actor, key), INSTANCE, List.of("snapshot-only"), null);
    }

    private static RequestContext context(String tenant, String actor, String key) {
        return new RequestContext(tenant, actor, "request-" + key, key, null);
    }

    private record Identity(String tenant, String actor, UUID instance) {
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static final class Fixture {
        private InstanceStatus status = InstanceStatus.RUNNING;
        private int projectionReads;
        private int appendCalls;
        private int receiptCalls;
        private int auditCalls;
        private boolean rejectProjectionReads;
        private final Set<String> dedupKeys = new HashSet<>();
        private final ApprovalMessageService service = new ApprovalMessageService(
            new ActorBoundReplayGuard(),
            proxy(ApprovalProjectionStore.class, (proxy, method, args) -> {
                if (rejectProjectionReads) throw new AssertionError("cached replay must not read mutable projections");
                projectionReads++;
                return switch (method.getName()) {
                    case "findInstance" -> "tenant-a".equals(args[0]) && INSTANCE.equals(args[1])
                        ? Optional.of(instance()) : Optional.empty();
                    case "findTasks" -> List.of(task("current", TaskStatus.PENDING), task("historical", TaskStatus.COMPLETED));
                    default -> throw new AssertionError("unexpected projection method: " + method.getName());
                };
            }),
            proxy(ApprovalMessageStore.class, (proxy, method, args) -> {
                if (method.getName().equals("append")) {
                    appendCalls++;
                    int created = 0;
                    for (Object item : (List<?>) args[0]) {
                        if (dedupKeys.add(((ApprovalMessage) item).dedupKey())) created++;
                    }
                    return created;
                }
                if (method.getName().equals("findReceipts")) {
                    receiptCalls++;
                    return List.of();
                }
                throw new AssertionError("unexpected message method: " + method.getName());
            }),
            event -> auditCalls++, Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID
        );

        private InstanceProjection instance() {
            return new InstanceProjection(
                INSTANCE, "tenant-a", "business", "engine-instance", "definition", 1, "form", 1,
                "compiler", "hash", "initiator", BigDecimal.TEN, "supplier", "purchase-order", List.of(),
                new AssigneeSnapshot("current", "snapshot-only", List.of(), Map.of()),
                "request-hash", status, 1, NOW, NOW
            );
        }

        private TaskProjection task(String actor, TaskStatus taskStatus) {
            return new TaskProjection(UUID.nameUUIDFromBytes(actor.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                INSTANCE, "tenant-a", "engine-task-" + actor, "approval", "Approval", actor, taskStatus,
                1, NOW, NOW, taskStatus == TaskStatus.COMPLETED ? NOW : null);
        }
    }

    /** Exercises service ordering against the port contract; JDBC guard durability is covered separately. */
    private static final class ActorBoundReplayGuard implements IdempotencyGuard {
        private final Map<String, CachedResult> results = new HashMap<>();

        @Override
        public <T> T execute(RequestContext context, String operation, String hash, Class<T> type, Supplier<T> action) {
            String key = context.tenantId() + ':' + operation + ':' + context.idempotencyKey();
            CachedResult cached = results.get(key);
            if (cached != null) {
                if (!cached.actor().equals(context.operatorId()) || !cached.hash().equals(hash)) {
                    throw new IdempotencyConflictException("idempotency key is bound to another actor or request");
                }
                return type.cast(cached.value());
            }
            T result = action.get();
            results.put(key, new CachedResult(context.operatorId(), hash, result));
            return result;
        }
    }

    private record CachedResult(String actor, String hash, Object value) {
    }
}
