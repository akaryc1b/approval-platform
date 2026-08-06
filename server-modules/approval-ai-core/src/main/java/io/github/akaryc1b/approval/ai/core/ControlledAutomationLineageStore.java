package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.core.ControlledAutomationConfirmationService.ControlledAutomationConfirmationEvidence;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.ParameterBinding;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable P4 port for hash-only controlled-automation lineage.
 *
 * <p>This port records non-executing lineage only. It cannot admit or invoke a command, acquire
 * authority, retry an UNKNOWN outcome or store raw Proposal parameter values.</p>
 */
public interface ControlledAutomationLineageStore {

    RegistrationResult register(RegistrationCommand command);

    TransitionResult transition(TransitionCommand command);

    Optional<LineageSnapshot> find(
        String tenantEvidenceHash,
        String operatorEvidenceHash,
        UUID proposalId
    );

    enum RegistrationDisposition {
        REGISTERED,
        REPLAYED,
        CONFLICT
    }

    enum TransitionDisposition {
        APPLIED,
        REPLAYED,
        NOT_FOUND,
        IDENTITY_MISMATCH,
        REVISION_CONFLICT,
        STATE_CONFLICT,
        IDEMPOTENCY_CONFLICT
    }

    enum LineageStatus {
        CONFIRMED,
        CANCELLED,
        SUCCEEDED,
        FAILED,
        PARTIAL,
        UNKNOWN;

        public boolean terminal() {
            return this != CONFIRMED;
        }
    }

    enum LineageOutcome {
        NONE,
        SUCCESS,
        FAILURE,
        PARTIAL,
        UNKNOWN
    }

