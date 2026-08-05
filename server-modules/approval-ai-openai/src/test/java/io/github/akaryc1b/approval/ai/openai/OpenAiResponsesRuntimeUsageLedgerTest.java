package io.github.akaryc1b.approval.ai.openai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiResponsesRuntimeUsageLedgerTest {

    private static final Instant NOW = Instant.parse("2026-08-05T11:15:00Z");
    private static final String TENANT_A = hash("tenant-a");
    private static final String TENANT_B = hash("tenant-b");

    @Test
    void dispatchedUsageIsTenantIsolatedAndBoundedByTheRateEnvelope() {
        OpenAiResponsesRuntimeUsageLedger ledger = ledger(2, 3, 10);

        ledger.recordDispatched(TENANT_A, NOW, 400);
        ledger.recordDispatched(TENANT_A, NOW, 500);
        ledger.recordDispatched(TENANT_B, NOW, 300);

        var tenantA = ledger.snapshot(TENANT_A, NOW);
        var tenantB = ledger.snapshot(TENANT_B, NOW);

        assertEquals(2, tenantA.committedRequests());
        assertEquals(2, tenantA.requestLimit());
        assertEquals(0, tenantA.remainingRequests());
        assertEquals(900, tenantA.committedUpperBoundMicros());
        assertEquals(2_000, tenantA.derivedEnvelopeMicros());
        assertEquals(1_100, tenantA.remainingDerivedEnvelopeMicros());
        assertTrue(tenantA.tenantSaturated());
        assertTrue(tenantA.globalSaturated());

        assertEquals(1, tenantB.committedRequests());
        assertEquals(300, tenantB.committedUpperBoundMicros());
        assertFalse(tenantB.tenantSaturated());
        assertTrue(tenantB.globalSaturated());
        assertNotEquals(tenantA.evidenceHash(), tenantB.evidenceHash());
        assertFalse(tenantA.toString().contains("tenant-a"));
    }

    @Test
    void snapshotsAreSideEffectFreeAndDoNotConsumeTenantCapacity() {
        OpenAiResponsesRuntimeUsageLedger ledger = ledger(1, 2, 1);

        var empty = ledger.snapshot(TENANT_A, NOW);
        assertEquals(0, empty.committedRequests());

        ledger.recordDispatched(TENANT_B, NOW, 100);
        assertEquals(1, ledger.snapshot(TENANT_B, NOW).committedRequests());
    }

    @Test
    void aNewWindowReturnsZeroWithoutClaimingDurabilityOrActualBilling() {
        OpenAiResponsesRuntimeUsageLedger ledger = ledger(2, 3, 10);
        ledger.recordDispatched(TENANT_A, NOW, 400);

        var nextWindow = ledger.snapshot(TENANT_A, NOW.plusSeconds(60));

        assertEquals(0, nextWindow.committedRequests());
        assertEquals(0, nextWindow.committedUpperBoundMicros());
        assertTrue(nextWindow.processLocal());
        assertFalse(nextWindow.durable());
        assertFalse(nextWindow.actualProviderCost());
    }

    @Test
    void invalidOrOutOfEnvelopeRecordsFailClosed() {
        OpenAiResponsesRuntimeUsageLedger ledger = ledger(1, 1, 1);

        assertThrows(
            IllegalArgumentException.class,
            () -> ledger.recordDispatched(TENANT_A, NOW, 1_001)
        );
        ledger.recordDispatched(TENANT_A, NOW, 1_000);
        assertThrows(
            IllegalStateException.class,
            () -> ledger.recordDispatched(TENANT_A, NOW, 1)
        );
    }

    private static OpenAiResponsesRuntimeUsageLedger ledger(
        int tenantLimit,
        int globalLimit,
        int maximumTenants
    ) {
        return new OpenAiResponsesRuntimeUsageLedger(
            tenantLimit,
            globalLimit,
            maximumTenants,
            Duration.ofSeconds(60),
            1_000
        );
    }

    private static String hash(String value) {
        return OpenAiResponsesProtocol.sha256Utf8(value);
    }
}
