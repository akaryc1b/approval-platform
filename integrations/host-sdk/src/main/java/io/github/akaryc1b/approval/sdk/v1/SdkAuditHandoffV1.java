package io.github.akaryc1b.approval.sdk.v1;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reference-only audit handoff envelope and deterministic acknowledgement queue. */
public final class SdkAuditHandoffV1 {
    public static final String CONTRACT_VERSION = SdkTelemetryHandoffV1.CONTRACT_VERSION;
    private static final int MAX_QUEUE_CAPACITY = 10_000;

    private SdkAuditHandoffV1() {
    }

    public enum ScriptedHandoffOutcome {
        ACK,
        NACK,
        TIMEOUT_LIKE,
        FAILURE
    }

    public enum AuditHandoffStatus {
        ACKNOWLEDGED,
        DUPLICATE_ACKNOWLEDGED,
        PENDING,
        CAPACITY_REJECTED,
        FAILED_CLOSED
    }

    public record AuditHandoffInput(
        String contractVersion,
        String handoffId,
        String destinationReference,
        long handoffOrdinal,
        SdkAuditCompletenessV1.AuditCompletenessProof proof
    ) {
        public AuditHandoffInput {
            requireVersion(contractVersion);
            handoffId = required(handoffId, "auditHandoff.handoffId");
            destinationReference = required(
                destinationReference,
                "auditHandoff.destinationReference"
            );
            requireNonNegative(handoffOrdinal, "auditHandoff.handoffOrdinal");
            validateProof(proof);
        }
    }

    public record AuditHandoffEnvelope(
        String contractVersion,
        String handoffId,
        String destinationReference,
        long handoffOrdinal,
        String auditBatchDigest,
        String auditIdentityDigest,
        int recordCount,
        int attemptCount,
        String firstEventId,
        String terminalEventId,
        String envelopeDigest
    ) {
        public AuditHandoffEnvelope {
            requireVersion(contractVersion);
            handoffId = required(handoffId, "auditHandoff.handoffId");
            destinationReference = required(
                destinationReference,
                "auditHandoff.destinationReference"
            );
            requireNonNegative(handoffOrdinal, "auditHandoff.handoffOrdinal");
            auditBatchDigest = required(
                auditBatchDigest,
                "auditHandoff.auditBatchDigest"
            );
            auditIdentityDigest = required(
                auditIdentityDigest,
                "auditHandoff.auditIdentityDigest"
            );
            if (recordCount < 1 || attemptCount < 0 || recordCount != attemptCount + 2) {
                throw new IllegalArgumentException(
                    "auditHandoff recordCount must equal attemptCount + 2"
                );
            }
            firstEventId = required(firstEventId, "auditHandoff.firstEventId");
            terminalEventId = required(terminalEventId, "auditHandoff.terminalEventId");
            envelopeDigest = required(envelopeDigest, "auditHandoff.envelopeDigest");
        }
    }

    public record AuditHandoffAcknowledgement(
        String contractVersion,
        String acknowledgementId,
        String handoffId,
        String destinationReference,
        String envelopeDigest,
        String auditBatchDigest,
        int deliveryAttempt,
        long acknowledgedOrdinal
    ) {
        public AuditHandoffAcknowledgement {
            requireVersion(contractVersion);
            acknowledgementId = required(
                acknowledgementId,
                "auditHandoffAck.acknowledgementId"
            );
            handoffId = required(handoffId, "auditHandoffAck.handoffId");
            destinationReference = required(
                destinationReference,
                "auditHandoffAck.destinationReference"
            );
            envelopeDigest = required(envelopeDigest, "auditHandoffAck.envelopeDigest");
            auditBatchDigest = required(
                auditBatchDigest,
                "auditHandoffAck.auditBatchDigest"
            );
            if (deliveryAttempt < 1) {
                throw new IllegalArgumentException("deliveryAttempt must be positive");
            }
            requireNonNegative(acknowledgedOrdinal, "auditHandoffAck.acknowledgedOrdinal");
        }
    }

