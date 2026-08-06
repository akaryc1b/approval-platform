package io.github.akaryc1b.approval.ai.openai;

import io.github.akaryc1b.approval.connector.credential.CredentialMaterialFailure;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialSourceException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesP7FaultTestSupport.NOW;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesP7FaultTestSupport.TENANT_HASH;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesP7FaultTestSupport.TENANT_ID;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesP7FaultTestSupport.credentialRequest;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesP7FaultTestSupport.fixture;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesP7FaultTestSupport.hash;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesSecureHttpSenderTestSupport.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiResponsesRateUsageFaultAcceptanceTest {

    @Test
    void preDispatchCloseAndCancellationRecordZeroUsage() throws Exception {
        OpenAiResponsesP7FaultTestSupport.Fixture fixture = fixture(3, 1, 2, 10, 1_000_000);

        try (OpenAiResponsesTransportAdmission.Permit ignored =
                 fixture.admission().admit(request(), 100)) {
            // Closing before dispatch releases both the rate and Circuit reservations.
        }
        assertEquals(0, fixture.usage().snapshot(TENANT_HASH, NOW).committedRequests());

        OpenAiResponsesTransportPort.Request canonical = request();
        OpenAiResponsesTransportPort.Request cancelled = new OpenAiResponsesTransportPort.Request(
            canonical.bodyCopy(),
            canonical.bodyHash(),
            canonical.connectTimeout(),
            canonical.totalTimeout(),
            () -> true
        );
        OpenAiResponsesTransportException failure = assertThrows(
            OpenAiResponsesTransportException.class,
            () -> fixture.admission().admit(cancelled, 100)
        );
        assertEquals(OpenAiResponsesTransportException.Failure.CANCELLED, failure.failure());
        assertEquals(0, fixture.usage().snapshot(TENANT_HASH, NOW).committedRequests());
    }

    @Test
    void dispatchAndTerminalRecordingAreExactlyOnce() throws Exception {
        OpenAiResponsesP7FaultTestSupport.Fixture fixture = fixture(3, 2, 2, 10, 1_000_000);

        try (OpenAiResponsesTransportAdmission.Permit permit =
                 fixture.admission().admit(request(), 100)) {
            permit.markDispatched(request());
            assertThrows(
                OpenAiResponsesTransportException.class,
                () -> permit.markDispatched(request())
            );
            permit.record(OpenAiResponsesTransportControls.Outcome.SUCCESS);
            assertThrows(
                OpenAiResponsesTransportException.class,
                () -> permit.record(OpenAiResponsesTransportControls.Outcome.SUCCESS)
            );
        }

        var snapshot = fixture.usage().snapshot(TENANT_HASH, NOW);
        assertEquals(1, snapshot.committedRequests());
        assertTrue(snapshot.committedUpperBoundMicros() > 0);
        assertFalse(snapshot.actualProviderCost());
        assertFalse(snapshot.durable());
    }

    @Test
    void delayedDispatchRemainsOwnedByOriginalRateWindow() throws Exception {
        OpenAiResponsesP7FaultTestSupport.Fixture fixture = fixture(3, 2, 3, 10, 1_000_000);
        OpenAiResponsesTransportAdmission.Permit permit =
            fixture.admission().admit(request(), 100);

        fixture.clock().advance(Duration.ofSeconds(61));
        try (permit) {
            permit.markDispatched(request());
            permit.record(OpenAiResponsesTransportControls.Outcome.SUCCESS);
        }

        assertEquals(1, fixture.usage().snapshot(TENANT_HASH, NOW).committedRequests());
        assertEquals(
            0,
            fixture.usage().snapshot(TENANT_HASH, NOW.plusSeconds(61)).committedRequests()
        );
    }

    @Test
    void tenantAndGlobalSaturationRemainBoundedAndRedacted() {
        OpenAiResponsesRuntimeUsageLedger ledger = new OpenAiResponsesRuntimeUsageLedger(
            2,
            2,
            10,
            Duration.ofMinutes(1),
            1_000
        );
        String tenantA = hash("tenant\ntenant-a");
        String tenantB = hash("tenant\ntenant-b");
        ledger.recordDispatched(tenantA, windowStart(NOW), 300);
        ledger.recordDispatched(tenantB, windowStart(NOW), 400);

        var first = ledger.snapshot(tenantA, NOW);
        var second = ledger.snapshot(tenantB, NOW);
        assertEquals(1, first.committedRequests());
        assertEquals(1, second.committedRequests());
        assertTrue(first.globalSaturated());
        assertTrue(second.globalSaturated());
        assertFalse(first.tenantSaturated());
        assertFalse(second.tenantSaturated());
        assertNotEquals(first.evidenceHash(), second.evidenceHash());
        assertFalse(first.toString().contains(tenantB));
    }

    @Test
    void tenantCapacityAndEnvelopeOverflowFailClosed() {
        OpenAiResponsesRuntimeUsageLedger bounded = new OpenAiResponsesRuntimeUsageLedger(
            2,
            4,
            1,
            Duration.ofMinutes(1),
            1_000
        );
        bounded.recordDispatched(hash("tenant\none"), windowStart(NOW), 100);
        assertThrows(
            IllegalStateException.class,
            () -> bounded.recordDispatched(hash("tenant\ntwo"), windowStart(NOW), 100)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new OpenAiResponsesRuntimeUsageLedger(
                1_000_000,
                1_000_000,
                1,
                Duration.ofMinutes(1),
                Long.MAX_VALUE
            )
        );
    }

    @Test
    void expiredAndFutureCostPoliciesRejectBeforeDispatch() {
        OpenAiResponsesTransportControls.CostPolicy expired =
            OpenAiResponsesP7FaultTestSupport.costPolicy(
                1_000_000,
                NOW.minusSeconds(600),
                NOW.minusSeconds(1)
            );
        OpenAiResponsesTransportControls.CostPolicy future =
            OpenAiResponsesP7FaultTestSupport.costPolicy(
                1_000_000,
                NOW.plusSeconds(1),
                NOW.plusSeconds(600)
            );

        assertCostPolicyStale(expired);
        assertCostPolicyStale(future);
    }

    @Test
    void expiredAndFutureSecretVersionsFailBeforeMaterialRead() {
        assertCredentialWindowFailure(
            NOW.minusSeconds(600),
            NOW,
            CredentialMaterialFailure.CREDENTIAL_EXPIRED
        );
        assertCredentialWindowFailure(
            NOW.plusSeconds(1),
            NOW.plusSeconds(600),
            CredentialMaterialFailure.CREDENTIAL_NOT_YET_VALID
        );
    }

    private static void assertCostPolicyStale(
        OpenAiResponsesTransportControls.CostPolicy policy
    ) {
        OpenAiResponsesTransportException failure = assertThrows(
            OpenAiResponsesTransportException.class,
            () -> policy.estimate(100, 100, NOW)
        );
        assertEquals(
            OpenAiResponsesTransportException.Failure.COST_POLICY_STALE,
            failure.failure()
        );
    }

    private static void assertCredentialWindowFailure(
        Instant effectiveFrom,
        Instant expiresAt,
        CredentialMaterialFailure expected
    ) {
        var request = credentialRequest(TENANT_ID, effectiveFrom, expiresAt);
        OpenAiResponsesP7FaultTestSupport.MutableEnvironment environment =
            new OpenAiResponsesP7FaultTestSupport.MutableEnvironment(
                "sk-p7-secret",
                "key-v1"
            );
        OpenAiEnvironmentCredentialMaterialSource source =
            new OpenAiEnvironmentCredentialMaterialSource(
                request,
                environment,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new AtomicLong()::incrementAndGet
            );

        CredentialMaterialSourceException failure = assertThrows(
            CredentialMaterialSourceException.class,
            () -> source.openLease(request)
        );
        assertEquals(expected, failure.failure());
        assertEquals(0, environment.secretReads.get());
        assertEquals(0, environment.versionReads.get());
    }

    private static Instant windowStart(Instant value) {
        long epoch = Math.floorDiv(value.getEpochSecond(), 60) * 60;
        return Instant.ofEpochSecond(epoch);
    }
}
