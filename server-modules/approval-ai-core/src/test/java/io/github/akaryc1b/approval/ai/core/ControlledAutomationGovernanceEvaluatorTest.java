package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.core.ControlledAutomationActionWhitelist.ActionDefinition;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationActionWhitelist.ParameterDefinition;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationActionWhitelist.ParameterType;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationActionWhitelist.ReauthenticationRequirement;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationActionWhitelist.RiskClassification;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationGovernanceEvaluator.EvaluationDecision;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationGovernanceEvaluator.ReasonCode;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationGovernanceSnapshotSource.FreshGovernanceSnapshot;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.CreationTrigger;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.IdentifierParameter;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.ParameterBinding;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.ParameterSource;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.PolicyEvidence;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.ProposalStatus;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.SourceAdvisoryEvidence;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.TargetResourceEvidence;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposalFactory.ProposalCreationRequest;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ControlledAutomationGovernanceEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-08-05T05:00:00Z");
    private static final UUID PROPOSAL_ID = UUID.fromString(
        "30000000-0000-0000-0000-000000000003"
    );
    private static final UUID SOURCE_EVIDENCE_ID = UUID.fromString(
        "40000000-0000-0000-0000-000000000004"
    );
    private static final String SOURCE_EVIDENCE_HASH = "a".repeat(64);
    private static final String RESOURCE_ID_HASH = "b".repeat(64);
    private static final String ROLES_HASH = "c".repeat(64);
    private static final String AUTHORIZATION_HASH = "d".repeat(64);
    private static final String POLICY_HASH = "e".repeat(64);

    @Test
    void everyEvaluationReloadsFreshGovernanceAndWhitelistAndRemainsReadOnly() {
        ControlledAutomationProposal proposal = activeProposal();
        AtomicInteger snapshots = new AtomicInteger();
        AtomicInteger whitelists = new AtomicInteger();
        ControlledAutomationGovernanceEvaluator evaluator = new ControlledAutomationGovernanceEvaluator(
            (context, proposalId) -> {
                snapshots.incrementAndGet();
                return snapshot(proposal, ignored -> { });
            },
            () -> {
                whitelists.incrementAndGet();
                return whitelist("test-whitelist-v1", testAction());
            },
            Clock.fixed(NOW, ZoneOffset.UTC)
        );

        ControlledAutomationGovernanceEvaluator.EvaluationResult first = evaluator.evaluate(
            context("tenant-a", "operator-a"),
            proposal
        );
        ControlledAutomationGovernanceEvaluator.EvaluationResult second = evaluator.evaluate(
            context("tenant-a", "operator-a"),
            proposal
        );

        assertDecision(first, EvaluationDecision.ELIGIBLE, ReasonCode.ELIGIBLE_FRESH);
        assertEquals(2, snapshots.get());
        assertEquals(2, whitelists.get());
        assertEquals(first.evidenceHash(), second.evidenceHash());
        assertEquals(proposal.sideEffectSummary(), first.sideEffectSummary());
        assertEquals(proposal.riskClassification(), first.riskClassification());
    }

    @Test
    void currentEmptyWhitelistFailsActionNotWhitelisted() {
        ControlledAutomationProposal proposal = activeProposal();
        ControlledAutomationGovernanceEvaluator.EvaluationResult result = evaluator(
            proposal,
            ignored -> { },
            ControlledAutomationActionWhitelist.empty("test-whitelist-v1")
        ).evaluate(context("tenant-a", "operator-a"), proposal);

        assertDecision(
            result,
            EvaluationDecision.ACTION_NOT_WHITELISTED,
            ReasonCode.ACTION_MISSING_FROM_WHITELIST
        );
    }

    @Test
    void forgedTenantAndOperatorFailClosed() {
        ControlledAutomationProposal proposal = activeProposal();
        ControlledAutomationGovernanceEvaluator evaluator = evaluator(
            proposal,
            ignored -> { },
            whitelist("test-whitelist-v1", testAction())
        );

        assertDecision(
            evaluator.evaluate(context("tenant-b", "operator-a"), proposal),
            EvaluationDecision.AUTHORIZATION_DENIED,
            ReasonCode.TENANT_EVIDENCE_MISMATCH
        );
        assertDecision(
            evaluator.evaluate(context("tenant-a", "operator-b"), proposal),
            EvaluationDecision.AUTHORIZATION_DENIED,
            ReasonCode.OPERATOR_EVIDENCE_MISMATCH
        );
    }

    @Test
    void inactiveExpiredAndLineageTamperedProposalFailClosed() {
        ControlledAutomationProposal active = activeProposal();
        assertDecision(
            evaluate(active, mutable -> mutable.proposalStatus = ProposalStatus.CANCELLED),
            EvaluationDecision.INELIGIBLE,
            ReasonCode.PROPOSAL_NOT_ACTIVE
        );
        assertDecision(
            evaluate(active, mutable -> mutable.proposalLineageHash = "f".repeat(64)),
            EvaluationDecision.INELIGIBLE,
            ReasonCode.PROPOSAL_NOT_ACTIVE
        );

        ControlledAutomationProposal expired = proposal(
            NOW.minusSeconds(600),
            NOW.minusSeconds(1)
        );
        assertDecision(
            evaluate(expired, ignored -> { }),
            EvaluationDecision.EXPIRED,
            ReasonCode.PROPOSAL_EXPIRED
        );
    }

    @Test
    void deletedMismatchedAndTamperedSourceEvidenceFailClosed() {
        ControlledAutomationProposal proposal = activeProposal();
        assertDecision(
            evaluate(proposal, mutable -> {
                mutable.sourceEvidencePresent = false;
                mutable.sourceEvidenceId = null;
                mutable.sourceEvidenceHash = null;
            }),
            EvaluationDecision.SOURCE_EVIDENCE_INVALID,
            ReasonCode.SOURCE_EVIDENCE_MISSING
        );
        assertDecision(
            evaluate(proposal, mutable -> mutable.sourceEvidenceHash = "f".repeat(64)),
            EvaluationDecision.SOURCE_EVIDENCE_INVALID,
            ReasonCode.SOURCE_EVIDENCE_MISMATCH
        );
        assertDecision(
            evaluate(proposal, mutable -> mutable.sourceEvidenceIntegrityValid = false),
            EvaluationDecision.SOURCE_EVIDENCE_INVALID,
            ReasonCode.SOURCE_EVIDENCE_INTEGRITY_INVALID
        );
    }

    @Test
    void whitelistVersionAndDefinitionDriftFailClosed() {
        ControlledAutomationProposal proposal = activeProposal();
        assertDecision(
            evaluator(
                proposal,
                ignored -> { },
                whitelist("test-whitelist-v2", testAction())
            ).evaluate(context("tenant-a", "operator-a"), proposal),
            EvaluationDecision.ACTION_NOT_WHITELISTED,
            ReasonCode.WHITELIST_VERSION_DRIFT
        );
        ActionDefinition changed = new ActionDefinition(
            "TEST_ONLY_NON_EXECUTABLE_ACTION",
            "TEST_RESOURCE",
            testSchema(),
            RiskClassification.LOW,
            "Changed test-only side effect description.",
            ReauthenticationRequirement.REQUIRED
        );
        assertDecision(
            evaluator(
                proposal,
                ignored -> { },
                whitelist("test-whitelist-v1", changed)
            ).evaluate(context("tenant-a", "operator-a"), proposal),
            EvaluationDecision.ACTION_NOT_WHITELISTED,
            ReasonCode.ACTION_DEFINITION_DRIFT
        );
    }

    @Test
    void policyAuthorizationStateAndHumanGatesFailClosed() {
        ControlledAutomationProposal proposal = activeProposal();
        List<DecisionCase> cases = List.of(
            decision(
                mutable -> mutable.currentPolicy = new PolicyEvidence(
                    "test-policy-v2",
                    "f".repeat(64)
                ),
                EvaluationDecision.POLICY_BLOCKED,
                ReasonCode.POLICY_VERSION_DRIFT
            ),
            decision(
                mutable -> mutable.policyAllowed = false,
                EvaluationDecision.POLICY_BLOCKED,
                ReasonCode.POLICY_DENIED
            ),
            decision(
                mutable -> mutable.featureEnabled = false,
                EvaluationDecision.POLICY_BLOCKED,
                ReasonCode.FEATURE_DISABLED
            ),
            decision(
                mutable -> mutable.killSwitchActive = true,
                EvaluationDecision.POLICY_BLOCKED,
                ReasonCode.KILL_SWITCH_ACTIVE
            ),
            decision(
                mutable -> mutable.permissionGranted = false,
                EvaluationDecision.AUTHORIZATION_DENIED,
                ReasonCode.PERMISSION_REVOKED
            ),
            decision(
                mutable -> mutable.resourceAuthorized = false,
                EvaluationDecision.AUTHORIZATION_DENIED,
                ReasonCode.RESOURCE_AUTHORIZATION_DENIED
            ),
            decision(
                mutable -> mutable.currentResource = resource(
                    "f".repeat(64),
                    "PENDING",
                    7
                ),
                EvaluationDecision.STALE,
                ReasonCode.RESOURCE_EVIDENCE_DRIFT
            ),
            decision(
                mutable -> mutable.currentResource = resource(
                    RESOURCE_ID_HASH,
                    "COMPLETED",
                    7
                ),
                EvaluationDecision.STALE,
                ReasonCode.RESOURCE_STATE_DRIFT
            ),
            decision(
                mutable -> mutable.currentResource = resource(
                    RESOURCE_ID_HASH,
                    "PENDING",
                    8
                ),
                EvaluationDecision.STALE,
                ReasonCode.RESOURCE_VERSION_DRIFT
            ),
            decision(
                mutable -> mutable.separationOfDutiesAllowed = false,
                EvaluationDecision.INELIGIBLE,
                ReasonCode.SEPARATION_OF_DUTIES_DENIED
            ),
            decision(
                mutable -> mutable.commandPreconditionsSatisfied = false,
                EvaluationDecision.INELIGIBLE,
                ReasonCode.COMMAND_PRECONDITION_FAILED
            ),
            decision(
                mutable -> mutable.reauthenticationSatisfied = false,
                EvaluationDecision.REAUTHENTICATION_REQUIRED,
                ReasonCode.REAUTHENTICATION_REQUIRED
            )
        );

        for (DecisionCase decisionCase : cases) {
            assertDecision(
                evaluate(proposal, decisionCase.changes()),
                decisionCase.decision(),
                decisionCase.reasonCode()
            );
        }
    }

    @Test
    void evaluationEvidenceBindsFreshSnapshotAndStateComparison() {
        ControlledAutomationProposal proposal = activeProposal();
        ControlledAutomationGovernanceEvaluator.EvaluationResult result = evaluate(
            proposal,
            ignored -> { }
        );

        assertEquals(proposal.lineageHash(), result.proposalLineageHash());
        assertEquals(RESOURCE_ID_HASH, result.stateComparison().expectedResourceIdEvidenceHash());
        assertEquals(RESOURCE_ID_HASH, result.stateComparison().currentResourceIdEvidenceHash());
        assertEquals(7, result.stateComparison().expectedVersion());
        assertEquals(7, result.stateComparison().currentVersion());
        assertNotEquals(result.freshSnapshotHash(), result.evidenceHash());
        assertEquals(64, result.evidenceHash().length());
    }

    private static ControlledAutomationGovernanceEvaluator.EvaluationResult evaluate(
        ControlledAutomationProposal proposal,
        Consumer<MutableSnapshot> changes
    ) {
        return evaluator(
            proposal,
            changes,
            whitelist("test-whitelist-v1", testAction())
        ).evaluate(context("tenant-a", "operator-a"), proposal);
    }

    private static ControlledAutomationGovernanceEvaluator evaluator(
        ControlledAutomationProposal proposal,
        Consumer<MutableSnapshot> changes,
        ControlledAutomationActionWhitelist whitelist
    ) {
        return new ControlledAutomationGovernanceEvaluator(
            (context, proposalId) -> snapshot(proposal, changes),
            () -> whitelist,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static FreshGovernanceSnapshot snapshot(
        ControlledAutomationProposal proposal,
        Consumer<MutableSnapshot> changes
    ) {
        MutableSnapshot mutable = new MutableSnapshot(proposal);
        changes.accept(mutable);
        return FreshGovernanceSnapshot.create(
            mutable.rolesEvidenceHash,
            mutable.authorizationEvidenceHash,
            mutable.permissionGranted,
            mutable.resourceAuthorized,
            mutable.currentResource,
            mutable.currentPolicy,
            mutable.policyAllowed,
            mutable.separationOfDutiesAllowed,
            mutable.featureEnabled,
            mutable.killSwitchActive,
            mutable.killSwitchGeneration,
            mutable.commandPreconditionsSatisfied,
            mutable.sourceEvidencePresent,
            mutable.sourceEvidenceId,
            mutable.sourceEvidenceHash,
            mutable.sourceEvidenceIntegrityValid,
            mutable.proposalStatus,
            mutable.proposalLineageHash,
            mutable.reauthenticationSatisfied,
            NOW.minusMillis(1)
        );
    }

    private static ControlledAutomationProposal activeProposal() {
        return proposal(NOW.minusSeconds(1), NOW.plusSeconds(300));
    }

    private static ControlledAutomationProposal proposal(Instant createdAt, Instant expiresAt) {
        ControlledAutomationProposalFactory factory = new ControlledAutomationProposalFactory(
            whitelist("test-whitelist-v1", testAction()),
            Clock.fixed(createdAt, ZoneOffset.UTC),
            () -> PROPOSAL_ID
        );
        return factory.create(new ProposalCreationRequest(
            context("tenant-a", "operator-a"),
            CreationTrigger.EXPLICIT_USER_ACTION,
            sourceEvidence(),
            "TEST_ONLY_NON_EXECUTABLE_ACTION",
            parameters(),
            resource(RESOURCE_ID_HASH, "PENDING", 7),
            new PolicyEvidence("test-policy-v1", POLICY_HASH),
            expiresAt
        )).proposal().orElseThrow();
    }

    private static AiServerRequestContext context(String tenantId, String operatorId) {
        return new AiServerRequestContext(tenantId, operatorId, "request-a", "trace-a");
    }

    private static TargetResourceEvidence resource(
        String resourceIdHash,
        String state,
        long version
    ) {
        return TargetResourceEvidence.create(
            "TEST_RESOURCE",
            resourceIdHash,
            state,
            version,
            NOW.minusSeconds(10)
        );
    }

    private static ActionDefinition testAction() {
        return new ActionDefinition(
            "TEST_ONLY_NON_EXECUTABLE_ACTION",
            "TEST_RESOURCE",
            testSchema(),
            RiskClassification.LOW,
            "Test-only contract fixture with no business side effect.",
            ReauthenticationRequirement.REQUIRED
        );
    }

    private static Map<String, ParameterDefinition> testSchema() {
        ParameterDefinition definition = new ParameterDefinition(
            "acknowledgementCode",
            ParameterType.IDENTIFIER,
            Set.of()
        );
        return Map.of(definition.name(), definition);
    }

    private static Map<String, ParameterBinding> parameters() {
        ParameterBinding binding = new ParameterBinding(
            "acknowledgementCode",
            new IdentifierParameter("ACK-1"),
            ParameterSource.EXPLICIT_USER_INPUT
        );
        return Map.of(binding.name(), binding);
    }

    private static ControlledAutomationActionWhitelist whitelist(
        String version,
        ActionDefinition action
    ) {
        return new ControlledAutomationActionWhitelist() {
            @Override
            public String version() {
                return version;
            }

            @Override
            public Optional<ActionDefinition> resolve(String canonicalActionType) {
                return action.canonicalActionType().equals(canonicalActionType)
                    ? Optional.of(action)
                    : Optional.empty();
            }
        };
    }

    private static SourceAdvisoryEvidence sourceEvidence() {
        return new SourceAdvisoryEvidence(
            SOURCE_EVIDENCE_ID,
            SOURCE_EVIDENCE_HASH,
            new AiVersionReferences(
                new AiVersionReferences.ProviderVersion("test-provider", "1"),
                new AiVersionReferences.ModelVersion("test-provider", "test-model", "1"),
                new AiVersionReferences.PromptTemplateVersion(
                    "test-prompt",
                    "1",
                    "prompt-hash"
                ),
                AiVersionReferences.KnowledgeSourceVersion.none(),
                new AiVersionReferences.PolicyVersion("test-policy", "1", "policy-hash"),
                new AiVersionReferences.OutputSchemaVersion("test-schema", 1)
            )
        );
    }

    private static DecisionCase decision(
        Consumer<MutableSnapshot> changes,
        EvaluationDecision decision,
        ReasonCode reasonCode
    ) {
        return new DecisionCase(changes, decision, reasonCode);
    }

    private static void assertDecision(
        ControlledAutomationGovernanceEvaluator.EvaluationResult result,
        EvaluationDecision decision,
        ReasonCode reasonCode
    ) {
        assertEquals(decision, result.decision());
        assertEquals(reasonCode, result.reasonCode());
        assertFalse(result.businessSideEffectProduced());
        assertFalse(result.providerInvoked());
        assertFalse(result.connectorInvoked());
        assertFalse(result.commandAttempted());
    }

    private record DecisionCase(
        Consumer<MutableSnapshot> changes,
        EvaluationDecision decision,
        ReasonCode reasonCode
    ) {
    }

    private static final class MutableSnapshot {
        private String rolesEvidenceHash = ROLES_HASH;
        private String authorizationEvidenceHash = AUTHORIZATION_HASH;
        private boolean permissionGranted = true;
        private boolean resourceAuthorized = true;
        private TargetResourceEvidence currentResource;
        private PolicyEvidence currentPolicy = new PolicyEvidence("test-policy-v1", POLICY_HASH);
        private boolean policyAllowed = true;
        private boolean separationOfDutiesAllowed = true;
        private boolean featureEnabled = true;
        private boolean killSwitchActive;
        private long killSwitchGeneration = 1;
        private boolean commandPreconditionsSatisfied = true;
        private boolean sourceEvidencePresent = true;
        private UUID sourceEvidenceId = SOURCE_EVIDENCE_ID;
        private String sourceEvidenceHash = SOURCE_EVIDENCE_HASH;
        private boolean sourceEvidenceIntegrityValid = true;
        private ProposalStatus proposalStatus = ProposalStatus.PROPOSED;
        private String proposalLineageHash;
        private boolean reauthenticationSatisfied = true;

        private MutableSnapshot(ControlledAutomationProposal proposal) {
            currentResource = proposal.targetResource();
            proposalLineageHash = proposal.lineageHash();
        }
    }
}
