package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.core.ControlledAutomationActionWhitelist.ActionDefinition;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationActionWhitelist.ParameterDefinition;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationGovernanceSnapshotSource.FreshGovernanceSnapshot;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.ParameterBinding;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.ProposalStatus;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.TargetResourceEvidence;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Fresh, read-only controlled-automation Eligibility and Authorization Preview.
 *
 * <p>Every call reloads governance state and the current server Action Whitelist. The evaluator
 * cannot call a Provider, connector, Flowable, persistence mutation or application command.</p>
 */
public final class ControlledAutomationGovernanceEvaluator {

    private final ControlledAutomationGovernanceSnapshotSource snapshotSource;
    private final Supplier<ControlledAutomationActionWhitelist> whitelistSupplier;
    private final Clock clock;

    public ControlledAutomationGovernanceEvaluator(
        ControlledAutomationGovernanceSnapshotSource snapshotSource,
        Supplier<ControlledAutomationActionWhitelist> whitelistSupplier,
        Clock clock
    ) {
        this.snapshotSource = Objects.requireNonNull(
            snapshotSource,
            "snapshotSource must not be null"
        );
        this.whitelistSupplier = Objects.requireNonNull(
            whitelistSupplier,
            "whitelistSupplier must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public EvaluationResult evaluate(
        AiServerRequestContext currentContext,
        ControlledAutomationProposal proposal
    ) {
        Objects.requireNonNull(currentContext, "currentContext must not be null");
        Objects.requireNonNull(proposal, "proposal must not be null");

        FreshGovernanceSnapshot fresh = Objects.requireNonNull(
            snapshotSource.load(currentContext, proposal.proposalId()),
            "fresh governance snapshot must not be null"
        );
        ControlledAutomationActionWhitelist currentWhitelist = Objects.requireNonNull(
            whitelistSupplier.get(),
            "current whitelist must not be null"
        );
        Instant evaluatedAt = clock.instant();
        StateComparisonEvidence stateComparison = StateComparisonEvidence.create(
            proposal.targetResource(),
            fresh.currentResource()
        );

        String currentTenantHash = ControlledAutomationProposal.hashTuple(
            "controlled-automation-tenant-v1",
            currentContext.tenantId()
        );
        if (!proposal.tenantEvidenceHash().equals(currentTenantHash)) {
            return result(
                proposal,
                fresh,
                currentWhitelist,
                evaluatedAt,
                stateComparison,
                EvaluationDecision.AUTHORIZATION_DENIED,
                ReasonCode.TENANT_EVIDENCE_MISMATCH
            );
        }
        String currentOperatorHash = ControlledAutomationProposal.hashTuple(
            "controlled-automation-operator-v1",
            currentContext.tenantId(),
            currentContext.operatorId()
        );
        if (!proposal.operatorEvidenceHash().equals(currentOperatorHash)) {
            return result(
                proposal,
                fresh,
                currentWhitelist,
                evaluatedAt,
                stateComparison,
                EvaluationDecision.AUTHORIZATION_DENIED,
                ReasonCode.OPERATOR_EVIDENCE_MISMATCH
            );
        }
        if (fresh.proposalStatus() != ProposalStatus.PROPOSED
            || !fresh.proposalLineageHash().equals(proposal.lineageHash())) {
            return result(
                proposal,
                fresh,
                currentWhitelist,
                evaluatedAt,
                stateComparison,
                EvaluationDecision.INELIGIBLE,
                ReasonCode.PROPOSAL_NOT_ACTIVE
            );
        }
        if (!proposal.expiresAt().isAfter(evaluatedAt)) {
            return result(
                proposal,
                fresh,
                currentWhitelist,
                evaluatedAt,
                stateComparison,
                EvaluationDecision.EXPIRED,
                ReasonCode.PROPOSAL_EXPIRED
            );
        }
        if (!fresh.sourceEvidencePresent()) {
            return result(
                proposal,
                fresh,
                currentWhitelist,
                evaluatedAt,
                stateComparison,
                EvaluationDecision.SOURCE_EVIDENCE_INVALID,
                ReasonCode.SOURCE_EVIDENCE_MISSING
            );
        }
        if (!proposal.sourceAdvisory().evidenceId().equals(fresh.sourceEvidenceId())
            || !proposal.sourceAdvisory().evidenceHash().equals(fresh.sourceEvidenceHash())) {
            return result(
                proposal,
                fresh,
                currentWhitelist,
                evaluatedAt,
                stateComparison,
                EvaluationDecision.SOURCE_EVIDENCE_INVALID,
                ReasonCode.SOURCE_EVIDENCE_MISMATCH
            );
        }
        if (!fresh.sourceEvidenceIntegrityValid()) {
            return result(
                proposal,
                fresh,
                currentWhitelist,
                evaluatedAt,
                stateComparison,
                EvaluationDecision.SOURCE_EVIDENCE_INVALID,
                ReasonCode.SOURCE_EVIDENCE_INTEGRITY_INVALID
            );
        }

        Optional<ActionDefinition> currentAction = currentWhitelist.resolve(
            proposal.canonicalActionType()
        );
        if (!proposal.whitelistVersion().equals(currentWhitelist.version())) {
            return result(
                proposal,
                fresh,
                currentWhitelist,
                evaluatedAt,
                stateComparison,
                EvaluationDecision.ACTION_NOT_WHITELISTED,
                ReasonCode.WHITELIST_VERSION_DRIFT
            );
        }
        if (currentAction.isEmpty()) {
            return result(
                proposal,
                fresh,
                currentWhitelist,
                evaluatedAt,
                stateComparison,
                EvaluationDecision.ACTION_NOT_WHITELISTED,
                ReasonCode.ACTION_MISSING_FROM_WHITELIST
            );
        }
        if (!matchesActionDefinition(proposal, currentAction.orElseThrow())) {
            return result(
                proposal,
                fresh,
                currentWhitelist,
                evaluatedAt,
                stateComparison,
                EvaluationDecision.ACTION_NOT_WHITELISTED,
                ReasonCode.ACTION_DEFINITION_DRIFT
            );
        }

        if (!proposal.policy().version().equals(fresh.currentPolicy().version())
            || !proposal.policy().evidenceHash().equals(fresh.currentPolicy().evidenceHash())) {
            return result(
                proposal,
                fresh,
                currentWhitelist,
                evaluatedAt,
                stateComparison,
                EvaluationDecision.POLICY_BLOCKED,
                ReasonCode.POLICY_VERSION_DRIFT
            );
        }
        if (!fresh.policyAllowed()) {
            return result(
                proposal,
                fresh,
                currentWhitelist,
                evaluatedAt,
                stateComparison,
                EvaluationDecision.POLICY_BLOCKED,
                ReasonCode.POLICY_DENIED
            );
        }
        if (!fresh.featureEnabled()) {
            return result(
                proposal,
                fresh,
                currentWhitelist,
                evaluatedAt,
                stateComparison,
                EvaluationDecision.POLICY_BLOCKED,
                ReasonCode.FEATURE_DISABLED
            );
        }
        if (fresh.killSwitchActive()) {
            return result(
                proposal,
                fresh,
                currentWhitelist,
                evaluatedAt,
                stateComparison,
                EvaluationDecision.POLICY_BLOCKED,
                ReasonCode.KILL_SWITCH_ACTIVE
            );
        }
        if (!fresh.permissionGranted()) {
            return result(
                proposal,
                fresh,
                currentWhitelist,
                evaluatedAt,
                stateComparison,
                EvaluationDecision.AUTHORIZATION_DENIED,
                ReasonCode.PERMISSION_REVOKED
            );
        }
        if (!fresh.resourceAuthorized()) {
            return result(
                proposal,
                fresh,
                currentWhitelist,
                evaluatedAt,
                stateComparison,
                EvaluationDecision.AUTHORIZATION_DENIED,
                ReasonCode.RESOURCE_AUTHORIZATION_DENIED
            );
        }
        ReasonCode stateDrift = stateDrift(proposal.targetResource(), fresh.currentResource());
        if (stateDrift != null) {
            return result(
                proposal,
                fresh,
                currentWhitelist,
                evaluatedAt,
                stateComparison,
                EvaluationDecision.STALE,
                stateDrift
            );
        }
        if (!fresh.separationOfDutiesAllowed()) {
            return result(
                proposal,
                fresh,
                currentWhitelist,
                evaluatedAt,
                stateComparison,
                EvaluationDecision.INELIGIBLE,
                ReasonCode.SEPARATION_OF_DUTIES_DENIED
            );
        }
        if (!fresh.commandPreconditionsSatisfied()) {
            return result(
                proposal,
                fresh,
                currentWhitelist,
                evaluatedAt,
                stateComparison,
                EvaluationDecision.INELIGIBLE,
                ReasonCode.COMMAND_PRECONDITION_FAILED
            );
        }
        if (!fresh.reauthenticationSatisfied()) {
            return result(
                proposal,
                fresh,
                currentWhitelist,
                evaluatedAt,
                stateComparison,
                EvaluationDecision.REAUTHENTICATION_REQUIRED,
                ReasonCode.REAUTHENTICATION_REQUIRED
            );
        }
        return result(
            proposal,
            fresh,
            currentWhitelist,
            evaluatedAt,
            stateComparison,
            EvaluationDecision.ELIGIBLE,
            ReasonCode.ELIGIBLE_FRESH
        );
    }

    private static ReasonCode stateDrift(
        TargetResourceEvidence expected,
        TargetResourceEvidence current
    ) {
        if (!expected.resourceType().equals(current.resourceType())
            || !expected.resourceIdEvidenceHash().equals(current.resourceIdEvidenceHash())) {
            return ReasonCode.RESOURCE_EVIDENCE_DRIFT;
        }
        if (!expected.expectedState().equals(current.expectedState())) {
            return ReasonCode.RESOURCE_STATE_DRIFT;
        }
        if (expected.expectedVersion() != current.expectedVersion()) {
            return ReasonCode.RESOURCE_VERSION_DRIFT;
        }
        return null;
    }

    private static boolean matchesActionDefinition(
        ControlledAutomationProposal proposal,
        ActionDefinition action
    ) {
        if (!proposal.canonicalActionType().equals(action.canonicalActionType())
            || !proposal.targetResource().resourceType().equals(action.targetResourceType())
            || proposal.riskClassification() != action.riskClassification()
            || !proposal.sideEffectSummary().equals(action.sideEffectSummary())
            || proposal.reauthenticationRequirement() != action.reauthenticationRequirement()) {
            return false;
        }
        Map<String, ParameterDefinition> schema = action.parameterSchema();
        if (!schema.keySet().equals(proposal.parameters().keySet())) {
            return false;
        }
        for (Map.Entry<String, ParameterDefinition> entry : schema.entrySet()) {
            ParameterBinding binding = proposal.parameters().get(entry.getKey());
            if (binding == null || binding.value().type() != entry.getValue().type()) {
                return false;
            }
            if (entry.getValue().type()
                == ControlledAutomationActionWhitelist.ParameterType.ENUM
                && !entry.getValue().allowedEnumValues().contains(
                    binding.value().canonicalValue()
                )) {
                return false;
            }
        }
        return true;
    }

    private static EvaluationResult result(
        ControlledAutomationProposal proposal,
        FreshGovernanceSnapshot fresh,
        ControlledAutomationActionWhitelist currentWhitelist,
        Instant evaluatedAt,
        StateComparisonEvidence stateComparison,
        EvaluationDecision decision,
        ReasonCode reasonCode
    ) {
        return EvaluationResult.create(
            decision,
            reasonCode,
            proposal.riskClassification(),
            proposal.sideEffectSummary(),
            stateComparison,
            currentWhitelist.version(),
            fresh.rolesEvidenceHash(),
            fresh.authorizationEvidenceHash(),
            fresh.killSwitchGeneration(),
            proposal.lineageHash(),
            fresh.snapshotHash(),
            evaluatedAt
        );
    }

    public enum EvaluationDecision {
        ELIGIBLE,
        INELIGIBLE,
        EXPIRED,
        STALE,
        POLICY_BLOCKED,
        AUTHORIZATION_DENIED,
        SOURCE_EVIDENCE_INVALID,
        ACTION_NOT_WHITELISTED,
        REAUTHENTICATION_REQUIRED
    }

    public enum ReasonCode {
        ELIGIBLE_FRESH,
        TENANT_EVIDENCE_MISMATCH,
        OPERATOR_EVIDENCE_MISMATCH,
        PROPOSAL_NOT_ACTIVE,
        PROPOSAL_EXPIRED,
        SOURCE_EVIDENCE_MISSING,
        SOURCE_EVIDENCE_MISMATCH,
        SOURCE_EVIDENCE_INTEGRITY_INVALID,
        WHITELIST_VERSION_DRIFT,
        ACTION_MISSING_FROM_WHITELIST,
        ACTION_DEFINITION_DRIFT,
        POLICY_VERSION_DRIFT,
        POLICY_DENIED,
        FEATURE_DISABLED,
        KILL_SWITCH_ACTIVE,
        PERMISSION_REVOKED,
        RESOURCE_AUTHORIZATION_DENIED,
        RESOURCE_EVIDENCE_DRIFT,
        RESOURCE_STATE_DRIFT,
        RESOURCE_VERSION_DRIFT,
        SEPARATION_OF_DUTIES_DENIED,
        COMMAND_PRECONDITION_FAILED,
        REAUTHENTICATION_REQUIRED
    }

    public enum PreviewAuthority {
        READ_ONLY_NON_EXECUTING_PREVIEW
    }

    public record StateComparisonEvidence(
        String resourceType,
        String expectedResourceIdEvidenceHash,
        String currentResourceIdEvidenceHash,
        String expectedState,
        String currentState,
        long expectedVersion,
        long currentVersion,
        String expectedResourceEvidenceHash,
        String currentResourceEvidenceHash,
        String evidenceHash
    ) {
        public StateComparisonEvidence {
            resourceType = ControlledAutomationProposal.requireResourceType(resourceType);
            expectedResourceIdEvidenceHash = ControlledAutomationProposal.requireSha256(
                expectedResourceIdEvidenceHash,
                "expectedResourceIdEvidenceHash"
            );
            currentResourceIdEvidenceHash = ControlledAutomationProposal.requireSha256(
                currentResourceIdEvidenceHash,
                "currentResourceIdEvidenceHash"
            );
            expectedState = ControlledAutomationProposal.requireEnumValue(expectedState);
            currentState = ControlledAutomationProposal.requireEnumValue(currentState);
            expectedResourceEvidenceHash = ControlledAutomationProposal.requireSha256(
                expectedResourceEvidenceHash,
                "expectedResourceEvidenceHash"
            );
            currentResourceEvidenceHash = ControlledAutomationProposal.requireSha256(
                currentResourceEvidenceHash,
                "currentResourceEvidenceHash"
            );
            evidenceHash = ControlledAutomationProposal.requireSha256(
                evidenceHash,
                "stateComparisonEvidenceHash"
            );
            if (expectedVersion < 0 || currentVersion < 0) {
                throw new IllegalArgumentException("resource versions must not be negative");
            }
            String expectedHash = computeHash(
                resourceType,
                expectedResourceIdEvidenceHash,
                currentResourceIdEvidenceHash,
                expectedState,
                currentState,
                expectedVersion,
                currentVersion,
                expectedResourceEvidenceHash,
                currentResourceEvidenceHash
            );
            if (!evidenceHash.equals(expectedHash)) {
                throw new IllegalArgumentException(
                    "state comparison evidence must match the exact fresh state"
                );
            }
        }

        public static StateComparisonEvidence create(
            TargetResourceEvidence expected,
            TargetResourceEvidence current
        ) {
            Objects.requireNonNull(expected, "expected resource must not be null");
            Objects.requireNonNull(current, "current resource must not be null");
            return new StateComparisonEvidence(
                expected.resourceType(),
                expected.resourceIdEvidenceHash(),
                current.resourceIdEvidenceHash(),
                expected.expectedState(),
                current.expectedState(),
                expected.expectedVersion(),
                current.expectedVersion(),
                expected.evidenceHash(),
                current.evidenceHash(),
                computeHash(
                    expected.resourceType(),
                    expected.resourceIdEvidenceHash(),
                    current.resourceIdEvidenceHash(),
                    expected.expectedState(),
                    current.expectedState(),
                    expected.expectedVersion(),
                    current.expectedVersion(),
                    expected.evidenceHash(),
                    current.evidenceHash()
                )
            );
        }

        private static String computeHash(
            String resourceType,
            String expectedResourceIdEvidenceHash,
            String currentResourceIdEvidenceHash,
            String expectedState,
            String currentState,
            long expectedVersion,
            long currentVersion,
            String expectedResourceEvidenceHash,
            String currentResourceEvidenceHash
        ) {
            return ControlledAutomationProposal.hashTuple(
                "controlled-automation-state-comparison-v1",
                resourceType,
                expectedResourceIdEvidenceHash,
                currentResourceIdEvidenceHash,
                expectedState,
                currentState,
                Long.toString(expectedVersion),
                Long.toString(currentVersion),
                expectedResourceEvidenceHash,
                currentResourceEvidenceHash
            );
        }
    }

    public record EvaluationResult(
        EvaluationDecision decision,
        ReasonCode reasonCode,
        ControlledAutomationActionWhitelist.RiskClassification riskClassification,
        String sideEffectSummary,
        StateComparisonEvidence stateComparison,
        String currentWhitelistVersion,
        String rolesEvidenceHash,
        String authorizationEvidenceHash,
        long killSwitchGeneration,
        String proposalLineageHash,
        String freshSnapshotHash,
        Instant evaluatedAt,
        PreviewAuthority authority,
        boolean businessSideEffectProduced,
        boolean providerInvoked,
        boolean connectorInvoked,
        boolean commandAttempted,
        String evidenceHash
    ) {
        public EvaluationResult {
            decision = Objects.requireNonNull(decision, "decision must not be null");
            reasonCode = Objects.requireNonNull(reasonCode, "reasonCode must not be null");
            riskClassification = Objects.requireNonNull(
                riskClassification,
                "riskClassification must not be null"
            );
            sideEffectSummary = ControlledAutomationProposal.requireHumanSummary(
                sideEffectSummary,
                "sideEffectSummary"
            );
            stateComparison = Objects.requireNonNull(
                stateComparison,
                "stateComparison must not be null"
            );
            currentWhitelistVersion = ControlledAutomationProposal.requireVersion(
                currentWhitelistVersion,
                "currentWhitelistVersion"
            );
            rolesEvidenceHash = ControlledAutomationProposal.requireSha256(
                rolesEvidenceHash,
                "rolesEvidenceHash"
            );
            authorizationEvidenceHash = ControlledAutomationProposal.requireSha256(
                authorizationEvidenceHash,
                "authorizationEvidenceHash"
            );
            proposalLineageHash = ControlledAutomationProposal.requireSha256(
                proposalLineageHash,
                "proposalLineageHash"
            );
            freshSnapshotHash = ControlledAutomationProposal.requireSha256(
                freshSnapshotHash,
                "freshSnapshotHash"
            );
            evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
            authority = Objects.requireNonNull(authority, "authority must not be null");
            evidenceHash = ControlledAutomationProposal.requireSha256(
                evidenceHash,
                "evaluationEvidenceHash"
            );
            if (killSwitchGeneration < 1) {
                throw new IllegalArgumentException("killSwitchGeneration must be positive");
            }
            if (authority != PreviewAuthority.READ_ONLY_NON_EXECUTING_PREVIEW
                || businessSideEffectProduced
                || providerInvoked
                || connectorInvoked
                || commandAttempted) {
                throw new IllegalArgumentException(
                    "governance evaluation must remain read-only and non-executing"
                );
            }
            String expectedHash = computeHash(
                decision,
                reasonCode,
                riskClassification,
                sideEffectSummary,
                stateComparison,
                currentWhitelistVersion,
                rolesEvidenceHash,
                authorizationEvidenceHash,
                killSwitchGeneration,
                proposalLineageHash,
                freshSnapshotHash,
                evaluatedAt,
                authority,
                businessSideEffectProduced,
                providerInvoked,
                connectorInvoked,
                commandAttempted
            );
            if (!evidenceHash.equals(expectedHash)) {
                throw new IllegalArgumentException(
                    "evaluation evidence must match the exact read-only preview"
                );
            }
        }

        public static EvaluationResult create(
            EvaluationDecision decision,
            ReasonCode reasonCode,
            ControlledAutomationActionWhitelist.RiskClassification riskClassification,
            String sideEffectSummary,
            StateComparisonEvidence stateComparison,
            String currentWhitelistVersion,
            String rolesEvidenceHash,
            String authorizationEvidenceHash,
            long killSwitchGeneration,
            String proposalLineageHash,
            String freshSnapshotHash,
            Instant evaluatedAt
        ) {
            return new EvaluationResult(
                decision,
                reasonCode,
                riskClassification,
                sideEffectSummary,
                stateComparison,
                currentWhitelistVersion,
                rolesEvidenceHash,
                authorizationEvidenceHash,
                killSwitchGeneration,
                proposalLineageHash,
                freshSnapshotHash,
                evaluatedAt,
                PreviewAuthority.READ_ONLY_NON_EXECUTING_PREVIEW,
                false,
                false,
                false,
                false,
                computeHash(
                    decision,
                    reasonCode,
                    riskClassification,
                    sideEffectSummary,
                    stateComparison,
                    currentWhitelistVersion,
                    rolesEvidenceHash,
                    authorizationEvidenceHash,
                    killSwitchGeneration,
                    proposalLineageHash,
                    freshSnapshotHash,
                    evaluatedAt,
                    PreviewAuthority.READ_ONLY_NON_EXECUTING_PREVIEW,
                    false,
                    false,
                    false,
                    false
                )
            );
        }

        private static String computeHash(
            EvaluationDecision decision,
            ReasonCode reasonCode,
            ControlledAutomationActionWhitelist.RiskClassification riskClassification,
            String sideEffectSummary,
            StateComparisonEvidence stateComparison,
            String currentWhitelistVersion,
            String rolesEvidenceHash,
            String authorizationEvidenceHash,
            long killSwitchGeneration,
            String proposalLineageHash,
            String freshSnapshotHash,
            Instant evaluatedAt,
            PreviewAuthority authority,
            boolean businessSideEffectProduced,
            boolean providerInvoked,
            boolean connectorInvoked,
            boolean commandAttempted
        ) {
            return ControlledAutomationProposal.hashTuple(
                "controlled-automation-evaluation-v1",
                decision.name(),
                reasonCode.name(),
                riskClassification.name(),
                sideEffectSummary,
                stateComparison.evidenceHash(),
                currentWhitelistVersion,
                rolesEvidenceHash,
                authorizationEvidenceHash,
                Long.toString(killSwitchGeneration),
                proposalLineageHash,
                freshSnapshotHash,
                evaluatedAt.toString(),
                authority.name(),
                Boolean.toString(businessSideEffectProduced),
                Boolean.toString(providerInvoked),
                Boolean.toString(connectorInvoked),
                Boolean.toString(commandAttempted)
            );
        }
    }
}
