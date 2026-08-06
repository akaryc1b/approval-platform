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
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.IdentifierParameter;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.ParameterBinding;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.ParameterSource;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.PolicyEvidence;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.ProposalStatus;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.SourceAdvisoryEvidence;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.TargetResourceEvidence;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposalFactory.ProposalCreationRequest;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationReauthenticationVerifier.ReauthenticationChallenge;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationReauthenticationVerifier.ReauthenticationMethod;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationReauthenticationVerifier.Verification;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlledAutomationConfirmationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-05T06:00:00Z");
    private static final UUID PROPOSAL_ID = UUID.fromString(
        "50000000-0000-0000-0000-000000000005"
    );
    private static final UUID SOURCE_EVIDENCE_ID = UUID.fromString(
        "60000000-0000-0000-0000-000000000006"
    );
    private static final UUID CHALLENGE_ID = UUID.fromString(
        "70000000-0000-0000-0000-000000000007"
    );
    private static final UUID CONFIRMATION_ID = UUID.fromString(
        "80000000-0000-0000-0000-000000000008"
    );

    @Test
    void currentUnavailableReauthenticationBlocksConfirmationWithoutAllocatingId() {
        Fixture fixture = fixture();
        AtomicInteger identifiers = new AtomicInteger();
        ControlledAutomationConfirmationService service = new ControlledAutomationConfirmationService(
            ControlledAutomationReauthenticationVerifier.unavailable(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> {
                identifiers.incrementAndGet();
                return CONFIRMATION_ID;
            }
        );

        ControlledAutomationConfirmationService.ConfirmationResult result = service.confirm(
            fixture.context(),
            fixture.proposal(),
            fixture.evaluation(),
            request(fixture, ConfirmationIntent.EXPLICIT_CLICK, validChallenge(fixture))
        );

        assertEquals(ConfirmationDisposition.REAUTHENTICATION_UNAVAILABLE, result.disposition());
        assertTrue(result.evidence().isEmpty());
        assertEquals(0, identifiers.get());
    }

    @Test
    void pageLoadEnterTimerRetryAndTabChangeCannotConfirm() {
        Fixture fixture = fixture();
        AtomicInteger verifierCalls = new AtomicInteger();
        ControlledAutomationConfirmationService service = service(
            (context, proposal, challenge) -> {
                verifierCalls.incrementAndGet();
                return Verification.accepted("f".repeat(64), NOW);
            }
        );

        for (ConfirmationIntent intent : ConfirmationIntent.values()) {
            if (intent == ConfirmationIntent.EXPLICIT_CLICK) {
                continue;
            }
            ControlledAutomationConfirmationService.ConfirmationResult result = service.confirm(
                fixture.context(),
                fixture.proposal(),
                fixture.evaluation(),
                request(fixture, intent, validChallenge(fixture))
            );
            assertEquals(ConfirmationDisposition.EXPLICIT_CLICK_REQUIRED, result.disposition());
            assertTrue(result.evidence().isEmpty());
        }
        assertEquals(0, verifierCalls.get());
    }

    @Test
    void exactProposalEvaluationIdentityAndChallengeBindingsAreMandatory() {
        Fixture fixture = fixture();
        ControlledAutomationConfirmationService service = service(
            (context, proposal, challenge) -> Verification.accepted("f".repeat(64), NOW)
        );
        ConfirmationRequest changedEvaluation = new ConfirmationRequest(
            fixture.proposal().proposalId(),
            fixture.proposal().lineageHash(),
            "9".repeat(64),
            ConfirmationIntent.EXPLICIT_CLICK,
            validChallenge(fixture)
        );
        assertEquals(
            ConfirmationDisposition.BINDING_MISMATCH,
            service.confirm(
                fixture.context(),
                fixture.proposal(),
                fixture.evaluation(),
                changedEvaluation
            ).disposition()
        );
        assertEquals(
            ConfirmationDisposition.IDENTITY_MISMATCH,
            service.confirm(
                context("tenant-a", "operator-b"),
                fixture.proposal(),
                fixture.evaluation(),
                request(fixture, ConfirmationIntent.EXPLICIT_CLICK, validChallenge(fixture))
            ).disposition()
        );
        ReauthenticationChallenge changedBinding = new ReauthenticationChallenge(
            CHALLENGE_ID,
            "8".repeat(64),
            ReauthenticationMethod.HOST_STEP_UP,
            NOW.minusSeconds(10),
            NOW.plusSeconds(30)
        );
        assertEquals(
            ConfirmationDisposition.BINDING_MISMATCH,
            service.confirm(
                fixture.context(),
                fixture.proposal(),
                fixture.evaluation(),
                request(fixture, ConfirmationIntent.EXPLICIT_CLICK, changedBinding)
            ).disposition()
        );
    }

    @Test
    void expiredChallengeAndFailedVerificationCannotConfirm() {
        Fixture fixture = fixture();
        ControlledAutomationConfirmationService failed = service(
            (context, proposal, challenge) -> Verification.failed()
        );
        assertEquals(
            ConfirmationDisposition.REAUTHENTICATION_FAILED,
            failed.confirm(
                fixture.context(),
                fixture.proposal(),
                fixture.evaluation(),
                request(fixture, ConfirmationIntent.EXPLICIT_CLICK, validChallenge(fixture))
            ).disposition()
        );

        ReauthenticationChallenge expired = challenge(
            fixture,
            NOW.minusSeconds(30),
            NOW.minusSeconds(1)
        );
        assertEquals(
            ConfirmationDisposition.REAUTHENTICATION_EXPIRED,
            failed.confirm(
                fixture.context(),
                fixture.proposal(),
                fixture.evaluation(),
                request(fixture, ConfirmationIntent.EXPLICIT_CLICK, expired)
            ).disposition()
        );
    }

    @Test
    void acceptedTestVerifierCreatesOnlyShortLivedNonExecutableEvidence() {
        Fixture fixture = fixture();
        AtomicInteger verifierCalls = new AtomicInteger();
        ControlledAutomationConfirmationService service = service(
            (context, proposal, challenge) -> {
                verifierCalls.incrementAndGet();
                return Verification.accepted("f".repeat(64), NOW);
            }
        );

        ControlledAutomationConfirmationService.ConfirmationResult result = service.confirm(
            fixture.context(),
            fixture.proposal(),
            fixture.evaluation(),
            request(fixture, ConfirmationIntent.EXPLICIT_CLICK, validChallenge(fixture))
        );

        assertEquals(ConfirmationDisposition.CONFIRMED_NON_EXECUTABLE, result.disposition());
        ControlledAutomationConfirmationService.ControlledAutomationConfirmationEvidence evidence =
            result.evidence().orElseThrow();
        assertEquals(1, verifierCalls.get());
        assertEquals(CONFIRMATION_ID, evidence.confirmationId());
        assertEquals(PROPOSAL_ID, evidence.proposalId());
        assertTrue(evidence.singleUseRequired());
        assertFalse(evidence.commandAdmitted());
        assertEquals(
            ControlledAutomationConfirmationService.ConfirmationAuthority
                .NON_EXECUTABLE_CONFIRMATION,
            evidence.authority()
        );
        assertEquals(NOW.plusSeconds(120), evidence.expiresAt());
        assertEquals(64, evidence.typedParameterHash().length());
        assertEquals(64, evidence.evidenceHash().length());
        assertNotEquals(evidence.reauthenticationEvidenceHash(), evidence.evidenceHash());
    }

    @Test
    void confirmationContractContainsNoCredentialOrCommandPayload() {
        Set<String> fields = Arrays.stream(
            ControlledAutomationConfirmationService.ControlledAutomationConfirmationEvidence
                .class.getDeclaredFields()
        ).map(Field::getName).collect(java.util.stream.Collectors.toSet());
        for (String forbidden : Set.of(
            "password",
            "totp",
            "apiKey",
            "bearerToken",
            "sessionCredential",
            "permissionToken",
            "confirmationToken",
            "secret",
            "commandPayload",
            "httpBody",
            "sql",
            "script"
        )) {
            assertFalse(fields.contains(forbidden));
        }
    }

    private static ControlledAutomationConfirmationService service(
        ControlledAutomationReauthenticationVerifier verifier
    ) {
        return new ControlledAutomationConfirmationService(
            verifier,
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> CONFIRMATION_ID
        );
    }

    private static ConfirmationRequest request(
        Fixture fixture,
        ConfirmationIntent intent,
        ReauthenticationChallenge challenge
    ) {
        return new ConfirmationRequest(
            fixture.proposal().proposalId(),
            fixture.proposal().lineageHash(),
            fixture.evaluation().evidenceHash(),
            intent,
            challenge
        );
    }

    private static ReauthenticationChallenge validChallenge(Fixture fixture) {
        return challenge(fixture, NOW.minusSeconds(10), NOW.plusSeconds(30));
    }

    private static ReauthenticationChallenge challenge(
        Fixture fixture,
        Instant issuedAt,
        Instant expiresAt
    ) {
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
        ).create(new ProposalCreationRequest(
            context,
            CreationTrigger.EXPLICIT_USER_ACTION,
            sourceEvidence(),
            "TEST_ONLY_NON_EXECUTABLE_ACTION",
            parameters(),
            resource(),
            new PolicyEvidence("test-policy-v1", "e".repeat(64)),
            NOW.plusSeconds(300)
        )).proposal().orElseThrow();
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
            SOURCE_EVIDENCE_ID,
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

    private static AiServerRequestContext context(String tenantId, String operatorId) {
        return new AiServerRequestContext(tenantId, operatorId, "request-a", "trace-a");
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

    private static ControlledAutomationActionWhitelist whitelist() {
        ActionDefinition action = new ActionDefinition(
            "TEST_ONLY_NON_EXECUTABLE_ACTION",
            "TEST_RESOURCE",
            Map.of(
                "acknowledgementCode",
                new ParameterDefinition(
                    "acknowledgementCode",
                    ParameterType.IDENTIFIER,
                    Set.of()
                )
            ),
            RiskClassification.LOW,
            "Test-only contract fixture with no business side effect.",
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

    private static Map<String, ParameterBinding> parameters() {
        ParameterBinding binding = new ParameterBinding(
            "acknowledgementCode",
            new IdentifierParameter("ACK-1"),
            ParameterSource.EXPLICIT_USER_INPUT
        );
        return Map.of(binding.name(), binding);
    }

    private static SourceAdvisoryEvidence sourceEvidence() {
        return new SourceAdvisoryEvidence(
            SOURCE_EVIDENCE_ID,
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

    private record Fixture(
        AiServerRequestContext context,
        ControlledAutomationProposal proposal,
        EvaluationResult evaluation
    ) {
    }
}
