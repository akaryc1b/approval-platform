package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.PolicyEvidence;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.ProposalStatus;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.TargetResourceEvidence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Fresh, read-only server governance snapshot used for one eligibility evaluation. */
public interface ControlledAutomationGovernanceSnapshotSource {

    FreshGovernanceSnapshot load(AiServerRequestContext context, UUID proposalId);

    record FreshGovernanceSnapshot(
        String rolesEvidenceHash,
        String authorizationEvidenceHash,
        boolean permissionGranted,
        boolean resourceAuthorized,
        TargetResourceEvidence currentResource,
        PolicyEvidence currentPolicy,
        boolean policyAllowed,
        boolean separationOfDutiesAllowed,
        boolean featureEnabled,
        boolean killSwitchActive,
        long killSwitchGeneration,
        boolean commandPreconditionsSatisfied,
        boolean sourceEvidencePresent,
        UUID sourceEvidenceId,
        String sourceEvidenceHash,
        boolean sourceEvidenceIntegrityValid,
        ProposalStatus proposalStatus,
        String proposalLineageHash,
        boolean reauthenticationSatisfied,
        Instant observedAt,
        String snapshotHash
    ) {
        public FreshGovernanceSnapshot {
            rolesEvidenceHash = ControlledAutomationProposal.requireSha256(
                rolesEvidenceHash,
                "rolesEvidenceHash"
            );
            authorizationEvidenceHash = ControlledAutomationProposal.requireSha256(
                authorizationEvidenceHash,
                "authorizationEvidenceHash"
            );
            currentResource = Objects.requireNonNull(
                currentResource,
                "currentResource must not be null"
            );
            currentPolicy = Objects.requireNonNull(
                currentPolicy,
                "currentPolicy must not be null"
            );
            if (killSwitchGeneration < 1) {
                throw new IllegalArgumentException("killSwitchGeneration must be positive");
            }
            if (sourceEvidencePresent) {
                sourceEvidenceId = Objects.requireNonNull(
                    sourceEvidenceId,
                    "sourceEvidenceId must not be null when evidence is present"
                );
                sourceEvidenceHash = ControlledAutomationProposal.requireSha256(
                    sourceEvidenceHash,
                    "sourceEvidenceHash"
                );
            } else if (sourceEvidenceId != null || sourceEvidenceHash != null) {
                throw new IllegalArgumentException(
                    "absent source evidence cannot contain an ID or hash"
                );
            }
            proposalStatus = Objects.requireNonNull(
                proposalStatus,
                "proposalStatus must not be null"
            );
            proposalLineageHash = ControlledAutomationProposal.requireSha256(
                proposalLineageHash,
                "proposalLineageHash"
            );
            observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
            snapshotHash = ControlledAutomationProposal.requireSha256(
                snapshotHash,
                "snapshotHash"
            );
            String expectedHash = computeHash(
                rolesEvidenceHash,
                authorizationEvidenceHash,
                permissionGranted,
                resourceAuthorized,
                currentResource,
                currentPolicy,
                policyAllowed,
                separationOfDutiesAllowed,
                featureEnabled,
                killSwitchActive,
                killSwitchGeneration,
                commandPreconditionsSatisfied,
                sourceEvidencePresent,
                sourceEvidenceId,
                sourceEvidenceHash,
                sourceEvidenceIntegrityValid,
                proposalStatus,
                proposalLineageHash,
                reauthenticationSatisfied,
                observedAt
            );
            if (!snapshotHash.equals(expectedHash)) {
                throw new IllegalArgumentException(
                    "snapshotHash must match the exact fresh governance snapshot"
                );
            }
        }

        public static FreshGovernanceSnapshot create(
            String rolesEvidenceHash,
            String authorizationEvidenceHash,
            boolean permissionGranted,
            boolean resourceAuthorized,
            TargetResourceEvidence currentResource,
            PolicyEvidence currentPolicy,
            boolean policyAllowed,
            boolean separationOfDutiesAllowed,
            boolean featureEnabled,
            boolean killSwitchActive,
            long killSwitchGeneration,
            boolean commandPreconditionsSatisfied,
            boolean sourceEvidencePresent,
            UUID sourceEvidenceId,
            String sourceEvidenceHash,
            boolean sourceEvidenceIntegrityValid,
            ProposalStatus proposalStatus,
            String proposalLineageHash,
            boolean reauthenticationSatisfied,
            Instant observedAt
        ) {
            return new FreshGovernanceSnapshot(
                rolesEvidenceHash,
                authorizationEvidenceHash,
                permissionGranted,
                resourceAuthorized,
                currentResource,
                currentPolicy,
                policyAllowed,
                separationOfDutiesAllowed,
                featureEnabled,
                killSwitchActive,
                killSwitchGeneration,
                commandPreconditionsSatisfied,
                sourceEvidencePresent,
                sourceEvidenceId,
                sourceEvidenceHash,
                sourceEvidenceIntegrityValid,
                proposalStatus,
                proposalLineageHash,
                reauthenticationSatisfied,
                observedAt,
                computeHash(
                    rolesEvidenceHash,
                    authorizationEvidenceHash,
                    permissionGranted,
                    resourceAuthorized,
                    currentResource,
                    currentPolicy,
                    policyAllowed,
                    separationOfDutiesAllowed,
                    featureEnabled,
                    killSwitchActive,
                    killSwitchGeneration,
                    commandPreconditionsSatisfied,
                    sourceEvidencePresent,
                    sourceEvidenceId,
                    sourceEvidenceHash,
                    sourceEvidenceIntegrityValid,
                    proposalStatus,
                    proposalLineageHash,
                    reauthenticationSatisfied,
                    observedAt
                )
            );
        }

        private static String computeHash(
            String rolesEvidenceHash,
            String authorizationEvidenceHash,
            boolean permissionGranted,
            boolean resourceAuthorized,
            TargetResourceEvidence currentResource,
            PolicyEvidence currentPolicy,
            boolean policyAllowed,
            boolean separationOfDutiesAllowed,
            boolean featureEnabled,
            boolean killSwitchActive,
            long killSwitchGeneration,
            boolean commandPreconditionsSatisfied,
            boolean sourceEvidencePresent,
            UUID sourceEvidenceId,
            String sourceEvidenceHash,
            boolean sourceEvidenceIntegrityValid,
            ProposalStatus proposalStatus,
            String proposalLineageHash,
            boolean reauthenticationSatisfied,
            Instant observedAt
        ) {
            return ControlledAutomationProposal.hashTuple(
                "controlled-automation-fresh-governance-snapshot-v1",
                rolesEvidenceHash,
                authorizationEvidenceHash,
                Boolean.toString(permissionGranted),
                Boolean.toString(resourceAuthorized),
                currentResource.evidenceHash(),
                currentPolicy.version(),
                currentPolicy.evidenceHash(),
                Boolean.toString(policyAllowed),
                Boolean.toString(separationOfDutiesAllowed),
                Boolean.toString(featureEnabled),
                Boolean.toString(killSwitchActive),
                Long.toString(killSwitchGeneration),
                Boolean.toString(commandPreconditionsSatisfied),
                Boolean.toString(sourceEvidencePresent),
                sourceEvidenceId == null ? "" : sourceEvidenceId.toString(),
                sourceEvidenceHash == null ? "" : sourceEvidenceHash,
                Boolean.toString(sourceEvidenceIntegrityValid),
                proposalStatus.name(),
                proposalLineageHash,
                Boolean.toString(reauthenticationSatisfied),
                observedAt.toString()
            );
        }
    }
}
