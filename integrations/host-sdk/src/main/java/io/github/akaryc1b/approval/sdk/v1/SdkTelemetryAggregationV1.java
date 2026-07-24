package io.github.akaryc1b.approval.sdk.v1;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic caller-ordinal telemetry aggregation with bounded fake state. */
public final class SdkTelemetryAggregationV1 {
    public static final String CONTRACT_VERSION = SdkAggregationReconciliationV1.CONTRACT_VERSION;
    private static final int MAX_BOUND = 10_000;

    private SdkTelemetryAggregationV1() {
    }

    public enum TelemetryAggregationStatus {
        AGGREGATED,
        DUPLICATE,
        CAPACITY_REJECTED,
        FAILED_CLOSED
    }

    public record TelemetryAggregationPolicy(
        String contractVersion,
        int windowSizeOrdinals,
        int deduplicationWindowOrdinals,
        int maxActiveWindows,
        int maxAggregationIdentities,
        int maxTrackedSignalDigests
    ) {
        public TelemetryAggregationPolicy {
            requireVersion(contractVersion);
            requireRange(windowSizeOrdinals, 1, 1_000_000, "aggregationPolicy.windowSizeOrdinals");
            requireRange(
                deduplicationWindowOrdinals,
                0,
                1_000_000,
                "aggregationPolicy.deduplicationWindowOrdinals"
            );
            requireRange(maxActiveWindows, 1, MAX_BOUND, "aggregationPolicy.maxActiveWindows");
            requireRange(
                maxAggregationIdentities,
                1,
                MAX_BOUND,
                "aggregationPolicy.maxAggregationIdentities"
            );
            requireRange(
                maxTrackedSignalDigests,
                1,
                MAX_BOUND,
                "aggregationPolicy.maxTrackedSignalDigests"
            );
        }
    }

    public record TelemetryAggregationSnapshot(
        String contractVersion,
        long windowStartOrdinal,
        long windowEndOrdinalExclusive,
        String aggregationIdentityDigest,
        String signalName,
        SdkTelemetrySignalV1.TelemetrySignalKind signalKind,
        String endpointId,
        String operation,
        String outcome,
        String provenanceDigest,
        Map<String, String> attributes,
        long totalQuantity,
        int signalCount,
        String firstSignalId,
        String lastSignalId,
        long firstOrdinal,
        long lastOrdinal,
        String snapshotDigest
    ) {
        public TelemetryAggregationSnapshot {
            requireVersion(contractVersion);
            aggregationIdentityDigest = required(
                aggregationIdentityDigest,
                "aggregationSnapshot.aggregationIdentityDigest"
            );
            signalName = required(signalName, "aggregationSnapshot.signalName");
            signalKind = Objects.requireNonNull(signalKind, "aggregationSnapshot.signalKind");
            endpointId = required(endpointId, "aggregationSnapshot.endpointId");
            operation = required(operation, "aggregationSnapshot.operation");
            outcome = required(outcome, "aggregationSnapshot.outcome");
            provenanceDigest = required(provenanceDigest, "aggregationSnapshot.provenanceDigest");
            attributes = immutableStringMap(attributes, "aggregationSnapshot.attributes");
            firstSignalId = required(firstSignalId, "aggregationSnapshot.firstSignalId");
            lastSignalId = required(lastSignalId, "aggregationSnapshot.lastSignalId");
            snapshotDigest = required(snapshotDigest, "aggregationSnapshot.snapshotDigest");
            if (windowStartOrdinal < 0 || windowEndOrdinalExclusive <= windowStartOrdinal
                || totalQuantity < 1 || signalCount < 1 || firstOrdinal < 0
                || lastOrdinal < firstOrdinal) {
                throw new IllegalArgumentException("Telemetry aggregation snapshot counters are invalid");
            }
        }
    }

    public record TelemetryAggregationResult(
        String contractVersion,
        TelemetryAggregationStatus status,
        String reasonCode,
        int acceptedSignalCount,
        int duplicateSignalCount,
        int activeWindowCount,
        int activeIdentityCount,
        int trackedSignalCount,
        TelemetryAggregationSnapshot snapshot,
        List<TelemetryAggregationSnapshot> rolledOverSnapshots
    ) {
        public TelemetryAggregationResult {
            requireVersion(contractVersion);
            status = Objects.requireNonNull(status, "aggregationResult.status");
            reasonCode = required(reasonCode, "aggregationResult.reasonCode");
            if (acceptedSignalCount < 0 || duplicateSignalCount < 0
                || activeWindowCount < 0 || activeIdentityCount < 0 || trackedSignalCount < 0) {
                throw new IllegalArgumentException("Telemetry aggregation result counters are invalid");
            }
            rolledOverSnapshots = List.copyOf(rolledOverSnapshots);
        }
    }

