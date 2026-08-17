package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.ai.core.AiAdvisoryExecutionEvidence;
import io.github.akaryc1b.approval.ai.core.AiAuthorizedResource;
import io.github.akaryc1b.approval.ai.core.AiInvocationBudget;
import io.github.akaryc1b.approval.ai.core.AiProviderCircuitBreaker;
import io.github.akaryc1b.approval.ai.core.AiProviderRoute;
import io.github.akaryc1b.approval.ai.core.AiServerRequestContext;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.ProjectionProvenance;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.Request;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.Result;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.ResultLimits;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.UseCase;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.FormSnapshot;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ProcessSnapshot;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ProjectionEvidence;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ProviderRequirements;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ResourceState;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ResourceStateSnapshot;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidence;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceSynchronousOrchestrator.InvocationMode;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceSynchronousOrchestrator.Outcome;
import io.github.akaryc1b.approval.ai.core.AiCoordinatedAdvisoryOutcome;
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class ApprovalAssistanceDurableEvidenceJdbcTestFixture {

    private static final Instant BASE = Instant.parse("2026-08-01T08:00:00Z");
    private static final PolicyVersion POLICY = new PolicyVersion(
        "approval-assistance",
        "v1",
        "policy-hash-v1"
    );

    private ApprovalAssistanceDurableEvidenceJdbcTestFixture() {
    }

    static Fixture fixture(String key, String amount, String summary) {
        return fixture(
            "tenant-h8-" + key,
            uuid("evidence-h8-" + key),
            "request-h8-" + key,
            amount,
            summary
        );
    }

    static Fixture fixture(
        String tenantId,
        UUID evidenceId,
        String requestId,
        String amount,
        String summary
    ) {
        Instant requestedAt = BASE.plusSeconds(Math.abs(requestId.hashCode() % 10_000L));
        AiVersionReferences versions = versions();
        ApprovalAssistanceContextProjection projection = projection(
            tenantId,
            requestId,
            amount,
            requestedAt
        );
        Request request = new Request(
            projection,
            UseCase.SUMMARY,
            versions,
            ResultLimits.conservativeDefaults(),
            ProjectionProvenance.from(projection),
            requestedAt
        );
        AiAdvisoryResult advisory = advisory(versions, summary);
        Result accepted = new Result(request, advisory);
        AiProviderRoute route = new AiProviderRoute(
            "route-h8",
            0,
            true,
            Set.of(AiCapability.APPROVAL_SUMMARY),
            versions,
            new AiInvocationBudget(Duration.ofMillis(100), 4_000, 8, 0.5d)
        );
        AiCoordinatedAdvisoryOutcome coordinated = new AiCoordinatedAdvisoryOutcome(
            route,
            AiProviderOutcome.success(advisory),
            AiUsageEvidence.platformObserved(100, 5),
            0,
            true,
            false,
            AiProviderCircuitBreaker.State.CLOSED,
            AiProviderCircuitBreaker.State.CLOSED
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
        AiAdvisoryExecutionEvidence execution = AiAdvisoryExecutionEvidence.create(
            projection.requestContext(),
            projection.authorizedResource(),
            request.useCase().capability(),
            coordinated
        );
        Instant recordedAt = requestedAt.plusSeconds(1);
        ApprovalAssistanceDurableEvidence evidence = ApprovalAssistanceDurableEvidence.create(
            evidenceId,
            outcome,
            execution,
            recordedAt,
            recordedAt.plus(Duration.ofDays(30))
        );
        return new Fixture(tenantId, evidence);
    }

    static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }

    static UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static ApprovalAssistanceContextProjection projection(
        String tenantId,
        String requestId,
        String amount,
        Instant requestedAt
    ) {
        return new ApprovalAssistanceContextProjection(
            new AiServerRequestContext(
                tenantId,
                "operator-h8",
                requestId,
                "trace-" + requestId
            ),
            new AiAuthorizedResource(
                tenantId,
                AiAuthorizedResource.ResourceType.APPROVAL_TASK,
                "task-" + requestId,
                "authz-" + requestId,
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
                tenantId,
                "instance-" + requestId,
                "task-" + requestId,
                "managerApproval",
                ResourceState.TASK_PENDING,
                7,
                requestedAt.minusSeconds(1)
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
            POLICY,
            new ProjectionEvidence(1, 1, 0, 0, 0, false)
        );
    }

    private static AiVersionReferences versions() {
        return new AiVersionReferences(
            new ProviderVersion("provider-a", "v1"),
            new ModelVersion("provider-a", "model-a", "v1"),
            new PromptTemplateVersion(
                "approval-summary",
                "v1",
                "prompt-hash-v1"
            ),
            KnowledgeSourceVersion.none(),
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

    record Fixture(String tenantId, ApprovalAssistanceDurableEvidence evidence) {
    }
}
