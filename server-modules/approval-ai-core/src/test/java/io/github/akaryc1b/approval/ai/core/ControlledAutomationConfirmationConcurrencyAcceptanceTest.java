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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlledAutomationConfirmationConcurrencyAcceptanceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T04:30:00Z");
    private static final UUID PROPOSAL_ID = UUID.fromString(
        "93000000-0000-0000-0000-000000000001"
    );
    private static final UUID SOURCE_ID = UUID.fromString(
        "93000000-0000-0000-0000-000000000002"
    );
    private static final UUID CHALLENGE_ID = UUID.fromString(
        "93000000-0000-0000-0000-000000000003"
    );

    @Test
    void proposalExpiryDuringReauthenticationCannotProduceConfirmationEvidence()
        throws Exception {
        MutableClock clock = new MutableClock(NOW);
        Fixture fixture = fixture(clock, NOW.plusSeconds(5), NOW.plusSeconds(60));
        CountDownLatch verifierEntered = new CountDownLatch(1);
        CountDownLatch verifierRelease = new CountDownLatch(1);
        AtomicInteger identifiers = new AtomicInteger();
        ControlledAutomationConfirmationService service = service(
            clock,
            verifierEntered,
            verifierRelease,
            identifiers
        );

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ControlledAutomationConfirmationService.ConfirmationResult> future =
                executor.submit(() -> service.confirm(
                    fixture.context(),
                    fixture.proposal(),
                    fixture.evaluation(),
                    request(fixture)
                ));
            assertTrue(verifierEntered.await(10, TimeUnit.SECONDS));
            clock.set(NOW.plusSeconds(6));
            verifierRelease.countDown();

            var result = future.get(10, TimeUnit.SECONDS);
            assertEquals(ConfirmationDisposition.PROPOSAL_NOT_ACTIVE, result.disposition());
            assertTrue(result.evidence().isEmpty());
            assertEquals(0, identifiers.get());
        }
    }

    @Test
    void challengeExpiryDuringReauthenticationCannotProduceConfirmationEvidence()
        throws Exception {
        MutableClock clock = new MutableClock(NOW);
        Fixture fixture = fixture(clock, NOW.plusSeconds(60), NOW.plusSeconds(5));
        CountDownLatch verifierEntered = new CountDownLatch(1);
        CountDownLatch verifierRelease = new CountDownLatch(1);
        AtomicInteger identifiers = new AtomicInteger();
        ControlledAutomationConfirmationService service = service(
            clock,
            verifierEntered,
            verifierRelease,
            identifiers
        );

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ControlledAutomationConfirmationService.ConfirmationResult> future =
                executor.submit(() -> service.confirm(
                    fixture.context(),
                    fixture.proposal(),
                    fixture.evaluation(),
                    request(fixture)
                ));
            assertTrue(verifierEntered.await(10, TimeUnit.SECONDS));
            clock.set(NOW.plusSeconds(6));
            verifierRelease.countDown();

            var result = future.get(10, TimeUnit.SECONDS);
            assertEquals(
                ConfirmationDisposition.REAUTHENTICATION_EXPIRED,
                result.disposition()
            );
            assertTrue(result.evidence().isEmpty());
            assertEquals(0, identifiers.get());
        }
    }

    @Test
    void concurrentValidAndForgedOperatorsYieldOneNonExecutableConfirmation()
        throws Exception {
        MutableClock clock = new MutableClock(NOW);
        Fixture fixture = fixture(clock, NOW.plusSeconds(60), NOW.plusSeconds(30));
        AtomicInteger verifierCalls = new AtomicInteger();
        AtomicInteger identifiers = new AtomicInteger();
        ControlledAutomationConfirmationService service =
            new ControlledAutomationConfirmationService(
                (context, proposal, challenge) -> {
                    verifierCalls.incrementAndGet();
                    return Verification.accepted("f".repeat(64), clock.instant());
                },
                clock,
                () -> new UUID(0x9300000000000000L, identifiers.incrementAndGet())
            );
        CyclicBarrier start = new CyclicBarrier(2);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ControlledAutomationConfirmationService.ConfirmationResult> valid =
                executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return service.confirm(
                        fixture.context(),
                        fixture.proposal(),
                        fixture.evaluation(),
                        request(fixture)
                    );
                });
            Future<ControlledAutomationConfirmationService.ConfirmationResult> forged =
                executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return service.confirm(
                        context("tenant-a", "operator-b"),
                        fixture.proposal(),
                        fixture.evaluation(),
                        request(fixture)
                    );
                });

            List<ConfirmationDisposition> dispositions = List.of(
                valid.get(10, TimeUnit.SECONDS).disposition(),
                forged.get(10, TimeUnit.SECONDS).disposition()
            );
            assertEquals(
                1,
                dispositions.stream()
                    .filter(value -> value
                        == ConfirmationDisposition.CONFIRMED_NON_EXECUTABLE)
                    .count()
            );
            assertEquals(
                1,
                dispositions.stream()
                    .filter(value -> value == ConfirmationDisposition.IDENTITY_MISMATCH)
                    .count()
            );
            assertEquals(1, verifierCalls.get());
            assertEquals(1, identifiers.get());
        }
    }

    @Test
    void confirmationServiceStillContainsNoCommandOrRetryAuthority() {
        Set<String> fields = java.util.Arrays.stream(
            ControlledAutomationConfirmationService.class.getDeclaredFields()
        ).map(java.lang.reflect.Field::getName).collect(java.util.stream.Collectors.toSet());

        assertFalse(fields.contains("commandService"));
        assertFalse(fields.contains("retryExecutor"));
        assertFalse(fields.contains("provider"));
    }

    private static ControlledAutomationConfirmationService service(
        MutableClock clock,
        CountDownLatch entered,
        CountDownLatch release,
        AtomicInteger identifiers
    ) {
        return new ControlledAutomationConfirmationService(
            (context, proposal, challenge) -> {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        return Verification.failed();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return Verification.failed();
                }
                return Verification.accepted("f".repeat(64), clock.instant());
            },
            clock,
            () -> new UUID(0x9300000000000000L, identifiers.incrementAndGet())
        );
    }

    private static ConfirmationRequest request(Fixture fixture) {
        return new ConfirmationRequest(
            fixture.proposal().proposalId(),
            fixture.proposal().lineageHash(),
            fixture.evaluation().evidenceHash(),
            ConfirmationIntent.EXPLICIT_CLICK,
            fixture.challenge()
        );
    }

    private static Fixture fixture(
        MutableClock clock,
        Instant proposalExpiresAt,
        Instant challengeExpiresAt
    ) {
        AiServerRequestContext context = context("tenant-a", "operator-a");
        ControlledAutomationActionWhitelist whitelist = whitelist();
        ControlledAutomationProposal proposal = new ControlledAutomationProposalFactory(
            whitelist,
            clock,
            () -> PROPOSAL_ID
        ).create(new ProposalCreationRequest(
            context,
            CreationTrigger.EXPLICIT_USER_ACTION,
            sourceEvidence(),
            "TEST_ONLY_NON_EXECUTABLE_ACTION",
            parameters(),
            resource(),
            new PolicyEvidence("test-policy-v1", "e".repeat(64)),
            proposalExpiresAt
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
            clock
        ).evaluate(context, proposal);
        Instant issuedAt = NOW.minusSeconds(10);
        ReauthenticationChallenge challenge = new ReauthenticationChallenge(
            CHALLENGE_ID,
            ControlledAutomationConfirmationService.challengeBindingHash(
                proposal,
                evaluation,
                CHALLENGE_ID,
                ReauthenticationMethod.HOST_STEP_UP.name(),
                issuedAt,
                challengeExpiresAt
            ),
            ReauthenticationMethod.HOST_STEP_UP,
            issuedAt,
            challengeExpiresAt
        );
        return new Fixture(context, proposal, evaluation, challenge);
    }

    private static AiServerRequestContext context(String tenant, String operator) {
        return new AiServerRequestContext(tenant, operator, "request-a", "trace-a");
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

    private static Map<String, ParameterBinding> parameters() {
        ParameterBinding binding = new ParameterBinding(
            "acknowledgementCode",
            new IdentifierParameter("ACK-1"),
            ParameterSource.EXPLICIT_USER_INPUT
        );
        return Map.of(binding.name(), binding);
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

    private record Fixture(
        AiServerRequestContext context,
        ControlledAutomationProposal proposal,
        EvaluationResult evaluation,
        ReauthenticationChallenge challenge
    ) {
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant initial) {
            instant = new AtomicReference<>(initial);
        }

        private void set(Instant value) {
            instant.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("P7 confirmation clock is UTC-only");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
