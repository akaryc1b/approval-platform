package io.github.akaryc1b.approval.sdk.v1;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Atomic audit completeness validation and deterministic fake batch sink. */
public final class SdkAuditCompletenessV1 {
    public static final String CONTRACT_VERSION = SdkEmissionPolicyV1.CONTRACT_VERSION;
    private static final int MAX_IN_MEMORY_CAPACITY = 10_000;

    private SdkAuditCompletenessV1() {
    }
    public enum AuditPhase {
        STARTED,
        ATTEMPT,
        TERMINAL
    }

    public enum AuditCompletenessReason {
        COMPLETE,
        RECORD_COUNT_MISMATCH,
        SEQUENCE_MISMATCH,
        PHASE_MISMATCH,
        EVENT_TYPE_MISMATCH,
        DUPLICATE_EVENT_ID,
        IDENTITY_MISMATCH,
        TIME_REGRESSION
    }

    public enum AuditBatchEmissionStatus {
        COMMITTED,
        FAILED_CLOSED
    }

    public enum AtomicAuditAppendStatus {
        ACCEPTED,
        CAPACITY_REJECTED,
        DUPLICATE_REJECTED
    }

    public record AuditCompletenessPolicy(
        String contractVersion,
        int expectedAttemptCount,
        String startedEventType,
        String attemptEventType,
        List<String> terminalEventTypes
    ) {
        public AuditCompletenessPolicy {
            requireVersion(contractVersion);
            if (expectedAttemptCount < 0 || expectedAttemptCount > MAX_IN_MEMORY_CAPACITY - 2) {
                throw new IllegalArgumentException("expectedAttemptCount exceeds the safe in-memory bound");
            }
            startedEventType = required(startedEventType, "auditPolicy.startedEventType");
            attemptEventType = required(attemptEventType, "auditPolicy.attemptEventType");
            terminalEventTypes = uniqueStrings(
                terminalEventTypes,
                "auditPolicy.terminalEventTypes"
            );
            if (terminalEventTypes.isEmpty()) {
                throw new IllegalArgumentException("auditPolicy.terminalEventTypes must be non-empty");
            }
        }
    }

    public record AuditEmissionRecord(
        String contractVersion,
        int sequence,
        AuditPhase phase,
        SdkDiagnosticsAuditV1.AdapterAuditEvent event
    ) {
        public AuditEmissionRecord {
            requireVersion(contractVersion);
            if (sequence < 0) {
                throw new IllegalArgumentException("auditRecord.sequence cannot be negative");
            }
            phase = Objects.requireNonNull(phase, "auditRecord.phase");
            event = Objects.requireNonNull(event, "auditRecord.event");
        }
    }

    public record AuditCompletenessProof(
        String contractVersion,
        int recordCount,
        int attemptCount,
        String firstEventId,
        String terminalEventId,
        String identityDigest,
        String batchDigest
    ) {
        public AuditCompletenessProof {
            requireVersion(contractVersion);
            if (recordCount < 1 || attemptCount < 0) {
                throw new IllegalArgumentException("Audit proof counters are invalid");
            }
            firstEventId = required(firstEventId, "auditProof.firstEventId");
            terminalEventId = required(terminalEventId, "auditProof.terminalEventId");
            identityDigest = required(identityDigest, "auditProof.identityDigest");
            batchDigest = required(batchDigest, "auditProof.batchDigest");
        }
    }

    public record AuditCompletenessValidation(
        boolean complete,
        AuditCompletenessReason reason,
        AuditCompletenessProof proof
    ) {
        public AuditCompletenessValidation {
            reason = Objects.requireNonNull(reason, "auditValidation.reason");
            if (complete && (reason != AuditCompletenessReason.COMPLETE || proof == null)) {
                throw new IllegalArgumentException("Complete audit validation requires proof");
            }
            if (!complete && proof != null) {
                throw new IllegalArgumentException("Incomplete audit validation cannot contain proof");
            }
        }
    }

    public record AtomicAuditBatch(
        AuditCompletenessProof proof,
        List<AuditEmissionRecord> records
    ) {
        public AtomicAuditBatch {
            proof = Objects.requireNonNull(proof, "auditBatch.proof");
            records = List.copyOf(records);
            if (records.isEmpty()) {
                throw new IllegalArgumentException("audit batch records must be non-empty");
            }
        }
    }

    public record AuditBatchEmissionResult(
        String contractVersion,
        AuditBatchEmissionStatus status,
        String reasonCode,
        AuditCompletenessProof proof,
        int committedRecordCount,
        int sinkSize
    ) {
        public AuditBatchEmissionResult {
            requireVersion(contractVersion);
            status = Objects.requireNonNull(status, "auditBatchResult.status");
            reasonCode = required(reasonCode, "auditBatchResult.reasonCode");
            if (committedRecordCount < 0 || sinkSize < 0) {
                throw new IllegalArgumentException("Audit batch counters cannot be negative");
            }
        }
    }

