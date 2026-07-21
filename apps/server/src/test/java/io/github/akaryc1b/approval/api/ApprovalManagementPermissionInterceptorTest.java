package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement;
import io.github.akaryc1b.approval.api.ApprovalManagementPermission.ResourceScope;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.security.ApprovalAuthorizationDecision;
import io.github.akaryc1b.approval.security.ApprovalEnterpriseRole;
import io.github.akaryc1b.approval.security.ApprovalManagementGovernanceRecorder;
import io.github.akaryc1b.approval.security.ApprovalPrincipal;
import io.github.akaryc1b.approval.security.ApprovalResource;
import io.github.akaryc1b.approval.security.ApprovalResourceScope;
import io.github.akaryc1b.approval.security.ApprovalResponsibilityAssignment;
import io.github.akaryc1b.approval.security.ApprovalResponsibilitySourceType;
import io.github.akaryc1b.approval.security.DefaultApprovalResponsibilityResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalManagementPermissionInterceptorTest {

    private static final Instant NOW = Instant.parse("2026-07-21T06:00:00Z");
    private static final String REASON = "Publish the reviewed enterprise release";

    private SimpleMeterRegistry meters;
    private List<GovernanceCall> governanceCalls;
    private ApprovalManagementPermissionInterceptor interceptor;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        governanceCalls = new ArrayList<>();
        ApprovalManagementGovernanceRecorder governance = (
            principal,
            requirement,
            resource,
            decision,
            reason,
            context
        ) -> governanceCalls.add(new GovernanceCall(
            principal,
            requirement,
            resource,
            decision,
            reason,
            context
        ));
        interceptor = new ApprovalManagementPermissionInterceptor(
            true,
            new DefaultApprovalResponsibilityResolver(
                Clock.fixed(NOW, ZoneOffset.UTC)
            ),
            governance,
            meters
        );
    }

    @Test
    void directPrincipalAuthorityAllowsExactManagementCapability() throws Exception {
        MockHttpServletRequest request = request(directPrincipal(Requirement.DESIGN));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, handler("design")));
        interceptor.afterCompletion(request, response, handler("design"), null);

        assertEquals(
            1.0,
            authorizationCount(
                "design",
                "allowed",
                "direct-authority",
                "direct-authority",
                "tenant"
            )
        );
        assertEquals(
            1L,
            meters.get("approval.management.request.duration")
                .tag("requirement", "design")
                .tag("outcome", "success")
                .timer()
                .count()
        );
        assertTrue(governanceCalls.isEmpty());
    }

    @Test
    void tenantAdministratorResponsibilityAllowsAndAuditsHighRiskCapability()
        throws Exception {
        MockHttpServletRequest request = highRiskRequest(principal(
            ApprovalEnterpriseRole.TENANT_ADMIN,
            ApprovalResourceScope.tenant()
        ));

        assertTrue(interceptor.preHandle(
            request,
            new MockHttpServletResponse(),
            handler("publish")
        ));
        assertEquals(
            1.0,
            authorizationCount(
                "publish",
                "allowed",
                "responsibility",
                "tenant-admin",
                "tenant"
            )
        );
        assertEquals(1, governanceCalls.size());
        GovernanceCall call = governanceCalls.getFirst();
        assertEquals(Requirement.PUBLISH, call.requirement());
        assertEquals(REASON, call.reason());
        assertEquals("idempotency-a", call.context().idempotencyKey());
        assertEquals("request-a", call.context().requestId());
        assertEquals("trace-a", call.context().traceId());
    }

    @Test
    void departmentAdministratorIsRestrictedToDeclaredMatchingDepartment()
        throws Exception {
        ApprovalPrincipal principal = principal(
            ApprovalEnterpriseRole.DEPARTMENT_APPROVAL_ADMIN,
            ApprovalResourceScope.departments(Set.of("department-a"))
        );
        MockHttpServletRequest matching = highRiskRequest(principal);
        matching.setAttribute(
            HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
            Map.of("departmentId", "department-a")
        );
        assertTrue(interceptor.preHandle(
            matching,
            new MockHttpServletResponse(),
            handler("departmentTransfer")
        ));
        assertEquals("department-a", governanceCalls.getFirst().resource().departmentId());

        MockHttpServletRequest wrongDepartment = request(principal);
        wrongDepartment.setAttribute(
            HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
            Map.of("departmentId", "department-b")
        );
        ApprovalManagementPermissionDeniedException wrongDepartmentException =
            assertThrows(
                ApprovalManagementPermissionDeniedException.class,
                () -> interceptor.preHandle(
                    wrongDepartment,
                    new MockHttpServletResponse(),
                    handler("departmentTransfer")
                )
            );
        assertEquals(
            ApprovalManagementPermissionDeniedException.Reason.RESOURCE_SCOPE_DENIED,
            wrongDepartmentException.reason()
        );

        ApprovalManagementPermissionDeniedException tenantWideException =
            assertThrows(
                ApprovalManagementPermissionDeniedException.class,
                () -> interceptor.preHandle(
                    request(principal),
                    new MockHttpServletResponse(),
                    handler("tenantTransfer")
                )
            );
        assertEquals(
            ApprovalManagementPermissionDeniedException.Reason.RESOURCE_SCOPE_DENIED,
            tenantWideException.reason()
        );
    }

    @Test
    void highRiskOperationRequiresSafeReasonAndIdempotency() throws Exception {
        ApprovalPrincipal principal = directPrincipal(Requirement.PUBLISH);

        ApprovalManagementGovernanceException missingReason = assertThrows(
            ApprovalManagementGovernanceException.class,
            () -> interceptor.preHandle(
                request(principal),
                new MockHttpServletResponse(),
                handler("publish")
            )
        );
        assertEquals("APPROVAL_OPERATION_REASON_REQUIRED", missingReason.code());

        MockHttpServletRequest invalidReason = request(principal);
        invalidReason.addHeader(
            ApprovalManagementPermissionInterceptor.OPERATION_REASON_HEADER,
            "invalid\nreason"
        );
        invalidReason.addHeader(
            ApprovalManagementPermissionInterceptor.IDEMPOTENCY_KEY_HEADER,
            "idempotency-a"
        );
        ApprovalManagementGovernanceException malformedReason = assertThrows(
            ApprovalManagementGovernanceException.class,
            () -> interceptor.preHandle(
                invalidReason,
                new MockHttpServletResponse(),
                handler("publish")
            )
        );
        assertEquals("APPROVAL_OPERATION_REASON_INVALID", malformedReason.code());

        MockHttpServletRequest missingIdempotency = request(principal);
        missingIdempotency.addHeader(
            ApprovalManagementPermissionInterceptor.OPERATION_REASON_HEADER,
            REASON
        );
        ApprovalManagementGovernanceException missingKey = assertThrows(
            ApprovalManagementGovernanceException.class,
            () -> interceptor.preHandle(
                missingIdempotency,
                new MockHttpServletResponse(),
                handler("publish")
            )
        );
        assertEquals("APPROVAL_IDEMPOTENCY_KEY_REQUIRED", missingKey.code());
        assertTrue(governanceCalls.isEmpty());
    }

    @Test
    void participantResponsibilityDoesNotCreateManagementCapability() throws Exception {
        ApprovalPrincipal participant = principal(
            ApprovalEnterpriseRole.PARTICIPANT,
            ApprovalResourceScope.tenant()
        );
        assertFalse(participant.hasAuthority(Requirement.READ.authority()));
        ApprovalManagementPermissionDeniedException exception = assertThrows(
            ApprovalManagementPermissionDeniedException.class,
            () -> interceptor.preHandle(
                request(participant),
                new MockHttpServletResponse(),
                handler("read")
            )
        );
        assertEquals(
            ApprovalManagementPermissionDeniedException.Reason.INSUFFICIENT_PERMISSION,
            exception.reason()
        );
        assertEquals(
            "operator is not permitted to perform this approval management operation",
            exception.getMessage()
        );
    }

    @Test
    void malformedDepartmentResourceAndMissingPrincipalFailClosed() throws Exception {
        ApprovalPrincipal departmentAdmin = principal(
            ApprovalEnterpriseRole.DEPARTMENT_APPROVAL_ADMIN,
            ApprovalResourceScope.departments(Set.of("department-a"))
        );
        ApprovalManagementPermissionDeniedException missingResource = assertThrows(
            ApprovalManagementPermissionDeniedException.class,
            () -> interceptor.preHandle(
                request(departmentAdmin),
                new MockHttpServletResponse(),
                handler("departmentTransfer")
            )
        );
        assertEquals(
            ApprovalManagementPermissionDeniedException.Reason.RESOURCE_CONTEXT_INVALID,
            missingResource.reason()
        );

        ApprovalManagementPermissionDeniedException missingPrincipal = assertThrows(
            ApprovalManagementPermissionDeniedException.class,
            () -> interceptor.preHandle(
                request(null),
                new MockHttpServletResponse(),
                handler("design")
            )
        );
        assertEquals(
            ApprovalManagementPermissionDeniedException.Reason.UNAUTHENTICATED,
            missingPrincipal.reason()
        );
    }

    @Test
    void disabledBoundaryIsObservableAndParticipantHandlerIsIgnored() throws Exception {
        ApprovalManagementPermissionInterceptor bypassed =
            new ApprovalManagementPermissionInterceptor(
                false,
                new DefaultApprovalResponsibilityResolver(
                    Clock.fixed(NOW, ZoneOffset.UTC)
                ),
                meters
            );
        assertTrue(bypassed.preHandle(
            request(null),
            new MockHttpServletResponse(),
            handler("design")
        ));
        assertEquals(
            1.0,
            authorizationCount("design", "bypassed", "bypassed", "none", "tenant")
        );

        MockHttpServletRequest participant = request(null);
        participant.setRequestURI("/api/approval/instances/instance-a");
        assertTrue(interceptor.preHandle(
            participant,
            new MockHttpServletResponse(),
            runtimeHandler()
        ));
    }

    @Test
    void undeclaredManagementHandlerIsDeniedEvenWhenBoundaryIsDisabled() throws Exception {
        for (boolean enforced : new boolean[] {true, false}) {
            ApprovalManagementPermissionInterceptor boundary =
                new ApprovalManagementPermissionInterceptor(
                    enforced,
                    new DefaultApprovalResponsibilityResolver(
                        Clock.fixed(NOW, ZoneOffset.UTC)
                    ),
                    meters
                );
            MockHttpServletRequest management = request(null);
            management.setRequestURI("/api/approval/management/undeclared");
            assertThrows(
                ApprovalManagementPermissionDeniedException.class,
                () -> boundary.preHandle(
                    management,
                    new MockHttpServletResponse(),
                    runtimeHandler()
                )
            );
        }
        assertEquals(
            2.0,
            authorizationCount(
                "undeclared",
                "denied",
                "undeclared",
                "none",
                "undeclared"
            )
        );
    }

    private double authorizationCount(
        String requirement,
        String outcome,
        String decision,
        String role,
        String resourceScope
    ) {
        return meters.get("approval.management.authorization")
            .tag("requirement", requirement)
            .tag("outcome", outcome)
            .tag("decision", decision)
            .tag("role", role)
            .tag("resource_scope", resourceScope)
            .counter()
            .count();
    }

    private static MockHttpServletRequest highRiskRequest(ApprovalPrincipal principal) {
        MockHttpServletRequest request = request(principal);
        request.addHeader(
            ApprovalManagementPermissionInterceptor.OPERATION_REASON_HEADER,
            REASON
        );
        request.addHeader(
            ApprovalManagementPermissionInterceptor.IDEMPOTENCY_KEY_HEADER,
            "idempotency-a"
        );
        return request;
    }

    private static MockHttpServletRequest request(ApprovalPrincipal principal) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/approval/management/test");
        request.addHeader("X-Tenant-Id", "tenant-a");
        request.addHeader("X-Operator-Id", "operator-a");
        request.addHeader("X-Request-Id", "request-a");
        request.addHeader("X-Trace-Id", "trace-a");
        if (principal != null) {
            request.setUserPrincipal(principal);
        }
        return request;
    }

    private static ApprovalPrincipal directPrincipal(Requirement requirement) {
        return ApprovalPrincipal.active(
            "tenant-a",
            "operator-a",
            Set.of(requirement.authority()),
            null
        );
    }

    private static ApprovalPrincipal principal(
        ApprovalEnterpriseRole role,
        ApprovalResourceScope scope
    ) {
        return ApprovalPrincipal.active(
            "tenant-a",
            "operator-a",
            Set.of(),
            Set.of(new ApprovalResponsibilityAssignment(
                role,
                ApprovalResponsibilitySourceType.ROLE,
                "role-" + role.name().toLowerCase(java.util.Locale.ROOT),
                scope
            )),
            null
        );
    }

    private static HandlerMethod handler(String methodName) throws NoSuchMethodException {
        Method method = ManagementController.class.getDeclaredMethod(methodName);
        return new HandlerMethod(new ManagementController(), method);
    }

    private static HandlerMethod runtimeHandler() throws NoSuchMethodException {
        Method method = RuntimeController.class.getDeclaredMethod("start");
        return new HandlerMethod(new RuntimeController(), method);
    }

    private record GovernanceCall(
        ApprovalPrincipal principal,
        Requirement requirement,
        ApprovalResource resource,
        ApprovalAuthorizationDecision decision,
        String reason,
        RequestContext context
    ) {
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

        @ApprovalManagementPermission(Requirement.TRANSFER)
        public void tenantTransfer() {
        }

        @ApprovalManagementPermission(
            value = Requirement.TRANSFER,
            resourceScope = ResourceScope.DEPARTMENT,
            departmentPathVariable = "departmentId"
        )
        public void departmentTransfer() {
        }
    }

    static class RuntimeController {
        public void start() {
        }
    }
}
