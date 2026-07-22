package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement;
import io.github.akaryc1b.approval.application.port.ApprovalParticipantSlaQuery;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaNotFoundException;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.security.ApprovalAuthorizationDecision;
import io.github.akaryc1b.approval.security.ApprovalEnterpriseRole;
import io.github.akaryc1b.approval.security.ApprovalPrincipal;
import io.github.akaryc1b.approval.security.ApprovalResource;
import io.github.akaryc1b.approval.security.ApprovalResourceScope;
import io.github.akaryc1b.approval.security.ApprovalResponsibilityAssignment;
import io.github.akaryc1b.approval.security.ApprovalResponsibilitySourceType;
import io.github.akaryc1b.approval.security.DefaultApprovalResponsibilityResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalSlaManagementSecurityContractTest {

    private static final Instant NOW = Instant.parse("2026-07-22T06:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String REASON = "Publish the reviewed SLA governance version";

    @Test
    void participantAndUnrelatedEnterpriseRoleCannotAccessSlaManagementHandlers()
        throws Exception {
        ApprovalPrincipal principal = principalWithRoles(
            ApprovalEnterpriseRole.PARTICIPANT,
            ApprovalEnterpriseRole.CONNECTOR_ADMIN
        );
        ApprovalManagementPermissionInterceptor interceptor = interceptor(
            (ignoredPrincipal, ignoredRequirement, ignoredResource, ignoredDecision,
                ignoredReason, ignoredContext) -> {
            }
        );

        for (HandlerMethod handler : managementHandlers()) {
            ApprovalManagementPermissionDeniedException exception = assertThrows(
                ApprovalManagementPermissionDeniedException.class,
                () -> interceptor.preHandle(
                    request(principal),
                    new MockHttpServletResponse(),
                    handler
                ),
                handler.getBeanType().getSimpleName() + '.' + handler.getMethod().getName()
            );
            assertEquals(
                ApprovalManagementPermissionDeniedException.Reason.INSUFFICIENT_PERMISSION,
                exception.reason()
            );
        }
    }

    @Test
    void calendarAndPolicyPublishActivateDeclareHighRiskTenantCapabilities() {
        for (HandlerMethod handler : transitionHandlers()) {
            ApprovalManagementPermission permission = permission(handler);
            Requirement expected = handler.getMethod().getName().startsWith("publish")
                ? Requirement.SLA_PUBLISH
                : Requirement.SLA_ACTIVATE;

            assertEquals(expected, permission.value());
            assertEquals(
                ApprovalManagementPermission.ResourceScope.TENANT,
                permission.resourceScope()
            );
            assertTrue(
                permission.value().requiresReason(),
                handler.getBeanType().getSimpleName() + '.' + handler.getMethod().getName()
                    + " must remain a high-risk operation"
            );
        }
    }

    @Test
    void actualSlaTransitionsRequireReasonAndIdempotencyBeforeAudit() throws Exception {
        List<GovernanceEvidence> evidence = new ArrayList<>();
        ApprovalManagementPermissionInterceptor interceptor = interceptor(
            (principal, requirement, resource, decision, reason, context) -> evidence.add(
                new GovernanceEvidence(
                    principal,
                    requirement,
                    resource,
                    decision,
                    reason,
                    context
                )
            )
        );

        for (HandlerMethod handler : transitionHandlers()) {
            Requirement requirement = permission(handler).value();
            ApprovalPrincipal principal = directPrincipal(requirement);

            ApprovalManagementGovernanceException missingReason = assertThrows(
                ApprovalManagementGovernanceException.class,
                () -> interceptor.preHandle(
                    request(principal),
                    new MockHttpServletResponse(),
                    handler
                )
            );
            assertEquals("APPROVAL_OPERATION_REASON_REQUIRED", missingReason.code());

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
                    handler
                )
            );
            assertEquals("APPROVAL_IDEMPOTENCY_KEY_REQUIRED", missingKey.code());
        }

        assertTrue(evidence.isEmpty(), "invalid evidence must never reach the audit recorder");
    }

    @Test
    void actualSlaTransitionsRecordPrincipalOwnedAuditEvidenceBeforeHandler() throws Exception {
        List<GovernanceEvidence> evidence = new ArrayList<>();
        ApprovalManagementPermissionInterceptor interceptor = interceptor(
            (principal, requirement, resource, decision, reason, context) -> evidence.add(
                new GovernanceEvidence(
                    principal,
                    requirement,
                    resource,
                    decision,
                    reason,
                    context
                )
            )
        );

        int sequence = 0;
        for (HandlerMethod handler : transitionHandlers()) {
            Requirement requirement = permission(handler).value();
            ApprovalPrincipal principal = directPrincipal(requirement);
            String idempotencyKey = "sla-transition-" + ++sequence;
            MockHttpServletRequest request = request(principal);
            request.addHeader(
                ApprovalManagementPermissionInterceptor.OPERATION_REASON_HEADER,
                REASON
            );
            request.addHeader(
                ApprovalManagementPermissionInterceptor.IDEMPOTENCY_KEY_HEADER,
                idempotencyKey
            );

            assertTrue(interceptor.preHandle(
                request,
                new MockHttpServletResponse(),
                handler
            ));

            GovernanceEvidence recorded = evidence.getLast();
            assertEquals("tenant-principal", recorded.principal().tenantId());
            assertEquals("operator-principal", recorded.principal().operatorId());
            assertEquals("tenant-principal", recorded.resource().tenantId());
            assertEquals(requirement, recorded.requirement());
            assertEquals(REASON, recorded.reason());
            assertEquals(idempotencyKey, recorded.context().idempotencyKey());
            assertEquals("request-principal", recorded.context().requestId());
            assertEquals("trace-principal", recorded.context().traceId());
        }

        assertEquals(transitionHandlers().size(), evidence.size());
    }

    @Test
    void auditFailureFailsClosedBeforeActualSlaTransitionHandler() throws Exception {
        ApprovalManagementPermissionInterceptor interceptor = interceptor(
            (ignoredPrincipal, ignoredRequirement, ignoredResource, ignoredDecision,
                ignoredReason, ignoredContext) -> {
                throw new IllegalStateException("audit store unavailable");
            }
        );

        for (HandlerMethod handler : transitionHandlers()) {
            Requirement requirement = permission(handler).value();
            MockHttpServletRequest request = request(directPrincipal(requirement));
            request.addHeader(
                ApprovalManagementPermissionInterceptor.OPERATION_REASON_HEADER,
                REASON
            );
            request.addHeader(
                ApprovalManagementPermissionInterceptor.IDEMPOTENCY_KEY_HEADER,
                "audit-failure-" + handler.getMethod().getName()
            );

            ApprovalManagementGovernanceException exception = assertThrows(
                ApprovalManagementGovernanceException.class,
                () -> interceptor.preHandle(
                    request,
                    new MockHttpServletResponse(),
                    handler
                )
            );
            assertEquals(503, exception.status());
            assertEquals("APPROVAL_MANAGEMENT_AUDIT_UNAVAILABLE", exception.code());
            assertTrue(exception.retryable());
        }
    }

    @Test
    void participantSlaEndpointUsesServerOwnedTenantOperatorAndTaskOnly() throws Exception {
        Method method = method(ApprovalParticipantSlaController.class, "findTaskSla");
        Parameter[] parameters = method.getParameters();
        assertEquals(3, parameters.length);
        assertEquals(
            "X-Tenant-Id",
            parameters[0].getAnnotation(RequestHeader.class).value()
        );
        assertEquals(
            "X-Operator-Id",
            parameters[1].getAnnotation(RequestHeader.class).value()
        );
        assertNotNull(parameters[2].getAnnotation(PathVariable.class));
        for (Parameter parameter : parameters) {
            assertFalse(
                parameter.isAnnotationPresent(RequestParam.class),
                "participant SLA must not accept nominated query identity"
            );
            assertFalse(
                parameter.isAnnotationPresent(RequestBody.class),
                "participant SLA must not accept nominated body identity"
            );
        }

        AtomicReference<String> tenant = new AtomicReference<>();
        AtomicReference<String> user = new AtomicReference<>();
        AtomicReference<UUID> task = new AtomicReference<>();
        ApprovalParticipantSlaQuery query = (tenantId, taskId, userId) -> {
            tenant.set(tenantId);
            task.set(taskId);
            user.set(userId);
            return Optional.empty();
        };
        ApprovalParticipantSlaController controller = new ApprovalParticipantSlaController(
            query,
            CLOCK
        );
        UUID taskId = UUID.fromString("71000000-0000-0000-0000-000000000001");

        assertThrows(
            SlaNotFoundException.class,
            () -> controller.findTaskSla("tenant-principal", "operator-principal", taskId)
        );
        assertEquals("tenant-principal", tenant.get());
        assertEquals("operator-principal", user.get());
        assertEquals(taskId, task.get());
    }

    private static ApprovalManagementPermissionInterceptor interceptor(
        io.github.akaryc1b.approval.security.ApprovalManagementGovernanceRecorder governance
    ) {
        return new ApprovalManagementPermissionInterceptor(
            true,
            new DefaultApprovalResponsibilityResolver(CLOCK),
            governance,
            new SimpleMeterRegistry()
        );
    }

    private static List<HandlerMethod> managementHandlers() {
        List<HandlerMethod> handlers = new ArrayList<>();
        handlers.add(handler(new ApprovalCalendarManagementController(null), "findCalendars"));
        handlers.add(handler(new ApprovalCalendarManagementController(null), "publishCalendarVersion"));
        handlers.add(handler(new ApprovalCalendarManagementController(null), "activateCalendarVersion"));
        handlers.add(handler(new ApprovalSlaPolicyManagementController(null), "findPolicies"));
        handlers.add(handler(new ApprovalSlaPolicyManagementController(null), "publishPolicyVersion"));
        handlers.add(handler(new ApprovalSlaPolicyManagementController(null), "activatePolicyVersion"));
        handlers.add(handler(
            new ApprovalSlaInstanceManagementController(null, null, CLOCK),
            "findInstances"
        ));
        return List.copyOf(handlers);
    }

    private static List<HandlerMethod> transitionHandlers() {
        return List.of(
            handler(new ApprovalCalendarManagementController(null), "publishCalendarVersion"),
            handler(new ApprovalCalendarManagementController(null), "activateCalendarVersion"),
            handler(new ApprovalSlaPolicyManagementController(null), "publishPolicyVersion"),
            handler(new ApprovalSlaPolicyManagementController(null), "activatePolicyVersion")
        );
    }

    private static HandlerMethod handler(Object controller, String methodName) {
        return new HandlerMethod(controller, method(controller.getClass(), methodName));
    }

    private static Method method(Class<?> type, String methodName) {
        return Stream.of(type.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(methodName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "missing handler " + type.getSimpleName() + '.' + methodName
            ));
    }

    private static ApprovalManagementPermission permission(HandlerMethod handler) {
        ApprovalManagementPermission permission = AnnotatedElementUtils.findMergedAnnotation(
            handler.getMethod(),
            ApprovalManagementPermission.class
        );
        assertNotNull(permission);
        return permission;
    }

    private static MockHttpServletRequest request(ApprovalPrincipal principal) {
        MockHttpServletRequest request = new MockHttpServletRequest(
            "POST",
            "/api/approval/management/sla/test"
        );
        request.setUserPrincipal(principal);
        request.addHeader("X-Tenant-Id", "spoofed-tenant");
        request.addHeader("X-Operator-Id", "spoofed-operator");
        request.addHeader("X-Request-Id", "request-principal");
        request.addHeader("X-Trace-Id", "trace-principal");
        request.addHeader("X-Approval-Trusted-Permissions", "approval.management.admin");
        return request;
    }

    private static ApprovalPrincipal directPrincipal(Requirement requirement) {
        return ApprovalPrincipal.active(
            "tenant-principal",
            "operator-principal",
            Set.of(requirement.authority()),
            null
        );
    }

    private static ApprovalPrincipal principalWithRoles(ApprovalEnterpriseRole... roles) {
        Set<ApprovalResponsibilityAssignment> assignments = Stream.of(roles)
            .map(role -> new ApprovalResponsibilityAssignment(
                role,
                ApprovalResponsibilitySourceType.ROLE,
                "role-" + role.name().toLowerCase(java.util.Locale.ROOT),
                ApprovalResourceScope.tenant()
            ))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return ApprovalPrincipal.active(
            "tenant-principal",
            "operator-principal",
            Set.of(),
            assignments,
            null
        );
    }

    private record GovernanceEvidence(
        ApprovalPrincipal principal,
        Requirement requirement,
        ApprovalResource resource,
        ApprovalAuthorizationDecision decision,
        String reason,
        RequestContext context
    ) {
    }
}
