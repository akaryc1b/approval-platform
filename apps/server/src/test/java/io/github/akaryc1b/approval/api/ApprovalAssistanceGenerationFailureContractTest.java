package io.github.akaryc1b.approval.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.UseCase;
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult.AssertionStatus;
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult.Authority;
import io.github.akaryc1b.approval.api.ApprovalAssistanceGenerationContracts.ResultStatus;
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
import static org.mockito.Mockito.when;

class ApprovalAssistanceGenerationFailureContractTest {

    private static final UUID TASK_ID = UUID.fromString(
        "10000000-0000-0000-0000-000000000001"
    );
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void everyProductionFailureUsesStableNoStoreAdvisoryOnlyResponse() {
        for (FailureCase failureCase : failureCases()) {
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
            )).thenReturn(GenerationOutcome.failure(failureCase.status()));
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

            assertEquals(
                failureCase.httpStatus(),
                response.getStatusCode().value(),
                failureCase.status().name()
            );
            assertEquals("no-store", response.getHeaders().getCacheControl());
            assertEquals(failureCase.resultStatus(), response.getBody().status());
            assertEquals(failureCase.code(), response.getBody().code());
            assertEquals(Authority.ADVISORY, response.getBody().authority());
            assertEquals(
                AssertionStatus.UNVERIFIED_ADVISORY,
                response.getBody().assertionStatus()
            );
            assertTrue(response.getBody().needsHumanReview());
            assertFalse(response.getBody().commandAvailable());
            assertFalse(response.getBody().providerSelectable());
            assertFalse(response.getBody().retryAttempted());
            assertFalse(response.getBody().fallbackAttempted());
            assertNull(response.getBody().evidenceId());
            assertNull(response.getBody().advisoryResult());
        }
    }

    private static List<FailureCase> failureCases() {
        return List.of(
            new FailureCase(
                GenerationStatus.DISABLED,
                503,
                ResultStatus.DISABLED,
                "AI_ASSISTANCE_DISABLED"
            ),
            new FailureCase(
                GenerationStatus.NOT_FOUND,
                404,
                ResultStatus.NOT_FOUND,
                "AI_ASSISTANCE_NOT_FOUND"
            ),
            new FailureCase(
                GenerationStatus.STALE_TASK,
                409,
                ResultStatus.STALE_TASK,
                "AI_ASSISTANCE_STALE_TASK"
            ),
            new FailureCase(
                GenerationStatus.POLICY_BLOCKED,
                429,
                ResultStatus.POLICY_BLOCKED,
                "AI_ASSISTANCE_POLICY_BLOCKED"
            ),
            new FailureCase(
                GenerationStatus.PROVIDER_UNAVAILABLE,
                503,
                ResultStatus.PROVIDER_UNAVAILABLE,
                "AI_ASSISTANCE_PROVIDER_UNAVAILABLE"
            ),
            new FailureCase(
                GenerationStatus.TIMEOUT,
                504,
                ResultStatus.TIMEOUT,
                "AI_ASSISTANCE_TIMEOUT"
            ),
            new FailureCase(
                GenerationStatus.INVALID_OUTPUT,
                502,
                ResultStatus.INVALID_OUTPUT,
                "AI_ASSISTANCE_INVALID_OUTPUT"
            ),
            new FailureCase(
                GenerationStatus.UNKNOWN,
                502,
                ResultStatus.UNKNOWN,
                "AI_ASSISTANCE_UNKNOWN"
            ),
            new FailureCase(
                GenerationStatus.EVIDENCE_CONFLICT,
                409,
                ResultStatus.EVIDENCE_CONFLICT,
                "AI_ASSISTANCE_EVIDENCE_CONFLICT"
            ),
            new FailureCase(
                GenerationStatus.EVIDENCE_UNAVAILABLE,
                503,
                ResultStatus.EVIDENCE_UNAVAILABLE,
                "AI_ASSISTANCE_EVIDENCE_UNAVAILABLE"
            )
        );
    }

    private record FailureCase(
        GenerationStatus status,
        int httpStatus,
        ResultStatus resultStatus,
        String code
    ) {
    }
}
