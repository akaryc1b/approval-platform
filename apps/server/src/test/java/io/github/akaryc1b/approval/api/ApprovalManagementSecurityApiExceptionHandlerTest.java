package io.github.akaryc1b.approval.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalManagementSecurityApiExceptionHandlerTest {

    private final ApprovalManagementSecurityApiExceptionHandler handler =
        new ApprovalManagementSecurityApiExceptionHandler();

    @Test
    void returnsStableNonLeakingPermissionFailure() {
        MockHttpServletRequest request = request();
        ResponseEntity<ApprovalManagementSecurityApiExceptionHandler.ApiError> response =
            handler.permissionDenied(
                new ApprovalManagementPermissionDeniedException(
                    ApprovalManagementPermission.Requirement.PUBLISH,
                    ApprovalManagementPermissionDeniedException.Reason.INSUFFICIENT_PERMISSION
                ),
                request
            );

        assertEquals(403, response.getStatusCode().value());
        assertEquals("request-a", response.getHeaders().getFirst("X-Request-Id"));
        assertEquals(
            "APPROVAL_MANAGEMENT_PERMISSION_DENIED",
            response.getBody().code()
        );
        assertFalse(response.getBody().retryable());
        assertFalse(response.getBody().message().contains("publish"));
    }

    @Test
    void returnsStableRetryableAuditFailure() {
        ResponseEntity<ApprovalManagementSecurityApiExceptionHandler.ApiError> response =
            handler.governance(
                new ApprovalManagementGovernanceException(
                    503,
                    "APPROVAL_MANAGEMENT_AUDIT_UNAVAILABLE",
                    "management authorization evidence could not be recorded",
                    true
                ),
                request()
            );

        assertEquals(503, response.getStatusCode().value());
        assertEquals(
            "APPROVAL_MANAGEMENT_AUDIT_UNAVAILABLE",
            response.getBody().code()
        );
        assertTrue(response.getBody().retryable());
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "request-a");
        return request;
    }
}