    public static final class ScriptedTelemetryAggregationStore {
        private final TelemetryAggregationPolicy policy;
        private final Set<Integer> failureOperationNumbers;
        private Map<Long, Map<String, MutableAggregate>> windows = new HashMap<>();
        private Map<String, Long> trackedSignalDigests = new HashMap<>();
        private long lastOrdinal = -1L;
        private int operationCount;
        private int acceptedSignalCount;
        private int duplicateSignalCount;

        public ScriptedTelemetryAggregationStore(TelemetryAggregationPolicy policy) {
            this(policy, List.of());
        }

        public ScriptedTelemetryAggregationStore(
            TelemetryAggregationPolicy policy,
            List<Integer> failureOperationNumbers
        ) {
            this.policy = Objects.requireNonNull(policy, "policy");
            this.failureOperationNumbers = positiveUniqueIntegers(
                failureOperationNumbers,
                "aggregationStore.failureOperationNumbers"
            );
        }

        public synchronized TelemetryAggregationResult aggregate(
            SdkTelemetrySignalV1.ReferenceTelemetrySignal signal
        ) {
            operationCount++;
            if (failureOperationNumbers.contains(operationCount)) {
                return result(
                    TelemetryAggregationStatus.FAILED_CLOSED,
                    "telemetry_aggregation_store_failed",
                    null,
                    List.of()
                );
            }
            try {
                validateReferenceTelemetrySignal(signal);
            } catch (RuntimeException exception) {
                return result(
                    TelemetryAggregationStatus.FAILED_CLOSED,
                    "telemetry_signal_invalid",
                    null,
                    List.of()
                );
            }
            if (signal.ordinal() < lastOrdinal) {
                return result(
                    TelemetryAggregationStatus.FAILED_CLOSED,
                    "telemetry_aggregation_ordinal_regression",
                    null,
                    List.of()
                );
            }

            Map<String, Long> nextTracked = new HashMap<>(trackedSignalDigests);
            long minimumTrackedOrdinal = Math.max(
                0L,
                signal.ordinal() - policy.deduplicationWindowOrdinals()
            );
            nextTracked.entrySet().removeIf(entry -> entry.getValue() < minimumTrackedOrdinal);
            if (nextTracked.containsKey(signal.signalDigest())) {
                trackedSignalDigests = nextTracked;
                lastOrdinal = signal.ordinal();
                duplicateSignalCount++;
                return result(
                    TelemetryAggregationStatus.DUPLICATE,
                    "telemetry_signal_duplicate",
                    null,
                    List.of()
                );
            }

            Map<Long, Map<String, MutableAggregate>> nextWindows = cloneWindows(windows);
            long windowStart = Math.floorDiv(
                signal.ordinal(),
                policy.windowSizeOrdinals()
            ) * (long) policy.windowSizeOrdinals();
            List<TelemetryAggregationSnapshot> rolledOverSnapshots = new ArrayList<>();
            if (!nextWindows.containsKey(windowStart)) {
                while (nextWindows.size() >= policy.maxActiveWindows()) {
                    long oldest = Collections.min(nextWindows.keySet());
                    Map<String, MutableAggregate> removed = nextWindows.remove(oldest);
                    if (removed != null) {
                        removed.values().stream()
                            .sorted((left, right) -> left.aggregationIdentityDigest.compareTo(
                                right.aggregationIdentityDigest
                            ))
                            .map(SdkTelemetryAggregationV1::snapshot)
                            .forEach(rolledOverSnapshots::add);
                    }
                }
            }

            int prospectiveIdentityCount = identityCount(nextWindows);
            Map<String, MutableAggregate> window = nextWindows.computeIfAbsent(
                windowStart,
                ignored -> new HashMap<>()
            );
            MutableAggregate existing = window.get(signal.aggregationIdentityDigest());
            if (existing == null && prospectiveIdentityCount >= policy.maxAggregationIdentities()) {
                return result(
                    TelemetryAggregationStatus.CAPACITY_REJECTED,
                    "telemetry_aggregation_identity_capacity",
                    null,
                    List.of()
                );
            }

            MutableAggregate nextAggregate;
            try {
                nextAggregate = existing == null
                    ? MutableAggregate.create(signal, windowStart, policy.windowSizeOrdinals())
                    : existing.updated(signal);
            } catch (ArithmeticException exception) {
                return result(
                    TelemetryAggregationStatus.FAILED_CLOSED,
                    "telemetry_aggregation_quantity_overflow",
                    null,
                    List.of()
                );
            }
            window.put(signal.aggregationIdentityDigest(), nextAggregate);

            while (nextTracked.size() >= policy.maxTrackedSignalDigests()) {
                Map.Entry<String, Long> candidate = nextTracked.entrySet().stream()
                    .min((left, right) -> {
                        int ordinalComparison = Long.compare(left.getValue(), right.getValue());
                        return ordinalComparison != 0
                            ? ordinalComparison
                            : left.getKey().compareTo(right.getKey());
                    })
                    .orElse(null);
                if (candidate == null) {
                    break;
                }
                nextTracked.remove(candidate.getKey());
            }
            nextTracked.put(signal.signalDigest(), signal.ordinal());

            windows = nextWindows;
            trackedSignalDigests = nextTracked;
            lastOrdinal = signal.ordinal();
            acceptedSignalCount++;
            TelemetryAggregationSnapshot aggregateSnapshot = snapshot(nextAggregate);
            return result(
                TelemetryAggregationStatus.AGGREGATED,
                rolledOverSnapshots.isEmpty()
                    ? "telemetry_signal_aggregated"
                    : "telemetry_signal_aggregated_with_rollover",
                aggregateSnapshot,
                rolledOverSnapshots
            );
        }

