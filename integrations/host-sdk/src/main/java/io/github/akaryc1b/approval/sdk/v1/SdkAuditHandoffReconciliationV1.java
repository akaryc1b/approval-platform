package io.github.akaryc1b.approval.sdk.v1;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reference-only audit handoff reconciliation with deterministic fake proof storage. */
public final class SdkAuditHandoffReconciliationV1 {
    public static final String CONTRACT_VERSION = SdkAggregationReconciliationV1.CONTRACT_VERSION;
    private static final int MAX_RECONCILIATION_CAPACITY = 10_000;

    private SdkAuditHandoffReconciliationV1() {
    }

    public enum ExpectedHandoffState {
        PENDING,
        ACKNOWLEDGED
    }

    public enum HandoffReconciliationClassification {
        PENDING_CONFIRMED,
        ACKNOWLEDGED_CONFIRMED,
        ACKNOWLEDGEMENT_MISSING,
        MISSING_NO_EVIDENCE,
        CONFLICTING_EVIDENCE
    }

    public enum HandoffReconciliationStatus {
        RECORDED,
        DUPLICATE,
        CAPACITY_REJECTED,
        FAILED_CLOSED
    }

    public enum ReconciliationAppendStatus {
        ACCEPTED,
        CAPACITY_REJECTED,
        DUPLICATE_REJECTED
    }

    public record AuditHandoffReconciliationInput(
        String contractVersion,
        ExpectedHandoffState expectedState,
        SdkAuditHandoffV1.AuditHandoffEnvelope envelope,
        SdkAuditHandoffV1.AuditHandoffEnvelope pendingEnvelope,
        SdkAuditHandoffV1.AuditHandoffAcknowledgement acknowledgement,
        long reconciliationOrdinal
    ) {
        public AuditHandoffReconciliationInput {
            requireVersion(contractVersion);
            expectedState = Objects.requireNonNull(expectedState, "reconciliation.expectedState");
            envelope = Objects.requireNonNull(envelope, "reconciliation.envelope");
            if (reconciliationOrdinal < 0) {
                throw new IllegalArgumentException("reconciliationOrdinal cannot be negative");
            }
        }
    }

    public record AuditHandoffReconciliationProof(
        String contractVersion,
        String handoffId,
        ExpectedHandoffState expectedState,
        HandoffReconciliationClassification classification,
        boolean safeToFinalize,
        long reconciliationOrdinal,
        String envelopeDigest,
        String pendingEnvelopeDigest,
        String acknowledgementId,
        String acknowledgementEnvelopeDigest,
        String evidenceDigest,
        String proofDigest
    ) {
        public AuditHandoffReconciliationProof {
            requireVersion(contractVersion);
            handoffId = required(handoffId, "reconciliationProof.handoffId");
            expectedState = Objects.requireNonNull(expectedState, "reconciliationProof.expectedState");
            classification = Objects.requireNonNull(
                classification,
                "reconciliationProof.classification"
            );
            if (safeToFinalize
                != (classification == HandoffReconciliationClassification.ACKNOWLEDGED_CONFIRMED)) {
                throw new IllegalArgumentException("safeToFinalize must match acknowledged confirmation");
            }
            if (reconciliationOrdinal < 0) {
                throw new IllegalArgumentException("reconciliationOrdinal cannot be negative");
            }
            envelopeDigest = required(envelopeDigest, "reconciliationProof.envelopeDigest");
            evidenceDigest = required(evidenceDigest, "reconciliationProof.evidenceDigest");
            proofDigest = required(proofDigest, "reconciliationProof.proofDigest");
        }
    }

    public record AuditHandoffReconciliationResult(
        String contractVersion,
        HandoffReconciliationStatus status,
        String reasonCode,
        AuditHandoffReconciliationProof proof,
        int storedProofCount
    ) {
        public AuditHandoffReconciliationResult {
            requireVersion(contractVersion);
            status = Objects.requireNonNull(status, "reconciliationResult.status");
            reasonCode = required(reasonCode, "reconciliationResult.reasonCode");
            proof = Objects.requireNonNull(proof, "reconciliationResult.proof");
            if (storedProofCount < 0) {
                throw new IllegalArgumentException("storedProofCount cannot be negative");
            }
        }
    }

