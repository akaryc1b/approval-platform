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

/** Caller-ordinal reconciliation escalation and acknowledged finalization checkpoints. */
public final class SdkReconciliationEscalationV1 {
    public static final String CONTRACT_VERSION = SdkCheckpointEscalationV1.CONTRACT_VERSION;
    private static final int MAX_BOUND = 10_000;

    private SdkReconciliationEscalationV1() {
    }

    public enum ReconciliationEscalationLevel {
        NONE,
        OBSERVE,
        INVESTIGATE,
        BLOCK,
        RESOLVED
    }

    public enum ReconciliationEscalationStatus {
        RECORDED,
        DUPLICATE,
        CAPACITY_REJECTED,
        FAILED_CLOSED
    }

    public enum ReconciliationEscalationAppendStatus {
        ACCEPTED,
        DUPLICATE_REJECTED,
        CAPACITY_REJECTED,
        ORDINAL_REJECTED,
        LEVEL_REGRESSION_REJECTED,
        FINALIZATION_CONFLICT_REJECTED
    }

    public record ReconciliationEscalationPolicy(
        String contractVersion,
        long observeAfterOrdinals,
        long investigateAfterOrdinals,
        long blockAfterOrdinals,
        int maxStoredProofs
    ) {
        public ReconciliationEscalationPolicy {
            requireVersion(contractVersion);
            requireNonNegative(observeAfterOrdinals, "escalationPolicy.observeAfterOrdinals");
            requireNonNegative(
                investigateAfterOrdinals,
                "escalationPolicy.investigateAfterOrdinals"
            );
            requireNonNegative(blockAfterOrdinals, "escalationPolicy.blockAfterOrdinals");
            if (observeAfterOrdinals > investigateAfterOrdinals
                || investigateAfterOrdinals > blockAfterOrdinals) {
                throw new IllegalArgumentException("Escalation thresholds must be monotonic");
            }
            requireRange(maxStoredProofs, 1, MAX_BOUND, "escalationPolicy.maxStoredProofs");
        }
    }

    public record ReconciliationEscalationInput(
        String contractVersion,
        SdkAuditHandoffReconciliationV1.AuditHandoffReconciliationProof reconciliationProof,
        long evaluationOrdinal
    ) {
        public ReconciliationEscalationInput {
            requireVersion(contractVersion);
            reconciliationProof = Objects.requireNonNull(
                reconciliationProof,
                "escalation.reconciliationProof"
            );
            requireNonNegative(evaluationOrdinal, "escalation.evaluationOrdinal");
        }
    }

    public record HandoffFinalizationCheckpoint(
        String contractVersion,
        String handoffId,
        String envelopeDigest,
        String acknowledgementId,
        String reconciliationProofDigest,
        long evaluationOrdinal,
        String checkpointDigest
    ) {
        public HandoffFinalizationCheckpoint {
            requireVersion(contractVersion);
            handoffId = required(handoffId, "finalizationCheckpoint.handoffId");
            envelopeDigest = required(
                envelopeDigest,
                "finalizationCheckpoint.envelopeDigest"
            );
            acknowledgementId = required(
                acknowledgementId,
                "finalizationCheckpoint.acknowledgementId"
            );
            reconciliationProofDigest = required(
                reconciliationProofDigest,
                "finalizationCheckpoint.reconciliationProofDigest"
            );
            requireNonNegative(evaluationOrdinal, "finalizationCheckpoint.evaluationOrdinal");
            checkpointDigest = required(
                checkpointDigest,
                "finalizationCheckpoint.checkpointDigest"
            );
        }
    }

