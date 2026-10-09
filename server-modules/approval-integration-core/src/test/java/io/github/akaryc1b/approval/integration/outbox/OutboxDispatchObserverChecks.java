package io.github.akaryc1b.approval.integration.outbox;

import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector.CallbackReceipt;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector.DeliveryStatus;
import io.github.akaryc1b.approval.integration.outbox.OutboxDispatchObserver.Attempt;
import io.github.akaryc1b.approval.integration.outbox.OutboxDispatchObserver.Outcome;
import io.github.akaryc1b.approval.integration.retry.RetryPolicy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Dependency-free behavioral checks, also invoked by the normal Maven JUnit suite. */
final class OutboxDispatchObserverChecks {
    private static final Instant NOW = Instant.parse("2026-09-19T00:00:00Z");
    private static final RetryPolicy RETRY = new RetryPolicy() {
        public int maxAttempts() { return 3; }
        public Duration nextDelay(int attempt) { return Duration.ofSeconds(attempt); }
    };

    static int runAll() {
        int count = 0;
        for (DeliveryStatus status : DeliveryStatus.values()) {
            for (String fault : List.of("none", "start", "null", "outcome", "close", "both")) {
                Memory repository = new Memory();
                Probe probe = new Probe(fault, repository);
                int[] calls = {0};
                BusinessCallbackConnector connector = (context, event) -> {
                    require(event == repository.message.event(), "event identity changed");
                    require(context == repository.message.context(), "business context changed");
                    require(probe.open || fault.equals("start") || fault.equals("null"), "scope missing");
                    calls[0]++;
                    return new CallbackReceipt(status, "provider", 200, NOW, "private provider text");
                };
                OutboxDispatcher.DispatchReport report = dispatcher(repository, key -> connector, probe)
                    .dispatchBatch(10, "worker");
                Outcome expected = switch (status) {
                    case DELIVERED -> Outcome.DELIVERED;
                    case RETRYABLE_FAILURE -> Outcome.RESCHEDULED;
                    case PERMANENT_FAILURE -> Outcome.DEAD;
                };
                require(calls[0] == 1, "telemetry failure duplicated external delivery");
                require(repository.updates == 1, "telemetry failure changed persistence calls");
                require(repository.state.equals(expected.name()), "wrong stored outcome");
                require(report.delivered() + report.rescheduled() + report.dead() == 1, "wrong report");
                if (!fault.equals("start") && !fault.equals("null")) {
                    require(probe.outcomes.equals(List.of(expected)), "wrong observed outcome");
                    require(probe.closed == 1 && !probe.open, "scope not closed exactly once");
                }
                count++;
            }
        }
        for (DeliveryStatus status : DeliveryStatus.values()) {
            Memory repository = new Memory(); repository.owns = false;
            Probe probe = new Probe("none", repository);
            var report = dispatcher(repository, key -> (context, event) ->
                new CallbackReceipt(status, null, 200, NOW, null), probe).dispatchBatch(10, "worker");
            require(report.leaseLost() == 1, "lease loss became acknowledgement");
            require(probe.outcomes.equals(List.of(Outcome.LEASE_LOST)), "lease loss mislabeled");
            require(probe.closed == 1, "lease loss leaked scope"); count++;
        }
        for (boolean resolutionFailure : List.of(false, true)) {
            Memory repository = new Memory(); Probe probe = new Probe("none", repository);
            RuntimeException original = new IllegalArgumentException("business failure");
            BusinessCallbackResolver resolver = key -> {
                if (resolutionFailure) throw original;
                return (context, event) -> { throw original; };
            };
            require(dispatcher(repository, resolver, probe).dispatchBatch(10, "worker").rescheduled() == 1,
                "existing failure retry semantics changed");
            require(probe.outcomes.equals(List.of(Outcome.RESCHEDULED)) && probe.closed == 1,
                "failure did not record reschedule and close"); count++;
        }
        Memory exhausted = new Memory(); exhausted.attempts = 2;
        Probe exhaustedProbe = new Probe("none", exhausted);
        require(dispatcher(exhausted, key -> (context, event) ->
            new CallbackReceipt(DeliveryStatus.RETRYABLE_FAILURE, null, 503, NOW, null), exhaustedProbe)
            .dispatchBatch(10, "worker").dead() == 1, "retry exhaustion changed"); count++;

        Memory broken = new Memory(); broken.writeFailure = new IllegalStateException("database failed");
        Probe brokenProbe = new Probe("both", broken); int[] sideEffects = {0};
        try {
            dispatcher(broken, key -> (context, event) -> {
                sideEffects[0]++;
                return new CallbackReceipt(DeliveryStatus.DELIVERED, null, 200, NOW, null);
            }, brokenProbe).dispatchBatch(10, "worker");
            throw new AssertionError("database error swallowed");
        } catch (IllegalStateException expected) {
            require(expected == broken.writeFailure, "telemetry replaced business exception");
        }
        require(sideEffects[0] == 1, "database error caused duplicate external send");
        require(brokenProbe.outcomes.equals(List.of(Outcome.FAILED)) && brokenProbe.closed == 1,
            "escaping failure did not close scope"); count++;

        Memory empty = new Memory(); empty.empty = true; Probe emptyProbe = new Probe("none", empty);
        require(dispatcher(empty, key -> { throw new AssertionError("unexpected connector"); }, emptyProbe)
            .dispatchBatch(10, "worker").claimed() == 0 && emptyProbe.starts == 0, "empty claim observed"); count++;
        Memory two = new Memory(); two.two = true; Probe twoProbe = new Probe("none", two);
        require(dispatcher(two, key -> (context, event) ->
            new CallbackReceipt(DeliveryStatus.DELIVERED, null, 204, NOW, null), twoProbe)
            .dispatchBatch(10, "worker").delivered() == 2, "multi-message batch changed");
        require(twoProbe.starts == 2 && twoProbe.closed == 2 && !twoProbe.open, "message scopes overlap"); count++;

        Memory legacy = new Memory();
        require(new OutboxDispatcher(legacy, key -> (context, event) ->
            new CallbackReceipt(DeliveryStatus.DELIVERED, null, 204, NOW, null), RETRY,
            Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30)).dispatchBatch(10, "worker").delivered() == 1,
            "legacy constructor changed"); count++;
        return count;
    }

    public static void main(String[] args) {
        System.out.println("OUTBOX_DISPATCH_OBSERVER_CHECKS=" + runAll());
    }

    private static OutboxDispatcher dispatcher(Memory memory, BusinessCallbackResolver resolver, Probe probe) {
        return new OutboxDispatcher(memory, resolver, RETRY, Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofSeconds(30), probe);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Probe implements OutboxDispatchObserver {
        final String fault;
        final Memory repository;
        final List<Outcome> outcomes = new ArrayList<>();
        boolean open;
        int starts;
        int closed;
        Probe(String fault, Memory repository) { this.fault = fault; this.repository = repository; }
        public Attempt start(UUID eventId) {
            require(repository.claimed, "observation before claim");
            require(!open, "previous message still in scope");
            require(eventId.equals(repository.message.event().eventId()), "wrong event identity");
            starts++;
            if (fault.equals("start")) throw new IllegalStateException("telemetry start failed");
            if (fault.equals("null")) return null;
            open = true;
            return new Attempt() {
                public void outcome(Outcome value) {
                    outcomes.add(value);
                    if (fault.equals("outcome") || fault.equals("both")) throw new IllegalStateException("tag failed");
                }
                public void close() {
                    closed++; open = false;
                    if (fault.equals("close") || fault.equals("both")) throw new IllegalStateException("export failed");
                }
            };
        }
    }

    private static final class Memory implements OutboxRepository {
        final OutboxMessage message = OutboxMessage.create(new ConnectorContext("generic-rest", "tenant-a",
            "request-a", "business-correlation", NOW), new BusinessCallbackConnector.BusinessEvent(
            UUID.fromString("c6aab1b2-54a7-4c0e-b2f9-9d5d76f2b083"), "PROCESS_COMPLETED.v1", "PROCESS", "process-a",
            NOW, "original-idempotency-key", Map.of("private", "unchanged")), NOW);
        boolean claimed;
        boolean empty;
        boolean two;
        boolean owns = true;
        int attempts;
        int updates;
        String state;
        RuntimeException writeFailure;
        public AppendResult append(OutboxMessage value) { throw new AssertionError("unexpected append"); }
        public List<ClaimedMessage> claimDue(Instant now, int limit, String worker, Duration lease) {
            claimed = true;
            ClaimedMessage value = new ClaimedMessage(message, attempts, worker, now.plus(lease));
            return empty ? List.of() : two ? List.of(value, value) : List.of(value);
        }
        private boolean update(String value) {
            updates++;
            if (writeFailure != null) throw writeFailure;
            state = value; return owns;
        }
        public boolean markDelivered(UUID id, String worker, String provider, int code, Instant now) {
            return update("DELIVERED");
        }
        public boolean reschedule(UUID id, String worker, int count, Instant available, String error, Instant now) {
            require(count == attempts + 1, "retry count changed");
            require(available.equals(NOW.plusSeconds(count)), "backoff changed");
            return update("RESCHEDULED");
        }
        public boolean markDead(UUID id, String worker, int count, String error, Instant now) {
            require(count == attempts + 1, "dead-letter attempt changed");
            return update("DEAD");
        }
    }
}