    public static final class ScriptedHandoffReconciliationStore {
        private final int capacity;
        private final Set<Integer> failureOperationNumbers;
        private final Set<String> proofDigests = new HashSet<>();
        private final List<AuditHandoffReconciliationProof> proofs = new ArrayList<>();
        private int appendAttempts;

        public ScriptedHandoffReconciliationStore(int capacity) {
            this(capacity, List.of());
        }

        public ScriptedHandoffReconciliationStore(
            int capacity,
            List<Integer> failureOperationNumbers
        ) {
            requireRange(
                capacity,
                1,
                MAX_RECONCILIATION_CAPACITY,
                "reconciliationStore.capacity"
            );
            this.capacity = capacity;
            this.failureOperationNumbers = positiveUniqueIntegers(
                failureOperationNumbers,
                "reconciliationStore.failureOperationNumbers"
            );
        }

        public synchronized ReconciliationAppendStatus append(
            AuditHandoffReconciliationProof proof
        ) {
            Objects.requireNonNull(proof, "proof");
            appendAttempts++;
            if (failureOperationNumbers.contains(appendAttempts)) {
                throw new IllegalStateException("scripted reconciliation store failure");
            }
            if (proofDigests.contains(proof.proofDigest())) {
                return ReconciliationAppendStatus.DUPLICATE_REJECTED;
            }
            if (proofs.size() >= capacity) {
                return ReconciliationAppendStatus.CAPACITY_REJECTED;
            }
            proofs.add(proof);
            proofDigests.add(proof.proofDigest());
            return ReconciliationAppendStatus.ACCEPTED;
        }

        public synchronized int size() {
            return proofs.size();
        }

        public synchronized List<AuditHandoffReconciliationProof> proofs() {
            return List.copyOf(proofs);
        }
    }