    public record AuditHandoffResult(
        String contractVersion,
        AuditHandoffStatus status,
        String reasonCode,
        AuditHandoffEnvelope envelope,
        AuditHandoffAcknowledgement acknowledgement,
        int deliveryAttempt,
        int pendingCount,
        int acknowledgedCount
    ) {
        public AuditHandoffResult {
            requireVersion(contractVersion);
            status = Objects.requireNonNull(status, "auditHandoffResult.status");
            reasonCode = required(reasonCode, "auditHandoffResult.reasonCode");
            envelope = Objects.requireNonNull(envelope, "auditHandoffResult.envelope");
            if (deliveryAttempt < 0 || pendingCount < 0 || acknowledgedCount < 0) {
                throw new IllegalArgumentException(
                    "Audit handoff result counters cannot be negative"
                );
            }
        }
    }

    private static final class PendingState {
        private final AuditHandoffEnvelope envelope;
        private int deliveryAttempts;

        private PendingState(AuditHandoffEnvelope envelope) {
            this.envelope = envelope;
        }
    }

    public static final class ScriptedAuditHandoffQueue {
        private final int capacity;
        private final List<ScriptedHandoffOutcome> outcomes;
        private final Map<String, PendingState> pending = new LinkedHashMap<>();
        private final Map<String, AuditHandoffAcknowledgement> acknowledged =
            new LinkedHashMap<>();
        private int nextOutcomeIndex;

        public ScriptedAuditHandoffQueue(
            int capacity,
            List<ScriptedHandoffOutcome> outcomes
        ) {
            if (capacity < 1 || capacity > MAX_QUEUE_CAPACITY) {
                throw new IllegalArgumentException(
                    "auditHandoffQueue.capacity exceeds the safe bound"
                );
            }
            Objects.requireNonNull(outcomes, "auditHandoffQueue.outcomes");
            if (outcomes.isEmpty()) {
                throw new IllegalArgumentException(
                    "auditHandoffQueue.outcomes must be non-empty"
                );
            }
            this.capacity = capacity;
            this.outcomes = List.copyOf(outcomes);
        }

        public synchronized int pendingCount() {
            return pending.size();
        }

        public synchronized int acknowledgedCount() {
            return acknowledged.size();
        }