    record RegistrationCommand(
        UUID proposalId,
        UUID confirmationId,
        String tenantEvidenceHash,
        String operatorEvidenceHash,
        String proposalLineageHash,
        String confirmationEvidenceHash,
        String canonicalActionType,
        String resourceEvidenceHash,
        String whitelistVersion,
        String policyVersion,
        String idempotencyKeyHash,
        String idempotencyPayloadHash,
        Instant confirmedAt,
        Instant expiresAt,
        String evidenceHash
    ) {
        public RegistrationCommand {
            proposalId = Objects.requireNonNull(proposalId, "proposalId must not be null");
            confirmationId = Objects.requireNonNull(
                confirmationId,
                "confirmationId must not be null"
            );
            tenantEvidenceHash = sha256(tenantEvidenceHash, "tenantEvidenceHash");
            operatorEvidenceHash = sha256(operatorEvidenceHash, "operatorEvidenceHash");
            proposalLineageHash = sha256(proposalLineageHash, "proposalLineageHash");
            confirmationEvidenceHash = sha256(
                confirmationEvidenceHash,
                "confirmationEvidenceHash"
            );
            canonicalActionType = ControlledAutomationProposal.requireCanonicalActionType(
                canonicalActionType
            );
            resourceEvidenceHash = sha256(resourceEvidenceHash, "resourceEvidenceHash");
            whitelistVersion = ControlledAutomationProposal.requireVersion(
                whitelistVersion,
                "whitelistVersion"
            );
            policyVersion = ControlledAutomationProposal.requireVersion(
                policyVersion,
                "policyVersion"
            );
            idempotencyKeyHash = sha256(idempotencyKeyHash, "idempotencyKeyHash");
            idempotencyPayloadHash = sha256(
                idempotencyPayloadHash,
                "idempotencyPayloadHash"
            );
            confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt must not be null");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
            evidenceHash = sha256(evidenceHash, "registrationEvidenceHash");
            if (!expiresAt.isAfter(confirmedAt)) {
                throw new IllegalArgumentException("expiresAt must be after confirmedAt");
            }
            String expectedHash = registrationHash(
                proposalId,
                confirmationId,
                tenantEvidenceHash,
                operatorEvidenceHash,
                proposalLineageHash,
                confirmationEvidenceHash,
                canonicalActionType,
                resourceEvidenceHash,
                whitelistVersion,
                policyVersion,
                idempotencyKeyHash,
                idempotencyPayloadHash,
                confirmedAt,
                expiresAt
            );
            if (!evidenceHash.equals(expectedHash)) {
                throw new IllegalArgumentException(
                    "registration evidence must match the exact hash-only lineage"
                );
            }
        }

        public static RegistrationCommand from(
            ControlledAutomationProposal proposal,
            ControlledAutomationConfirmationEvidence confirmation,
            String idempotencyKeyHash,
            String idempotencyPayloadHash
        ) {
            Objects.requireNonNull(proposal, "proposal must not be null");
            Objects.requireNonNull(confirmation, "confirmation must not be null");
            if (!confirmation.proposalId().equals(proposal.proposalId())
                || !confirmation.tenantEvidenceHash().equals(proposal.tenantEvidenceHash())
                || !confirmation.operatorEvidenceHash().equals(proposal.operatorEvidenceHash())
                || !confirmation.sourceEvidenceHash().equals(
                    proposal.sourceAdvisory().evidenceHash()
                )
                || !confirmation.canonicalActionType().equals(proposal.canonicalActionType())
                || !confirmation.typedParameterHash().equals(parameterHash(proposal))
                || !confirmation.resourceEvidenceHash().equals(
                    proposal.targetResource().evidenceHash()
                )
                || !confirmation.whitelistVersion().equals(proposal.whitelistVersion())
                || !confirmation.policyVersion().equals(proposal.policy().version())
                || confirmation.commandAdmitted()) {
                throw new IllegalArgumentException(
                    "confirmation evidence must bind the exact non-executable Proposal"
                );
            }
            return fromEvidence(
                proposal.proposalId(),
                confirmation.confirmationId(),
                proposal.tenantEvidenceHash(),
                proposal.operatorEvidenceHash(),
                proposal.lineageHash(),
                confirmation.evidenceHash(),
                proposal.canonicalActionType(),
                proposal.targetResource().evidenceHash(),
                proposal.whitelistVersion(),
                proposal.policy().version(),
                idempotencyKeyHash,
                idempotencyPayloadHash,
                confirmation.confirmedAt(),
                confirmation.expiresAt()
            );
        }

        public static RegistrationCommand fromEvidence(
            UUID proposalId,
            UUID confirmationId,
            String tenantEvidenceHash,
            String operatorEvidenceHash,
            String proposalLineageHash,
            String confirmationEvidenceHash,
            String canonicalActionType,
            String resourceEvidenceHash,
            String whitelistVersion,
            String policyVersion,
            String idempotencyKeyHash,
            String idempotencyPayloadHash,
            Instant confirmedAt,
            Instant expiresAt
        ) {
            String evidenceHash = registrationHash(
                proposalId,
                confirmationId,
                tenantEvidenceHash,
                operatorEvidenceHash,
                proposalLineageHash,
                confirmationEvidenceHash,
                canonicalActionType,
                resourceEvidenceHash,
                whitelistVersion,
                policyVersion,
                idempotencyKeyHash,
                idempotencyPayloadHash,
                confirmedAt,
                expiresAt
            );
            return new RegistrationCommand(
                proposalId,
                confirmationId,
                tenantEvidenceHash,
                operatorEvidenceHash,
                proposalLineageHash,
                confirmationEvidenceHash,
                canonicalActionType,
                resourceEvidenceHash,
                whitelistVersion,
                policyVersion,
                idempotencyKeyHash,
                idempotencyPayloadHash,
                confirmedAt,
                expiresAt,
                evidenceHash
            );
        }
    }

