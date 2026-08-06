package io.github.akaryc1b.approval.ai.openai;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiResponsesProductionRuntimeUsageSnapshotTest {

    private static final Instant NOW = Instant.parse("2026-08-05T11:25:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void usageSnapshotIsSideEffectFreeHashOnlyAndDoesNotCreateProviderBinding()
        throws ReflectiveOperationException {
        OpenAiResponsesProductionRuntimeFactory factory =
            new OpenAiResponsesProductionRuntimeFactory(profile(), CLOCK);

        assertEquals(0, bindingCount(factory));
        var usage = factory.usageSnapshot("tenant-a");

        assertEquals(0, bindingCount(factory));
        assertEquals(NOW, usage.observedAt());
        assertEquals(0, usage.committedRequests());
        assertEquals(10, usage.requestLimit());
        assertEquals(10, usage.remainingRequests());
        assertEquals(10_000_000, usage.derivedEnvelopeMicros());
        assertTrue(usage.processLocal());
        assertFalse(usage.durable());
        assertFalse(usage.actualProviderCost());
        assertFalse(usage.toString().contains("tenant-a"));
        assertEquals(64, usage.tenantHash().length());
        assertEquals(64, usage.evidenceHash().length());
    }

    @Test
    void tenantScopesProduceIndependentEvidenceWithoutExposingGlobalExactUsage() {
        OpenAiResponsesProductionRuntimeFactory factory =
            new OpenAiResponsesProductionRuntimeFactory(profile(), CLOCK);

        var tenantA = factory.usageSnapshot("tenant-a");
        var tenantB = factory.usageSnapshot("tenant-b");

        assertNotEquals(tenantA.tenantHash(), tenantB.tenantHash());
        assertNotEquals(tenantA.evidenceHash(), tenantB.evidenceHash());
        assertFalse(tenantA.globalSaturated());
        assertFalse(tenantB.globalSaturated());
    }

    private static int bindingCount(OpenAiResponsesProductionRuntimeFactory factory)
        throws ReflectiveOperationException {
        Field field = OpenAiResponsesProductionRuntimeFactory.class.getDeclaredField("bindings");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(factory)).size();
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
            3,
            Duration.ofSeconds(60)
        );
    }
}