    public static final class ScriptedAtomicAuditSink {
        private final int capacity;
        private final Set<Integer> failureBatchNumbers;
        private final Set<String> batchDigests = new HashSet<>();
        private final List<AuditEmissionRecord> records = new ArrayList<>();
        private int appendAttempts;

        public ScriptedAtomicAuditSink(int capacity) {
            this(capacity, List.of());
        }

        public ScriptedAtomicAuditSink(
            int capacity,
            List<Integer> failureBatchNumbers
        ) {
            requireCapacity(capacity, "auditSink.capacity");
            this.capacity = capacity;
            this.failureBatchNumbers = positiveUniqueIntegers(
                failureBatchNumbers,
                "auditSink.failureBatchNumbers"
            );
        }

        public synchronized AtomicAuditAppendStatus appendBatch(AtomicAuditBatch batch) {
            AtomicAuditBatch requiredBatch = Objects.requireNonNull(batch, "batch");
            appendAttempts++;
            if (failureBatchNumbers.contains(appendAttempts)) {
                throw new IllegalStateException("scripted audit sink failure");
            }
            if (batchDigests.contains(requiredBatch.proof().batchDigest())) {
                return AtomicAuditAppendStatus.DUPLICATE_REJECTED;
            }
            if (records.size() + requiredBatch.records().size() > capacity) {
                return AtomicAuditAppendStatus.CAPACITY_REJECTED;
            }
            records.addAll(requiredBatch.records());
            batchDigests.add(requiredBatch.proof().batchDigest());
            return AtomicAuditAppendStatus.ACCEPTED;
        }

        public synchronized List<AuditEmissionRecord> records() {
            return List.copyOf(records);
        }

        public synchronized int size() {
            return records.size();
        }
    }