        public synchronized AuditHandoffResult handoff(AuditHandoffEnvelope envelope) {
            AuditHandoffEnvelope requiredEnvelope = validateAuditHandoffEnvelope(envelope);
            AuditHandoffAcknowledgement existingAcknowledgement = acknowledged.get(
                requiredEnvelope.handoffId()
            );
            if (existingAcknowledgement != null) {
                if (!existingAcknowledgement.envelopeDigest().equals(
                    requiredEnvelope.envelopeDigest()
                )) {
                    return result(
                        AuditHandoffStatus.FAILED_CLOSED,
                        "audit_handoff_identity_conflict",
                        requiredEnvelope,
                        null,
                        existingAcknowledgement.deliveryAttempt(),
                        this
                    );
                }
                return result(
                    AuditHandoffStatus.DUPLICATE_ACKNOWLEDGED,
                    "audit_handoff_duplicate_acknowledged",
                    requiredEnvelope,
                    existingAcknowledgement,
                    existingAcknowledgement.deliveryAttempt(),
                    this
                );
            }

            PendingState pendingState = pending.get(requiredEnvelope.handoffId());
            if (pendingState != null && !pendingState.envelope.envelopeDigest().equals(
                requiredEnvelope.envelopeDigest()
            )) {
                return result(
                    AuditHandoffStatus.FAILED_CLOSED,
                    "audit_handoff_identity_conflict",
                    requiredEnvelope,
                    null,
                    pendingState.deliveryAttempts,
                    this
                );
            }
            if (pendingState == null) {
                if (pending.size() + acknowledged.size() >= capacity) {
                    return result(
                        AuditHandoffStatus.CAPACITY_REJECTED,
                        "audit_handoff_queue_capacity",
                        requiredEnvelope,
                        null,
                        0,
                        this
                    );
                }
                pendingState = new PendingState(requiredEnvelope);
                pending.put(requiredEnvelope.handoffId(), pendingState);
            }

            pendingState.deliveryAttempts++;
            int deliveryAttempt = pendingState.deliveryAttempts;
            ScriptedHandoffOutcome outcome = nextOutcomeIndex < outcomes.size()
                ? outcomes.get(nextOutcomeIndex)
                : outcomes.get(outcomes.size() - 1);
            nextOutcomeIndex++;

            if (outcome == ScriptedHandoffOutcome.NACK) {
                return result(
                    AuditHandoffStatus.PENDING,
                    "audit_handoff_nack",
                    requiredEnvelope,
                    null,
                    deliveryAttempt,
                    this
                );
            }
            if (outcome == ScriptedHandoffOutcome.TIMEOUT_LIKE) {
                return result(
                    AuditHandoffStatus.PENDING,
                    "audit_handoff_timeout_like",
                    requiredEnvelope,
                    null,
                    deliveryAttempt,
                    this
                );
            }
            if (outcome == ScriptedHandoffOutcome.FAILURE) {
                return result(
                    AuditHandoffStatus.FAILED_CLOSED,
                    "audit_handoff_queue_failed",
                    requiredEnvelope,
                    null,
                    deliveryAttempt,
                    this
                );
            }

            AuditHandoffAcknowledgement acknowledgement = createAcknowledgement(
                requiredEnvelope,
                deliveryAttempt
            );
            pending.remove(requiredEnvelope.handoffId());
            acknowledged.put(requiredEnvelope.handoffId(), acknowledgement);
            return result(
                AuditHandoffStatus.ACKNOWLEDGED,
                "audit_handoff_acknowledged",
                requiredEnvelope,
                acknowledgement,
                deliveryAttempt,
                this
            );
        }
    }

    public static AuditHandoffEnvelope createAuditHandoffEnvelope(
        AuditHandoffInput input
    ) {
        Objects.requireNonNull(input, "input");
        SdkAuditCompletenessV1.AuditCompletenessProof proof = input.proof();
        Map<String, Object> envelopeInput = new LinkedHashMap<>();
        envelopeInput.put("attemptCount", proof.attemptCount());
        envelopeInput.put("auditBatchDigest", proof.batchDigest());
        envelopeInput.put("auditIdentityDigest", proof.identityDigest());
        envelopeInput.put("contractVersion", CONTRACT_VERSION);
        envelopeInput.put("destinationReference", input.destinationReference());
        envelopeInput.put("firstEventId", proof.firstEventId());
        envelopeInput.put("handoffId", input.handoffId());
        envelopeInput.put("handoffOrdinal", input.handoffOrdinal());
        envelopeInput.put("recordCount", proof.recordCount());
        envelopeInput.put("terminalEventId", proof.terminalEventId());
        String envelopeDigest = digest(envelopeInput);
        return new AuditHandoffEnvelope(
            CONTRACT_VERSION,
            input.handoffId(),
            input.destinationReference(),
            input.handoffOrdinal(),
            proof.batchDigest(),
            proof.identityDigest(),
            proof.recordCount(),
            proof.attemptCount(),
            proof.firstEventId(),
            proof.terminalEventId(),
            envelopeDigest
        );
    }

    public static AuditHandoffEnvelope validateAuditHandoffEnvelope(
        AuditHandoffEnvelope envelope
    ) {
        return Objects.requireNonNull(envelope, "envelope");
    }

