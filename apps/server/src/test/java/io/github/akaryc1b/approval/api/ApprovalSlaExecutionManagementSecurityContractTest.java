package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement;
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
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalSlaExecutionManagementSecurityContractTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-07-22T10:00:00Z"),
        ZoneOffset.UTC
    );
    private static final String REASON = "Replay the reviewed dead SLA execution intent";

    @Test
    void participantAndUnrelatedEnterpriseRoleCannotAccessExecutionManagementHandlers() {
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
                handler.getMethod().getName()
            );
            assertEquals(
                ApprovalManagementPermissionDeniedException.Reason.INSUFFICIENT_PERMISSION,
                exception.reason()
            );
        }
    }

    @Test
    void readsUseSlaReadAndReplayUsesHighRiskTenantCapability() {
        for (HandlerMethod handler : readHandlers()) {
            ApprovalManagementPermission permission = permission(handler);
            assertEquals(Requirement.SLA_READ, permission.value());
            assertEquals(
                ApprovalManagementPermission.ResourceScope.TENANT,
                permission.resourceScope()
            );
            assertFalse(permission.value().requiresReason());
        }

        ApprovalManagementPermission replay = permission(replayHandler());
        assertEquals(Requirement.OPERATIONAL_FAILURE_REPLAY, replay.value());
        assertEquals(
            ApprovalManagementPermission.ResourceScope.TENANT,
            replay.resourceScope()
        );
        assertTrue(replay.value().requiresReason());
    }

    @Test
    void replayRequiresReasonAndIdempotencyBeforeAudit() {
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
        HandlerMethod replay = replayHandler();
        ApprovalPrincipal principal = directPrincipal(Requirement.OPERATIONAL_FAILURE_REPLAY);

        ApprovalManagementGovernanceException missingReason = assertThrows(
            ApprovalManagementGovernanceException.class,
            () -> interceptor.preHandle(
                request(principal),
                new MockHttpServletResponse(),
                replay
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
                replay
            )
        );
        assertEquals("APPROVAL_IDEMPOTENCY_KEY_REQUIRED", missingKey.code());
        assertTrue(evidence.isEmpty());
    }

    @Test
    void replayAuditFailureFailsClosedBeforeHandler() {
        ApprovalManagementPermissionInterceptor interceptor = interceptor(
            (ignoredPrincipal, ignoredRequirement, ignoredResource, ignoredDecision,
                ignoredReason, ignoredContext) -> {
                throw new IllegalStateException("audit store unavailable");
            }
        );
        MockHttpServletRequest request = governedReplayRequest(
            directPrincipal(Requirement.OPERATIONAL_FAILURE_REPLAY),
            "replay-audit-failure"
        );

        ApprovalManagementGovernanceException exception = assertThrows(
            ApprovalManagementGovernanceException.class,
            () -> interceptor.preHandle(
                request,
                new MockHttpServletResponse(),
                replayHandler()
            )
        );
        assertEquals(503, exception.status());
        assertEquals("APPROVAL_MANAGEMENT_AUDIT_UNAVAILABLE", exception.code());
        assertTrue(exception.retryable());
    }

    @Test
    void validReplayGovernanceEvidenceUsesPrincipalOwnedIdentity() throws Exception {
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
        MockHttpServletRequest request = governedReplayRequest(
            directPrincipal(Requirement.OPERATIONAL_FAILURE_REPLAY),
            "replay-governed-1"
        );

        assertTrue(interceptor.preHandle(
            request,
            new MockHttpServletResponse(),
            replayHandler()
        ));
        GovernanceEvidence recorded = evidence.getFirst();
        assertEquals("tenant-principal", recorded.principal().tenantId());
        assertEquals("operator-principal", recorded.principal().operatorId());
        assertEquals("tenant-principal", recorded.resource().tenantId());
        assertEquals(Requirement.OPERATIONAL_FAILURE_REPLAY, recorded.requirement());
        assertEquals(REASON, recorded.reason());
        assertEquals("replay-governed-1", recorded.context().idempotencyKey());
        assertEquals("request-principal", recorded.context().requestId());
        assertEquals("trace-principal", recorded.context().traceId());
    }

    @Test
    void replayEndpointCannotNominateTenantWorkerUserOrAuditTarget() {
        Method replay = method(ApprovalSlaExecutionManagementController.class, "replay");
        Parameter[] parameters = replay.getParameters();
        assertEquals(7, parameters.length);
        for (Parameter parameter : parameters) {
            assertFalse(parameter.isAnnotationPresent(RequestBody.class));
            assertFalse(parameter.isAnnotationPresent(RequestParam.class));
        }
        assertTrue(Stream.of(parameters).anyMatch(
            parameter -> parameter.isAnnotationPresent(PathVariable.class)
        ));
        Set<String> headers = Stream.of(parameters)
            .map(parameter -> parameter.getAnnotation(RequestHeader.class))
            .filter(java.util.Objects::nonNull)
            .map(RequestHeader::value)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertEquals(Set.of(
            "X-Tenant-Id",
            "X-Operator-Id",
            "X-Request-Id",
            "X-Trace-Id",
            "X-Approval-Operation-Reason",
            "Idempotency-Key"
        ), headers);
        assertFalse(headers.stream().anyMatch(header -> header.contains("Worker")));
        assertFalse(headers.stream().anyMatch(header -> header.contains("Audit")));
        assertFalse(headers.stream().anyMatch(header -> header.contains("User-Id")));
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
        List<HandlerMethod> handlers = new ArrayList<>(readHandlers());
        handlers.add(replayHandler());
        return List.copyOf(handlers);
    }

    private static List<HandlerMethod> readHandlers() {
        ApprovalSlaExecutionManagementController controller = controller();
        return List.of(
            handler(controller, "summary"),
            handler(controller, "findIntents"),
            handler(controller, "findIntent")
        );
    }

    private static HandlerMethod replayHandler() {
        return handler(controller(), "replay");
    }

    private static ApprovalSlaExecutionManagementController controller() {
        return new ApprovalSlaExecutionManagementController(null, CLOCK);
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

    private static MockHttpServletRequest governedReplayRequest(
        ApprovalPrincipal principal,
        String idempotencyKey
    ) {
        MockHttpServletRequest request = request(principal);
        request.addHeader(
            ApprovalManagementPermissionInterceptor.OPERATION_REASON_HEADER,
            REASON
        );
        request.addHeader(
            ApprovalManagementPermissionInterceptor.IDEMPOTENCY_KEY_HEADER,
            idempotencyKey
        );
        return request;
    }

    private static MockHttpServletRequest request(ApprovalPrincipal principal) {
        MockHttpServletRequest request = new MockHttpServletRequest(
            "POST",
            "/api/approval/management/sla/executions/test/replay"
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
