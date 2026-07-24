package io.github.akaryc1b.approval.sdk.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.akaryc1b.approval.sdk.v1.SdkTelemetryAggregationV1.ScriptedTelemetryAggregationStore;
import io.github.akaryc1b.approval.sdk.v1.SdkTelemetryAggregationV1.TelemetryAggregationPolicy;
import io.github.akaryc1b.approval.sdk.v1.SdkTelemetryAggregationV1.TelemetryAggregationResult;
import io.github.akaryc1b.approval.sdk.v1.SdkTelemetrySignalV1.ReferenceTelemetrySignal;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SdkTelemetryAggregationV1Test {
    @Test
    void sharedFixtureProducesExactAggregationAndRolloverResults() throws IOException {
        Map<String, Object> fixture = AggregationReconciliationFixtureSupport.fixture();
        List<ReferenceTelemetrySignal> signals = AggregationReconciliationFixtureSupport.signals(
            fixture
        );
        ScriptedTelemetryAggregationStore store = new ScriptedTelemetryAggregationStore(
            AggregationReconciliationFixtureSupport.policy(fixture)
        );
        List<TelemetryAggregationResult> results = List.of(
            store.aggregate(signals.get(0)),
            store.aggregate(signals.get(0)),
            store.aggregate(signals.get(1)),
            store.aggregate(signals.get(2))
        );
        Map<String, Object> expectations = AggregationReconciliationFixtureSupport.object(
            fixture,
            "expectations"
        );
        List<Object> actual = results.stream()
            .map(SdkTelemetryAggregationV1::resultMap)
            .map(value -> (Object) value)
            .toList();
        assertEquals(
            CanonicalJson.canonicalizeValue(expectations.get("aggregationResults")),
            CanonicalJson.canonicalizeValue(actual)
        );
        assertEquals(
            CanonicalJson.canonicalizeValue(expectations.get("activeSnapshots")),
            CanonicalJson.canonicalizeValue(
                store.snapshots().stream()
                    .map(SdkTelemetryAggregationV1::snapshotMap)
                    .map(value -> (Object) value)
                    .toList()
            )
        );
    }

    @Test
    void duplicateAndRolloverPreserveQuantityWithoutPartialState() throws IOException {
        Map<String, Object> fixture = AggregationReconciliationFixtureSupport.fixture();
        List<ReferenceTelemetrySignal> signals = AggregationReconciliationFixtureSupport.signals(
            fixture
        );
        ScriptedTelemetryAggregationStore store = new ScriptedTelemetryAggregationStore(
            AggregationReconciliationFixtureSupport.policy(fixture)
        );
        store.aggregate(signals.get(0));
        TelemetryAggregationResult duplicate = store.aggregate(signals.get(0));
        TelemetryAggregationResult combined = store.aggregate(signals.get(1));
        TelemetryAggregationResult rollover = store.aggregate(signals.get(2));
        assertEquals("duplicate", duplicate.status().name().toLowerCase());
        assertEquals(5L, combined.snapshot().totalQuantity());
        assertEquals(5L, rollover.rolledOverSnapshots().get(0).totalQuantity());
        assertEquals(20L, rollover.snapshot().windowStartOrdinal());
    }

    @Test
    void forgedSignalOrdinalRegressionAndScriptedFailureFailClosed() throws IOException {
        Map<String, Object> fixture = AggregationReconciliationFixtureSupport.fixture();
        List<ReferenceTelemetrySignal> signals = AggregationReconciliationFixtureSupport.signals(
            fixture
        );
        ScriptedTelemetryAggregationStore store = new ScriptedTelemetryAggregationStore(
            AggregationReconciliationFixtureSupport.policy(fixture),
            List.of(2)
        );
        store.aggregate(signals.get(0));
        String before = CanonicalJson.canonicalizeValue(
            store.snapshots().stream().map(SdkTelemetryAggregationV1::snapshotMap).toList()
        );
        assertEquals(
            "telemetry_aggregation_store_failed",
            store.aggregate(signals.get(1)).reasonCode()
        );
        ReferenceTelemetrySignal forged = new ReferenceTelemetrySignal(
            signals.get(0).contractVersion(),
            signals.get(0).signalId(),
            signals.get(0).signalName(),
            signals.get(0).signalKind(),
            signals.get(0).endpointId(),
            signals.get(0).operation(),
            signals.get(0).requestId(),
            signals.get(0).traceId(),
            signals.get(0).bindingId(),
            signals.get(0).authenticationContextId(),
            signals.get(0).outcome(),
            signals.get(0).quantity(),
            signals.get(0).ordinal(),
            signals.get(0).provenanceDigest(),
            signals.get(0).attributes(),
            signals.get(0).aggregationIdentityDigest(),
            "forged"
        );
        assertEquals("telemetry_signal_invalid", store.aggregate(forged).reasonCode());
        assertEquals(
            before,
            CanonicalJson.canonicalizeValue(
                store.snapshots().stream().map(SdkTelemetryAggregationV1::snapshotMap).toList()
            )
        );
        ScriptedTelemetryAggregationStore regression = new ScriptedTelemetryAggregationStore(
            AggregationReconciliationFixtureSupport.policy(fixture)
        );
        regression.aggregate(signals.get(1));
        assertEquals(
            "telemetry_aggregation_ordinal_regression",
            regression.aggregate(signals.get(0)).reasonCode()
        );
    }

    @Test
    void unsafePolicyBoundsAndIdentityCapacityAreRejected() throws IOException {
        Map<String, Object> fixture = AggregationReconciliationFixtureSupport.fixture();
        TelemetryAggregationPolicy policy = AggregationReconciliationFixtureSupport.policy(fixture);
        assertThrows(
            SdkTelemetrySignalV1.UnsupportedTelemetryHandoffVersionException.class,
            () -> new TelemetryAggregationPolicy(
                "2",
                policy.windowSizeOrdinals(),
                policy.deduplicationWindowOrdinals(),
                policy.maxActiveWindows(),
                policy.maxAggregationIdentities(),
                policy.maxTrackedSignalDigests()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new TelemetryAggregationPolicy("1", 10, 20, 0, 3, 8)
        );
        List<ReferenceTelemetrySignal> signals = AggregationReconciliationFixtureSupport.signals(
            fixture
        );
        ScriptedTelemetryAggregationStore store = new ScriptedTelemetryAggregationStore(
            new TelemetryAggregationPolicy("1", 100, 20, 1, 1, 8)
        );
        assertEquals("aggregated", store.aggregate(signals.get(0)).status().name().toLowerCase());
        String before = CanonicalJson.canonicalizeValue(
            store.snapshots().stream().map(SdkTelemetryAggregationV1::snapshotMap).toList()
        );
        TelemetryAggregationResult rejected = store.aggregate(signals.get(2));
        assertEquals("capacity_rejected", rejected.status().name().toLowerCase());
        assertEquals(
            before,
            CanonicalJson.canonicalizeValue(
                store.snapshots().stream().map(SdkTelemetryAggregationV1::snapshotMap).toList()
            )
        );
        assertFalse(rejected.rolledOverSnapshots().iterator().hasNext());
        assertTrue(store.activeIdentityCount() == 1);
    }
}
