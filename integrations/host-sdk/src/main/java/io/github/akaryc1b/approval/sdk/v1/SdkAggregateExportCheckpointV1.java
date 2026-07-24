package io.github.akaryc1b.approval.sdk.v1;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic aggregate export checkpoints over complete reference-only snapshot sets. */
public final class SdkAggregateExportCheckpointV1 {
    public static final String CONTRACT_VERSION = SdkCheckpointEscalationV1.CONTRACT_VERSION;
    private static final int MAX_BOUND = 10_000;

    private SdkAggregateExportCheckpointV1() {
    }

    public enum AggregateCheckpointStatus {
        RECORDED,
        DUPLICATE,
        CAPACITY_REJECTED,
        FAILED_CLOSED
    }

    public enum AggregateCheckpointAppendStatus {
        ACCEPTED,
        DUPLICATE_REJECTED,
        CAPACITY_REJECTED,
        CONTINUITY_REJECTED,
        ORDINAL_REJECTED,
        SNAPSHOT_REUSE_REJECTED
    }

    public record AggregateExportCheckpointPolicy(
        String contractVersion,
        int maxSnapshotsPerCheckpoint,
        int maxStoredCheckpoints
    ) {
        public AggregateExportCheckpointPolicy {
            requireVersion(contractVersion);
            requireRange(
                maxSnapshotsPerCheckpoint,
                1,
                MAX_BOUND,
                "aggregateCheckpointPolicy.maxSnapshotsPerCheckpoint"
            );
            requireRange(
                maxStoredCheckpoints,
                1,
                MAX_BOUND,
                "aggregateCheckpointPolicy.maxStoredCheckpoints"
            );
        }
    }

    public record AggregateExportCheckpointInput(
        String contractVersion,
        String streamReference,
        long checkpointOrdinal,
        String previousCheckpointDigest,
        List<SdkTelemetryAggregationV1.TelemetryAggregationSnapshot> snapshots,
        List<String> exportedSnapshotDigests
    ) {
        public AggregateExportCheckpointInput {
            requireVersion(contractVersion);
            streamReference = required(streamReference, "aggregateCheckpoint.streamReference");
            requireNonNegative(checkpointOrdinal, "aggregateCheckpoint.checkpointOrdinal");
            snapshots = List.copyOf(Objects.requireNonNull(snapshots, "snapshots"));
            exportedSnapshotDigests = List.copyOf(
                Objects.requireNonNull(exportedSnapshotDigests, "exportedSnapshotDigests")
            );
            if (previousCheckpointDigest != null) {
                previousCheckpointDigest = required(
                    previousCheckpointDigest,
                    "aggregateCheckpoint.previousCheckpointDigest"
                );
            }
        }
    }

    public record AggregateExportCheckpoint(
        String contractVersion,
        String checkpointId,
        String streamReference,
        long checkpointOrdinal,
        String previousCheckpointDigest,
        int snapshotCount,
        String firstSnapshotDigest,
        String lastSnapshotDigest,
        long minimumWindowStartOrdinal,
        long maximumWindowEndOrdinalExclusive,
        String snapshotSetDigest,
        String checkpointDigest
    ) {
        public AggregateExportCheckpoint {
            requireVersion(contractVersion);
            checkpointId = required(checkpointId, "aggregateCheckpoint.checkpointId");
            streamReference = required(streamReference, "aggregateCheckpoint.streamReference");
            requireNonNegative(checkpointOrdinal, "aggregateCheckpoint.checkpointOrdinal");
            if (previousCheckpointDigest != null) {
                previousCheckpointDigest = required(
                    previousCheckpointDigest,
                    "aggregateCheckpoint.previousCheckpointDigest"
                );
            }
            requireRange(snapshotCount, 1, MAX_BOUND, "aggregateCheckpoint.snapshotCount");
            firstSnapshotDigest = required(
                firstSnapshotDigest,
                "aggregateCheckpoint.firstSnapshotDigest"
            );
            lastSnapshotDigest = required(
                lastSnapshotDigest,
                "aggregateCheckpoint.lastSnapshotDigest"
            );
            if (minimumWindowStartOrdinal < 0
                || maximumWindowEndOrdinalExclusive <= minimumWindowStartOrdinal) {
                throw new IllegalArgumentException("Aggregate checkpoint window bounds are invalid");
            }
            snapshotSetDigest = required(
                snapshotSetDigest,
                "aggregateCheckpoint.snapshotSetDigest"
            );
            checkpointDigest = required(
                checkpointDigest,
                "aggregateCheckpoint.checkpointDigest"
            );
        }
    }

