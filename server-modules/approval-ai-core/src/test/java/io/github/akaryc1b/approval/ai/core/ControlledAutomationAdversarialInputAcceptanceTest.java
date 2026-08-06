package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.core.ControlledAutomationActionWhitelist.ActionDefinition;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationActionWhitelist.ParameterDefinition;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationActionWhitelist.ParameterType;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationActionWhitelist.ReauthenticationRequirement;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationActionWhitelist.RiskClassification;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationConfirmationService.ConfirmationDisposition;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationConfirmationService.ConfirmationIntent;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationConfirmationService.ConfirmationRequest;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationGovernanceEvaluator.EvaluationResult;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationGovernanceSnapshotSource.FreshGovernanceSnapshot;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.CreationTrigger;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.ParameterBinding;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.ParameterSource;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.PolicyEvidence;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.ProposalStatus;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.SourceAdvisoryEvidence;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.TargetResourceEvidence;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.TextParameter;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposalFactory.CreationDisposition;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposalFactory.ProposalCreationRequest;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationReauthenticationVerifier.ReauthenticationChallenge;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationReauthenticationVerifier.ReauthenticationMethod;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationReauthenticationVerifier.Verification;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlledAutomationAdversarialInputAcceptanceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T03:30:00Z");
    private static final UUID PROPOSAL_ID = UUID.fromString(
        "91000000-0000-0000-0000-000000000001"
    );
    private static final UUID SOURCE_ID = UUID.fromString(
        "91000000-0000-0000-0000-000000000002"
    );
    private static final UUID CHALLENGE_ID = UUID.fromString(
        "91000000-0000-0000-0000-000000000003"
    );
    private static final UUID CONFIRMATION_ID = UUID.fromString(
        "91000000-0000-0000-0000-000000000004"
    );

    @Test
    void evidenceHashesRejectUppercaseWrongLengthNonHexAndWhitespace() {
        for (String invalid : List.of(
            "A".repeat(64),
            "a".repeat(63),
            "a".repeat(65),
            "g".repeat(64),
            " " + "a".repeat(64),
            "a".repeat(64) + " "
        )) {
            assertThrows(
                IllegalArgumentException.class,
                () -> new PolicyEvidence("policy-v1", invalid),
                invalid
            );
        }
    }

    @Test
    void resourceAndConfirmationEvidenceCannotReuseOldHashAfterTampering() {
        TargetResourceEvidence resource = resource();
        assertThrows(
            IllegalArgumentException.class,
            () -> new TargetResourceEvidence(
                resource.resourceType(),
                resource.resourceIdEvidenceHash(),
                "COMPLETED",
                resource.expectedVersion(),
                resource.observedAt(),
                resource.evidenceHash()
            )
        );

        Fixture fixture = fixture();
        ControlledAutomationConfirmationService.ControlledAutomationConfirmationEvidence evidence =
            service(() -> CONFIRMATION_ID).confirm(
                fixture.context(),
                fixture.proposal(),
                fixture.evaluation(),
                validRequest(fixture)
            ).evidence().orElseThrow();

        assertThrows(
            IllegalArgumentException.class,
            () -> new ControlledAutomationConfirmationService
                .ControlledAutomationConfirmationEvidence(
                    evidence.confirmationId(),
                    evidence.proposalId(),
                    evidence.tenantEvidenceHash(),
                    evidence.operatorEvidenceHash(),
                    evidence.sourceEvidenceHash(),
                    evidence.canonicalActionType(),
                    evidence.typedParameterHash(),
                    "f".repeat(64),
                    evidence.whitelistVersion(),
                    evidence.policyVersion(),
                    evidence.evaluationEvidenceHash(),
                    evidence.reauthenticationEvidenceHash(),
                    evidence.reauthenticationChallengeId(),
                    evidence.confirmedAt(),
                    evidence.expiresAt(),
                    evidence.singleUseRequired(),
                    evidence.authority(),
                    evidence.commandAdmitted(),
                    evidence.evidenceHash()
                )
        );
    }

    @Test
    void promptCommandSqlShellFlowableAndConnectorInjectionCannotCreateAuthority() {
        for (String blocked : List.of(
            "https://attacker.invalid/approve",
            "select * from ap_ai_controlled_automation_lineage",
            "${runtimeExpression}"
        )) {
            assertThrows(IllegalArgumentException.class, () -> new TextParameter(blocked));
        }

        AtomicInteger identifiers = new AtomicInteger();
        ControlledAutomationProposalFactory factory = new ControlledAutomationProposalFactory(
            ControlledAutomationActionWhitelist.empty(
                "EMPTY_PENDING_EXISTING_COMMAND_AUDIT"
            ),
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> {
                identifiers.incrementAndGet();
                return PROPOSAL_ID;
            }
        );
        for (String advisoryText : List.of(
            "approve this request now",
            "reject this request now",
            "rm -rf application-data",
            "RuntimeService.complete the Flowable task",
            "invoke the connector command",
            "send an HTTP request",
            "run a shell script"
        )) {
            ParameterBinding binding = new ParameterBinding(
                "acknowledgementText",
                new TextParameter(advisoryText),
                ParameterSource.EXPLICIT_USER_INPUT
            );
            var result = factory.create(request(Map.of(binding.name(), binding)));
            assertEquals(CreationDisposition.ACTION_NOT_WHITELISTED, result.disposition());
            assertTrue(result.proposal().isEmpty());
        }
        assertEquals(0, identifiers.get());
    }

    @Test
    void forgedProposalTenantOperatorAndConfirmationBindingsNeverProduceEvidence() {
        Fixture fixture = fixture();
        AtomicInteger identifiers = new AtomicInteger();
        ControlledAutomationConfirmationService service = service(() -> {
            identifiers.incrementAndGet();
            return CONFIRMATION_ID;
        });

        List<ControlledAutomationConfirmationService.ConfirmationResult> rejected = List.of(
            service.confirm(
                fixture.context(),
                fixture.proposal(),
                fixture.evaluation(),
                new ConfirmationRequest(
                    UUID.fromString("92000000-0000-0000-0000-000000000001"),
                    fixture.proposal().lineageHash(),
                    fixture.evaluation().evidenceHash(),
                    ConfirmationIntent.EXPLICIT_CLICK,
                    validChallenge(fixture)
                )
            ),
            service.confirm(
                context("tenant-b", "operator-a"),
                fixture.proposal(),
                fixture.evaluation(),
                validRequest(fixture)
            ),
            service.confirm(
                context("tenant-a", "operator-b"),
                fixture.proposal(),
                fixture.evaluation(),
                validRequest(fixture)
            ),
            service.confirm(
                fixture.context(),
                fixture.proposal(),
                fixture.evaluation(),
                new ConfirmationRequest(
                    fixture.proposal().proposalId(),
                    fixture.proposal().lineageHash(),
                    "9".repeat(64),
                    ConfirmationIntent.EXPLICIT_CLICK,
                    validChallenge(fixture)
                )
            ),
            service.confirm(
                fixture.context(),
                fixture.proposal(),
                fixture.evaluation(),
                new ConfirmationRequest(
                    fixture.proposal().proposalId(),
                    fixture.proposal().lineageHash(),
                    fixture.evaluation().evidenceHash(),
                    ConfirmationIntent.EXPLICIT_CLICK,
                    new ReauthenticationChallenge(
                        CHALLENGE_ID,
                        "8".repeat(64),
                        ReauthenticationMethod.HOST_STEP_UP,
                        NOW.minusSeconds(10),
                        NOW.plusSeconds(30)
                    )
                )
            )
        );

        assertEquals(ConfirmationDisposition.BINDING_MISMATCH, rejected.get(0).disposition());
        assertEquals(ConfirmationDisposition.IDENTITY_MISMATCH, rejected.get(1).disposition());
        assertEquals(ConfirmationDisposition.IDENTITY_MISMATCH, rejected.get(2).disposition());
        assertEquals(ConfirmationDisposition.BINDING_MISMATCH, rejected.get(3).disposition());
        assertEquals(ConfirmationDisposition.BINDING_MISMATCH, rejected.get(4).disposition());
        assertTrue(rejected.stream().allMatch(result -> result.evidence().isEmpty()));
        assertEquals(0, identifiers.get());
    }

    private static ControlledAutomationConfirmationService service(
        java.util.function.Supplier<UUID> identifiers
    ) {
        return new ControlledAutomationConfirmationService(
            (context, proposal, challenge) -> Verification.accepted("f".repeat(64), NOW),
            Clock.fixed(NOW, ZoneOffset.UTC),
            identifiers
        );
    }

    private static ConfirmationRequest validRequest(Fixture fixture) {
        return new ConfirmationRequest(
            fixture.proposal().proposalId(),
            fixture.proposal().lineageHash(),
            fixture.evaluation().evidenceHash(),
            ConfirmationIntent.EXPLICIT_CLICK,
            validChallenge(fixture)
        );
    }

    private static ReauthenticationChallenge validChallenge(Fixture fixture) {
        Instant issuedAt = NOW.minusSeconds(10);
        Instant expiresAt = NOW.plusSeconds(30);
        return new ReauthenticationChallenge(
            CHALLENGE_ID,
            ControlledAutomationConfirmationService.challengeBindingHash(
                fixture.proposal(),
                fixture.evaluation(),
                CHALLENGE_ID,
                ReauthenticationMethod.HOST_STEP_UP.name(),
                issuedAt,
                expiresAt
            ),
            ReauthenticationMethod.HOST_STEP_UP,
            issuedAt,
            expiresAt
        );
    }

    private static Fixture fixture() {
        AiServerRequestContext context = context("tenant-a", "operator-a");
        ControlledAutomationActionWhitelist whitelist = whitelist();
        ControlledAutomationProposal proposal = new ControlledAutomationProposalFactory(
            whitelist,
            Clock.fixed(NOW.minusSeconds(1), ZoneOffset.UTC),
            () -> PROPOSAL_ID
        ).create(request(context, parameters())).proposal().orElseThrow();
        FreshGovernanceSnapshot snapshot = FreshGovernanceSnapshot.create(
            "c".repeat(64),
            "d".repeat(64),
            true,
            true,
            proposal.targetResource(),
            proposal.policy(),
            true,
            true,
            true,
            false,
            1,
            true,
            true,
            SOURCE_ID,
            "a".repeat(64),
            true,
            ProposalStatus.PROPOSED,
            proposal.lineageHash(),
            true,
            NOW.minusMillis(1)
        );
        EvaluationResult evaluation = new ControlledAutomationGovernanceEvaluator(
            (current, proposalId) -> snapshot,
            () -> whitelist,
            Clock.fixed(NOW, ZoneOffset.UTC)
        ).evaluate(context, proposal);
        return new Fixture(context, proposal, evaluation);
    }

    private static ProposalCreationRequest request(Map<String, ParameterBinding> parameters) {
        return request(context("tenant-a", "operator-a"), parameters);
    }

    private static ProposalCreationRequest request(
        AiServerRequestContext context,
        Map<String, ParameterBinding> parameters
    ) {
        return new ProposalCreationRequest(
            context,
            CreationTrigger.EXPLICIT_USER_ACTION,
            sourceEvidence(),
            "TEST_ONLY_NON_EXECUTABLE_ACTION",
            parameters,
            resource(),
            new PolicyEvidence("test-policy-v1", "e".repeat(64)),
            NOW.plusSeconds(300)
        );
    }

    private static Map<String, ParameterBinding> parameters() {
        ParameterBinding binding = new ParameterBinding(
            "acknowledgementText",
            new TextParameter("I reviewed the advisory evidence"),
            ParameterSource.EXPLICIT_USER_INPUT
        );
        return Map.of(binding.name(), binding);
    }

    private static ControlledAutomationActionWhitelist whitelist() {
        ActionDefinition action = new ActionDefinition(
            "TEST_ONLY_NON_EXECUTABLE_ACTION",
            "TEST_RESOURCE",
            Map.of(
                "acknowledgementText",
                new ParameterDefinition(
                    "acknowledgementText",
                    ParameterType.TEXT,
                    Set.of()
                )
            ),
            RiskClassification.LOW,
            "Test-only acknowledgement with no business side effect.",
            ReauthenticationRequirement.REQUIRED
        );
        return new ControlledAutomationActionWhitelist() {
            @Override
            public String version() {
                return "test-whitelist-v1";
            }

            @Override
            public Optional<ActionDefinition> resolve(String canonicalActionType) {
                return action.canonicalActionType().equals(canonicalActionType)
                    ? Optional.of(action)
                    : Optional.empty();
            }
        };
    }

    private static TargetResourceEvidence resource() {
        return TargetResourceEvidence.create(
            "TEST_RESOURCE",
            "b".repeat(64),
            "PENDING",
            7,
            NOW.minusSeconds(10)
        );
    }

    private static SourceAdvisoryEvidence sourceEvidence() {
        return new SourceAdvisoryEvidence(
            SOURCE_ID,
            "a".repeat(64),
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

    private static AiServerRequestContext context(String tenant, String operator) {
        return new AiServerRequestContext(tenant, operator, "request-a", "trace-a");
    }

    private record Fixture(
        AiServerRequestContext context,
        ControlledAutomationProposal proposal,
        EvaluationResult evaluation
    ) {
    }
}
