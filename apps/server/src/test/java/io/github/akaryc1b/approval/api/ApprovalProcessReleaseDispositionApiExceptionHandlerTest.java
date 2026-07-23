package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalProcessReleaseDispositionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApprovalProcessReleaseDispositionApiExceptionHandlerTest {

    private final ApprovalProcessReleaseDispositionApiExceptionHandler handler =
        new ApprovalProcessReleaseDispositionApiExceptionHandler();

    @Test
    void returnsStableNotFoundAndConflictResponses() {
        ResponseEntity<ApprovalProcessReleaseDispositionApiExceptionHandler.ApiError> notFound =
            handler.notFound(
                new ApprovalProcessReleaseDispositionService.ProcessReleaseNotFoundException(
                    "Release lifecycle was not found for the tenant"
                ),
                request()
            );
        assertEquals(404, notFound.getStatusCode().value());
        assertEquals("APPROVAL_PROCESS_RELEASE_NOT_FOUND", notFound.getBody().code());
        assertEquals("request-a", notFound.getHeaders().getFirst("X-Request-Id"));

        ResponseEntity<ApprovalProcessReleaseDispositionApiExceptionHandler.ApiError> conflict =
            handler.conflict(
                new ApprovalProcessReleaseDispositionService.DispositionEvidenceConflictException(
                    "Release lifecycle changed concurrently"
                ),
                request()
            );
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals("APPROVAL_RELEASE_DISPOSITION_CONFLICT", conflict.getBody().code());
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "request-a");
        return request;
    }
}
