package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.core.ControlledAutomationGovernanceEvaluator.EvaluationDecision;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationGovernanceEvaluator.EvaluationResult;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.ProposalStatus;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationReauthenticationVerifier.ReauthenticationChallenge;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationReauthenticationVerifier.Verification;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationReauthenticationVerifier.VerificationStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Explicit human-confirmation boundary for a non-executable controlled-automation Proposal.
 *
 * <p>Even an accepted confirmation grants no command admission. P4 must add durable single-use
 * replay protection, and P5-A remains blocked by the empty Action Whitelist.</p>
 */
public final class ControlledAutomationConfirmationService {

    private static final Duration MAXIMUM_CONFIRMATION_LIFETIME = Duration.ofMinutes(2);

    private final ControlledAutomationReauthenticationVerifier reauthenticationVerifier;
    private final Clock clock;
    private final Supplier<UUID> confirmationIdSupplier;

    public ControlledAutomationConfirmationService(
        ControlledAutomationReauthenticationVerifier reauthenticationVerifier,
        Clock clock,
        Supplier<UUID> confirmationIdSupplier
    ) {
        this.reauthenticationVerifier = Objects.requireNonNull(
            reauthenticationVerifier,
            "reauthenticationVerifier must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.confirmationIdSupplier = Objects.requireNonNull(
            confirmationIdSupplier,
            "confirmationIdSupplier must not be null"
        );
    }

    public ConfirmationResult confirm(
        AiServerRequestContext currentContext,
        ControlledAutomationProposal proposal,
        EvaluationResult evaluation,
        ConfirmationRequest request
    ) {
        Objects.requireNonNull(currentContext, "currentContext must not be null");
        Objects.requireNonNull(proposal, "proposal must not be null");
        Objects.requireNonNull(evaluation, "evaluation must not be null");
        Objects.requireNonNull(request, "request must not be null");

        Instant now = clock.instant();
        if (request.intent() != ConfirmationIntent.EXPLICIT_CLICK) {
            return ConfirmationResult.rejected(ConfirmationDisposition.EXPLICIT_CLICK_REQUIRED);
        }
        if (!request.proposalId().equals(proposal.proposalId())
            || !request.proposalLineageHash().equals(proposal.lineageHash())
            || !request.evaluationEvidenceHash().equals(evaluation.evidenceHash())) {
            return ConfirmationResult.rejected(ConfirmationDisposition.BINDING_MISMATCH);
        }
        String tenantHash = ControlledAutomationProposal.hashTuple(
            "controlled-automation-tenant-v1",
            currentContext.tenantId()
        );
        String operatorHash = ControlledAutomationProposal.hashTuple(
            "controlled-automation-operator-v1",
            currentContext.tenantId(),
            currentContext.operatorId()
        );
        if (!proposal.tenantEvidenceHash().equals(tenantHash)
            || !proposal.operatorEvidenceHash().equals(operatorHash)) {
            return ConfirmationResult.rejected(ConfirmationDisposition.IDENTITY_MISMATCH);
        }
        if (proposal.status() != ProposalStatus.PROPOSED
            || !proposal.expiresAt().isAfter(now)) {
            return ConfirmationResult.rejected(ConfirmationDisposition.PROPOSAL_NOT_ACTIVE);
        }
        if (evaluation.decision() != EvaluationDecision.ELIGIBLE
            || evaluation.commandAttempted()
            || evaluation.businessSideEffectProduced()) {
            return ConfirmationResult.rejected(ConfirmationDisposition.EVALUATION_NOT_ELIGIBLE);
        }
        if (!evaluation.proposalLineageHash().equals(proposal.lineageHash())
            || !evaluation.currentWhitelistVersion().equals(proposal.whitelistVersion())) {
            return ConfirmationResult.rejected(ConfirmationDisposition.EVALUATION_DRIFT);
        }
        String expectedChallengeBinding = challengeBindingHash(
            proposal,
            evaluation,
            request.challenge().challengeId(),
            request.challenge().method().name(),
            request.challenge().issuedAt(),
            request.challenge().expiresAt()
        );
        if (!request.challenge().bindingHash().equals(expectedChallengeBinding)) {
            return ConfirmationResult.rejected(ConfirmationDisposition.BINDING_MISMATCH);
        }
        if (!request.challenge().expiresAt().isAfter(now)) {
            return ConfirmationResult.rejected(ConfirmationDisposition.REAUTHENTICATION_EXPIRED);
        }

        Verification verification = Objects.requireNonNull(
            reauthenticationVerifier.verify(currentContext, proposal, request.challenge()),
            "reauthentication verification must not be null"
        );
        if (verification.status() == VerificationStatus.UNAVAILABLE) {
            return ConfirmationResult.rejected(
                ConfirmationDisposition.REAUTHENTICATION_UNAVAILABLE
            );
        }
        if (verification.status() == VerificationStatus.EXPIRED) {
            return ConfirmationResult.rejected(ConfirmationDisposition.REAUTHENTICATION_EXPIRED);
        }
        if (verification.status() != VerificationStatus.ACCEPTED) {
            return ConfirmationResult.rejected(ConfirmationDisposition.REAUTHENTICATION_FAILED);
        }
        if (verification.verifiedAt().isBefore(request.challenge().issuedAt())
            || verification.verifiedAt().isAfter(now)) {
            return ConfirmationResult.rejected(ConfirmationDisposition.REAUTHENTICATION_FAILED);
        }

        Instant expiresAt = now.plus(MAXIMUM_CONFIRMATION_LIFETIME);
        if (expiresAt.isAfter(proposal.expiresAt())) {
            expiresAt = proposal.expiresAt();
        }
        ControlledAutomationConfirmationEvidence evidence =
            ControlledAutomationConfirmationEvidence.create(
                Objects.requireNonNull(
                    confirmationIdSupplier.get(),
                    "confirmationId must not be null"
                ),
                proposal,
                evaluation,
                request.challenge(),
                verification,
                now,
                expiresAt
            );
        return ConfirmationResult.confirmed(evidence);
    }