    public record ReconciliationEscalationProof(
        String contractVersion,
        String handoffId,
        String reconciliationProofDigest,
        SdkAuditHandoffReconciliationV1.HandoffReconciliationClassification classification,
        long reconciliationOrdinal,
        long evaluationOrdinal,
        long ageOrdinals,
        ReconciliationEscalationLevel escalationLevel,
        boolean requiresManualAction,
        boolean safeToFinalize,
        String finalizationCheckpointDigest,
        String proofDigest
    ) {
        public ReconciliationEscalationProof {
            requireVersion(contractVersion);
            handoffId = required(handoffId, "escalationProof.handoffId");
            reconciliationProofDigest = required(
                reconciliationProofDigest,
                "escalationProof.reconciliationProofDigest"
            );
            classification = Objects.requireNonNull(
                classification,
                "escalationProof.classification"
            );
            requireNonNegative(reconciliationOrdinal, "escalationProof.reconciliationOrdinal");
            requireNonNegative(evaluationOrdinal, "escalationProof.evaluationOrdinal");
            requireNonNegative(ageOrdinals, "escalationProof.ageOrdinals");
            escalationLevel = Objects.requireNonNull(
                escalationLevel,
                "escalationProof.escalationLevel"
            );
            if (safeToFinalize != (escalationLevel == ReconciliationEscalationLevel.RESOLVED)) {
                throw new IllegalArgumentException("safeToFinalize must match resolved escalation");
            }
            boolean manualLevel = escalationLevel
                == ReconciliationEscalationLevel.INVESTIGATE
                || escalationLevel == ReconciliationEscalationLevel.BLOCK;
            if (requiresManualAction != manualLevel) {
                throw new IllegalArgumentException(
                    "requiresManualAction must match escalation level"
                );
            }
            if (safeToFinalize) {
                finalizationCheckpointDigest = required(
                    finalizationCheckpointDigest,
                    "escalationProof.finalizationCheckpointDigest"
                );
            } else if (finalizationCheckpointDigest != null) {
                throw new IllegalArgumentException(
                    "Non-finalizable escalation cannot reference finalization checkpoint"
                );
            }
            proofDigest = required(proofDigest, "escalationProof.proofDigest");
        }
    }

    public record ReconciliationEscalationResult(
        String contractVersion,
        ReconciliationEscalationStatus status,
        String reasonCode,
        ReconciliationEscalationProof proof,
        HandoffFinalizationCheckpoint finalizationCheckpoint,
        int storedProofCount,
        int storedFinalizationCount
    ) {
        public ReconciliationEscalationResult {
            requireVersion(contractVersion);
            status = Objects.requireNonNull(status, "escalationResult.status");
            reasonCode = required(reasonCode, "escalationResult.reasonCode");
            proof = Objects.requireNonNull(proof, "escalationResult.proof");
            if (storedProofCount < 0 || storedFinalizationCount < 0) {
                throw new IllegalArgumentException("Escalation result counters cannot be negative");
            }
        }
    }

    private record StoredEscalation(
        ReconciliationEscalationProof proof,
        HandoffFinalizationCheckpoint finalizationCheckpoint
    ) {
    }

    public static final class ScriptedReconciliationEscalationStore {
        private final int capacity;
        private final Set<Integer> failureOperationNumbers;
        private final List<StoredEscalation> values = new ArrayList<>();
        private final Set<String> proofDigests = new HashSet<>();
        private final Map<String, StoredEscalation> latestByHandoff = new HashMap<>();
        private final Map<String, HandoffFinalizationCheckpoint> finalizationByHandoff =
            new HashMap<>();
        private int appendAttempts;

        public ScriptedReconciliationEscalationStore(int capacity) {
            this(capacity, List.of());
        }

        public ScriptedReconciliationEscalationStore(
            int capacity,
            List<Integer> failureOperationNumbers
        ) {
            requireRange(capacity, 1, MAX_BOUND, "reconciliationEscalationStore.capacity");
            this.capacity = capacity;
            this.failureOperationNumbers = positiveUniqueIntegers(
                failureOperationNumbers,
                "reconciliationEscalationStore.failureOperationNumbers"
            );
        }