    record TransitionCommand(
        String tenantEvidenceHash,
        String operatorEvidenceHash,
        UUID proposalId,
        long expectedRevision,
        LineageStatus expectedStatus,
        LineageStatus targetStatus,
        LineageOutcome outcome,
        String resultEvidenceHash,
        String idempotencyKeyHash,
        String idempotencyPayloadHash,
        Instant occurredAt,
        int commandAttempts,
        boolean automaticRetryAllowed,
        String transitionHash
    ) {
        public TransitionCommand {
            tenantEvidenceHash = sha256(tenantEvidenceHash, "tenantEvidenceHash");
            operatorEvidenceHash = sha256(operatorEvidenceHash, "operatorEvidenceHash");
            proposalId = Objects.requireNonNull(proposalId, "proposalId must not be null");
            expectedStatus = Objects.requireNonNull(
                expectedStatus,
                "expectedStatus must not be null"
            );
            targetStatus = Objects.requireNonNull(targetStatus, "targetStatus must not be null");
            outcome = Objects.requireNonNull(outcome, "outcome must not be null");
            resultEvidenceHash = sha256(resultEvidenceHash, "resultEvidenceHash");
            idempotencyKeyHash = sha256(idempotencyKeyHash, "idempotencyKeyHash");
            idempotencyPayloadHash = sha256(
                idempotencyPayloadHash,
                "idempotencyPayloadHash"
            );
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
            transitionHash = sha256(transitionHash, "transitionHash");
            if (expectedRevision < 1) {
                throw new IllegalArgumentException("expectedRevision must be positive");
            }
            requireTerminalPair(
                targetStatus,
                outcome,
                commandAttempts,
                automaticRetryAllowed
            );
            String expectedHash = computeTransitionHash(
                tenantEvidenceHash,
                operatorEvidenceHash,
                proposalId,
                expectedRevision,
                expectedStatus,
                targetStatus,
                outcome,
                resultEvidenceHash,
                idempotencyKeyHash,
                idempotencyPayloadHash,
                occurredAt,
                commandAttempts,
                automaticRetryAllowed
            );
            if (!transitionHash.equals(expectedHash)) {
                throw new IllegalArgumentException(
                    "transition hash must match the exact terminal lineage evidence"
                );
            }
        }

        public static TransitionCommand create(
            String tenantEvidenceHash,
            String operatorEvidenceHash,
            UUID proposalId,
            long expectedRevision,
            LineageStatus expectedStatus,
            LineageStatus targetStatus,
            LineageOutcome outcome,
            String resultEvidenceHash,
            String idempotencyKeyHash,
            String idempotencyPayloadHash,
            Instant occurredAt,
            int commandAttempts
        ) {
            boolean automaticRetryAllowed = false;
            String transitionHash = computeTransitionHash(
                tenantEvidenceHash,
                operatorEvidenceHash,
                proposalId,
                expectedRevision,
                expectedStatus,
                targetStatus,
                outcome,
                resultEvidenceHash,
                idempotencyKeyHash,
                idempotencyPayloadHash,
                occurredAt,
                commandAttempts,
                automaticRetryAllowed
            );
            return new TransitionCommand(
                tenantEvidenceHash,
                operatorEvidenceHash,
                proposalId,
                expectedRevision,
                expectedStatus,
                targetStatus,
                outcome,
                resultEvidenceHash,
                idempotencyKeyHash,
                idempotencyPayloadHash,
                occurredAt,
                commandAttempts,
                automaticRetryAllowed,
                transitionHash
            );
        }
    }