    public static AuditHandoffReconciliationResult reconcile(
        AuditHandoffReconciliationInput input,
        ScriptedHandoffReconciliationStore store
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(store, "store");
        SdkAuditHandoffV1.AuditHandoffEnvelope envelope =
            SdkAuditHandoffV1.validateAuditHandoffEnvelope(input.envelope());
        SdkAuditHandoffV1.AuditHandoffEnvelope pendingEnvelope = input.pendingEnvelope() == null
            ? null
            : SdkAuditHandoffV1.validateAuditHandoffEnvelope(input.pendingEnvelope());
        SdkAuditHandoffV1.AuditHandoffAcknowledgement acknowledgement =
            input.acknowledgement();

        HandoffReconciliationClassification classification = classify(
            envelope,
            input.expectedState(),
            pendingEnvelope,
            acknowledgement
        );
        Map<String, Object> evidenceInput = new LinkedHashMap<>();
        evidenceInput.put(
            "acknowledgement",
            acknowledgement == null ? null : acknowledgementMap(acknowledgement)
        );
        evidenceInput.put("envelope", envelopeMap(envelope));
        evidenceInput.put(
            "pendingEnvelope",
            pendingEnvelope == null ? null : envelopeMap(pendingEnvelope)
        );
        String evidenceDigest = digest(evidenceInput);

        boolean safeToFinalize = classification
            == HandoffReconciliationClassification.ACKNOWLEDGED_CONFIRMED;
        Map<String, Object> proofInput = new LinkedHashMap<>();
        proofInput.put(
            "acknowledgementEnvelopeDigest",
            acknowledgement == null ? null : acknowledgement.envelopeDigest()
        );
        proofInput.put(
            "acknowledgementId",
            acknowledgement == null ? null : acknowledgement.acknowledgementId()
        );
        proofInput.put("classification", lower(classification));
        proofInput.put("contractVersion", CONTRACT_VERSION);
        proofInput.put("envelopeDigest", envelope.envelopeDigest());
        proofInput.put("evidenceDigest", evidenceDigest);
        proofInput.put("expectedState", lower(input.expectedState()));
        proofInput.put("handoffId", envelope.handoffId());
        proofInput.put(
            "pendingEnvelopeDigest",
            pendingEnvelope == null ? null : pendingEnvelope.envelopeDigest()
        );
        proofInput.put("reconciliationOrdinal", input.reconciliationOrdinal());
        proofInput.put("safeToFinalize", safeToFinalize);
        String proofDigest = digest(proofInput);

        AuditHandoffReconciliationProof proof = new AuditHandoffReconciliationProof(
            CONTRACT_VERSION,
            envelope.handoffId(),
            input.expectedState(),
            classification,
            safeToFinalize,
            input.reconciliationOrdinal(),
            envelope.envelopeDigest(),
            pendingEnvelope == null ? null : pendingEnvelope.envelopeDigest(),
            acknowledgement == null ? null : acknowledgement.acknowledgementId(),
            acknowledgement == null ? null : acknowledgement.envelopeDigest(),
            evidenceDigest,
            proofDigest
        );

        ReconciliationAppendStatus appendStatus;
        try {
            appendStatus = store.append(proof);
        } catch (RuntimeException exception) {
            return result(
                HandoffReconciliationStatus.FAILED_CLOSED,
                "handoff_reconciliation_store_failed",
                proof,
                store
            );
        }
        if (appendStatus == ReconciliationAppendStatus.CAPACITY_REJECTED) {
            return result(
                HandoffReconciliationStatus.CAPACITY_REJECTED,
                "handoff_reconciliation_store_capacity",
                proof,
                store
            );
        }
        if (appendStatus == ReconciliationAppendStatus.DUPLICATE_REJECTED) {
            return result(
                HandoffReconciliationStatus.DUPLICATE,
                "handoff_reconciliation_duplicate_proof",
                proof,
                store
            );
        }
        return result(
            HandoffReconciliationStatus.RECORDED,
            reasonCode(classification),
            proof,
            store
        );
    }