    public static Map<String, Object> auditHandoffEnvelopeMap(
        AuditHandoffEnvelope envelope
    ) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("attemptCount", envelope.attemptCount());
        output.put("auditBatchDigest", envelope.auditBatchDigest());
        output.put("auditIdentityDigest", envelope.auditIdentityDigest());
        output.put("contractVersion", envelope.contractVersion());
        output.put("destinationReference", envelope.destinationReference());
        output.put("envelopeDigest", envelope.envelopeDigest());
        output.put("firstEventId", envelope.firstEventId());
        output.put("handoffId", envelope.handoffId());
        output.put("handoffOrdinal", envelope.handoffOrdinal());
        output.put("recordCount", envelope.recordCount());
        output.put("terminalEventId", envelope.terminalEventId());
        return output;
    }

    public static Map<String, Object> auditHandoffResultMap(AuditHandoffResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("acknowledgedCount", result.acknowledgedCount());
        output.put(
            "acknowledgement",
            result.acknowledgement() == null
                ? null
                : acknowledgementMap(result.acknowledgement())
        );
        output.put("contractVersion", result.contractVersion());
        output.put("deliveryAttempt", result.deliveryAttempt());
        output.put("envelope", auditHandoffEnvelopeMap(result.envelope()));
        output.put("pendingCount", result.pendingCount());
        output.put("reasonCode", result.reasonCode());
        output.put("status", lower(result.status()));
        return output;
    }

    private static Map<String, Object> acknowledgementMap(
        AuditHandoffAcknowledgement acknowledgement
    ) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("acknowledgedOrdinal", acknowledgement.acknowledgedOrdinal());
        output.put("acknowledgementId", acknowledgement.acknowledgementId());
        output.put("auditBatchDigest", acknowledgement.auditBatchDigest());
        output.put("contractVersion", acknowledgement.contractVersion());
        output.put("deliveryAttempt", acknowledgement.deliveryAttempt());
        output.put("destinationReference", acknowledgement.destinationReference());
        output.put("envelopeDigest", acknowledgement.envelopeDigest());
        output.put("handoffId", acknowledgement.handoffId());
        return output;
    }

    private static AuditHandoffAcknowledgement createAcknowledgement(
        AuditHandoffEnvelope envelope,
        int deliveryAttempt
    ) {
        String acknowledgementId = CanonicalJson.sha256Hex(
            (
                "audit-handoff-ack-v1\n"
                    + envelope.envelopeDigest()
                    + "\n"
                    + deliveryAttempt
            ).getBytes(StandardCharsets.UTF_8)
        );
        return new AuditHandoffAcknowledgement(
            CONTRACT_VERSION,
            acknowledgementId,
            envelope.handoffId(),
            envelope.destinationReference(),
            envelope.envelopeDigest(),
            envelope.auditBatchDigest(),
            deliveryAttempt,
            envelope.handoffOrdinal() + deliveryAttempt
        );
    }

    private static AuditHandoffResult result(
        AuditHandoffStatus status,
        String reasonCode,
        AuditHandoffEnvelope envelope,
        AuditHandoffAcknowledgement acknowledgement,
        int deliveryAttempt,
        ScriptedAuditHandoffQueue queue
    ) {
        return new AuditHandoffResult(
            CONTRACT_VERSION,
            status,
            reasonCode,
            envelope,
            acknowledgement,
            deliveryAttempt,
            queue.pendingCount(),
            queue.acknowledgedCount()
        );
    }

    private static void validateProof(
        SdkAuditCompletenessV1.AuditCompletenessProof proof
    ) {
        Objects.requireNonNull(proof, "auditHandoff.proof");
        requireVersion(proof.contractVersion());
        if (
            proof.recordCount() < 1
                || proof.attemptCount() < 0
                || proof.recordCount() != proof.attemptCount() + 2
        ) {
            throw new IllegalArgumentException(
                "auditProof recordCount must equal attemptCount + 2"
            );
        }
        required(proof.firstEventId(), "auditProof.firstEventId");
        required(proof.terminalEventId(), "auditProof.terminalEventId");
        required(proof.identityDigest(), "auditProof.identityDigest");
        required(proof.batchDigest(), "auditProof.batchDigest");
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

    private static void requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " cannot be negative");
        }
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase();
    }
}