    public record AggregateExportCheckpointResult(
        String contractVersion,
        AggregateCheckpointStatus status,
        String reasonCode,
        AggregateExportCheckpoint checkpoint,
        int storedCheckpointCount
    ) {
        public AggregateExportCheckpointResult {
            requireVersion(contractVersion);
            status = Objects.requireNonNull(status, "aggregateCheckpointResult.status");
            reasonCode = required(reasonCode, "aggregateCheckpointResult.reasonCode");
            if (storedCheckpointCount < 0) {
                throw new IllegalArgumentException("storedCheckpointCount cannot be negative");
            }
        }
    }

    public static final class UnsupportedCheckpointEscalationVersionException
        extends IllegalArgumentException {
        private final String contractVersion;

        public UnsupportedCheckpointEscalationVersionException(String contractVersion) {
            super("Unsupported checkpoint/escalation contract version: " + contractVersion);
            this.contractVersion = contractVersion;
        }

        public String contractVersion() {
            return contractVersion;
        }
    }

    public static final class ScriptedAggregateCheckpointStore {
        private final int capacity;
        private final Set<Integer> failureOperationNumbers;
        private final List<AggregateExportCheckpoint> checkpoints = new ArrayList<>();
        private final Set<String> checkpointDigests = new HashSet<>();
        private final Map<String, AggregateExportCheckpoint> latestByStream = new HashMap<>();
        private final Map<String, Set<String>> checkpointedSnapshotsByStream = new HashMap<>();
        private int appendAttempts;

        public ScriptedAggregateCheckpointStore(int capacity) {
            this(capacity, List.of());
        }

        public ScriptedAggregateCheckpointStore(
            int capacity,
            List<Integer> failureOperationNumbers
        ) {
            requireRange(capacity, 1, MAX_BOUND, "aggregateCheckpointStore.capacity");
            this.capacity = capacity;
            this.failureOperationNumbers = positiveUniqueIntegers(
                failureOperationNumbers,
                "aggregateCheckpointStore.failureOperationNumbers"
            );
        }

        public synchronized AggregateCheckpointAppendStatus append(
            AggregateExportCheckpoint checkpoint,
            List<String> snapshotDigests
        ) {
            Objects.requireNonNull(checkpoint, "checkpoint");
            Objects.requireNonNull(snapshotDigests, "snapshotDigests");
            appendAttempts++;
            if (failureOperationNumbers.contains(appendAttempts)) {
                throw new IllegalStateException("scripted aggregate checkpoint store failure");
            }
            if (checkpointDigests.contains(checkpoint.checkpointDigest())) {
                return AggregateCheckpointAppendStatus.DUPLICATE_REJECTED;
            }

            AggregateExportCheckpoint latest = latestByStream.get(checkpoint.streamReference());
            if (latest == null) {
                if (checkpoint.previousCheckpointDigest() != null) {
                    return AggregateCheckpointAppendStatus.CONTINUITY_REJECTED;
                }
            } else {
                if (!latest.checkpointDigest().equals(checkpoint.previousCheckpointDigest())) {
                    return AggregateCheckpointAppendStatus.CONTINUITY_REJECTED;
                }
                if (checkpoint.checkpointOrdinal() <= latest.checkpointOrdinal()) {
                    return AggregateCheckpointAppendStatus.ORDINAL_REJECTED;
                }
            }

            Set<String> checkpointed = checkpointedSnapshotsByStream.getOrDefault(
                checkpoint.streamReference(),
                Set.of()
            );
            if (snapshotDigests.stream().anyMatch(checkpointed::contains)) {
                return AggregateCheckpointAppendStatus.SNAPSHOT_REUSE_REJECTED;
            }
            if (checkpoints.size() >= capacity) {
                return AggregateCheckpointAppendStatus.CAPACITY_REJECTED;
            }

            Set<String> nextCheckpointed = new HashSet<>(checkpointed);
            nextCheckpointed.addAll(snapshotDigests);
            checkpoints.add(checkpoint);
            checkpointDigests.add(checkpoint.checkpointDigest());
            latestByStream.put(checkpoint.streamReference(), checkpoint);
            checkpointedSnapshotsByStream.put(
                checkpoint.streamReference(),
                Set.copyOf(nextCheckpointed)
            );
            return AggregateCheckpointAppendStatus.ACCEPTED;
        }

