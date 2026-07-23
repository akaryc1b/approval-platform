package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApprovalProcessReleaseMigrationAssessmentApiExceptionHandlerTest {

    private final ApprovalProcessReleaseMigrationAssessmentApiExceptionHandler handler =
        new ApprovalProcessReleaseMigrationAssessmentApiExceptionHandler();

    @Test
    void returnsStableNotFoundAndConflictResponses() {
        ResponseEntity<ApprovalProcessReleaseMigrationAssessmentApiExceptionHandler.ApiError>
            notFound = handler.notFound(
                new ApprovalProcessReleaseMigrationAssessmentService
                    .MigrationEvidenceNotFoundException("source release was not found"),
                request()
            );
        assertEquals(404, notFound.getStatusCode().value());
        assertEquals(
            "APPROVAL_RELEASE_MIGRATION_EVIDENCE_NOT_FOUND",
            notFound.getBody().code()
        );
        assertEquals("request-a", notFound.getHeaders().getFirst("X-Request-Id"));

        ResponseEntity<ApprovalProcessReleaseMigrationAssessmentApiExceptionHandler.ApiError>
            conflict = handler.conflict(
                new ApprovalProcessReleaseMigrationAssessmentService
                    .MigrationEvidenceConflictException("binding evidence conflicts"),
                request()
            );
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals(
            "APPROVAL_RELEASE_MIGRATION_EVIDENCE_CONFLICT",
            conflict.getBody().code()
        );
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "request-a");
        return request;
    }
}