    record LineageSnapshot(
        UUID proposalId,
        UUID confirmationId,
        String tenantEvidenceHash,
        String operatorEvidenceHash,
        String proposalLineageHash,
        String confirmationEvidenceHash,
        String canonicalActionType,
        String resourceEvidenceHash,
        String whitelistVersion,
        String policyVersion,
        String registrationIdempotencyKeyHash,
        String registrationIdempotencyPayloadHash,
        long revision,
        LineageStatus status,
        LineageOutcome outcome,
        int commandAttempts,
        boolean automaticRetryAllowed,
        Instant confirmedAt,
        Instant expiresAt,
        Instant updatedAt,
        String currentEvidenceHash,
        String currentEventHash
    ) {
        public LineageSnapshot {
            proposalId = Objects.requireNonNull(proposalId, "proposalId must not be null");
            confirmationId = Objects.requireNonNull(
                confirmationId,
                "confirmationId must not be null"
            );
            tenantEvidenceHash = sha256(tenantEvidenceHash, "tenantEvidenceHash");
            operatorEvidenceHash = sha256(operatorEvidenceHash, "operatorEvidenceHash");
            proposalLineageHash = sha256(proposalLineageHash, "proposalLineageHash");
            confirmationEvidenceHash = sha256(
                confirmationEvidenceHash,
                "confirmationEvidenceHash"
            );
            canonicalActionType = ControlledAutomationProposal.requireCanonicalActionType(
                canonicalActionType
            );
            resourceEvidenceHash = sha256(resourceEvidenceHash, "resourceEvidenceHash");
            whitelistVersion = ControlledAutomationProposal.requireVersion(
                whitelistVersion,
                "whitelistVersion"
            );
            policyVersion = ControlledAutomationProposal.requireVersion(
                policyVersion,
                "policyVersion"
            );
            registrationIdempotencyKeyHash = sha256(
                registrationIdempotencyKeyHash,
                "registrationIdempotencyKeyHash"
            );
            registrationIdempotencyPayloadHash = sha256(
                registrationIdempotencyPayloadHash,
                "registrationIdempotencyPayloadHash"
            );
            status = Objects.requireNonNull(status, "status must not be null");
            outcome = Objects.requireNonNull(outcome, "outcome must not be null");
            confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt must not be null");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
            currentEvidenceHash = sha256(currentEvidenceHash, "currentEvidenceHash");
            currentEventHash = sha256(currentEventHash, "currentEventHash");
            if (revision < 1 || !expiresAt.isAfter(confirmedAt)) {
                throw new IllegalArgumentException("lineage revision and lifetime are invalid");
            }
            requireSnapshotPair(
                revision,
                status,
                outcome,
                commandAttempts,
                automaticRetryAllowed
            );
        }
    }

    record RegistrationResult(
        RegistrationDisposition disposition,
        LineageSnapshot snapshot
    ) {
        public RegistrationResult {
            disposition = Objects.requireNonNull(disposition, "disposition must not be null");
            snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        }
    }

    record TransitionResult(
        TransitionDisposition disposition,
        Optional<LineageSnapshot> snapshot
    ) {
        public TransitionResult {
            disposition = Objects.requireNonNull(disposition, "disposition must not be null");
            snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
            if (disposition == TransitionDisposition.NOT_FOUND && snapshot.isPresent()) {
                throw new IllegalArgumentException("NOT_FOUND cannot expose a snapshot");
            }
        }
    }

    private static String parameterHash(ControlledAutomationProposal proposal) {
        List<String> values = new ArrayList<>();
        proposal.parameters().values().stream()
            .sorted(Comparator.comparing(ParameterBinding::name))
            .forEach(binding -> {
                values.add(binding.name());
                values.add(binding.value().type().name());
                values.add(binding.value().canonicalValue());
                values.add(binding.source().name());
            });
        return ControlledAutomationProposal.hashTuple(
            "controlled-automation-confirmation-parameters-v1",
            values.toArray(String[]::new)
        );
    }

    private static String registrationHash(
        UUID proposalId,
        UUID confirmationId,
        String tenantEvidenceHash,
        String operatorEvidenceHash,
        String proposalLineageHash,
        String confirmationEvidenceHash,
        String canonicalActionType,
        String resourceEvidenceHash,
        String whitelistVersion,
        String policyVersion,
        String idempotencyKeyHash,
        String idempotencyPayloadHash,
        Instant confirmedAt,
        Instant expiresAt
    ) {
        return ControlledAutomationProposal.hashTuple(
            "controlled-automation-lineage-registration-v1",
            Objects.requireNonNull(proposalId, "proposalId must not be null").toString(),
            Objects.requireNonNull(
                confirmationId,
                "confirmationId must not be null"
            ).toString(),
            sha256(tenantEvidenceHash, "tenantEvidenceHash"),
            sha256(operatorEvidenceHash, "operatorEvidenceHash"),
            sha256(proposalLineageHash, "proposalLineageHash"),
            sha256(confirmationEvidenceHash, "confirmationEvidenceHash"),
            ControlledAutomationProposal.requireCanonicalActionType(canonicalActionType),
            sha256(resourceEvidenceHash, "resourceEvidenceHash"),
            ControlledAutomationProposal.requireVersion(whitelistVersion, "whitelistVersion"),
            ControlledAutomationProposal.requireVersion(policyVersion, "policyVersion"),
            sha256(idempotencyKeyHash, "idempotencyKeyHash"),
            sha256(idempotencyPayloadHash, "idempotencyPayloadHash"),
            Objects.requireNonNull(confirmedAt, "confirmedAt must not be null").toString(),
            Objects.requireNonNull(expiresAt, "expiresAt must not be null").toString()
        );
    }