    public static AuditCompletenessValidation validateAuditCompleteness(
        AuditCompletenessPolicy policy,
        List<AuditEmissionRecord> records
    ) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(records, "records");
        if (records.size() != policy.expectedAttemptCount() + 2) {
            return incomplete(AuditCompletenessReason.RECORD_COUNT_MISMATCH);
        }
        Set<String> eventIds = new HashSet<>();
        long previousTime = -1L;
        Map<String, Object> identity = auditIdentity(records.get(0).event());
        for (int index = 0; index < records.size(); index++) {
            AuditEmissionRecord record = Objects.requireNonNull(records.get(index), "record");
            if (record.sequence() != index) {
                return incomplete(AuditCompletenessReason.SEQUENCE_MISMATCH);
            }
            AuditPhase expectedPhase = index == 0
                ? AuditPhase.STARTED
                : index == records.size() - 1 ? AuditPhase.TERMINAL : AuditPhase.ATTEMPT;
            if (record.phase() != expectedPhase) {
                return incomplete(AuditCompletenessReason.PHASE_MISMATCH);
            }
            if (index == 0 && !record.event().eventType().equals(policy.startedEventType())) {
                return incomplete(AuditCompletenessReason.EVENT_TYPE_MISMATCH);
            }
            if (expectedPhase == AuditPhase.ATTEMPT
                && !record.event().eventType().equals(policy.attemptEventType())) {
                return incomplete(AuditCompletenessReason.EVENT_TYPE_MISMATCH);
            }
            if (expectedPhase == AuditPhase.TERMINAL
                && !policy.terminalEventTypes().contains(record.event().eventType())) {
                return incomplete(AuditCompletenessReason.EVENT_TYPE_MISMATCH);
            }
            if (!eventIds.add(record.event().eventId())) {
                return incomplete(AuditCompletenessReason.DUPLICATE_EVENT_ID);
            }
            if (!CanonicalJson.canonicalizeValue(identity).equals(
                CanonicalJson.canonicalizeValue(auditIdentity(record.event()))
            )) {
                return incomplete(AuditCompletenessReason.IDENTITY_MISMATCH);
            }
            if (record.event().occurredAtEpochSeconds() < previousTime) {
                return incomplete(AuditCompletenessReason.TIME_REGRESSION);
            }
            previousTime = record.event().occurredAtEpochSeconds();
        }
        String identityDigest = CanonicalJson.sha256Hex(
            CanonicalJson.canonicalizeValue(identity).getBytes(StandardCharsets.UTF_8)
        );
        List<Object> batch = records.stream()
            .map(SdkAuditCompletenessV1::auditRecordMap)
            .map(value -> (Object) value)
            .toList();
        String batchDigest = CanonicalJson.sha256Hex(
            CanonicalJson.canonicalizeValue(batch).getBytes(StandardCharsets.UTF_8)
        );
        AuditCompletenessProof proof = new AuditCompletenessProof(
            CONTRACT_VERSION,
            records.size(),
            policy.expectedAttemptCount(),
            records.get(0).event().eventId(),
            records.get(records.size() - 1).event().eventId(),
            identityDigest,
            batchDigest
        );
        return new AuditCompletenessValidation(
            true,
            AuditCompletenessReason.COMPLETE,
            proof
        );
    }

    public static AuditBatchEmissionResult emitCompleteAuditBatch(
        AuditCompletenessPolicy policy,
        List<AuditEmissionRecord> records,
        ScriptedAtomicAuditSink sink
    ) {
        Objects.requireNonNull(sink, "sink");
        AuditCompletenessValidation validation = validateAuditCompleteness(policy, records);
        if (!validation.complete()) {
            return auditBatchResult(
                AuditBatchEmissionStatus.FAILED_CLOSED,
                "audit_" + lower(validation.reason()),
                null,
                0,
                sink
            );
        }
        AtomicAuditAppendStatus appendStatus;
        try {
            appendStatus = sink.appendBatch(new AtomicAuditBatch(validation.proof(), records));
        } catch (RuntimeException exception) {
            return auditBatchResult(
                AuditBatchEmissionStatus.FAILED_CLOSED,
                "audit_sink_failed",
                validation.proof(),
                0,
                sink
            );
        }
        if (appendStatus == AtomicAuditAppendStatus.CAPACITY_REJECTED) {
            return auditBatchResult(
                AuditBatchEmissionStatus.FAILED_CLOSED,
                "audit_sink_capacity",
                validation.proof(),
                0,
                sink
            );
        }
        if (appendStatus == AtomicAuditAppendStatus.DUPLICATE_REJECTED) {
            return auditBatchResult(
                AuditBatchEmissionStatus.FAILED_CLOSED,
                "audit_duplicate_batch",
                validation.proof(),
                0,
                sink
            );
        }
        return auditBatchResult(
            AuditBatchEmissionStatus.COMMITTED,
            "audit_batch_committed",
            validation.proof(),
            records.size(),
            sink
        );
    }

    public static Map<String, Object> proofMap(AuditCompletenessProof proof) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("attemptCount", proof.attemptCount());
        output.put("batchDigest", proof.batchDigest());
        output.put("contractVersion", proof.contractVersion());
        output.put("firstEventId", proof.firstEventId());
        output.put("identityDigest", proof.identityDigest());
        output.put("recordCount", proof.recordCount());
        output.put("terminalEventId", proof.terminalEventId());
        return output;
    }

    public static Map<String, Object> auditBatchResultMap(AuditBatchEmissionResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("committedRecordCount", result.committedRecordCount());
        output.put("contractVersion", result.contractVersion());
        output.put("proof", result.proof() == null ? null : proofMap(result.proof()));
        output.put("reasonCode", result.reasonCode());
        output.put("sinkSize", result.sinkSize());
        output.put("status", lower(result.status()));
        return output;
    }

    private static AuditBatchEmissionResult auditBatchResult(
        AuditBatchEmissionStatus status,
        String reasonCode,
        AuditCompletenessProof proof,
        int committedRecordCount,
        ScriptedAtomicAuditSink sink
    ) {
        return new AuditBatchEmissionResult(
            CONTRACT_VERSION,
            status,
            reasonCode,
            proof,
            committedRecordCount,
            sink.size()
        );
    }

    private static AuditCompletenessValidation incomplete(AuditCompletenessReason reason) {
        return new AuditCompletenessValidation(false, reason, null);
    }

    private static Map<String, Object> auditIdentity(
        SdkDiagnosticsAuditV1.AdapterAuditEvent event
    ) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("authenticationContextId", event.authenticationContextId());
        output.put("bindingId", event.bindingId());
        output.put("endpointId", event.endpointId());
        output.put("operation", event.operation());
        output.put("provenanceDigest", event.provenanceDigest());
        output.put("requestId", event.requestId());
        output.put("traceId", event.traceId());
        return output;
    }

    private static Map<String, Object> auditRecordMap(AuditEmissionRecord record) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("contractVersion", record.contractVersion());
        output.put("event", auditEventMap(record.event()));
        output.put("phase", lower(record.phase()));
        output.put("sequence", record.sequence());
        return output;
    }

    private static Map<String, Object> auditEventMap(
        SdkDiagnosticsAuditV1.AdapterAuditEvent event
    ) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("authenticationContextId", event.authenticationContextId());
        output.put("bindingId", event.bindingId());
        output.put("contractVersion", event.contractVersion());
        output.put("endpointId", event.endpointId());
        output.put("eventId", event.eventId());
        output.put("eventType", event.eventType());
        output.put("occurredAtEpochSeconds", event.occurredAtEpochSeconds());
        output.put("operation", event.operation());
        output.put("outcome", lower(event.outcome()));
        output.put("provenanceDigest", event.provenanceDigest());
        output.put("reasonCode", event.reasonCode());
        output.put("requestId", event.requestId());
        output.put("traceId", event.traceId());
        return output;
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

    private static List<String> uniqueStrings(List<String> values, String field) {
        Objects.requireNonNull(values, field);
        List<String> output = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            String requiredValue = required(value, field);
            if (!seen.add(requiredValue)) {
                throw new IllegalArgumentException(field + " contains a duplicate value");
            }
            output.add(requiredValue);
        }
        return List.copyOf(output);
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
