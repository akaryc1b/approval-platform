package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.ProjectionProvenance;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.Request;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.Result;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.ResultLimits;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.UseCase;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.FormSnapshot;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ProcessSnapshot;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ProjectionEvidence;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ProviderRequirements;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ResourceState;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ResourceStateSnapshot;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidence.AdvisoryCounts;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceSynchronousOrchestrator.InvocationMode;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceSynchronousOrchestrator.Outcome;
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiProviderOutcome;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import io.github.akaryc1b.approval.ai.spi.AiUsageEvidence;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.KnowledgeSourceVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.ModelVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.OutputSchemaVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.PolicyVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.PromptTemplateVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.ProviderVersion;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalAssistanceDurableEvidenceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T08:00:00Z");
    private static final UUID EVIDENCE_ID = UUID.fromString(
        "10000000-0000-0000-0000-000000000001"
    );
    private static final PolicyVersion POLICY = new PolicyVersion(
        "approval-assistance",
        "v1",
        "policy-hash-v1"
    );

    @Test
    void createsHashOnlyEvidenceForAnExactSuccessfulOutcome() {
        Fixture fixture = successfulFixture("1000.00", "Bounded summary");

        ApprovalAssistanceDurableEvidence evidence = ApprovalAssistanceDurableEvidence.create(
            EVIDENCE_ID,
            fixture.outcome,
            fixture.executionEvidence,
            NOW.plusSeconds(1),
            NOW.plus(Duration.ofDays(30))
        );

        assertEquals("tenant-a", evidence.tenantId());
        assertEquals(UseCase.SUMMARY, evidence.useCase());
        assertEquals(AiOutcomeClassification.SUCCESS, evidence.classification());
        assertEquals(1, evidence.providerAttempts());
        assertTrue(evidence.providerInvocationStarted());
        assertFalse(evidence.retryAttempted());
        assertFalse(evidence.postInvocationFallbackAttempted());
        assertTrue(evidence.advisoryResultPresent());
        assertEquals(new AdvisoryCounts(1, 0, 0, 1, 1, 1), evidence.advisoryCounts());
        assertEquals(0.90d, evidence.confidenceScore());
        assertEquals(AiAdvisoryResult.ConfidenceBand.HIGH, evidence.confidenceBand());
        assertEquals(64, evidence.projectionEvidenceHash().length());
        assertEquals(64, evidence.outcomeEvidenceHash().length());
        assertEquals(64, evidence.evidenceHash().length());
    }

    @Test
    void ProviderSafeValueChangesProduceDifferentProjectionEvidenceHashes() {
        ApprovalAssistanceDurableEvidence first = create(successfulFixture(
            "1000.00",
            "Same summary"
        ));
        ApprovalAssistanceDurableEvidence second = create(successfulFixture(
            "2000.00",
            "Same summary"
        ));

        assertNotEquals(first.projectionEvidenceHash(), second.projectionEvidenceHash());
        assertEquals(first.outcomeEvidenceHash(), second.outcomeEvidenceHash());
        assertNotEquals(first.evidenceHash(), second.evidenceHash());
    }

    @Test
    void AdvisoryTextChangesProduceDifferentOutcomeEvidenceHashes() {
        ApprovalAssistanceDurableEvidence first = create(successfulFixture(
            "1000.00",
            "First bounded summary"
        ));
        ApprovalAssistanceDurableEvidence second = create(successfulFixture(
            "1000.00",
            "Second bounded summary"
        ));

        assertEquals(first.projectionEvidenceHash(), second.projectionEvidenceHash());
        assertNotEquals(first.outcomeEvidenceHash(), second.outcomeEvidenceHash());
        assertNotEquals(first.evidenceHash(), second.evidenceHash());
    }

    @Test
    void failureEvidenceContainsNoManufacturedAdvisoryMetadata() {
        AiVersionReferences versions = versions(KnowledgeSourceVersion.none());
        Request request = request(versions, "1000.00");
        AiProviderRoute route = route(versions);
        AiProviderOutcome providerOutcome = AiProviderOutcome.failure(
            AiOutcomeClassification.TIMEOUT,
            "AI_PROVIDER_TIMEOUT",
            "bounded timeout",
            false
        );
        AiCoordinatedAdvisoryOutcome coordinated = coordinated(route, providerOutcome);
        Outcome outcome = new Outcome(
            request,
            coordinated,
            null,
            InvocationMode.DETERMINISTIC_TEST_ONLY,
            1,
            false,
            1
        );
        AiAdvisoryExecutionEvidence execution = execution(request, coordinated);

        ApprovalAssistanceDurableEvidence evidence = ApprovalAssistanceDurableEvidence.create(
            EVIDENCE_ID,
            outcome,
            execution,
            NOW.plusSeconds(1),
            NOW.plus(Duration.ofDays(30))
        );

        assertEquals(AiOutcomeClassification.TIMEOUT, evidence.classification());
        assertFalse(evidence.advisoryResultPresent());
        assertTrue(evidence.advisoryCounts().empty());
        assertNull(evidence.confidenceScore());
        assertNull(evidence.confidenceBand());
    }

    @Test
    void mismatchedExecutionEvidenceIsRejected() {
        Fixture fixture = successfulFixture("1000.00", "Bounded summary");
        AiCoordinatedAdvisoryOutcome mismatch = new AiCoordinatedAdvisoryOutcome(
            fixture.outcome.coordinated().selectedRoute(),
            AiProviderOutcome.failure(
                AiOutcomeClassification.UNKNOWN,
                "MISMATCH",
                "mismatch",
                false
            ),
            fixture.outcome.coordinated().usageEvidence(),
            0,
            true,
            false,
            AiProviderCircuitBreaker.State.CLOSED,
            AiProviderCircuitBreaker.State.OPEN
        );
        AiAdvisoryExecutionEvidence wrongExecution = execution(
            fixture.outcome.request(),
            mismatch
        );

        assertThrows(IllegalArgumentException.class, () ->
            ApprovalAssistanceDurableEvidence.create(
                EVIDENCE_ID,
                fixture.outcome,
                wrongExecution,
                NOW.plusSeconds(1),
                NOW.plus(Duration.ofDays(30))
            )
        );
    }

    @Test
    void customerKnowledgeMetadataIsRejected() {
        AiVersionReferences versions = versions(new KnowledgeSourceVersion(
            "tenant-knowledge",
            "v1",
            "knowledge-hash-v1",
            true
        ));

        assertThrows(IllegalArgumentException.class, () -> request(versions, "1000.00"));
    }

    @Test
    void invalidRetentionWindowsAreRejected() {
        Fixture fixture = successfulFixture("1000.00", "Bounded summary");

        assertThrows(IllegalArgumentException.class, () ->
            ApprovalAssistanceDurableEvidence.create(
                EVIDENCE_ID,
                fixture.outcome,
                fixture.executionEvidence,
                NOW.plusSeconds(1),
                NOW
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            ApprovalAssistanceDurableEvidence.create(
                EVIDENCE_ID,
                fixture.outcome,
                fixture.executionEvidence,
                NOW.plusSeconds(1),
                NOW.plus(Duration.ofDays(3_651))
            )
        );
    }

    @Test
    void directConstructionCannotTamperWithCanonicalEvidenceHash() {
        ApprovalAssistanceDurableEvidence valid = create(successfulFixture(
            "1000.00",
            "Bounded summary"
        ));

        assertThrows(IllegalArgumentException.class, () ->
            new ApprovalAssistanceDurableEvidence(
                valid.evidenceId(),
                valid.tenantId(),
                valid.requestEvidenceHash(),
                valid.subjectEvidenceHash(),
                valid.resourceEvidenceHash(),
                valid.projectionEvidenceHash(),
                valid.executionEvidenceHash(),
                valid.routeEvidenceHash(),
                valid.versionEvidenceHash(),
                valid.outcomeEvidenceHash(),
                valid.useCase(),
                valid.classification(),
                valid.versions(),
                valid.providerAttempts(),
                valid.providerInvocationStarted(),
                valid.retryAttempted(),
                valid.postInvocationFallbackAttempted(),
                valid.killSwitchGeneration(),
                valid.advisoryResultPresent(),
                valid.advisoryCounts(),
                valid.confidenceScore(),
                valid.confidenceBand(),
                valid.requestedAt(),
                valid.recordedAt(),
                valid.retentionUntil(),
                "0".repeat(64)
            )
        );
    }

    private static ApprovalAssistanceDurableEvidence create(Fixture fixture) {
        return ApprovalAssistanceDurableEvidence.create(
            EVIDENCE_ID,
            fixture.outcome,
            fixture.executionEvidence,
            NOW.plusSeconds(1),
            NOW.plus(Duration.ofDays(30))
        );
    }

    private static Fixture successfulFixture(String amount, String summary) {
        AiVersionReferences versions = versions(KnowledgeSourceVersion.none());
        Request request = request(versions, amount);
        AiAdvisoryResult advisory = advisory(versions, summary);
        Result accepted = new Result(request, advisory);
        AiProviderRoute route = route(versions);
        AiCoordinatedAdvisoryOutcome coordinated = coordinated(
            route,
            AiProviderOutcome.success(advisory)
        );
        Outcome outcome = new Outcome(
            request,
            coordinated,
            accepted,
            InvocationMode.DETERMINISTIC_TEST_ONLY,
            1,
            false,
            1
        );
        return new Fixture(outcome, execution(request, coordinated));
    }

    private static AiCoordinatedAdvisoryOutcome coordinated(
        AiProviderRoute route,
        AiProviderOutcome outcome
    ) {
        return new AiCoordinatedAdvisoryOutcome(
            route,
            outcome,
            AiUsageEvidence.platformObserved(100, 5),
            0,
            true,
            false,
            AiProviderCircuitBreaker.State.CLOSED,
            AiProviderCircuitBreaker.State.CLOSED
        );
    }

    private static AiAdvisoryExecutionEvidence execution(
        Request request,
        AiCoordinatedAdvisoryOutcome coordinated
    ) {
        return AiAdvisoryExecutionEvidence.create(
            request.projection().requestContext(),
            request.projection().authorizedResource(),
            request.useCase().capability(),
            coordinated
        );
    }

    private static Request request(AiVersionReferences versions, String amount) {
        ApprovalAssistanceContextProjection projection = projection(versions, amount);
        return new Request(
            projection,
            UseCase.SUMMARY,
            versions,
            ResultLimits.conservativeDefaults(),
            ProjectionProvenance.from(projection),
            NOW
        );
    }

    private static ApprovalAssistanceContextProjection projection(
        AiVersionReferences versions,
        String amount
    ) {
        return new ApprovalAssistanceContextProjection(
            new AiServerRequestContext(
                "tenant-a",
                "operator-a",
                "request-1",
                "trace-1"
            ),
            new AiAuthorizedResource(
                "tenant-a",
                AiAuthorizedResource.ResourceType.APPROVAL_TASK,
                "task-1",
                "authz-ref",
                Set.of("amount")
            ),
            new ProcessSnapshot(
                "purchase-payment",
                2,
                "compiler-1.1.0",
                "definition-hash-v2",
                "purchase-form",
                3,
                5,
                "release-package-hash-v5"
            ),
            new ResourceStateSnapshot(
                "tenant-a",
                "instance-1",
                "task-1",
                "managerApproval",
                ResourceState.TASK_PENDING,
                7,
                NOW.minusSeconds(1)
            ),
            new FormSnapshot(
                "purchase-form",
                3,
                "1.0",
                "form-hash-v3",
                1,
                2,
                "ui-hash-v2",
                "managerApproval",
                4
            ),
            List.of(new AiProviderRequest.InputField(
                "amount",
                "NUMBER",
                amount,
                AiProviderRequest.MaskingDisposition.INCLUDED
            )),
            new ProviderRequirements(
                Set.of(AiCapability.APPROVAL_SUMMARY),
                8,
                1_000,
                4_000,
                8,
                3,
                true,
                true
            ),
            versions.policy(),
            new ProjectionEvidence(1, 1, 0, 0, 0, false)
        );
    }

    private static AiProviderRoute route(AiVersionReferences versions) {
        return new AiProviderRoute(
            "route-1",
            0,
            true,
            Set.of(AiCapability.APPROVAL_SUMMARY),
            versions,
            new AiInvocationBudget(Duration.ofMillis(100), 4_000, 8, 0.5d)
        );
    }

    private static AiVersionReferences versions(KnowledgeSourceVersion knowledge) {
        return new AiVersionReferences(
            new ProviderVersion("provider-a", "v1"),
            new ModelVersion("provider-a", "model-a", "v1"),
            new PromptTemplateVersion(
                "approval-summary",
                "v1",
                "prompt-hash-v1"
            ),
            knowledge,
            POLICY,
            new OutputSchemaVersion("approval-assistance", 1)
        );
    }

    private static AiAdvisoryResult advisory(
        AiVersionReferences versions,
        String summary
    ) {
        return new AiAdvisoryResult(
            summary,
            List.of(new AiAdvisoryResult.Observation(
                "observation-1",
                "Authorized amount is present",
                List.of("evidence-1")
            )),
            List.of(),
            List.of(),
            List.of(new AiAdvisoryResult.Recommendation(
                "recommendation-1",
                AiAdvisoryResult.RecommendationType.VERIFY_EVIDENCE,
                "Verify the amount evidence",
                List.of("evidence-1")
            )),
            List.of(new AiAdvisoryResult.EvidenceReference(
                "evidence-1",
                "amount",
                "Authorized amount"
            )),
            new AiAdvisoryResult.Confidence(
                0.90d,
                AiAdvisoryResult.ConfidenceBand.HIGH
            ),
            List.of("Human review is required"),
            true,
            versions,
            AiAdvisoryResult.Authority.ADVISORY,
            AiAdvisoryResult.AssertionStatus.UNVERIFIED_ADVISORY
        );
    }

    private record Fixture(
        Outcome outcome,
        AiAdvisoryExecutionEvidence executionEvidence
    ) {
    }
}