        public synchronized int size() {
            return checkpoints.size();
        }

        public synchronized List<AggregateExportCheckpoint> checkpoints() {
            return List.copyOf(checkpoints);
        }

        public synchronized AggregateExportCheckpoint latest(String streamReference) {
            return latestByStream.get(streamReference);
        }

        public int capacity() {
            return capacity;
        }
    }

    public static AggregateExportCheckpointResult record(
        AggregateExportCheckpointPolicy policy,
        AggregateExportCheckpointInput input,
        ScriptedAggregateCheckpointStore store
    ) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(store, "store");
        if (input.snapshots().isEmpty()
            || input.snapshots().size() > policy.maxSnapshotsPerCheckpoint()) {
            throw new IllegalArgumentException(
                "aggregateCheckpoint.snapshots is outside policy bounds"
            );
        }
        if (store.capacity() > policy.maxStoredCheckpoints()) {
            throw new IllegalArgumentException(
                "aggregateCheckpoint store capacity exceeds policy maximum"
            );
        }

        List<SdkTelemetryAggregationV1.TelemetryAggregationSnapshot> snapshots =
            input.snapshots().stream().sorted(SdkAggregateExportCheckpointV1::compare).toList();
        List<String> snapshotDigests = snapshots.stream()
            .map(SdkTelemetryAggregationV1.TelemetryAggregationSnapshot::snapshotDigest)
            .toList();
        if (new HashSet<>(snapshotDigests).size() != snapshotDigests.size()) {
            return result(
                AggregateCheckpointStatus.FAILED_CLOSED,
                "aggregate_checkpoint_duplicate_snapshot",
                null,
                store
            );
        }
        snapshots.forEach(SdkAggregateExportCheckpointV1::validateSnapshot);
        if (!snapshotDigests.equals(input.exportedSnapshotDigests())) {
            return result(
                AggregateCheckpointStatus.FAILED_CLOSED,
                "aggregate_checkpoint_partial_export",
                null,
                store
            );
        }

        long maximumLastOrdinal = snapshots.stream()
            .mapToLong(SdkTelemetryAggregationV1.TelemetryAggregationSnapshot::lastOrdinal)
            .max()
            .orElseThrow();
        if (input.checkpointOrdinal() < maximumLastOrdinal) {
            return result(
                AggregateCheckpointStatus.FAILED_CLOSED,
                "aggregate_checkpoint_before_snapshot",
                null,
                store
            );
        }