    static String challengeBindingHash(
        ControlledAutomationProposal proposal,
        EvaluationResult evaluation,
        UUID challengeId,
        String method,
        Instant issuedAt,
        Instant expiresAt
    ) {
        return ControlledAutomationProposal.hashTuple(
            "controlled-automation-reauthentication-challenge-v1",
            proposal.proposalId().toString(),
            proposal.tenantEvidenceHash(),
            proposal.operatorEvidenceHash(),
            proposal.lineageHash(),
            evaluation.evidenceHash(),
            challengeId.toString(),
            method,
            issuedAt.toString(),
            expiresAt.toString()
        );
    }

    public enum ConfirmationIntent {
        EXPLICIT_CLICK,
        PAGE_LOAD,
        KEYBOARD_ENTER,
        TIMER,
        RETRY,
        TAB_CHANGE
    }

    public enum ConfirmationDisposition {
        CONFIRMED_NON_EXECUTABLE,
        EXPLICIT_CLICK_REQUIRED,
        BINDING_MISMATCH,
        IDENTITY_MISMATCH,
        PROPOSAL_NOT_ACTIVE,
        EVALUATION_NOT_ELIGIBLE,
        EVALUATION_DRIFT,
        REAUTHENTICATION_UNAVAILABLE,
        REAUTHENTICATION_EXPIRED,
        REAUTHENTICATION_FAILED
    }

    public enum ConfirmationAuthority {
        NON_EXECUTABLE_CONFIRMATION
    }

    public record ConfirmationRequest(
        UUID proposalId,
        String proposalLineageHash,
        String evaluationEvidenceHash,
        ConfirmationIntent intent,
        ReauthenticationChallenge challenge
    ) {
        public ConfirmationRequest {
            proposalId = Objects.requireNonNull(proposalId, "proposalId must not be null");
            proposalLineageHash = ControlledAutomationProposal.requireSha256(
                proposalLineageHash,
                "proposalLineageHash"
            );
            evaluationEvidenceHash = ControlledAutomationProposal.requireSha256(
                evaluationEvidenceHash,
                "evaluationEvidenceHash"
            );
            intent = Objects.requireNonNull(intent, "intent must not be null");
            challenge = Objects.requireNonNull(challenge, "challenge must not be null");
        }
    }

