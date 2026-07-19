package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement;
import io.github.akaryc1b.approval.api.ApprovalManagementPermissionInterceptor.AuthoritySource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalManagementPermissionInterceptorTest {

    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
    }

    @Test
    void principalAuthorityAllowsExactManagementCapability() throws Exception {
        ApprovalManagementPermissionInterceptor interceptor = interceptor(
            true,
            AuthoritySource.PRINCIPAL
        );
        MockHttpServletRequest request = request();
        request.setUserPrincipal(principal("designer"));
        request.addUserRole(Requirement.DESIGN.authority());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, handler("design")));
        interceptor.afterCompletion(request, response, handler("design"), null);

        assertEquals(1.0, authorizationCount("design", "allowed"));
        assertEquals(
            1L,
            meters.get("approval.management.request.duration")
                .tag("requirement", "design")
                .tag("outcome", "success")
                .timer()
                .count()
        );
    }

    @Test
    void adminAuthorityAllowsAllManagementCapabilities() throws Exception {
        ApprovalManagementPermissionInterceptor interceptor = interceptor(
            true,
            AuthoritySource.PRINCIPAL
        );
        MockHttpServletRequest request = request();
        request.setUserPrincipal(principal("administrator"));
        request.addUserRole(ApprovalManagementPermission.ADMIN_AUTHORITY);

        assertTrue(interceptor.preHandle(
            request,
            new MockHttpServletResponse(),
            handler("publish")
        ));
        assertEquals(1.0, authorizationCount("publish", "allowed"));
    }

    @Test
    void missingPrincipalAndWrongRoleAreDeniedWithoutLeakingAuthorities() throws Exception {
        ApprovalManagementPermissionInterceptor interceptor = interceptor(
            true,
            AuthoritySource.PRINCIPAL
        );
        MockHttpServletRequest missing = request();
        assertThrows(
            ApprovalManagementPermissionDeniedException.class,
            () -> interceptor.preHandle(
                missing,
                new MockHttpServletResponse(),
                handler("design")
            )
        );

        MockHttpServletRequest wrong = request();
        wrong.setUserPrincipal(principal("reader"));
        wrong.addUserRole(Requirement.READ.authority());
        ApprovalManagementPermissionDeniedException exception = assertThrows(
            ApprovalManagementPermissionDeniedException.class,
            () -> interceptor.preHandle(
                wrong,
                new MockHttpServletResponse(),
                handler("design")
            )
        );
        assertEquals(
            "operator is not permitted to perform this approval management operation",
            exception.getMessage()
        );
        assertEquals(2.0, authorizationCount("design", "denied"));
    }

    @Test
    void trustedHeaderModeAcceptsOnlyBoundedCanonicalAuthorities() throws Exception {
        ApprovalManagementPermissionInterceptor interceptor = interceptor(
            true,
            AuthoritySource.TRUSTED_HEADER
        );
        MockHttpServletRequest allowed = request();
        allowed.addHeader(
            ApprovalManagementPermissionInterceptor.TRUSTED_PERMISSION_HEADER,
            Requirement.READ.authority() + "," + Requirement.DESIGN.authority()
        );
        assertTrue(interceptor.preHandle(
            allowed,
            new MockHttpServletResponse(),
            handler("design")
        ));

        for (String invalid : new String[] {
            "",
            "APPROVAL.MANAGEMENT.DESIGN",
            "approval.management.design,",
            "approval.management.design<script>"
        }) {
            MockHttpServletRequest request = request();
            request.addHeader(
                ApprovalManagementPermissionInterceptor.TRUSTED_PERMISSION_HEADER,
                invalid
            );
            assertThrows(
                ApprovalManagementPermissionDeniedException.class,
                () -> interceptor.preHandle(
                    request,
                    new MockHttpServletResponse(),
                    handler("design")
                )
            );
        }
    }

    @Test
    void disabledBoundaryIsObservableAndUnannotatedRuntimeHandlerIsIgnored()
        throws Exception {
        ApprovalManagementPermissionInterceptor interceptor = interceptor(
            false,
            AuthoritySource.PRINCIPAL
        );
        assertTrue(interceptor.preHandle(
            request(),
            new MockHttpServletResponse(),
            handler("design")
        ));
        assertEquals(1.0, authorizationCount("design", "bypassed"));

        ApprovalManagementPermissionInterceptor enforced = interceptor(
            true,
            AuthoritySource.PRINCIPAL
        );
        assertTrue(enforced.preHandle(
            request(),
            new MockHttpServletResponse(),
            runtimeHandler()
        ));
    }

    private ApprovalManagementPermissionInterceptor interceptor(
        boolean enforced,
        AuthoritySource source
    ) {
        return new ApprovalManagementPermissionInterceptor(
            enforced,
            source,
            ApprovalManagementPermissionInterceptor.TRUSTED_PERMISSION_HEADER,
            meters
        );
    }

    private double authorizationCount(String requirement, String outcome) {
        return meters.get("approval.management.authorization")
            .tag("requirement", requirement)
            .tag("outcome", outcome)
            .counter()
            .count();
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "tenant-a");
        request.addHeader("X-Operator-Id", "operator-a");
        request.addHeader("X-Request-Id", "request-a");
        return request;
    }

    private static Principal principal(String name) {
        return () -> name;
    }

    private static HandlerMethod handler(String methodName) throws NoSuchMethodException {
        Method method = ManagementController.class.getDeclaredMethod(methodName);
        return new HandlerMethod(new ManagementController(), method);
    }

    private static HandlerMethod runtimeHandler() throws NoSuchMethodException {
        Method method = RuntimeController.class.getDeclaredMethod("start");
        return new HandlerMethod(new RuntimeController(), method);
    }

    @ApprovalManagementPermission(Requirement.READ)
    static class ManagementController {
        public void read() {
        }

        @ApprovalManagementPermission(Requirement.DESIGN)
        public void design() {
        }

        @ApprovalManagementPermission(Requirement.PUBLISH)
        public void publish() {
        }
    }

    static class RuntimeController {
        public void start() {
        }
    }
}