    public static Map<String, Object> proofMap(AuditHandoffReconciliationProof value) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("acknowledgementEnvelopeDigest", value.acknowledgementEnvelopeDigest());
        output.put("acknowledgementId", value.acknowledgementId());
        output.put("classification", lower(value.classification()));
        output.put("contractVersion", value.contractVersion());
        output.put("envelopeDigest", value.envelopeDigest());
        output.put("evidenceDigest", value.evidenceDigest());
        output.put("expectedState", lower(value.expectedState()));
        output.put("handoffId", value.handoffId());
        output.put("pendingEnvelopeDigest", value.pendingEnvelopeDigest());
        output.put("proofDigest", value.proofDigest());
        output.put("reconciliationOrdinal", value.reconciliationOrdinal());
        output.put("safeToFinalize", value.safeToFinalize());
        return output;
    }

    public static Map<String, Object> resultMap(AuditHandoffReconciliationResult value) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("contractVersion", value.contractVersion());
        output.put("proof", proofMap(value.proof()));
        output.put("reasonCode", value.reasonCode());
        output.put("status", lower(value.status()));
        output.put("storedProofCount", value.storedProofCount());
        return output;
    }

    private static HandoffReconciliationClassification classify(
        SdkAuditHandoffV1.AuditHandoffEnvelope envelope,
        ExpectedHandoffState expectedState,
        SdkAuditHandoffV1.AuditHandoffEnvelope pendingEnvelope,
        SdkAuditHandoffV1.AuditHandoffAcknowledgement acknowledgement
    ) {
        if (pendingEnvelope != null && acknowledgement != null) {
            return HandoffReconciliationClassification.CONFLICTING_EVIDENCE;
        }
        if (pendingEnvelope != null && !sameEnvelope(envelope, pendingEnvelope)) {
            return HandoffReconciliationClassification.CONFLICTING_EVIDENCE;
        }
        if (acknowledgement != null && !sameAcknowledgement(envelope, acknowledgement)) {
            return HandoffReconciliationClassification.CONFLICTING_EVIDENCE;
        }
        if (acknowledgement != null) {
            return HandoffReconciliationClassification.ACKNOWLEDGED_CONFIRMED;
        }
        if (pendingEnvelope != null) {
            return expectedState == ExpectedHandoffState.ACKNOWLEDGED
                ? HandoffReconciliationClassification.ACKNOWLEDGEMENT_MISSING
                : HandoffReconciliationClassification.PENDING_CONFIRMED;
        }
        return HandoffReconciliationClassification.MISSING_NO_EVIDENCE;
    }

    private static boolean sameEnvelope(
        SdkAuditHandoffV1.AuditHandoffEnvelope expected,
        SdkAuditHandoffV1.AuditHandoffEnvelope observed
    ) {
        return expected.handoffId().equals(observed.handoffId())
            && expected.destinationReference().equals(observed.destinationReference())
            && expected.envelopeDigest().equals(observed.envelopeDigest())
            && expected.auditBatchDigest().equals(observed.auditBatchDigest())
            && expected.auditIdentityDigest().equals(observed.auditIdentityDigest());
    }

    private static boolean sameAcknowledgement(
        SdkAuditHandoffV1.AuditHandoffEnvelope expected,
        SdkAuditHandoffV1.AuditHandoffAcknowledgement acknowledgement
    ) {
        return expected.handoffId().equals(acknowledgement.handoffId())
            && expected.destinationReference().equals(acknowledgement.destinationReference())
            && expected.envelopeDigest().equals(acknowledgement.envelopeDigest())
            && expected.auditBatchDigest().equals(acknowledgement.auditBatchDigest());
    }

    private static Map<String, Object> envelopeMap(
        SdkAuditHandoffV1.AuditHandoffEnvelope value
    ) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("attemptCount", value.attemptCount());
        output.put("auditBatchDigest", value.auditBatchDigest());
        output.put("auditIdentityDigest", value.auditIdentityDigest());
        output.put("contractVersion", value.contractVersion());
        output.put("destinationReference", value.destinationReference());
        output.put("envelopeDigest", value.envelopeDigest());
        output.put("firstEventId", value.firstEventId());
        output.put("handoffId", value.handoffId());
        output.put("handoffOrdinal", value.handoffOrdinal());
        output.put("recordCount", value.recordCount());
        output.put("terminalEventId", value.terminalEventId());
        return output;
    }

    private static Map<String, Object> acknowledgementMap(
        SdkAuditHandoffV1.AuditHandoffAcknowledgement value
    ) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("acknowledgedOrdinal", value.acknowledgedOrdinal());
        output.put("acknowledgementId", value.acknowledgementId());
        output.put("auditBatchDigest", value.auditBatchDigest());
        output.put("contractVersion", value.contractVersion());
        output.put("deliveryAttempt", value.deliveryAttempt());
        output.put("destinationReference", value.destinationReference());
        output.put("envelopeDigest", value.envelopeDigest());
        output.put("handoffId", value.handoffId());
        return output;
    }

    private static String reasonCode(HandoffReconciliationClassification classification) {
        return switch (classification) {
            case ACKNOWLEDGED_CONFIRMED -> "handoff_acknowledged_confirmed";
            case PENDING_CONFIRMED -> "handoff_pending_confirmed";
            case ACKNOWLEDGEMENT_MISSING -> "handoff_acknowledgement_missing";
            case MISSING_NO_EVIDENCE -> "handoff_missing_no_evidence";
            case CONFLICTING_EVIDENCE -> "handoff_conflicting_evidence";
        };
    }

    private static AuditHandoffReconciliationResult result(
        HandoffReconciliationStatus status,
        String reasonCode,
        AuditHandoffReconciliationProof proof,
        ScriptedHandoffReconciliationStore store
    ) {
        return new AuditHandoffReconciliationResult(
            CONTRACT_VERSION,
            status,
            reasonCode,
            proof,
            store.size()
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