        public synchronized ReconciliationEscalationAppendStatus append(
            ReconciliationEscalationProof proof,
            HandoffFinalizationCheckpoint finalizationCheckpoint
        ) {
            Objects.requireNonNull(proof, "proof");
            appendAttempts++;
            if (failureOperationNumbers.contains(appendAttempts)) {
                throw new IllegalStateException("scripted reconciliation escalation store failure");
            }
            if (proofDigests.contains(proof.proofDigest())) {
                return ReconciliationEscalationAppendStatus.DUPLICATE_REJECTED;
            }

            HandoffFinalizationCheckpoint existingFinalization = finalizationByHandoff.get(
                proof.handoffId()
            );
            if (existingFinalization != null) {
                if (finalizationCheckpoint == null
                    || !existingFinalization.checkpointDigest().equals(
                        finalizationCheckpoint.checkpointDigest()
                    )) {
                    return ReconciliationEscalationAppendStatus
                        .FINALIZATION_CONFLICT_REJECTED;
                }
                return ReconciliationEscalationAppendStatus.DUPLICATE_REJECTED;
            }

            StoredEscalation latest = latestByHandoff.get(proof.handoffId());
            if (latest != null) {
                if (proof.evaluationOrdinal() < latest.proof().evaluationOrdinal()) {
                    return ReconciliationEscalationAppendStatus.ORDINAL_REJECTED;
                }
                if (!allowsTransition(
                    latest.proof().escalationLevel(),
                    proof.escalationLevel()
                )) {
                    return ReconciliationEscalationAppendStatus.LEVEL_REGRESSION_REJECTED;
                }
            }
            if (values.size() >= capacity) {
                return ReconciliationEscalationAppendStatus.CAPACITY_REJECTED;
            }

            StoredEscalation stored = new StoredEscalation(proof, finalizationCheckpoint);
            values.add(stored);
            proofDigests.add(proof.proofDigest());
            latestByHandoff.put(proof.handoffId(), stored);
            if (finalizationCheckpoint != null) {
                finalizationByHandoff.put(proof.handoffId(), finalizationCheckpoint);
            }
            return ReconciliationEscalationAppendStatus.ACCEPTED;
        }

        public synchronized int size() {
            return values.size();
        }

        public synchronized int finalizationCount() {
            return finalizationByHandoff.size();
        }

        public synchronized List<ReconciliationEscalationProof> proofs() {
            return values.stream().map(StoredEscalation::proof).toList();
        }

        public synchronized List<HandoffFinalizationCheckpoint> finalizations() {
            return List.copyOf(finalizationByHandoff.values());
        }

        public int capacity() {
            return capacity;
        }
    }

    public static ReconciliationEscalationResult evaluate(
        ReconciliationEscalationPolicy policy,
        ReconciliationEscalationInput input,
        ScriptedReconciliationEscalationStore store
    ) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(store, "store");
        if (store.capacity() > policy.maxStoredProofs()) {
            throw new IllegalArgumentException(
                "Escalation store capacity exceeds policy maximum"
            );
        }

        SdkAuditHandoffReconciliationV1.AuditHandoffReconciliationProof reconciliation =
            validateReconciliationProof(input.reconciliationProof());
        if (input.evaluationOrdinal() < reconciliation.reconciliationOrdinal()) {
            ReconciliationEscalationProof placeholder = createProof(
                reconciliation,
                input.evaluationOrdinal(),
                0,
                baseLevel(reconciliation.classification()),
                false,
                null
            );
            return result(
                ReconciliationEscalationStatus.FAILED_CLOSED,
                "reconciliation_escalation_before_reconciliation",
                placeholder,
                null,
                store
            );
        }