        List<Object> snapshotSetValue = snapshots.stream()
            .map(SdkTelemetryAggregationV1::snapshotMap)
            .map(value -> (Object) value)
            .toList();
        String snapshotSetDigest = digest(snapshotSetValue);
        long minimumWindowStartOrdinal = snapshots.stream()
            .mapToLong(SdkTelemetryAggregationV1.TelemetryAggregationSnapshot::windowStartOrdinal)
            .min()
            .orElseThrow();
        long maximumWindowEndOrdinalExclusive = snapshots.stream()
            .mapToLong(
                SdkTelemetryAggregationV1.TelemetryAggregationSnapshot::windowEndOrdinalExclusive
            )
            .max()
            .orElseThrow();
        String checkpointId = CanonicalJson.sha256Hex(
            ("aggregate-export-checkpoint-v1\n"
                + input.streamReference() + "\n"
                + input.checkpointOrdinal() + "\n"
                + snapshotSetDigest).getBytes(StandardCharsets.UTF_8)
        );
        Map<String, Object> checkpointValue = new LinkedHashMap<>();
        checkpointValue.put("checkpointId", checkpointId);
        checkpointValue.put("checkpointOrdinal", input.checkpointOrdinal());
        checkpointValue.put("contractVersion", CONTRACT_VERSION);
        checkpointValue.put("firstSnapshotDigest", snapshotDigests.get(0));
        checkpointValue.put("lastSnapshotDigest", snapshotDigests.get(snapshotDigests.size() - 1));
        checkpointValue.put(
            "maximumWindowEndOrdinalExclusive",
            maximumWindowEndOrdinalExclusive
        );
        checkpointValue.put("minimumWindowStartOrdinal", minimumWindowStartOrdinal);
        checkpointValue.put("previousCheckpointDigest", input.previousCheckpointDigest());
        checkpointValue.put("snapshotCount", snapshots.size());
        checkpointValue.put("snapshotSetDigest", snapshotSetDigest);
        checkpointValue.put("streamReference", input.streamReference());
        String checkpointDigest = digest(checkpointValue);
        AggregateExportCheckpoint checkpoint = new AggregateExportCheckpoint(
            CONTRACT_VERSION,
            checkpointId,
            input.streamReference(),
            input.checkpointOrdinal(),
            input.previousCheckpointDigest(),
            snapshots.size(),
            snapshotDigests.get(0),
            snapshotDigests.get(snapshotDigests.size() - 1),
            minimumWindowStartOrdinal,
            maximumWindowEndOrdinalExclusive,
            snapshotSetDigest,
            checkpointDigest
        );