    public record ControlledAutomationConfirmationEvidence(
        UUID confirmationId,
        UUID proposalId,
        String tenantEvidenceHash,
        String operatorEvidenceHash,
        String sourceEvidenceHash,
        String canonicalActionType,
        String typedParameterHash,
        String resourceEvidenceHash,
        String whitelistVersion,
        String policyVersion,
        String evaluationEvidenceHash,
        String reauthenticationEvidenceHash,
        UUID reauthenticationChallengeId,
        Instant confirmedAt,
        Instant expiresAt,
        boolean singleUseRequired,
        ConfirmationAuthority authority,
        boolean commandAdmitted,
        String evidenceHash
    ) {
        public ControlledAutomationConfirmationEvidence {
            confirmationId = Objects.requireNonNull(
                confirmationId,
                "confirmationId must not be null"
            );
            proposalId = Objects.requireNonNull(proposalId, "proposalId must not be null");
            tenantEvidenceHash = ControlledAutomationProposal.requireSha256(
                tenantEvidenceHash,
                "tenantEvidenceHash"
            );
            operatorEvidenceHash = ControlledAutomationProposal.requireSha256(
                operatorEvidenceHash,
                "operatorEvidenceHash"
            );
            sourceEvidenceHash = ControlledAutomationProposal.requireSha256(
                sourceEvidenceHash,
                "sourceEvidenceHash"
            );
            canonicalActionType = ControlledAutomationProposal.requireCanonicalActionType(
                canonicalActionType
            );
            typedParameterHash = ControlledAutomationProposal.requireSha256(
                typedParameterHash,
                "typedParameterHash"
            );
            resourceEvidenceHash = ControlledAutomationProposal.requireSha256(
                resourceEvidenceHash,
                "resourceEvidenceHash"
            );
            whitelistVersion = ControlledAutomationProposal.requireVersion(
                whitelistVersion,
                "whitelistVersion"
            );
            policyVersion = ControlledAutomationProposal.requireVersion(
                policyVersion,
                "policyVersion"
            );
            evaluationEvidenceHash = ControlledAutomationProposal.requireSha256(
                evaluationEvidenceHash,
                "evaluationEvidenceHash"
            );
            reauthenticationEvidenceHash = ControlledAutomationProposal.requireSha256(
                reauthenticationEvidenceHash,
                "reauthenticationEvidenceHash"
            );
            reauthenticationChallengeId = Objects.requireNonNull(
                reauthenticationChallengeId,
                "reauthenticationChallengeId must not be null"
            );
            confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt must not be null");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
            authority = Objects.requireNonNull(authority, "authority must not be null");
            evidenceHash = ControlledAutomationProposal.requireSha256(
                evidenceHash,
                "confirmationEvidenceHash"
            );
            if (!expiresAt.isAfter(confirmedAt)
                || !singleUseRequired
                || authority != ConfirmationAuthority.NON_EXECUTABLE_CONFIRMATION
                || commandAdmitted) {
                throw new IllegalArgumentException(
                    "confirmation must be single-use, non-executable and command-free"
                );
            }
            String expectedHash = computeHash(
                confirmationId,
                proposalId,
                tenantEvidenceHash,
                operatorEvidenceHash,
                sourceEvidenceHash,
                canonicalActionType,
                typedParameterHash,
                resourceEvidenceHash,
                whitelistVersion,
                policyVersion,
                evaluationEvidenceHash,
                reauthenticationEvidenceHash,
                reauthenticationChallengeId,
                confirmedAt,
                expiresAt,
                singleUseRequired,
                authority,
                commandAdmitted
            );
            if (!evidenceHash.equals(expectedHash)) {
                throw new IllegalArgumentException(
                    "confirmation evidence must match the exact explicit human confirmation"
                );
            }
        }

        public static ControlledAutomationConfirmationEvidence create(
            UUID confirmationId,
            ControlledAutomationProposal proposal,
            EvaluationResult evaluation,
            ReauthenticationChallenge challenge,
            Verification verification,
            Instant confirmedAt,
            Instant expiresAt
        ) {
            String parameterHash = parameterHash(proposal);
            return new ControlledAutomationConfirmationEvidence(
                confirmationId,
                proposal.proposalId(),
                proposal.tenantEvidenceHash(),
                proposal.operatorEvidenceHash(),
                proposal.sourceAdvisory().evidenceHash(),
                proposal.canonicalActionType(),
                parameterHash,
                proposal.targetResource().evidenceHash(),
                proposal.whitelistVersion(),
                proposal.policy().version(),
                evaluation.evidenceHash(),
                verification.evidenceHash(),
                challenge.challengeId(),
                confirmedAt,
                expiresAt,
                true,
                ConfirmationAuthority.NON_EXECUTABLE_CONFIRMATION,
                false,
                computeHash(
                    confirmationId,
                    proposal.proposalId(),
                    proposal.tenantEvidenceHash(),
                    proposal.operatorEvidenceHash(),
                    proposal.sourceAdvisory().evidenceHash(),
                    proposal.canonicalActionType(),
                    parameterHash,
                    proposal.targetResource().evidenceHash(),
                    proposal.whitelistVersion(),
                    proposal.policy().version(),
                    evaluation.evidenceHash(),
                    verification.evidenceHash(),
                    challenge.challengeId(),
                    confirmedAt,
                    expiresAt,
                    true,
                    ConfirmationAuthority.NON_EXECUTABLE_CONFIRMATION,
                    false
                )
            );
        }

        private static String computeHash(
            UUID confirmationId,
            UUID proposalId,
            String tenantEvidenceHash,
            String operatorEvidenceHash,
            String sourceEvidenceHash,
            String canonicalActionType,
            String typedParameterHash,
            String resourceEvidenceHash,
            String whitelistVersion,
            String policyVersion,
            String evaluationEvidenceHash,
            String reauthenticationEvidenceHash,
            UUID reauthenticationChallengeId,
            Instant confirmedAt,
            Instant expiresAt,
            boolean singleUseRequired,
            ConfirmationAuthority authority,
            boolean commandAdmitted
        ) {
            return ControlledAutomationProposal.hashTuple(
                "controlled-automation-confirmation-v1",
                confirmationId.toString(),
                proposalId.toString(),
                tenantEvidenceHash,
                operatorEvidenceHash,
                sourceEvidenceHash,
                canonicalActionType,
                typedParameterHash,
                resourceEvidenceHash,
                whitelistVersion,
                policyVersion,
                evaluationEvidenceHash,
                reauthenticationEvidenceHash,
                reauthenticationChallengeId.toString(),
                confirmedAt.toString(),
                expiresAt.toString(),
                Boolean.toString(singleUseRequired),
                authority.name(),
                Boolean.toString(commandAdmitted)
            );
        }
    }

    public record ConfirmationResult(
        ConfirmationDisposition disposition,
        Optional<ControlledAutomationConfirmationEvidence> evidence
    ) {
        public ConfirmationResult {
            disposition = Objects.requireNonNull(disposition, "disposition must not be null");
            evidence = Objects.requireNonNull(evidence, "evidence must not be null");
            if ((disposition == ConfirmationDisposition.CONFIRMED_NON_EXECUTABLE)
                != evidence.isPresent()) {
                throw new IllegalArgumentException(
                    "only non-executable confirmation may contain evidence"
                );
            }
        }

        private static ConfirmationResult confirmed(
            ControlledAutomationConfirmationEvidence evidence
        ) {
            return new ConfirmationResult(
                ConfirmationDisposition.CONFIRMED_NON_EXECUTABLE,
                Optional.of(evidence)
            );
        }

        private static ConfirmationResult rejected(ConfirmationDisposition disposition) {
            return new ConfirmationResult(disposition, Optional.empty());
        }
    }

    private static String parameterHash(ControlledAutomationProposal proposal) {
        List<String> values = new ArrayList<>();
        proposal.parameters().values().stream()
            .sorted(Comparator.comparing(
                ControlledAutomationProposal.ParameterBinding::name
            ))
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
}