        long ageOrdinals = input.evaluationOrdinal() - reconciliation.reconciliationOrdinal();
        ReconciliationEscalationLevel level = escalationLevel(
            policy,
            reconciliation.classification(),
            ageOrdinals
        );
        boolean safeToFinalize = reconciliation.classification()
            == SdkAuditHandoffReconciliationV1.HandoffReconciliationClassification
                .ACKNOWLEDGED_CONFIRMED
            && reconciliation.safeToFinalize();
        HandoffFinalizationCheckpoint finalizationCheckpoint = null;
        if (safeToFinalize) {
            String acknowledgementId = required(
                reconciliation.acknowledgementId(),
                "reconciliationProof.acknowledgementId"
            );
            Map<String, Object> finalizationValue = new LinkedHashMap<>();
            finalizationValue.put("acknowledgementId", acknowledgementId);
            finalizationValue.put("contractVersion", CONTRACT_VERSION);
            finalizationValue.put("envelopeDigest", reconciliation.envelopeDigest());
            finalizationValue.put("evaluationOrdinal", input.evaluationOrdinal());
            finalizationValue.put("handoffId", reconciliation.handoffId());
            finalizationValue.put("reconciliationProofDigest", reconciliation.proofDigest());
            String checkpointDigest = digest(finalizationValue);
            finalizationCheckpoint = new HandoffFinalizationCheckpoint(
                CONTRACT_VERSION,
                reconciliation.handoffId(),
                reconciliation.envelopeDigest(),
                acknowledgementId,
                reconciliation.proofDigest(),
                input.evaluationOrdinal(),
                checkpointDigest
            );
        }