    private static String computeTransitionHash(
        String tenantEvidenceHash,
        String operatorEvidenceHash,
        UUID proposalId,
        long expectedRevision,
        LineageStatus expectedStatus,
        LineageStatus targetStatus,
        LineageOutcome outcome,
        String resultEvidenceHash,
        String idempotencyKeyHash,
        String idempotencyPayloadHash,
        Instant occurredAt,
        int commandAttempts,
        boolean automaticRetryAllowed
    ) {
        return ControlledAutomationProposal.hashTuple(
            "controlled-automation-lineage-transition-v1",
            sha256(tenantEvidenceHash, "tenantEvidenceHash"),
            sha256(operatorEvidenceHash, "operatorEvidenceHash"),
            Objects.requireNonNull(proposalId, "proposalId must not be null").toString(),
            Long.toString(expectedRevision),
            Objects.requireNonNull(expectedStatus, "expectedStatus must not be null").name(),
            Objects.requireNonNull(targetStatus, "targetStatus must not be null").name(),
            Objects.requireNonNull(outcome, "outcome must not be null").name(),
            sha256(resultEvidenceHash, "resultEvidenceHash"),
            sha256(idempotencyKeyHash, "idempotencyKeyHash"),
            sha256(idempotencyPayloadHash, "idempotencyPayloadHash"),
            Objects.requireNonNull(occurredAt, "occurredAt must not be null").toString(),
            Integer.toString(commandAttempts),
            Boolean.toString(automaticRetryAllowed)
        );
    }

    private static String sha256(String value, String name) {
        return ControlledAutomationProposal.requireSha256(value, name);
    }

    private static void requireTerminalPair(
        LineageStatus status,
        LineageOutcome outcome,
        int commandAttempts,
        boolean automaticRetryAllowed
    ) {
        if (automaticRetryAllowed || commandAttempts < 0 || commandAttempts > 1) {
            throw new IllegalArgumentException(
                "P4 permits zero or one command attempt and never automatic retry"
            );
        }
        boolean valid = switch (status) {
            case CANCELLED -> outcome == LineageOutcome.NONE && commandAttempts == 0;
            case SUCCEEDED -> outcome == LineageOutcome.SUCCESS && commandAttempts == 1;
            case FAILED -> outcome == LineageOutcome.FAILURE && commandAttempts == 1;
            case PARTIAL -> outcome == LineageOutcome.PARTIAL && commandAttempts == 1;
            case UNKNOWN -> outcome == LineageOutcome.UNKNOWN && commandAttempts == 1;
            case CONFIRMED -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                "terminal status, outcome and command-attempt evidence must match exactly"
            );
        }
    }

    private static void requireSnapshotPair(
        long revision,
        LineageStatus status,
        LineageOutcome outcome,
        int commandAttempts,
        boolean automaticRetryAllowed
    ) {
        if (status == LineageStatus.CONFIRMED) {
            if (revision != 1
                || outcome != LineageOutcome.NONE
                || commandAttempts != 0
                || automaticRetryAllowed) {
                throw new IllegalArgumentException(
                    "confirmed lineage must be non-executing revision one"
                );
            }
            return;
        }
        if (revision != 2) {
            throw new IllegalArgumentException("terminal lineage must be revision two");
        }
        requireTerminalPair(status, outcome, commandAttempts, automaticRetryAllowed);
    }
}