        public synchronized List<TelemetryAggregationSnapshot> snapshots() {
            List<TelemetryAggregationSnapshot> output = new ArrayList<>();
            windows.keySet().stream().sorted().forEach(start -> windows.get(start).values().stream()
                .sorted((left, right) -> left.aggregationIdentityDigest.compareTo(
                    right.aggregationIdentityDigest
                ))
                .map(SdkTelemetryAggregationV1::snapshot)
                .forEach(output::add));
            return List.copyOf(output);
        }

        public synchronized int activeWindowCount() {
            return windows.size();
        }

        public synchronized int activeIdentityCount() {
            return identityCount(windows);
        }

        public synchronized int trackedSignalCount() {
            return trackedSignalDigests.size();
        }

        private TelemetryAggregationResult result(
            TelemetryAggregationStatus status,
            String reasonCode,
            TelemetryAggregationSnapshot value,
            List<TelemetryAggregationSnapshot> rolledOverSnapshots
        ) {
            return new TelemetryAggregationResult(
                CONTRACT_VERSION,
                status,
                reasonCode,
                acceptedSignalCount,
                duplicateSignalCount,
                activeWindowCount(),
                activeIdentityCount(),
                trackedSignalCount(),
                value,
                rolledOverSnapshots
            );
        }
    }