        ReconciliationEscalationProof proof = createProof(
            reconciliation,
            input.evaluationOrdinal(),
            ageOrdinals,
            level,
            safeToFinalize,
            finalizationCheckpoint == null ? null : finalizationCheckpoint.checkpointDigest()
        );
        ReconciliationEscalationAppendStatus appendStatus;
        try {
            appendStatus = store.append(proof, finalizationCheckpoint);
        } catch (RuntimeException exception) {
            return result(
                ReconciliationEscalationStatus.FAILED_CLOSED,
                "reconciliation_escalation_store_failed",
                proof,
                finalizationCheckpoint,
                store
            );
        }
        return switch (appendStatus) {
            case DUPLICATE_REJECTED -> result(
                ReconciliationEscalationStatus.DUPLICATE,
                "reconciliation_escalation_duplicate_proof",
                proof,
                finalizationCheckpoint,
                store
            );
            case CAPACITY_REJECTED -> result(
                ReconciliationEscalationStatus.CAPACITY_REJECTED,
                "reconciliation_escalation_store_capacity",
                proof,
                finalizationCheckpoint,
                store
            );
            case ORDINAL_REJECTED -> result(
                ReconciliationEscalationStatus.FAILED_CLOSED,
                "reconciliation_escalation_ordinal_regression",
                proof,
                finalizationCheckpoint,
                store
            );
            case LEVEL_REGRESSION_REJECTED -> result(
                ReconciliationEscalationStatus.FAILED_CLOSED,
                "reconciliation_escalation_level_regression",
                proof,
                finalizationCheckpoint,
                store
            );
            case FINALIZATION_CONFLICT_REJECTED -> result(
                ReconciliationEscalationStatus.FAILED_CLOSED,
                "reconciliation_finalization_conflict",
                proof,
                finalizationCheckpoint,
                store
            );
            case ACCEPTED -> result(
                ReconciliationEscalationStatus.RECORDED,
                level == ReconciliationEscalationLevel.RESOLVED
                    ? "reconciliation_finalization_checkpoint_recorded"
                    : "reconciliation_escalation_" + lower(level),
                proof,
                finalizationCheckpoint,
                store
            );
        };
    }

    public static Map<String, Object> finalizationCheckpointMap(
        HandoffFinalizationCheckpoint value
    ) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("acknowledgementId", value.acknowledgementId());
        output.put("checkpointDigest", value.checkpointDigest());
        output.put("contractVersion", value.contractVersion());
        output.put("envelopeDigest", value.envelopeDigest());
        output.put("evaluationOrdinal", value.evaluationOrdinal());
        output.put("handoffId", value.handoffId());
        output.put("reconciliationProofDigest", value.reconciliationProofDigest());
        return output;
    }

    public static Map<String, Object> proofMap(ReconciliationEscalationProof value) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ageOrdinals", value.ageOrdinals());
        output.put("classification", lower(value.classification()));
        output.put("contractVersion", value.contractVersion());
        output.put("escalationLevel", lower(value.escalationLevel()));
        output.put("evaluationOrdinal", value.evaluationOrdinal());
        output.put("finalizationCheckpointDigest", value.finalizationCheckpointDigest());
        output.put("handoffId", value.handoffId());
        output.put("proofDigest", value.proofDigest());
        output.put("reconciliationOrdinal", value.reconciliationOrdinal());
        output.put("reconciliationProofDigest", value.reconciliationProofDigest());
        output.put("requiresManualAction", value.requiresManualAction());
        output.put("safeToFinalize", value.safeToFinalize());
        return output;
    }

    public static Map<String, Object> resultMap(ReconciliationEscalationResult value) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("contractVersion", value.contractVersion());
        output.put(
            "finalizationCheckpoint",
            value.finalizationCheckpoint() == null
                ? null
                : finalizationCheckpointMap(value.finalizationCheckpoint())
        );
        output.put("proof", proofMap(value.proof()));
        output.put("reasonCode", value.reasonCode());
        output.put("status", lower(value.status()));
        output.put("storedFinalizationCount", value.storedFinalizationCount());
        output.put("storedProofCount", value.storedProofCount());
        return output;
    }

    private static ReconciliationEscalationProof createProof(
        SdkAuditHandoffReconciliationV1.AuditHandoffReconciliationProof reconciliation,
        long evaluationOrdinal,
        long ageOrdinals,
        ReconciliationEscalationLevel level,
        boolean safeToFinalize,
        String finalizationCheckpointDigest
    ) {
        boolean requiresManualAction = level == ReconciliationEscalationLevel.INVESTIGATE
            || level == ReconciliationEscalationLevel.BLOCK;
        Map<String, Object> proofValue = new LinkedHashMap<>();
        proofValue.put("ageOrdinals", ageOrdinals);
        proofValue.put("classification", lower(reconciliation.classification()));
        proofValue.put("contractVersion", CONTRACT_VERSION);
        proofValue.put("escalationLevel", lower(level));
        proofValue.put("evaluationOrdinal", evaluationOrdinal);
        proofValue.put("finalizationCheckpointDigest", finalizationCheckpointDigest);
        proofValue.put("handoffId", reconciliation.handoffId());
        proofValue.put("reconciliationOrdinal", reconciliation.reconciliationOrdinal());
        proofValue.put("reconciliationProofDigest", reconciliation.proofDigest());
        proofValue.put("requiresManualAction", requiresManualAction);
        proofValue.put("safeToFinalize", safeToFinalize);
        String proofDigest = digest(proofValue);
        return new ReconciliationEscalationProof(
            CONTRACT_VERSION,
            reconciliation.handoffId(),
            reconciliation.proofDigest(),
            reconciliation.classification(),
            reconciliation.reconciliationOrdinal(),
            evaluationOrdinal,
            ageOrdinals,
            level,
            requiresManualAction,
            safeToFinalize,
            finalizationCheckpointDigest,
            proofDigest
        );
    }

    private static SdkAuditHandoffReconciliationV1.AuditHandoffReconciliationProof
        validateReconciliationProof(
            SdkAuditHandoffReconciliationV1.AuditHandoffReconciliationProof value
        ) {
        Objects.requireNonNull(value, "reconciliationProof");
        requireVersion(value.contractVersion());
        required(value.handoffId(), "reconciliationProof.handoffId");
        required(value.envelopeDigest(), "reconciliationProof.envelopeDigest");
        required(value.evidenceDigest(), "reconciliationProof.evidenceDigest");
        required(value.proofDigest(), "reconciliationProof.proofDigest");
        requireNonNegative(
            value.reconciliationOrdinal(),
            "reconciliationProof.reconciliationOrdinal"
        );
        boolean acknowledged = value.classification()
            == SdkAuditHandoffReconciliationV1.HandoffReconciliationClassification
                .ACKNOWLEDGED_CONFIRMED;
        if (value.safeToFinalize() != acknowledged) {
            throw new IllegalArgumentException(
                "safeToFinalize must match acknowledged confirmation"
            );
        }
        if (acknowledged) {
            required(value.acknowledgementId(), "reconciliationProof.acknowledgementId");
            required(
                value.acknowledgementEnvelopeDigest(),
                "reconciliationProof.acknowledgementEnvelopeDigest"
            );
        }
        return value;
    }

    private static ReconciliationEscalationLevel baseLevel(
        SdkAuditHandoffReconciliationV1.HandoffReconciliationClassification classification
    ) {
        return switch (classification) {
            case ACKNOWLEDGED_CONFIRMED -> ReconciliationEscalationLevel.RESOLVED;
            case CONFLICTING_EVIDENCE -> ReconciliationEscalationLevel.INVESTIGATE;
            case ACKNOWLEDGEMENT_MISSING, MISSING_NO_EVIDENCE ->
                ReconciliationEscalationLevel.OBSERVE;
            case PENDING_CONFIRMED -> ReconciliationEscalationLevel.NONE;
        };
    }

    private static ReconciliationEscalationLevel escalationLevel(
        ReconciliationEscalationPolicy policy,
        SdkAuditHandoffReconciliationV1.HandoffReconciliationClassification classification,
        long ageOrdinals
    ) {
        ReconciliationEscalationLevel level = baseLevel(classification);
        if (level == ReconciliationEscalationLevel.RESOLVED) {
            return level;
        }
        if (ageOrdinals >= policy.blockAfterOrdinals()) {
            return maxLevel(level, ReconciliationEscalationLevel.BLOCK);
        }
        if (ageOrdinals >= policy.investigateAfterOrdinals()) {
            return maxLevel(level, ReconciliationEscalationLevel.INVESTIGATE);
        }
        if (ageOrdinals >= policy.observeAfterOrdinals()) {
            return maxLevel(level, ReconciliationEscalationLevel.OBSERVE);
        }
        return level;
    }

    private static ReconciliationEscalationLevel maxLevel(
        ReconciliationEscalationLevel left,
        ReconciliationEscalationLevel right
    ) {
        return levelRank(left) >= levelRank(right) ? left : right;
    }

    private static boolean allowsTransition(
        ReconciliationEscalationLevel previous,
        ReconciliationEscalationLevel next
    ) {
        if (previous == ReconciliationEscalationLevel.RESOLVED) {
            return next == ReconciliationEscalationLevel.RESOLVED;
        }
        if (next == ReconciliationEscalationLevel.RESOLVED) {
            return true;
        }
        return levelRank(next) >= levelRank(previous);
    }

    private static int levelRank(ReconciliationEscalationLevel value) {
        return switch (value) {
            case NONE -> 0;
            case OBSERVE -> 1;
            case INVESTIGATE -> 2;
            case BLOCK -> 3;
            case RESOLVED -> 4;
        };
    }

    private static ReconciliationEscalationResult result(
        ReconciliationEscalationStatus status,
        String reasonCode,
        ReconciliationEscalationProof proof,
        HandoffFinalizationCheckpoint finalizationCheckpoint,
        ScriptedReconciliationEscalationStore store
    ) {
        return new ReconciliationEscalationResult(
            CONTRACT_VERSION,
            status,
            reasonCode,
            proof,
            finalizationCheckpoint,
            store.size(),
            store.finalizationCount()
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
            throw new SdkAggregateExportCheckpointV1
                .UnsupportedCheckpointEscalationVersionException(value);
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
