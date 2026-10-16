package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.security.ApprovalPrincipal;
import io.github.akaryc1b.approval.security.DefaultApprovalResponsibilityResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalLegacyPublicationSecurityTest {

    @Test
    void runtimeRootPublishAliasesRequirePublisherCapabilityAndGovernedReason() {
        var interceptor = new ApprovalManagementPermissionInterceptor(
            true, new DefaultApprovalResponsibilityResolver(Clock.systemUTC()), new SimpleMeterRegistry()
        );
        for (Object controller : List.of(new PurchasePaymentController(null, null, null, null),
            new ApprovalFormController(null), new ApprovalUiSchemaController(null))) {
            var method = Arrays.stream(controller.getClass().getDeclaredMethods())
                .filter(value -> value.getName().equals("publish")).findFirst().orElseThrow();
            var handler = new HandlerMethod(controller, method);
            assertThrows(ApprovalManagementPermissionDeniedException.class,
                () -> interceptor.preHandle(request(Set.of(), true), new MockHttpServletResponse(), handler));
            assertThrows(ApprovalManagementGovernanceException.class,
                () -> interceptor.preHandle(request(Set.of("approval.management.publish"), false),
                    new MockHttpServletResponse(), handler));
            assertTrue(interceptor.preHandle(request(Set.of("approval.management.publish"), true),
                new MockHttpServletResponse(), handler));
        }
    }

    private MockHttpServletRequest request(Set<String> authorities, boolean reason) {
        var request = new MockHttpServletRequest("POST", "/api/approval/definitions/purchase-payment/publish");
        request.setUserPrincipal(ApprovalPrincipal.active("tenant-a", "operator", authorities, null));
        request.addHeader("X-Tenant-Id", "tenant-a");
        request.addHeader("X-Operator-Id", "operator");
        request.addHeader("X-Request-Id", "publish-test");
        request.addHeader("Idempotency-Key", "publish-key");
        if (reason) request.addHeader("X-Approval-Operation-Reason", "Publish approved definition");
        return request;
    }
}
