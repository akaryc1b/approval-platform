package io.github.akaryc1b.approval.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.UseCase;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidenceStore.StoreDisposition;
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.api.ApprovalAssistanceGenerationService.GenerationOutcome;
import io.github.akaryc1b.approval.api.ApprovalAssistanceGenerationService.GenerationStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApprovalAssistanceGenerationControllerTest {

    private static final UUID TASK_ID = UUID.fromString(
        "10000000-0000-0000-0000-000000000001"
    );
    private static final UUID EVIDENCE_ID = UUID.fromString(
        "90000000-0000-0000-0000-000000000001"
    );
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void unknownClientAiConfigurationFieldIsRejectedBeforeService() {
        ApprovalAssistanceGenerationService service = mock(
            ApprovalAssistanceGenerationService.class
        );
        ApprovalAssistanceGenerationController controller =
            new ApprovalAssistanceGenerationController(service);
        var body = MAPPER.createObjectNode()
            .put("useCase", "SUMMARY")
            .put("model", "client-controlled");

        var response = controller.generate(
            "tenant-a",
            "operator-a",
            "request-a",
            "trace-a",
            TASK_ID,
            body
        );

        assertEquals(400, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("AI_ASSISTANCE_REQUEST_INVALID", response.getBody().code());
        assertFalse(response.getBody().commandAvailable());
        assertFalse(response.getBody().providerSelectable());
        assertNull(response.getBody().advisoryResult());
        verifyNoInteractions(service);
    }

    @Test
    void disabledRuntimeReturnsStableNoStoreAdvisoryOnlyFailure() {
        ApprovalAssistanceGenerationService service = mock(
            ApprovalAssistanceGenerationService.class
        );
        when(service.generate(
            "tenant-a",
            "operator-a",
            "request-a",
            "trace-a",
            TASK_ID,
            UseCase.SUMMARY
        )).thenReturn(GenerationOutcome.failure(GenerationStatus.DISABLED));
        ApprovalAssistanceGenerationController controller =
            new ApprovalAssistanceGenerationController(service);

        var response = controller.generate(
            "tenant-a",
            "operator-a",
            "request-a",
            "trace-a",
            TASK_ID,
            MAPPER.createObjectNode().put("useCase", "SUMMARY")
        );

        assertEquals(503, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("AI_ASSISTANCE_DISABLED", response.getBody().code());
        assertTrue(response.getBody().needsHumanReview());
        assertFalse(response.getBody().retryAttempted());
        assertFalse(response.getBody().fallbackAttempted());
        assertNull(response.getBody().evidenceId());
    }

    @Test
    void lowConfidenceResultReturnsOnlySafeAdvisoryAndEvidenceId() {
        ApprovalAssistanceGenerationService service = mock(
            ApprovalAssistanceGenerationService.class
        );
        when(service.generate(
            "tenant-a",
            "operator-a",
            "request-a",
            "trace-a",
            TASK_ID,
            UseCase.SUMMARY
        )).thenReturn(new GenerationOutcome(
            GenerationStatus.LOW_CONFIDENCE,
            advisory(),
            EVIDENCE_ID,
            StoreDisposition.STORED,
            AiOutcomeClassification.LOW_CONFIDENCE
        ));
        ApprovalAssistanceGenerationController controller =
            new ApprovalAssistanceGenerationController(service);

        var response = controller.generate(
            "tenant-a",
            "operator-a",
            "request-a",
            "trace-a",
            TASK_ID,
            MAPPER.createObjectNode().put("useCase", "SUMMARY")
        );

        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("AI_ASSISTANCE_LOW_CONFIDENCE", response.getBody().code());
        assertEquals(EVIDENCE_ID, response.getBody().evidenceId());
        assertEquals("Bounded advisory", response.getBody().advisoryResult().summary());
        assertTrue(response.getBody().needsHumanReview());
        assertFalse(response.getBody().commandAvailable());
        assertFalse(response.getBody().retryAttempted());
        assertFalse(response.getBody().fallbackAttempted());
    }

    private static AiAdvisoryResult advisory() {
        return new AiAdvisoryResult(
            "Bounded advisory",
            List.of(new AiAdvisoryResult.Observation(
                "observation-1",
                "Review the submitted supplier field.",
                List.of("evidence-1")
            )),
            List.of(),
            List.of(),
            List.of(),
            List.of(new AiAdvisoryResult.EvidenceReference(
                "evidence-1",
                "supplier",
                "Provider-safe supplier field"
            )),
            new AiAdvisoryResult.Confidence(
                0.40d,
                AiAdvisoryResult.ConfidenceBand.LOW
            ),
            List.of("This output is unverified and requires human review."),
            true,
            versions(),
            AiAdvisoryResult.Authority.ADVISORY,
            AiAdvisoryResult.AssertionStatus.UNVERIFIED_ADVISORY
        );
    }

    private static AiVersionReferences versions() {
        return new AiVersionReferences(
            new AiVersionReferences.ProviderVersion("openai-responses", "responses-v1"),
            new AiVersionReferences.ModelVersion(
                "openai-responses",
                "gpt-5-mini",
                "2025-08-07"
            ),
            new AiVersionReferences.PromptTemplateVersion(
                "approval-summary",
                "p6-e-v1",
                "a".repeat(64)
            ),
            AiVersionReferences.KnowledgeSourceVersion.none(),
            new AiVersionReferences.PolicyVersion(
                "approval-assistance-production",
                "p6-e-v1",
                "b".repeat(64)
            ),
            new AiVersionReferences.OutputSchemaVersion("approval-assistance", 1)
        );
    }
}
