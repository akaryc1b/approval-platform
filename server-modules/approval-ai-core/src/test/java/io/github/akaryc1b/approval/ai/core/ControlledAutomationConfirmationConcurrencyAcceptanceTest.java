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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlledAutomationConfirmationConcurrencyAcceptanceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T05:30:00Z");
    private static final UUID PROPOSAL_ID = UUID.fromString(
        "91000000-0000-0000-0000-000000000001"
    );
    private static final UUID SOURCE_EVIDENCE_ID = UUID.fromString(
        "92000000-0000-0000-0000-000000000002"
    );
    private static final UUID CHALLENGE_ID = UUID.fromString(
        "93000000-0000-0000-0000-000000000003"
    );

    @Test
    void twoOperatorsRacingCanOnlyConfirmForTheProposalBoundOperator() throws Exception {
        Fixture fixture = fixture();
        AtomicInteger verifierCalls = new AtomicInteger();
        AtomicInteger identifierCalls = new AtomicInteger();
        ControlledAutomationConfirmationService service = service(
            NOW,
            verifierCalls,
            identifierCalls
        );
        ConfirmationRequest request = request(fixture, fixture.evaluation());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ControlledAutomationConfirmationService.ConfirmationResult> owner =
                executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return service.confirm(
                        fixture.context(),
                        fixture.proposal(),
                        fixture.evaluation(),
                        request
                    );
                });
            Future<ControlledAutomationConfirmationService.ConfirmationResult> attacker =
                executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return service.confirm(
                        context("tenant-a", "operator-b"),
                        fixture.proposal(),
                        fixture.evaluation(),
                        request
                    );
                });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            var accepted = owner.get();
            var denied = attacker.get();
            assertEquals(
                ConfirmationDisposition.CONFIRMED_NON_EXECUTABLE,
                accepted.disposition()
            );
            assertEquals(ConfirmationDisposition.IDENTITY_MISMATCH, denied.disposition());
            assertFalse(accepted.evidence().orElseThrow().commandAdmitted());
            assertTrue(denied.evidence().isEmpty());
        }
        assertEquals(1, verifierCalls.get());
        assertEquals(1, identifierCalls.get());
    }

    @Test
    void duplicateSameOperatorConfirmationCreatesOnlySingleUseNonExecutableEvidence()
        throws Exception {
        Fixture fixture = fixture();
        AtomicInteger verifierCalls = new AtomicInteger();
        AtomicInteger identifierCalls = new AtomicInteger();
        ControlledAutomationConfirmationService service = service(
            NOW,
            verifierCalls,
            identifierCalls
        );
        ConfirmationRequest request = request(fixture, fixture.evaluation());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ControlledAutomationConfirmationService.ConfirmationResult> first =
                executor.submit(() -> confirmAtBarrier(service, fixture, request, ready, start));
            Future<ControlledAutomationConfirmationService.ConfirmationResult> second =
                executor.submit(() -> confirmAtBarrier(service, fixture, request, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            var firstEvidence = first.get().evidence().orElseThrow();
            var secondEvidence = second.get().evidence().orElseThrow();
            assertTrue(firstEvidence.singleUseRequired());
            assertTrue(secondEvidence.singleUseRequired());
            assertFalse(firstEvidence.commandAdmitted());
            assertFalse(secondEvidence.commandAdmitted());
            assertNotEquals(firstEvidence.confirmationId(), secondEvidence.confirmationId());
            assertNotEquals(firstEvidence.evidenceHash(), secondEvidence.evidenceHash());
        }
        assertEquals(2, verifierCalls.get());
        assertEquals(2, identifierCalls.get());
    }

    @Test
    void confirmationAndExpiryRaceUsesExactControlledClockBoundaries() throws Exception {
        Fixture fixture = fixture();
        AtomicInteger verifierCalls = new AtomicInteger();
        AtomicInteger identifierCalls = new AtomicInteger();
        ControlledAutomationConfirmationService active = service(
            NOW,
            verifierCalls,
            identifierCalls
        );
        ControlledAutomationConfirmationService expired = service(
            fixture.proposal().expiresAt(),
            verifierCalls,
            identifierCalls
        );
        ConfirmationRequest request = request(fixture, fixture.evaluation());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ConfirmationDisposition> before = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return active.confirm(
                    fixture.context(),
                    fixture.proposal(),
                    fixture.evaluation(),
                    request
                ).disposition();
            });
            Future<ConfirmationDisposition> atExpiry = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return expired.confirm(
                    fixture.context(),
                    fixture.proposal(),
                    fixture.evaluation(),
                    request
                ).disposition();
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            assertEquals(ConfirmationDisposition.CONFIRMED_NON_EXECUTABLE, before.get());
            assertEquals(ConfirmationDisposition.PROPOSAL_NOT_ACTIVE, atExpiry.get());
        }
        assertEquals(1, verifierCalls.get());
        assertEquals(1, identifierCalls.get());
    }

    @Test
    void freshConfirmationRacingPolicyAndVersionDriftFailsClosed() throws Exception {
        Fixture fixture = fixture();
        EvaluationResult policyDenied = evaluation(
            fixture,
            snapshot(fixture.proposal(), false, 7)
        );
        EvaluationResult staleVersion = evaluation(
            fixture,
            snapshot(fixture.proposal(), true, 8)
        );

        for (EvaluationResult changed : List.of(policyDenied, staleVersion)) {
            AtomicInteger verifierCalls = new AtomicInteger();
            AtomicInteger identifierCalls = new AtomicInteger();
            ControlledAutomationConfirmationService service = service(
                NOW,
                verifierCalls,
                identifierCalls
            );
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<ConfirmationDisposition> eligible = executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return service.confirm(
                        fixture.context(),
                        fixture.proposal(),
                        fixture.evaluation(),
                        request(fixture, fixture.evaluation())
                    ).disposition();
                });
                Future<ConfirmationDisposition> changedResult = executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return service.confirm(
                        fixture.context(),
                        fixture.proposal(),
                        changed,
                        request(fixture, changed)
                    ).disposition();
                });
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();

                assertEquals(
                    ConfirmationDisposition.CONFIRMED_NON_EXECUTABLE,
                    eligible.get()
                );
                assertEquals(
                    ConfirmationDisposition.EVALUATION_NOT_ELIGIBLE,
                    changedResult.get()
                );
            }
            assertEquals(1, verifierCalls.get());
            assertEquals(1, identifierCalls.get());
        }
    }

    private static ControlledAutomationConfirmationService.ConfirmationResult confirmAtBarrier(
        ControlledAutomationConfirmationService service,
        Fixture fixture,
        ConfirmationRequest request,
        CountDownLatch ready,
        CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return service.confirm(
            fixture.context(),
            fixture.proposal(),
            fixture.evaluation(),
            request
        );
    }

    private static ControlledAutomationConfirmationService service(
        Instant now,
        AtomicInteger verifierCalls,
        AtomicInteger identifierCalls
    ) {
        AtomicLong sequence = new AtomicLong();
        return new ControlledAutomationConfirmationService(
            (context, proposal, challenge) -> {
                verifierCalls.incrementAndGet();
                return Verification.accepted("f".repeat(64), NOW);
            },
            Clock.fixed(now, ZoneOffset.UTC),
            () -> {
                identifierCalls.incrementAndGet();
                return new UUID(0x9400000000000000L, sequence.incrementAndGet());
            }
        );
    }

    private static ConfirmationRequest request(Fixture fixture, EvaluationResult evaluation) {
        return new ConfirmationRequest(
            fixture.proposal().proposalId(),
            fixture.proposal().lineageHash(),
            evaluation.evidenceHash(),
            ConfirmationIntent.EXPLICIT_CLICK,
            challenge(fixture.proposal(), evaluation)
        );
    }

    private static ReauthenticationChallenge challenge(
        ControlledAutomationProposal proposal,
        EvaluationResult evaluation
    ) {
        Instant issuedAt = NOW.minusSeconds(10);
        Instant expiresAt = NOW.plusSeconds(30);
        return new ReauthenticationChallenge(
            CHALLENGE_ID,
            ControlledAutomationConfirmationService.challengeBindingHash(
                proposal,
                evaluation,
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
            resource(7),
            new PolicyEvidence("test-policy-v1", "e".repeat(64)),
            NOW.plusSeconds(300)
        )).proposal().orElseThrow();
        Fixture fixture = new Fixture(context, proposal, null, whitelist);
        return new Fixture(
            context,
            proposal,
            evaluation(fixture, snapshot(proposal, true, 7)),
            whitelist
        );
    }

    private static EvaluationResult evaluation(Fixture fixture, FreshGovernanceSnapshot snapshot) {
        return new ControlledAutomationGovernanceEvaluator(
            (current, proposalId) -> snapshot,
            () -> fixture.whitelist(),
            Clock.fixed(NOW, ZoneOffset.UTC)
        ).evaluate(fixture.context(), fixture.proposal());
    }

    private static FreshGovernanceSnapshot snapshot(
        ControlledAutomationProposal proposal,
        boolean policyAllowed,
        long resourceVersion
    ) {
        return FreshGovernanceSnapshot.create(
            "c".repeat(64),
            "d".repeat(64),
            true,
            true,
            resource(resourceVersion),
            proposal.policy(),
            policyAllowed,
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
    }

    private static AiServerRequestContext context(String tenantId, String operatorId) {
        return new AiServerRequestContext(tenantId, operatorId, "request-a", "trace-a");
    }

    private static TargetResourceEvidence resource(long version) {
        return TargetResourceEvidence.create(
            "TEST_RESOURCE",
            "b".repeat(64),
            "PENDING",
            version,
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
        EvaluationResult evaluation,
        ControlledAutomationActionWhitelist whitelist
    ) {
    }
}
