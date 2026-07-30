package io.github.akaryc1b.approval.sdk.v1;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic bounded diagnostic emission policy with fake sinks only. */
public final class SdkDiagnosticEmissionV1 {
    public static final String CONTRACT_VERSION = SdkEmissionPolicyV1.CONTRACT_VERSION;
    private static final int MAX_SAMPLE_DENOMINATOR = 1_000_000;
    private static final long MAX_DEDUPLICATION_WINDOW = 1_000_000L;
    private static final int MAX_IN_MEMORY_CAPACITY = 10_000;

    private SdkDiagnosticEmissionV1() {
    }
    public enum DiagnosticEmissionStatus {
        EMITTED,
        BELOW_THRESHOLD,
        SAMPLED_OUT,
        DUPLICATE,
        SINK_CAPACITY,
        SINK_FAILED
    }

    public enum DiagnosticSinkAppendStatus {
        ACCEPTED,
        CAPACITY_REJECTED
    }

    public record DiagnosticEmissionPolicy(
        String contractVersion,
        SdkDiagnosticsAuditV1.DiagnosticSeverity minimumSeverity,
        int sampleNumerator,
        int sampleDenominator,
        String sampleSalt,
        long deduplicationWindowOrdinals,
        int deduplicationCapacity
    ) {
        public DiagnosticEmissionPolicy {
            requireVersion(contractVersion);
            minimumSeverity = Objects.requireNonNull(minimumSeverity, "policy.minimumSeverity");
            if (sampleNumerator < 0) {
                throw new IllegalArgumentException("sampleNumerator cannot be negative");
            }
            if (sampleDenominator < 1 || sampleDenominator > MAX_SAMPLE_DENOMINATOR) {
                throw new IllegalArgumentException(
                    "sampleDenominator must be between 1 and " + MAX_SAMPLE_DENOMINATOR
                );
            }
            if (sampleNumerator > sampleDenominator) {
                throw new IllegalArgumentException("sampleNumerator cannot exceed sampleDenominator");
            }
            sampleSalt = required(sampleSalt, "policy.sampleSalt");
            requireWindow(deduplicationWindowOrdinals);
            requireCapacity(deduplicationCapacity, "policy.deduplicationCapacity");
        }
    }

    public record DiagnosticEmissionRequest(
        String contractVersion,
        String emissionId,
        String sampleKey,
        long ordinal,
        SdkDiagnosticsAuditV1.SafeDiagnostic diagnostic
    ) {
        public DiagnosticEmissionRequest {
            requireVersion(contractVersion);
            emissionId = required(emissionId, "emission.emissionId");
            sampleKey = required(sampleKey, "emission.sampleKey");
            if (ordinal < 0) {
                throw new IllegalArgumentException("emission.ordinal cannot be negative");
            }
            diagnostic = Objects.requireNonNull(diagnostic, "emission.diagnostic");
        }
    }

    public record DiagnosticSinkRecord(
        String emissionId,
        long ordinal,
        String fingerprint,
        SdkDiagnosticsAuditV1.SafeDiagnostic diagnostic
    ) {
        public DiagnosticSinkRecord {
            emissionId = required(emissionId, "diagnosticSinkRecord.emissionId");
            if (ordinal < 0) {
                throw new IllegalArgumentException("diagnosticSinkRecord.ordinal cannot be negative");
            }
            fingerprint = required(fingerprint, "diagnosticSinkRecord.fingerprint");
            diagnostic = Objects.requireNonNull(diagnostic, "diagnosticSinkRecord.diagnostic");
        }
    }

    public record DiagnosticEmissionResult(
        String contractVersion,
        DiagnosticEmissionStatus status,
        String reasonCode,
        String fingerprint,
        int sampleBucket,
        int sinkSize,
        int trackerSize
    ) {
        public DiagnosticEmissionResult {
            requireVersion(contractVersion);
            status = Objects.requireNonNull(status, "emissionResult.status");
            reasonCode = required(reasonCode, "emissionResult.reasonCode");
            fingerprint = required(fingerprint, "emissionResult.fingerprint");
            if (sampleBucket < 0 || sinkSize < 0 || trackerSize < 0) {
                throw new IllegalArgumentException("Emission result counters cannot be negative");
            }
        }
    }

    public static final class BoundedDiagnosticDeduplicationTracker {
        private final int capacity;
        private final Map<String, Long> seen = new LinkedHashMap<>();
        private long lastObservedOrdinal = -1L;

        public BoundedDiagnosticDeduplicationTracker(int capacity) {
            requireCapacity(capacity, "deduplicationTracker.capacity");
            this.capacity = capacity;
        }