        AggregateCheckpointAppendStatus appendStatus;
        try {
            appendStatus = store.append(checkpoint, snapshotDigests);
        } catch (RuntimeException exception) {
            return result(
                AggregateCheckpointStatus.FAILED_CLOSED,
                "aggregate_checkpoint_store_failed",
                checkpoint,
                store
            );
        }
        return switch (appendStatus) {
            case DUPLICATE_REJECTED -> result(
                AggregateCheckpointStatus.DUPLICATE,
                "aggregate_checkpoint_duplicate",
                checkpoint,
                store
            );
            case CAPACITY_REJECTED -> result(
                AggregateCheckpointStatus.CAPACITY_REJECTED,
                "aggregate_checkpoint_store_capacity",
                checkpoint,
                store
            );
            case CONTINUITY_REJECTED -> result(
                AggregateCheckpointStatus.FAILED_CLOSED,
                "aggregate_checkpoint_continuity_mismatch",
                checkpoint,
                store
            );
            case ORDINAL_REJECTED -> result(
                AggregateCheckpointStatus.FAILED_CLOSED,
                "aggregate_checkpoint_ordinal_regression",
                checkpoint,
                store
            );
            case SNAPSHOT_REUSE_REJECTED -> result(
                AggregateCheckpointStatus.FAILED_CLOSED,
                "aggregate_checkpoint_snapshot_reuse",
                checkpoint,
                store
            );
            case ACCEPTED -> result(
                AggregateCheckpointStatus.RECORDED,
                "aggregate_checkpoint_recorded",
                checkpoint,
                store
            );
        };
    }

    public static Map<String, Object> checkpointMap(AggregateExportCheckpoint value) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("checkpointDigest", value.checkpointDigest());
        output.put("checkpointId", value.checkpointId());
        output.put("checkpointOrdinal", value.checkpointOrdinal());
        output.put("contractVersion", value.contractVersion());
        output.put("firstSnapshotDigest", value.firstSnapshotDigest());
        output.put("lastSnapshotDigest", value.lastSnapshotDigest());
        output.put(
            "maximumWindowEndOrdinalExclusive",
            value.maximumWindowEndOrdinalExclusive()
        );
        output.put("minimumWindowStartOrdinal", value.minimumWindowStartOrdinal());
        output.put("previousCheckpointDigest", value.previousCheckpointDigest());
        output.put("snapshotCount", value.snapshotCount());
        output.put("snapshotSetDigest", value.snapshotSetDigest());
        output.put("streamReference", value.streamReference());
        return output;
    }

    public static Map<String, Object> resultMap(AggregateExportCheckpointResult value) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put(
            "checkpoint",
            value.checkpoint() == null ? null : checkpointMap(value.checkpoint())
        );
        output.put("contractVersion", value.contractVersion());
        output.put("reasonCode", value.reasonCode());
        output.put("status", lower(value.status()));
        output.put("storedCheckpointCount", value.storedCheckpointCount());
        return output;
    }

    private static AggregateExportCheckpointResult result(
        AggregateCheckpointStatus status,
        String reasonCode,
        AggregateExportCheckpoint checkpoint,
        ScriptedAggregateCheckpointStore store
    ) {
        return new AggregateExportCheckpointResult(
            CONTRACT_VERSION,
            status,
            reasonCode,
            checkpoint,
            store.size()
        );
    }

    private static void validateSnapshot(
        SdkTelemetryAggregationV1.TelemetryAggregationSnapshot snapshot
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        requireVersion(snapshot.contractVersion());
        if (snapshot.windowStartOrdinal() < 0
            || snapshot.windowEndOrdinalExclusive() <= snapshot.windowStartOrdinal()
            || snapshot.totalQuantity() < 1
            || snapshot.signalCount() < 1
            || snapshot.firstOrdinal() < snapshot.windowStartOrdinal()
            || snapshot.lastOrdinal() < snapshot.firstOrdinal()
            || snapshot.lastOrdinal() >= snapshot.windowEndOrdinalExclusive()) {
            throw new IllegalArgumentException("aggregateSnapshot ordinal range is invalid");
        }
        Map<String, Object> value = new LinkedHashMap<>(
            SdkTelemetryAggregationV1.snapshotMap(snapshot)
        );
        value.remove("snapshotDigest");
        if (!digest(value).equals(snapshot.snapshotDigest())) {
            throw new IllegalArgumentException("aggregateSnapshot digest mismatch");
        }
    }

    private static int compare(
        SdkTelemetryAggregationV1.TelemetryAggregationSnapshot left,
        SdkTelemetryAggregationV1.TelemetryAggregationSnapshot right
    ) {
        int window = Long.compare(left.windowStartOrdinal(), right.windowStartOrdinal());
        if (window != 0) {
            return window;
        }
        int identity = left.aggregationIdentityDigest().compareTo(
            right.aggregationIdentityDigest()
        );
        return identity != 0
            ? identity
            : left.snapshotDigest().compareTo(right.snapshotDigest());
    }

    private static String digest(Object value) {
        return CanonicalJson.sha256Hex(
            CanonicalJson.canonicalizeValue(value).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static Set<Integer> positiveUniqueIntegers(List<Integer> values, String field) {
        Objects.requireNonNull(values, field);
        Set<Integer> output = new HashSet<>();
        for (Integer value : values) {
            Objects.requireNonNull(value, field);
            requireRange(value, 1, Integer.MAX_VALUE, field);
            if (!output.add(value)) {
                throw new IllegalArgumentException(field + " contains duplicates");
            }
        }
        return Set.copyOf(output);
    }

    private static void requireVersion(String value) {
        if (!CONTRACT_VERSION.equals(value)) {
            throw new UnsupportedCheckpointEscalationVersionException(value);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " must be non-empty");
        }
        return value;
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " cannot be negative");
        }
    }

    private static void requireRange(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                field + " must be in [" + min + ", " + max + "]"
            );
        }
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase();
    }
}