    public static Map<String, Object> snapshotMap(TelemetryAggregationSnapshot value) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("aggregationIdentityDigest", value.aggregationIdentityDigest());
        output.put("attributes", value.attributes());
        output.put("contractVersion", value.contractVersion());
        output.put("endpointId", value.endpointId());
        output.put("firstOrdinal", value.firstOrdinal());
        output.put("firstSignalId", value.firstSignalId());
        output.put("lastOrdinal", value.lastOrdinal());
        output.put("lastSignalId", value.lastSignalId());
        output.put("operation", value.operation());
        output.put("outcome", value.outcome());
        output.put("provenanceDigest", value.provenanceDigest());
        output.put("signalCount", value.signalCount());
        output.put("signalKind", lower(value.signalKind()));
        output.put("signalName", value.signalName());
        output.put("snapshotDigest", value.snapshotDigest());
        output.put("totalQuantity", value.totalQuantity());
        output.put("windowEndOrdinalExclusive", value.windowEndOrdinalExclusive());
        output.put("windowStartOrdinal", value.windowStartOrdinal());
        return output;
    }

    public static Map<String, Object> resultMap(TelemetryAggregationResult value) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("acceptedSignalCount", value.acceptedSignalCount());
        output.put("activeIdentityCount", value.activeIdentityCount());
        output.put("activeWindowCount", value.activeWindowCount());
        output.put("contractVersion", value.contractVersion());
        output.put("duplicateSignalCount", value.duplicateSignalCount());
        output.put("reasonCode", value.reasonCode());
        output.put(
            "rolledOverSnapshots",
            value.rolledOverSnapshots().stream().map(SdkTelemetryAggregationV1::snapshotMap).toList()
        );
        output.put("snapshot", value.snapshot() == null ? null : snapshotMap(value.snapshot()));
        output.put("status", lower(value.status()));
        output.put("trackedSignalCount", value.trackedSignalCount());
        return output;
    }

    private static TelemetryAggregationSnapshot snapshot(MutableAggregate value) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("aggregationIdentityDigest", value.aggregationIdentityDigest);
        input.put("attributes", value.attributes);
        input.put("contractVersion", CONTRACT_VERSION);
        input.put("endpointId", value.endpointId);
        input.put("firstOrdinal", value.firstOrdinal);
        input.put("firstSignalId", value.firstSignalId);
        input.put("lastOrdinal", value.lastOrdinal);
        input.put("lastSignalId", value.lastSignalId);
        input.put("operation", value.operation);
        input.put("outcome", value.outcome);
        input.put("provenanceDigest", value.provenanceDigest);
        input.put("signalCount", value.signalCount);
        input.put("signalKind", lower(value.signalKind));
        input.put("signalName", value.signalName);
        input.put("totalQuantity", value.totalQuantity);
        input.put("windowEndOrdinalExclusive", value.windowEndOrdinalExclusive);
        input.put("windowStartOrdinal", value.windowStartOrdinal);
        String snapshotDigest = digest(input);
        return new TelemetryAggregationSnapshot(
            CONTRACT_VERSION,
            value.windowStartOrdinal,
            value.windowEndOrdinalExclusive,
            value.aggregationIdentityDigest,
            value.signalName,
            value.signalKind,
            value.endpointId,
            value.operation,
            value.outcome,
            value.provenanceDigest,
            value.attributes,
            value.totalQuantity,
            value.signalCount,
            value.firstSignalId,
            value.lastSignalId,
            value.firstOrdinal,
            value.lastOrdinal,
            snapshotDigest
        );
    }

    private static void validateReferenceTelemetrySignal(
        SdkTelemetrySignalV1.ReferenceTelemetrySignal signal
    ) {
        Objects.requireNonNull(signal, "signal");
        requireVersion(signal.contractVersion());
        Map<String, Object> aggregationInput = new LinkedHashMap<>();
        aggregationInput.put("attributes", signal.attributes());
        aggregationInput.put("endpointId", signal.endpointId());
        aggregationInput.put("operation", signal.operation());
        aggregationInput.put("outcome", signal.outcome());
        aggregationInput.put("provenanceDigest", signal.provenanceDigest());
        aggregationInput.put("signalKind", lower(signal.signalKind()));
        aggregationInput.put("signalName", signal.signalName());
        if (!digest(aggregationInput).equals(signal.aggregationIdentityDigest())) {
            throw new IllegalArgumentException("Telemetry aggregation identity digest mismatch");
        }
        Map<String, Object> signalInput = new LinkedHashMap<>();
        signalInput.put("aggregationIdentityDigest", signal.aggregationIdentityDigest());
        signalInput.put("attributes", signal.attributes());
        signalInput.put("authenticationContextId", signal.authenticationContextId());
        signalInput.put("bindingId", signal.bindingId());
        signalInput.put("contractVersion", CONTRACT_VERSION);
        signalInput.put("endpointId", signal.endpointId());
        signalInput.put("operation", signal.operation());
        signalInput.put("ordinal", signal.ordinal());
        signalInput.put("outcome", signal.outcome());
        signalInput.put("provenanceDigest", signal.provenanceDigest());
        signalInput.put("quantity", signal.quantity());
        signalInput.put("requestId", signal.requestId());
        signalInput.put("signalId", signal.signalId());
        signalInput.put("signalKind", lower(signal.signalKind()));
        signalInput.put("signalName", signal.signalName());
        signalInput.put("traceId", signal.traceId());
        if (!digest(signalInput).equals(signal.signalDigest())) {
            throw new IllegalArgumentException("Telemetry signal digest mismatch");
        }
    }

    private static Map<Long, Map<String, MutableAggregate>> cloneWindows(
        Map<Long, Map<String, MutableAggregate>> source
    ) {
        Map<Long, Map<String, MutableAggregate>> output = new HashMap<>();
        for (Map.Entry<Long, Map<String, MutableAggregate>> entry : source.entrySet()) {
            Map<String, MutableAggregate> values = new HashMap<>();
            entry.getValue().forEach((key, value) -> values.put(key, value.copy()));
            output.put(entry.getKey(), values);
        }
        return output;
    }

    private static int identityCount(Map<Long, Map<String, MutableAggregate>> values) {
        return values.values().stream().mapToInt(Map::size).sum();
    }

    private static final class MutableAggregate {
        private final long windowStartOrdinal;
        private final long windowEndOrdinalExclusive;
        private final String aggregationIdentityDigest;
        private final String signalName;
        private final SdkTelemetrySignalV1.TelemetrySignalKind signalKind;
        private final String endpointId;
        private final String operation;
        private final String outcome;
        private final String provenanceDigest;
        private final Map<String, String> attributes;
        private long totalQuantity;
        private int signalCount;
        private final String firstSignalId;
        private String lastSignalId;
        private final long firstOrdinal;
        private long lastOrdinal;

        private MutableAggregate(
            long windowStartOrdinal,
            long windowEndOrdinalExclusive,
            String aggregationIdentityDigest,
            String signalName,
            SdkTelemetrySignalV1.TelemetrySignalKind signalKind,
            String endpointId,
            String operation,
            String outcome,
            String provenanceDigest,
            Map<String, String> attributes,
            long totalQuantity,
            int signalCount,
            String firstSignalId,
            String lastSignalId,
            long firstOrdinal,
            long lastOrdinal
        ) {
            this.windowStartOrdinal = windowStartOrdinal;
            this.windowEndOrdinalExclusive = windowEndOrdinalExclusive;
            this.aggregationIdentityDigest = aggregationIdentityDigest;
            this.signalName = signalName;
            this.signalKind = signalKind;
            this.endpointId = endpointId;
            this.operation = operation;
            this.outcome = outcome;
            this.provenanceDigest = provenanceDigest;
            this.attributes = attributes;
            this.totalQuantity = totalQuantity;
            this.signalCount = signalCount;
            this.firstSignalId = firstSignalId;
            this.lastSignalId = lastSignalId;
            this.firstOrdinal = firstOrdinal;
            this.lastOrdinal = lastOrdinal;
        }

        private static MutableAggregate create(
            SdkTelemetrySignalV1.ReferenceTelemetrySignal signal,
            long windowStartOrdinal,
            int windowSizeOrdinals
        ) {
            return new MutableAggregate(
                windowStartOrdinal,
                windowStartOrdinal + windowSizeOrdinals,
                signal.aggregationIdentityDigest(),
                signal.signalName(),
                signal.signalKind(),
                signal.endpointId(),
                signal.operation(),
                signal.outcome(),
                signal.provenanceDigest(),
                signal.attributes(),
                signal.quantity(),
                1,
                signal.signalId(),
                signal.signalId(),
                signal.ordinal(),
                signal.ordinal()
            );
        }

        private MutableAggregate updated(SdkTelemetrySignalV1.ReferenceTelemetrySignal signal) {
            MutableAggregate value = copy();
            value.totalQuantity = Math.addExact(value.totalQuantity, signal.quantity());
            value.signalCount = Math.addExact(value.signalCount, 1);
            value.lastSignalId = signal.signalId();
            value.lastOrdinal = signal.ordinal();
            return value;
        }

        private MutableAggregate copy() {
            return new MutableAggregate(
                windowStartOrdinal,
                windowEndOrdinalExclusive,
                aggregationIdentityDigest,
                signalName,
                signalKind,
                endpointId,
                operation,
                outcome,
                provenanceDigest,
                attributes,
                totalQuantity,
                signalCount,
                firstSignalId,
                lastSignalId,
                firstOrdinal,
                lastOrdinal
            );
        }
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

    private static Map<String, String> immutableStringMap(
        Map<String, String> values,
        String field
    ) {
        Objects.requireNonNull(values, field);
        Map<String, String> output = new LinkedHashMap<>();
        values.keySet().stream().sorted().forEach(key -> output.put(
            required(key, field + " key"),
            required(values.get(key), field + "." + key)
        ));
        return Collections.unmodifiableMap(output);
    }

    private static String digest(Object value) {
        return CanonicalJson.sha256Hex(
            CanonicalJson.canonicalizeValue(value).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void requireVersion(String value) {
        if (!CONTRACT_VERSION.equals(value)) {
            throw new SdkTelemetrySignalV1.UnsupportedTelemetryHandoffVersionException(value);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " must be non-empty");
        }
        return value;
    }

    private static void requireRange(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " must be in [" + min + ", " + max + "]");
        }
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase();
    }
}
