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
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest.InputField;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest.MaskingDisposition;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.KnowledgeSourceVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.ModelVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.OutputSchemaVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.PolicyVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.PromptTemplateVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.ProviderVersion;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalAssistanceAdvisoryContractTest {

    private static final PolicyVersion POLICY = new PolicyVersion(
        "approval-assistance",
        "v1",
        "policy-hash-v1"
    );

    @Test
    void acceptsAllThreeBoundedUseCases() {
        for (UseCase useCase : UseCase.values()) {
            Request request = request(projection(Set.of(useCase.capability())), useCase, versions());
            assertDoesNotThrow(() -> new Result(request, advisory(versions())));
        }
    }

    @Test
    void rejectsAUseCaseThatDoesNotMatchTheProjectionCapability() {
        ApprovalAssistanceContextProjection projection = projection(Set.of(AiCapability.RISK_SIGNALS));
        assertThrows(IllegalArgumentException.class, () -> request(
            projection,
            UseCase.SUMMARY,
            versions()
        ));
    }

    @Test
    void rejectsMultipleCapabilitiesForOneAssistanceRequest() {
        ApprovalAssistanceContextProjection projection = projection(Set.of(
            AiCapability.APPROVAL_SUMMARY,
            AiCapability.RISK_SIGNALS
        ));
        assertThrows(IllegalArgumentException.class, () -> request(
            projection,
            UseCase.SUMMARY,
            versions()
        ));
    }

    @Test
    void rejectsCustomerOrGeneralKnowledgeSourcesInP2() {
        AiVersionReferences withKnowledge = versions(new KnowledgeSourceVersion(
            "case-history",
            "v1",
            "case-history-hash",
            true
        ), POLICY);
        assertThrows(IllegalArgumentException.class, () -> request(
            projection(Set.of(AiCapability.APPROVAL_SUMMARY)),
            UseCase.SUMMARY,
            withKnowledge
        ));
    }

    @Test
    void rejectsAnExpectedPolicyThatDoesNotMatchTheProjection() {
        PolicyVersion otherPolicy = new PolicyVersion(
            "approval-assistance",
            "v2",
            "policy-hash-v2"
        );
        AiVersionReferences mismatched = versions(KnowledgeSourceVersion.none(), otherPolicy);
        assertThrows(IllegalArgumentException.class, () -> request(
            projection(Set.of(AiCapability.APPROVAL_SUMMARY)),
            UseCase.SUMMARY,
            mismatched
        ));
    }

    @Test
    void rejectsRequestProvenanceThatDoesNotMatchTheProjection() {
        ApprovalAssistanceContextProjection projection = projection(Set.of(
            AiCapability.APPROVAL_SUMMARY
        ));
        ProjectionProvenance stale = new ProjectionProvenance(
            projection.resourceState().stateVersion() + 1,
            projection.resourceState().observedAt(),
            projection.form().formContentHash(),
            projection.form().uiSchemaHash(),
            projection.form().submissionRevision(),
            projection.dataPolicyVersion()
        );
        assertThrows(IllegalArgumentException.class, () -> new Request(
            projection,
            UseCase.SUMMARY,
            versions(),
            ResultLimits.conservativeDefaults(),
            stale,
            Instant.parse("2026-07-31T10:00:00Z")
        ));
    }

    @Test
    void rejectsResultVersionsThatDoNotMatchTheRequest() {
        Request request = request(
            projection(Set.of(AiCapability.APPROVAL_SUMMARY)),
            UseCase.SUMMARY,
            versions()
        );
        AiVersionReferences other = new AiVersionReferences(
            new ProviderVersion("provider-a", "v2"),
            new ModelVersion("provider-a", "model-a", "v2"),
            new PromptTemplateVersion("approval-summary", "v2", "prompt-hash-v2"),
            KnowledgeSourceVersion.none(),
            POLICY,
            new OutputSchemaVersion("approval-assistance", 2)
        );
        assertThrows(IllegalArgumentException.class, () -> new Result(
            request,
            advisory(other)
        ));
    }

    @Test
    void rejectsEvidenceForAFieldOutsideTheProviderSafeProjection() {
        Request request = request(
            projection(Set.of(AiCapability.APPROVAL_SUMMARY)),
            UseCase.SUMMARY,
            versions()
        );
        AiAdvisoryResult result = advisoryWithEvidence(
            versions(),
            List.of(new AiAdvisoryResult.EvidenceReference(
                "evidence-1",
                "hidden-field",
                "Unauthorized field"
            )),
            List.of("evidence-1")
        );
        assertThrows(IllegalArgumentException.class, () -> new Result(request, result));
    }

    @Test
    void rejectsAnUnresolvedEvidenceReference() {
        Request request = request(
            projection(Set.of(AiCapability.APPROVAL_SUMMARY)),
            UseCase.SUMMARY,
            versions()
        );
        AiAdvisoryResult result = advisoryWithEvidence(
            versions(),
            List.of(),
            List.of("missing-evidence")
        );
        assertThrows(IllegalArgumentException.class, () -> new Result(request, result));
    }

    @Test
    void rejectsDuplicateEvidenceReferencesWithinOneItem() {
        Request request = request(
            projection(Set.of(AiCapability.APPROVAL_SUMMARY)),
            UseCase.SUMMARY,
            versions()
        );
        AiAdvisoryResult result = advisoryWithEvidence(
            versions(),
            List.of(evidence()),
            List.of("evidence-1", "evidence-1")
        );
        assertThrows(IllegalArgumentException.class, () -> new Result(request, result));
    }

    @Test
    void rejectsDuplicateAdvisoryItemIdsAcrossCategories() {
        Request request = request(
            projection(Set.of(AiCapability.APPROVAL_SUMMARY)),
            UseCase.SUMMARY,
            versions()
        );
        AiAdvisoryResult result = new AiAdvisoryResult(
            "Bounded summary",
            List.of(new AiAdvisoryResult.Observation(
                "item-1",
                "Authorized amount is present",
                List.of("evidence-1")
            )),
            List.of(),
            List.of(),
            List.of(new AiAdvisoryResult.Recommendation(
                "item-1",
                AiAdvisoryResult.RecommendationType.VERIFY_EVIDENCE,
                "Verify the amount evidence",
                List.of("evidence-1")
            )),
            List.of(evidence()),
            new AiAdvisoryResult.Confidence(0.85d, AiAdvisoryResult.ConfidenceBand.HIGH),
            List.of("Unverified advisory material requires human review"),
            true,
            versions(),
            AiAdvisoryResult.Authority.ADVISORY,
            AiAdvisoryResult.AssertionStatus.UNVERIFIED_ADVISORY
        );
        assertThrows(IllegalArgumentException.class, () -> new Result(request, result));
    }

    @Test
    void rejectsAConfidenceBandThatDoesNotMatchTheScore() {
        Request request = request(
            projection(Set.of(AiCapability.APPROVAL_SUMMARY)),
            UseCase.SUMMARY,
            versions()
        );
        AiAdvisoryResult result = new AiAdvisoryResult(
            "Bounded summary",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new AiAdvisoryResult.Confidence(0.40d, AiAdvisoryResult.ConfidenceBand.HIGH),
            List.of("Unverified advisory material requires human review"),
            true,
            versions(),
            AiAdvisoryResult.Authority.ADVISORY,
            AiAdvisoryResult.AssertionStatus.UNVERIFIED_ADVISORY
        );
        assertThrows(IllegalArgumentException.class, () -> new Result(request, result));
    }

    @Test
    void rejectsOutputThatExceedsTheRequestResultLimit() {
        ApprovalAssistanceContextProjection projection = projection(Set.of(
            AiCapability.APPROVAL_SUMMARY
        ));
        Request request = new Request(
            projection,
            UseCase.SUMMARY,
            versions(),
            new ResultLimits(1, 25, 25, 25, 64, 12),
            ProjectionProvenance.from(projection),
            Instant.parse("2026-07-31T10:00:00Z")
        );
        AiAdvisoryResult result = new AiAdvisoryResult(
            "Bounded summary",
            List.of(
                new AiAdvisoryResult.Observation("observation-1", "First", List.of()),
                new AiAdvisoryResult.Observation("observation-2", "Second", List.of())
            ),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new AiAdvisoryResult.Confidence(0.60d, AiAdvisoryResult.ConfidenceBand.MEDIUM),
            List.of("Unverified advisory material requires human review"),
            true,
            versions(),
            AiAdvisoryResult.Authority.ADVISORY,
            AiAdvisoryResult.AssertionStatus.UNVERIFIED_ADVISORY
        );
        assertThrows(IllegalArgumentException.class, () -> new Result(request, result));
    }

    @Test
    void rejectsDuplicateLimitations() {
        Request request = request(
            projection(Set.of(AiCapability.APPROVAL_SUMMARY)),
            UseCase.SUMMARY,
            versions()
        );
        String limitation = "Unverified advisory material requires human review";
        AiAdvisoryResult result = new AiAdvisoryResult(
            "Bounded summary",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new AiAdvisoryResult.Confidence(0.60d, AiAdvisoryResult.ConfidenceBand.MEDIUM),
            List.of(limitation, limitation),
            true,
            versions(),
            AiAdvisoryResult.Authority.ADVISORY,
            AiAdvisoryResult.AssertionStatus.UNVERIFIED_ADVISORY
        );
        assertThrows(IllegalArgumentException.class, () -> new Result(request, result));
    }

    private static Request request(
        ApprovalAssistanceContextProjection projection,
        UseCase useCase,
        AiVersionReferences versions
    ) {
        return new Request(
            projection,
            useCase,
            versions,
            ResultLimits.conservativeDefaults(),
            ProjectionProvenance.from(projection),
            Instant.parse("2026-07-31T10:00:00Z")
        );
    }

    private static AiAdvisoryResult advisory(AiVersionReferences versions) {
        return advisoryWithEvidence(
            versions,
            List.of(evidence()),
            List.of("evidence-1")
        );
    }

    private static AiAdvisoryResult advisoryWithEvidence(
        AiVersionReferences versions,
        List<AiAdvisoryResult.EvidenceReference> evidenceReferences,
        List<String> referencedIds
    ) {
        return new AiAdvisoryResult(
            "Bounded summary",
            List.of(new AiAdvisoryResult.Observation(
                "observation-1",
                "Authorized amount is present",
                referencedIds
            )),
            List.of(),
            List.of(),
            List.of(new AiAdvisoryResult.Recommendation(
                "recommendation-1",
                AiAdvisoryResult.RecommendationType.VERIFY_EVIDENCE,
                "Verify the authorized amount evidence",
                referencedIds
            )),
            evidenceReferences,
            new AiAdvisoryResult.Confidence(0.85d, AiAdvisoryResult.ConfidenceBand.HIGH),
            List.of("Unverified advisory material requires human review"),
            true,
            versions,
            AiAdvisoryResult.Authority.ADVISORY,
            AiAdvisoryResult.AssertionStatus.UNVERIFIED_ADVISORY
        );
    }

    private static AiAdvisoryResult.EvidenceReference evidence() {
        return new AiAdvisoryResult.EvidenceReference(
            "evidence-1",
            "amount",
            "Authorized amount field"
        );
    }

    private static AiVersionReferences versions() {
        return versions(KnowledgeSourceVersion.none(), POLICY);
    }

    private static AiVersionReferences versions(
        KnowledgeSourceVersion knowledge,
        PolicyVersion policy
    ) {
        return new AiVersionReferences(
            new ProviderVersion("provider-a", "v1"),
            new ModelVersion("provider-a", "model-a", "v1"),
            new PromptTemplateVersion("approval-summary", "v1", "prompt-hash-v1"),
            knowledge,
            policy,
            new OutputSchemaVersion("approval-assistance", 1)
        );
    }

    private static ApprovalAssistanceContextProjection projection(
        Set<AiCapability> capabilities
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
                Instant.parse("2026-07-31T08:00:00Z")
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
            List.of(new InputField(
                "amount",
                "NUMBER",
                "1000.00",
                MaskingDisposition.INCLUDED
            )),
            new ProviderRequirements(
                capabilities,
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
}
