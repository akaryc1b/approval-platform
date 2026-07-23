package io.github.akaryc1b.approval.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.security.ApprovalIdentityContextFilter.AuthenticationMode;
import io.github.akaryc1b.approval.security.ApprovalPrincipal.AccountStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalIdentityContextFilterTest {

    private static final Instant NOW = Instant.parse("2026-07-21T05:00:00Z");
    private static final String PERMISSION_HEADER = "X-Approval-Trusted-Permissions";

    @Test
    void principalContextOverridesForgedOperatorAndRemovesPermissionHeader() throws Exception {
        ApprovalPrincipal principal = ApprovalPrincipal.active(
            "tenant-a",
            "operator-a",
            Set.of("approval.management.design"),
            NOW.plusSeconds(300)
        );
        MockHttpServletRequest request = approvalRequest();
        request.setUserPrincipal(principal);
        request.addHeader(ApprovalIdentityContextFilter.TENANT_ID_HEADER, "tenant-a");
        request.addHeader(ApprovalIdentityContextFilter.OPERATOR_ID_HEADER, "attacker");
        request.addHeader(PERMISSION_HEADER, "approval.management.admin");
        request.addHeader(ApprovalIdentityContextFilter.REQUEST_ID_HEADER, "client-request");
        request.addHeader(ApprovalIdentityContextFilter.TRACE_ID_HEADER, "client-trace");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> trustedRequest = new AtomicReference<>();

        filter(AuthenticationMode.PRINCIPAL).doFilter(
            request,
            response,
            (filteredRequest, filteredResponse) -> trustedRequest.set(
                (HttpServletRequest) filteredRequest
            )
        );

        HttpServletRequest trusted = trustedRequest.get();
        assertSame(principal, trusted.getUserPrincipal());
        assertEquals("tenant-a", trusted.getHeader(
            ApprovalIdentityContextFilter.TENANT_ID_HEADER
        ));
        assertEquals("operator-a", trusted.getHeader(
            ApprovalIdentityContextFilter.OPERATOR_ID_HEADER
        ));
        assertEquals("client-request", trusted.getHeader(
            ApprovalIdentityContextFilter.REQUEST_ID_HEADER
        ));
        assertEquals("client-trace", trusted.getHeader(
            ApprovalIdentityContextFilter.TRACE_ID_HEADER
        ));
        assertNull(trusted.getHeader(PERMISSION_HEADER));
        assertTrue(trusted.isUserInRole("approval.management.design"));
        assertFalse(trusted.isUserInRole("approval.management.admin"));
        assertEquals("client-request", response.getHeader(
            ApprovalIdentityContextFilter.REQUEST_ID_HEADER
        ));
    }

    @Test
    void crossTenantClaimFailsWithoutDisclosingResourceExistence() throws Exception {
        MockHttpServletRequest request = approvalRequest();
        request.setUserPrincipal(activePrincipal());
        request.addHeader(ApprovalIdentityContextFilter.TENANT_ID_HEADER, "tenant-b");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(AuthenticationMode.PRINCIPAL).doFilter(
            request,
            response,
            (filteredRequest, filteredResponse) -> {
                throw new AssertionError("request must not reach the controller");
            }
        );

        assertEquals(404, response.getStatus());
        assertTrue(response.getContentAsString().contains(
            "APPROVAL_TENANT_CONTEXT_MISMATCH"
        ));
        assertTrue(response.getContentAsString().contains("approval resource was not found"));
    }

    @Test
    void missingDisabledAndExpiredPrincipalsFailClosed() throws Exception {
        assertSecurityFailure(
            approvalRequest(),
            401,
            "APPROVAL_AUTHENTICATION_REQUIRED"
        );

        MockHttpServletRequest disabled = approvalRequest();
        disabled.setUserPrincipal(new ApprovalPrincipal(
            "tenant-a",
            "operator-a",
            Set.of(),
            AccountStatus.DISABLED,
            NOW.plusSeconds(300)
        ));
        assertSecurityFailure(disabled, 403, "APPROVAL_PRINCIPAL_DISABLED");

        MockHttpServletRequest expired = approvalRequest();
        expired.setUserPrincipal(ApprovalPrincipal.active(
            "tenant-a",
            "operator-a",
            Set.of(),
            NOW
        ));
        assertSecurityFailure(expired, 401, "APPROVAL_SESSION_EXPIRED");
    }

    @Test
    void invalidRequestIdIsRejectedAndSafeCorrelationIsGenerated() throws Exception {
        MockHttpServletRequest request = approvalRequest();
        request.setUserPrincipal(activePrincipal());
        request.addHeader(
            ApprovalIdentityContextFilter.REQUEST_ID_HEADER,
            "unsafe\r\nvalue"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(AuthenticationMode.PRINCIPAL).doFilter(
            request,
            response,
            (filteredRequest, filteredResponse) -> {
                throw new AssertionError("request must not reach the controller");
            }
        );

        assertEquals(400, response.getStatus());
        assertEquals("generated-request", response.getHeader(
            ApprovalIdentityContextFilter.REQUEST_ID_HEADER
        ));
        assertTrue(response.getContentAsString().contains("APPROVAL_REQUEST_ID_INVALID"));
        assertFalse(response.getContentAsString().contains("unsafe"));
    }

    @Test
    void explicitLocalModeBuildsPrincipalFromBoundedDevelopmentHeaders() throws Exception {
        MockHttpServletRequest request = approvalRequest();
        request.addHeader(ApprovalIdentityContextFilter.TENANT_ID_HEADER, "tenant-local");
        request.addHeader(ApprovalIdentityContextFilter.OPERATOR_ID_HEADER, "developer");
        request.addHeader(PERMISSION_HEADER, "approval.management.read");
        AtomicReference<HttpServletRequest> trustedRequest = new AtomicReference<>();

        filter(AuthenticationMode.LOCAL_HEADERS).doFilter(
            request,
            new MockHttpServletResponse(),
            (filteredRequest, filteredResponse) -> trustedRequest.set(
                (HttpServletRequest) filteredRequest
            )
        );

        assertTrue(trustedRequest.get().getUserPrincipal() instanceof ApprovalPrincipal);
        assertEquals("tenant-local", trustedRequest.get().getHeader(
            ApprovalIdentityContextFilter.TENANT_ID_HEADER
        ));
        assertEquals("developer", trustedRequest.get().getRemoteUser());
        assertTrue(trustedRequest.get().isUserInRole("approval.management.read"));
        assertNull(trustedRequest.get().getHeader(PERMISSION_HEADER));
    }

    private void assertSecurityFailure(
        MockHttpServletRequest request,
        int expectedStatus,
        String expectedCode
    ) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(AuthenticationMode.PRINCIPAL).doFilter(
            request,
            response,
            (filteredRequest, filteredResponse) -> {
                throw new AssertionError("request must not reach the controller");
            }
        );
        assertEquals(expectedStatus, response.getStatus());
        assertTrue(response.getContentAsString().contains(expectedCode));
    }

    private static ApprovalPrincipal activePrincipal() {
        return ApprovalPrincipal.active(
            "tenant-a",
            "operator-a",
            Set.of("approval.management.read"),
            NOW.plusSeconds(300)
        );
    }

    private static MockHttpServletRequest approvalRequest() {
        return new MockHttpServletRequest("GET", "/api/approval/tasks/pending");
    }

    private static ApprovalIdentityContextFilter filter(AuthenticationMode mode) {
        return new ApprovalIdentityContextFilter(
            mode,
            PERMISSION_HEADER,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new ObjectMapper(),
            () -> "generated-request"
        );
    }
}