        public synchronized boolean isDuplicate(
            String fingerprint,
            long ordinal,
            long windowOrdinals
        ) {
            String requiredFingerprint = required(fingerprint, "fingerprint");
            if (ordinal < 0) {
                throw new IllegalArgumentException("ordinal cannot be negative");
            }
            requireWindow(windowOrdinals);
            if (ordinal < lastObservedOrdinal) {
                throw new IllegalArgumentException("Diagnostic emission ordinals must be monotonic");
            }
            lastObservedOrdinal = ordinal;
            expire(ordinal, windowOrdinals);
            Long previous = seen.get(requiredFingerprint);
            return previous != null && ordinal - previous <= windowOrdinals;
        }

        public synchronized void record(
            String fingerprint,
            long ordinal,
            long windowOrdinals
        ) {
            String requiredFingerprint = required(fingerprint, "fingerprint");
            if (ordinal < 0) {
                throw new IllegalArgumentException("ordinal cannot be negative");
            }
            requireWindow(windowOrdinals);
            if (ordinal < lastObservedOrdinal) {
                throw new IllegalArgumentException("Diagnostic emission ordinals must be monotonic");
            }
            lastObservedOrdinal = ordinal;
            expire(ordinal, windowOrdinals);
            if (!seen.containsKey(requiredFingerprint) && seen.size() >= capacity) {
                Map.Entry<String, Long> victim = seen.entrySet().stream()
                    .min(Comparator
                        .comparingLong((Map.Entry<String, Long> entry) -> entry.getValue())
                        .thenComparing(Map.Entry::getKey))
                    .orElse(null);
                if (victim != null) {
                    seen.remove(victim.getKey());
                }
            }
            seen.put(requiredFingerprint, ordinal);
        }

        public synchronized int size() {
            return seen.size();
        }

        public int capacity() {
            return capacity;
        }

        private void expire(long ordinal, long windowOrdinals) {
            seen.entrySet().removeIf(entry -> ordinal - entry.getValue() > windowOrdinals);
        }
    }

    /** Deterministic fake diagnostic sink with bounded capacity and scripted append failures. */
    public static final class ScriptedInMemoryDiagnosticSink {
        private final int capacity;
        private final Set<Integer> failureAppendNumbers;
        private final List<DiagnosticSinkRecord> records = new ArrayList<>();
        private int appendAttempts;

        public ScriptedInMemoryDiagnosticSink(int capacity) {
            this(capacity, List.of());
        }

        public ScriptedInMemoryDiagnosticSink(
            int capacity,
            List<Integer> failureAppendNumbers
        ) {
            requireCapacity(capacity, "diagnosticSink.capacity");
            this.capacity = capacity;
            this.failureAppendNumbers = positiveUniqueIntegers(
                failureAppendNumbers,
                "diagnosticSink.failureAppendNumbers"
            );
        }

        public synchronized DiagnosticSinkAppendStatus append(DiagnosticSinkRecord record) {
            appendAttempts++;
            if (failureAppendNumbers.contains(appendAttempts)) {
                throw new IllegalStateException(
                    "scripted diagnostic sink failure"
                );
            }
            if (records.size() >= capacity) {
                return DiagnosticSinkAppendStatus.CAPACITY_REJECTED;
            }
            records.add(Objects.requireNonNull(record, "record"));
            return DiagnosticSinkAppendStatus.ACCEPTED;
        }

        public synchronized List<DiagnosticSinkRecord> records() {
            return List.copyOf(records);
        }

        public synchronized int size() {
            return records.size();
        }
    }

    /** Deterministic fake atomic sink. Failed batches never mutate committed records. */
    public static String diagnosticFingerprint(
        SdkDiagnosticsAuditV1.SafeDiagnostic diagnostic
    ) {
        return CanonicalJson.sha256Hex(
            CanonicalJson.canonicalizeValue(safeDiagnosticMap(diagnostic))
                .getBytes(StandardCharsets.UTF_8)
        );
    }

    public static int diagnosticSampleBucket(
        DiagnosticEmissionPolicy policy,
        String sampleKey,
        String fingerprint
    ) {
        Objects.requireNonNull(policy, "policy");
        String input = policy.sampleSalt()
            + "\n"
            + required(sampleKey, "sampleKey")
            + "\n"
            + required(fingerprint, "fingerprint");
        String hash = CanonicalJson.sha256Hex(input.getBytes(StandardCharsets.UTF_8));
        long prefix = Long.parseUnsignedLong(hash.substring(0, 8), 16);
        return (int) (prefix % policy.sampleDenominator());
    }

