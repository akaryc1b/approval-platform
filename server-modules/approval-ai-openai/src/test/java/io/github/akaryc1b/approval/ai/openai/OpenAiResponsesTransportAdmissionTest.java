package io.github.akaryc1b.approval.ai.openai;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiResponsesTransportAdmissionTest {

    private static final Instant NOW = Instant.parse("2026-08-04T01:45:00Z");
    private static final String TENANT_HASH = hash("tenant-a");

    @Test
    void exactAdmissionIsSingleDispatchAndHashOnly() {
        AtomicReference<OpenAiResponsesTransportControls.KillSwitchSnapshot> source =
            new AtomicReference<>(killSwitch(true, 7));
        OpenAiResponsesTransportAdmission admission = admission(
            source,
            10,
            100,
            3,
            costPolicy(1_000_000)
        );
        OpenAiResponsesTransportPort.Request request = request(false);

        try (OpenAiResponsesTransportAdmission.Permit permit =
                 admission.admit(request, 2_048)) {
            permit.revalidateBeforeSecret(request);
            permit.revalidateBeforeDispatch(request);
            permit.markDispatched(request);
            assertTrue(permit.dispatched());
            assertTrue(permit.costEstimate().estimatedMicros() > 0);
            assertFalse(permit.toString().contains("tenant-a"));
            permit.record(OpenAiResponsesTransportControls.Outcome.SUCCESS);
        }
    }

    @Test
    void disabledAndDriftedKillSwitchesFailBeforeAdmission() {
        AtomicReference<OpenAiResponsesTransportControls.KillSwitchSnapshot> disabled =
            new AtomicReference<>(killSwitch(false, 7));
        OpenAiResponsesTransportAdmission disabledAdmission = admission(
            disabled,
            10,
            100,
            3,
            costPolicy(1_000_000)
        );
        assertFailure(
            disabledAdmission,
            request(false),
            OpenAiResponsesTransportException.Failure.KILL_SWITCH_DISABLED
        );

        AtomicReference<OpenAiResponsesTransportControls.KillSwitchSnapshot> source =
            new AtomicReference<>(killSwitch(true, 7));
        OpenAiResponsesTransportAdmission drifted = admission(
            source,
            10,
            100,
            3,
            costPolicy(1_000_000)
        );
        source.set(killSwitch(true, 8));
        assertFailure(
            drifted,
            request(false),
            OpenAiResponsesTransportException.Failure.KILL_SWITCH_DRIFT
        );
    }

    @Test
    void rateReservationRollsBackBeforeDispatchAndCommitsAfterDispatch() {
        AtomicReference<OpenAiResponsesTransportControls.KillSwitchSnapshot> source =
            new AtomicReference<>(killSwitch(true, 7));
        OpenAiResponsesTransportAdmission admission = admission(
            source,
            1,
            1,
            3,
            costPolicy(1_000_000)
        );
        OpenAiResponsesTransportPort.Request request = request(false);

        try (OpenAiResponsesTransportAdmission.Permit ignored =
                 admission.admit(request, 100)) {
            // Closing before dispatch must release the rate reservation.
        }

        try (OpenAiResponsesTransportAdmission.Permit permit =
                 admission.admit(request, 100)) {
            permit.markDispatched(request);
            permit.record(OpenAiResponsesTransportControls.Outcome.SUCCESS);
        }

        assertFailure(
            admission,
            request,
            OpenAiResponsesTransportException.Failure.RATE_LIMITED
        );
    }

    @Test
    void staleAndOverCeilingCostPoliciesFailClosed() {
        AtomicReference<OpenAiResponsesTransportControls.KillSwitchSnapshot> source =
            new AtomicReference<>(killSwitch(true, 7));
        OpenAiResponsesTransportControls.CostPolicy stale =
            new OpenAiResponsesTransportControls.CostPolicy(
                "pricing-v1",
                OpenAiResponsesProtocol.MODEL_SNAPSHOT,
                1,
                1,
                1_000_000,
                NOW.minusSeconds(600),
                NOW.minusSeconds(1)
            );
        assertFailure(
            admission(source, 10, 100, 3, stale),
            request(false),
            OpenAiResponsesTransportException.Failure.COST_POLICY_STALE
        );

        OpenAiResponsesTransportControls.CostPolicy lowCeiling = costPolicy(10);
        assertFailure(
            admission(source, 10, 100, 3, lowCeiling),
            request(false),
            OpenAiResponsesTransportException.Failure.COST_LIMIT_EXCEEDED
        );
    }

    @Test
    void circuitOpensAndAllowsOnlyOneHalfOpenProbe() {
        OpenAiResponsesTransportControls.CircuitBreaker circuit =
            new OpenAiResponsesTransportControls.CircuitBreaker(
                1,
                Duration.ofSeconds(30)
            );
        OpenAiResponsesTransportControls.CircuitBreaker.CircuitPermit first =
            circuit.tryAcquire(NOW);
        assertTrue(first.allowed());
        circuit.record(
            first,
            OpenAiResponsesTransportControls.Outcome.TRANSPORT_FAILURE,
            NOW
        );
        assertEquals(
            OpenAiResponsesTransportControls.CircuitBreaker.State.OPEN,
            circuit.state()
        );
        assertFalse(circuit.tryAcquire(NOW.plusSeconds(1)).allowed());

        OpenAiResponsesTransportControls.CircuitBreaker.CircuitPermit probe =
            circuit.tryAcquire(NOW.plusSeconds(31));
        assertTrue(probe.allowed());
        assertEquals(
            OpenAiResponsesTransportControls.CircuitBreaker.State.HALF_OPEN,
            probe.stateBefore()
        );
        assertFalse(circuit.tryAcquire(NOW.plusSeconds(31)).allowed());
        circuit.record(
            probe,
            OpenAiResponsesTransportControls.Outcome.SUCCESS,
            NOW.plusSeconds(31)
        );
        assertEquals(
            OpenAiResponsesTransportControls.CircuitBreaker.State.CLOSED,
            circuit.state()
        );
    }

    @Test
    void pricingMustBePositiveCurrentAndBoundToTheExactModel() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new OpenAiResponsesTransportControls.CostPolicy(
                "pricing-v1",
                "floating-model",
                1,
                1,
                1_000,
                NOW.minusSeconds(1),
                NOW.plusSeconds(1)
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new OpenAiResponsesTransportControls.CostPolicy(
                "pricing-v1",
                OpenAiResponsesProtocol.MODEL_SNAPSHOT,
                0,
                1,
                1_000,
                NOW.minusSeconds(1),
                NOW.plusSeconds(1)
            )
        );
    }

    @Test
    void expiredTenantBucketsAreReclaimedWithoutUnboundingMemory() {
        OpenAiResponsesTransportControls.RateLimiter limiter =
            new OpenAiResponsesTransportControls.RateLimiter(
                1,
                10,
                1,
                Duration.ofMinutes(1)
            );
        OpenAiResponsesTransportControls.RateLimiter.RatePermit first =
            limiter.reserve(hash("tenant-one"), NOW);
        assertTrue(first.allowed());
        limiter.commit(first);

        OpenAiResponsesTransportControls.RateLimiter.RatePermit second =
            limiter.reserve(hash("tenant-two"), NOW.plusSeconds(61));
        assertTrue(second.allowed());
        limiter.rollback(second);
    }

    @Test
    void cancellationIsRejectedWithoutAReservation() {
        AtomicReference<OpenAiResponsesTransportControls.KillSwitchSnapshot> source =
            new AtomicReference<>(killSwitch(true, 7));
        assertFailure(
            admission(source, 10, 100, 3, costPolicy(1_000_000)),
            request(true),
            OpenAiResponsesTransportException.Failure.CANCELLED
        );
    }

    private static OpenAiResponsesTransportAdmission admission(
        AtomicReference<OpenAiResponsesTransportControls.KillSwitchSnapshot> source,
        int perTenantLimit,
        int globalLimit,
        int circuitThreshold,
        OpenAiResponsesTransportControls.CostPolicy costPolicy
    ) {
        OpenAiResponsesTransportControls.KillSwitchSnapshot snapshot = source.get();
        return new OpenAiResponsesTransportAdmission(
            TENANT_HASH,
            source::get,
            snapshot.generation(),
            snapshot.evidenceHash(),
            new OpenAiResponsesTransportControls.CircuitBreaker(
                circuitThreshold,
                Duration.ofSeconds(30)
            ),
            new OpenAiResponsesTransportControls.RateLimiter(
                perTenantLimit,
                globalLimit,
                100,
                Duration.ofMinutes(1)
            ),
            costPolicy,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static OpenAiResponsesTransportControls.KillSwitchSnapshot killSwitch(
        boolean enabled,
        long generation
    ) {
        return new OpenAiResponsesTransportControls.KillSwitchSnapshot(
            OpenAiResponsesProtocol.PROVIDER_ID,
            OpenAiResponsesProtocol.PROVIDER_VERSION,
            generation,
            enabled,
            "kill-policy-v1"
        );
    }

    private static OpenAiResponsesTransportControls.CostPolicy costPolicy(long ceiling) {
        return new OpenAiResponsesTransportControls.CostPolicy(
            "pricing-v1",
            OpenAiResponsesProtocol.MODEL_SNAPSHOT,
            1,
            2,
            ceiling,
            NOW.minusSeconds(60),
            NOW.plusSeconds(600)
        );
    }

    private static OpenAiResponsesTransportPort.Request request(boolean cancelled) {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        return new OpenAiResponsesTransportPort.Request(
            body,
            OpenAiResponsesProtocol.sha256(body),
            Duration.ofSeconds(2),
            Duration.ofSeconds(10),
            () -> cancelled
        );
    }

    private static void assertFailure(
        OpenAiResponsesTransportAdmission admission,
        OpenAiResponsesTransportPort.Request request,
        OpenAiResponsesTransportException.Failure expected
    ) {
        OpenAiResponsesTransportException failure = assertThrows(
            OpenAiResponsesTransportException.class,
            () -> admission.admit(request, 2_048)
        );
        assertEquals(expected, failure.failure());
    }

    private static String hash(String value) {
        return OpenAiResponsesProtocol.sha256Utf8(value);
    }
}
