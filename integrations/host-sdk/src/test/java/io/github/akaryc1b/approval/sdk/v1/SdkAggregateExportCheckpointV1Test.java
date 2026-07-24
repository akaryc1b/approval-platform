package io.github.akaryc1b.approval.sdk.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SdkAggregateExportCheckpointV1Test {
    @Test
    void sharedFixtureProducesExactCheckpointAndDuplicate() throws IOException {
        Map<String, Object> fixture = CheckpointEscalationFixtureSupport.fixture();
        SdkAggregateExportCheckpointV1.ScriptedAggregateCheckpointStore store =
            new SdkAggregateExportCheckpointV1.ScriptedAggregateCheckpointStore(4);
        SdkAggregateExportCheckpointV1.AggregateExportCheckpointResult recorded =
            SdkAggregateExportCheckpointV1.record(
                CheckpointEscalationFixtureSupport.checkpointPolicy(fixture),
                CheckpointEscalationFixtureSupport.checkpointInput(fixture),
                store
            );
        SdkAggregateExportCheckpointV1.AggregateExportCheckpointResult duplicate =
            SdkAggregateExportCheckpointV1.record(
                CheckpointEscalationFixtureSupport.checkpointPolicy(fixture),
                CheckpointEscalationFixtureSupport.checkpointInput(fixture),
                store
            );
        Map<String, Object> expectations = CheckpointEscalationFixtureSupport.object(
            fixture,
            "expectations"
        );
        assertCanonicalEquals(
            expectations.get("checkpointResult"),
            SdkAggregateExportCheckpointV1.resultMap(recorded)
        );
        assertCanonicalEquals(
            expectations.get("duplicateCheckpointResult"),
            SdkAggregateExportCheckpointV1.resultMap(duplicate)
        );
        assertEquals(1, store.size());
    }

    @Test
    void partialExportAndDuplicateSnapshotStoreNoCheckpoint() throws IOException {
        Map<String, Object> fixture = CheckpointEscalationFixtureSupport.fixture();
        SdkAggregateExportCheckpointV1.AggregateExportCheckpointInput base =
            CheckpointEscalationFixtureSupport.checkpointInput(fixture);
        SdkAggregateExportCheckpointV1.ScriptedAggregateCheckpointStore partialStore =
            new SdkAggregateExportCheckpointV1.ScriptedAggregateCheckpointStore(4);
        SdkAggregateExportCheckpointV1.AggregateExportCheckpointResult partial =
            SdkAggregateExportCheckpointV1.record(
                CheckpointEscalationFixtureSupport.checkpointPolicy(fixture),
                new SdkAggregateExportCheckpointV1.AggregateExportCheckpointInput(
                    base.contractVersion(),
                    base.streamReference(),
                    base.checkpointOrdinal(),
                    base.previousCheckpointDigest(),
                    base.snapshots(),
                    List.of(base.snapshots().get(0).snapshotDigest())
                ),
                partialStore
            );
        assertEquals("aggregate_checkpoint_partial_export", partial.reasonCode());
        assertEquals(0, partialStore.size());

        SdkTelemetryAggregationV1.TelemetryAggregationSnapshot snapshot =
            base.snapshots().get(0);
        SdkAggregateExportCheckpointV1.ScriptedAggregateCheckpointStore duplicateStore =
            new SdkAggregateExportCheckpointV1.ScriptedAggregateCheckpointStore(4);
        SdkAggregateExportCheckpointV1.AggregateExportCheckpointResult duplicate =
            SdkAggregateExportCheckpointV1.record(
                CheckpointEscalationFixtureSupport.checkpointPolicy(fixture),
                new SdkAggregateExportCheckpointV1.AggregateExportCheckpointInput(
                    base.contractVersion(),
                    base.streamReference(),
                    base.checkpointOrdinal(),
                    base.previousCheckpointDigest(),
                    List.of(snapshot, snapshot),
                    List.of(snapshot.snapshotDigest(), snapshot.snapshotDigest())
                ),
                duplicateStore
            );
        assertEquals("aggregate_checkpoint_duplicate_snapshot", duplicate.reasonCode());
        assertEquals(0, duplicateStore.size());
    }

    @Test
    void checkpointChainRejectsContinuityOrdinalAndSnapshotReuse() throws IOException {
        Map<String, Object> fixture = CheckpointEscalationFixtureSupport.fixture();
        SdkAggregateExportCheckpointV1.AggregateExportCheckpointPolicy policy =
            CheckpointEscalationFixtureSupport.checkpointPolicy(fixture);
        SdkAggregateExportCheckpointV1.AggregateExportCheckpointInput base =
            CheckpointEscalationFixtureSupport.checkpointInput(fixture);
        SdkAggregateExportCheckpointV1.ScriptedAggregateCheckpointStore store =
            new SdkAggregateExportCheckpointV1.ScriptedAggregateCheckpointStore(4);
        SdkAggregateExportCheckpointV1.AggregateExportCheckpointResult first =
            SdkAggregateExportCheckpointV1.record(policy, base, store);

        assertEquals(
            "aggregate_checkpoint_continuity_mismatch",
            SdkAggregateExportCheckpointV1.record(
                policy,
                copy(base, base.streamReference(), 50, "wrong"),
                store
            ).reasonCode()
        );
        assertEquals(
            "aggregate_checkpoint_ordinal_regression",
            SdkAggregateExportCheckpointV1.record(
                policy,
                copy(
                    base,
                    base.streamReference(),
                    40,
                    first.checkpoint().checkpointDigest()
                ),
                store
            ).reasonCode()
        );
        assertEquals(
            "aggregate_checkpoint_snapshot_reuse",
            SdkAggregateExportCheckpointV1.record(
                policy,
                copy(
                    base,
                    base.streamReference(),
                    50,
                    first.checkpoint().checkpointDigest()
                ),
                store
            ).reasonCode()
        );
        assertEquals(1, store.size());
    }

    @Test
    void checkpointCapacityFailureAndPolicyBoundsAreAtomic() throws IOException {
        Map<String, Object> fixture = CheckpointEscalationFixtureSupport.fixture();
        SdkAggregateExportCheckpointV1.AggregateExportCheckpointInput base =
            CheckpointEscalationFixtureSupport.checkpointInput(fixture);
        SdkAggregateExportCheckpointV1.AggregateExportCheckpointPolicy one =
            new SdkAggregateExportCheckpointV1.AggregateExportCheckpointPolicy("1", 4, 1);
        SdkAggregateExportCheckpointV1.ScriptedAggregateCheckpointStore capacity =
            new SdkAggregateExportCheckpointV1.ScriptedAggregateCheckpointStore(1);
        SdkAggregateExportCheckpointV1.record(one, base, capacity);
        assertEquals(
            SdkAggregateExportCheckpointV1.AggregateCheckpointStatus.CAPACITY_REJECTED,
            SdkAggregateExportCheckpointV1.record(
                one,
                copy(base, "another-stream", 50, null),
                capacity
            ).status()
        );
        assertEquals(1, capacity.size());

        SdkAggregateExportCheckpointV1.ScriptedAggregateCheckpointStore failing =
            new SdkAggregateExportCheckpointV1.ScriptedAggregateCheckpointStore(
                4,
                List.of(1)
            );
        assertEquals(
            "aggregate_checkpoint_store_failed",
            SdkAggregateExportCheckpointV1.record(
                CheckpointEscalationFixtureSupport.checkpointPolicy(fixture),
                base,
                failing
            ).reasonCode()
        );
        assertEquals(0, failing.size());
        assertThrows(
            SdkAggregateExportCheckpointV1.UnsupportedCheckpointEscalationVersionException.class,
            () -> new SdkAggregateExportCheckpointV1.AggregateExportCheckpointPolicy(
                "2",
                4,
                4
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new SdkAggregateExportCheckpointV1.AggregateExportCheckpointPolicy(
                "1",
                0,
                4
            )
        );
    }

    private static SdkAggregateExportCheckpointV1.AggregateExportCheckpointInput copy(
        SdkAggregateExportCheckpointV1.AggregateExportCheckpointInput base,
        String stream,
        long ordinal,
        String previous
    ) {
        return new SdkAggregateExportCheckpointV1.AggregateExportCheckpointInput(
            base.contractVersion(),
            stream,
            ordinal,
            previous,
            base.snapshots(),
            base.exportedSnapshotDigests()
        );
    }

    private static void assertCanonicalEquals(Object expected, Object actual) {
        assertEquals(
            CanonicalJson.canonicalizeValue(expected),
            CanonicalJson.canonicalizeValue(actual)
        );
    }
}