    public static DiagnosticEmissionResult emitDiagnostic(
        DiagnosticEmissionPolicy policy,
        DiagnosticEmissionRequest request,
        BoundedDiagnosticDeduplicationTracker tracker,
        ScriptedInMemoryDiagnosticSink sink
    ) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(tracker, "tracker");
        Objects.requireNonNull(sink, "sink");
        if (tracker.capacity() != policy.deduplicationCapacity()) {
            throw new IllegalArgumentException("Diagnostic tracker capacity does not match policy");
        }
        String fingerprint = diagnosticFingerprint(request.diagnostic());
        int bucket = diagnosticSampleBucket(policy, request.sampleKey(), fingerprint);
        if (severityRank(request.diagnostic().severity()) < severityRank(policy.minimumSeverity())) {
            return emissionResult(
                DiagnosticEmissionStatus.BELOW_THRESHOLD,
                "diagnostic_below_threshold",
                fingerprint,
                bucket,
                sink,
                tracker
            );
        }
        if (bucket >= policy.sampleNumerator()) {
            return emissionResult(
                DiagnosticEmissionStatus.SAMPLED_OUT,
                "diagnostic_sampled_out",
                fingerprint,
                bucket,
                sink,
                tracker
            );
        }
        if (tracker.isDuplicate(
            fingerprint,
            request.ordinal(),
            policy.deduplicationWindowOrdinals()
        )) {
            return emissionResult(
                DiagnosticEmissionStatus.DUPLICATE,
                "diagnostic_duplicate",
                fingerprint,
                bucket,
                sink,
                tracker
            );
        }
        DiagnosticSinkAppendStatus appendStatus;
        try {
            appendStatus = sink.append(new DiagnosticSinkRecord(
                request.emissionId(),
                request.ordinal(),
                fingerprint,
                request.diagnostic()
            ));
        } catch (RuntimeException exception) {
            return emissionResult(
                DiagnosticEmissionStatus.SINK_FAILED,
                "diagnostic_sink_failed",
                fingerprint,
                bucket,
                sink,
                tracker
            );
        }
        if (appendStatus == DiagnosticSinkAppendStatus.CAPACITY_REJECTED) {
            return emissionResult(
                DiagnosticEmissionStatus.SINK_CAPACITY,
                "diagnostic_sink_capacity",
                fingerprint,
                bucket,
                sink,
                tracker
            );
        }
        tracker.record(
            fingerprint,
            request.ordinal(),
            policy.deduplicationWindowOrdinals()
        );
        return emissionResult(
            DiagnosticEmissionStatus.EMITTED,
            "diagnostic_emitted",
            fingerprint,
            bucket,
            sink,
            tracker
        );
    }

    public static Map<String, Object> diagnosticResultMap(DiagnosticEmissionResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("contractVersion", result.contractVersion());
        output.put("fingerprint", result.fingerprint());
        output.put("reasonCode", result.reasonCode());
        output.put("sampleBucket", result.sampleBucket());
        output.put("sinkSize", result.sinkSize());
        output.put("status", lower(result.status()));
        output.put("trackerSize", result.trackerSize());
        return output;
    }

    private static DiagnosticEmissionResult emissionResult(
        DiagnosticEmissionStatus status,
        String reasonCode,
        String fingerprint,
        int sampleBucket,
        ScriptedInMemoryDiagnosticSink sink,
        BoundedDiagnosticDeduplicationTracker tracker
    ) {
        return new DiagnosticEmissionResult(
            CONTRACT_VERSION,
            status,
            reasonCode,
            fingerprint,
            sampleBucket,
            sink.size(),
            tracker.size()
        );
    }

    private static Map<String, Object> safeDiagnosticMap(
        SdkDiagnosticsAuditV1.SafeDiagnostic diagnostic
    ) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("code", diagnostic.code());
        output.put("context", diagnostic.context());
        output.put("contractVersion", diagnostic.contractVersion());
        output.put("message", diagnostic.message());
        output.put("provenanceDigest", diagnostic.provenanceDigest());
        output.put("redactionCount", diagnostic.redactionCount());
        output.put("severity", lower(diagnostic.severity()));
        return output;
    }

    private static int severityRank(SdkDiagnosticsAuditV1.DiagnosticSeverity severity) {
        return switch (severity) {
            case INFO -> 0;
            case WARNING -> 1;
            case ERROR -> 2;
        };
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static void requireVersion(String value) {
        if (!CONTRACT_VERSION.equals(value)) {
            throw new SdkEmissionPolicyV1.UnsupportedEmissionPolicyVersionException(value);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
        return value;
    }

    private static void requireCapacity(int value, String field) {
        if (value < 1 || value > MAX_IN_MEMORY_CAPACITY) {
            throw new IllegalArgumentException(
                field + " must be between 1 and " + MAX_IN_MEMORY_CAPACITY
            );
        }
    }

    private static void requireWindow(long value) {
        if (value < 0 || value > MAX_DEDUPLICATION_WINDOW) {
            throw new IllegalArgumentException(
                "deduplicationWindowOrdinals must be between 0 and "
                    + MAX_DEDUPLICATION_WINDOW
            );
        }
    }

    private static Set<Integer> positiveUniqueIntegers(
        List<Integer> values,
        String field
    ) {
        Objects.requireNonNull(values, field);
        Set<Integer> output = new HashSet<>();
        for (Integer value : values) {
            if (value == null || value < 1) {
                throw new IllegalArgumentException(field + " must contain positive integers");
            }
            if (!output.add(value)) {
                throw new IllegalArgumentException(field + " contains a duplicate value");
            }
        }
        return Collections.unmodifiableSet(output);
    }
}
