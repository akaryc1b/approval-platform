package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistorySummary;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProductionRuntimeFactory;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesRuntimeUsageLedger;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesTransportControls;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceSnapshotSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlledAutomationGovernanceRuntimeStabilityAcceptanceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T06:45:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void circuitTransitionDuringHistoryQueryFailsClosedWithoutRetryOrBinding()
        throws Exception {
        Fixture fixture = fixture();
        OpenAiResponsesTransportControls.CircuitBreaker circuit = circuit(fixture.factory());
        AtomicInteger historyCalls = new AtomicInteger();
        var source = fixture.configuration()
            .controlledAutomationGovernanceIncidentReadinessSource(
                fixture.runtime(),
                window -> {
                    historyCalls.incrementAndGet();
                    var permit = circuit.tryAcquire(NOW);
                    circuit.record(
                        permit,
                        OpenAiResponsesTransportControls.Outcome.TRANSPORT_FAILURE,
                        NOW
                    );
                    return HistorySummary.empty(window);
                },
                fixture.snapshotSource()
            );

        assertThrows(
            IllegalStateException.class,
            () -> source.readiness("tenant-a", NOW.minusSeconds(3_600), NOW)
        );
        assertEquals(1, historyCalls.get());
        assertEquals(1, fixture.snapshotReads().get());
        assertEquals(0, bindingCount(fixture.factory()));
    }

    @Test
    void usageMutationDuringHistoryQueryFailsClosedWithoutRetryOrBinding()
        throws Exception {
        Fixture fixture = fixture();
        OpenAiResponsesRuntimeUsageLedger ledger = usageLedger(fixture.factory());
        AtomicInteger historyCalls = new AtomicInteger();
        var source = fixture.configuration()
            .controlledAutomationGovernanceIncidentReadinessSource(
                fixture.runtime(),
                window -> {
                    historyCalls.incrementAndGet();
                    ledger.recordDispatched(
                        OpenAiResponsesProtocol.sha256Utf8("tenant\ntenant-a"),
                        windowStart(NOW),
                        100
                    );
                    return HistorySummary.empty(window);
                },
                fixture.snapshotSource()
            );

        assertThrows(
            IllegalStateException.class,
            () -> source.readiness("tenant-a", NOW.minusSeconds(3_600), NOW)
        );
        assertEquals(1, historyCalls.get());
        assertEquals(1, fixture.snapshotReads().get());
        assertEquals(0, bindingCount(fixture.factory()));
    }

    @Test
    void stableRuntimeProducesOneCompositeWithoutBindingOrProviderAccess()
        throws Exception {
        Fixture fixture = fixture();
        AtomicInteger historyCalls = new AtomicInteger();
        var source = fixture.configuration()
            .controlledAutomationGovernanceIncidentReadinessSource(
                fixture.runtime(),
                window -> {
                    historyCalls.incrementAndGet();
                    return HistorySummary.empty(window);
                },
                fixture.snapshotSource()
            );

        var view = source.readiness("tenant-a", NOW.minusSeconds(3_600), NOW);

        assertEquals(NOW, view.observedAt());
        assertEquals(1, historyCalls.get());
        assertEquals(1, fixture.snapshotReads().get());
        assertEquals(0, bindingCount(fixture.factory()));
    }

    private static Fixture fixture() {
        var configuration = new ControlledAutomationGovernanceConfiguration();
        var factory = new OpenAiResponsesProductionRuntimeFactory(profile(), CLOCK);
        var runtime = ApprovalAssistanceProductionRuntime.configured(factory);
        ControlledAutomationGovernanceSnapshotSource delegate = configuration
            .controlledAutomationGovernanceSnapshotSource(runtime, CLOCK);
        AtomicInteger snapshotReads = new AtomicInteger();
        ControlledAutomationGovernanceSnapshotSource counted = () -> {
            snapshotReads.incrementAndGet();
            return delegate.current();
        };
        return new Fixture(configuration, factory, runtime, counted, snapshotReads);
    }

    private static OpenAiResponsesProductionRuntimeFactory.RuntimeProfile profile() {
        return new OpenAiResponsesProductionRuntimeFactory.RuntimeProfile(
            "key-v1",
            NOW.minusSeconds(60),
            NOW.plusSeconds(3_600),
            "secret-policy-v1",
            7,
            "kill-switch-policy-v1",
            "cost-v1",
            NOW.minusSeconds(60),
            NOW.plusSeconds(3_600),
            1,
            2,
            1_000_000,
            10,
            100,
            Duration.ofSeconds(60),
            1,
            Duration.ofSeconds(30)
        );
    }

    private static OpenAiResponsesTransportControls.CircuitBreaker circuit(
        OpenAiResponsesProductionRuntimeFactory factory
    ) throws ReflectiveOperationException {
        Field field = OpenAiResponsesProductionRuntimeFactory.class
            .getDeclaredField("circuitBreaker");
        field.setAccessible(true);
        return (OpenAiResponsesTransportControls.CircuitBreaker) field.get(factory);
    }

    private static OpenAiResponsesRuntimeUsageLedger usageLedger(
        OpenAiResponsesProductionRuntimeFactory factory
    ) throws ReflectiveOperationException {
        Field field = OpenAiResponsesProductionRuntimeFactory.class
            .getDeclaredField("usageLedger");
        field.setAccessible(true);
        return (OpenAiResponsesRuntimeUsageLedger) field.get(factory);
    }

    private static int bindingCount(OpenAiResponsesProductionRuntimeFactory factory)
        throws ReflectiveOperationException {
        Field field = OpenAiResponsesProductionRuntimeFactory.class.getDeclaredField("bindings");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(factory)).size();
    }

    private static Instant windowStart(Instant value) {
        long epoch = Math.floorDiv(value.getEpochSecond(), 60) * 60;
        return Instant.ofEpochSecond(epoch);
    }

    private record Fixture(
        ControlledAutomationGovernanceConfiguration configuration,
        OpenAiResponsesProductionRuntimeFactory factory,
        ApprovalAssistanceProductionRuntime runtime,
        ControlledAutomationGovernanceSnapshotSource snapshotSource,
        AtomicInteger snapshotReads
    ) {
    }
}
