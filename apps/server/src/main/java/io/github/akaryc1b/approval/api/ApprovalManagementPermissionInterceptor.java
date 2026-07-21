package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.security.ApprovalAuthorizationDecision;
import io.github.akaryc1b.approval.security.ApprovalPrincipal;
import io.github.akaryc1b.approval.security.ApprovalResource;
import io.github.akaryc1b.approval.security.ApprovalResponsibilityResolver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Fail-closed management authorization with enterprise responsibility scope. */
public final class ApprovalManagementPermissionInterceptor implements HandlerInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        ApprovalManagementPermissionInterceptor.class
    );
    private static final Pattern SAFE_LOG_VALUE = Pattern.compile("[^A-Za-z0-9_.:@-]");
    private static final int MAX_LOG_VALUE_LENGTH = 128;
    private static final String MANAGEMENT_PATH = "/api/approval/management";
    private static final String UNDECLARED_REQUIREMENT = "undeclared";
    private static final String AUTHORIZATION_METRIC = "approval.management.authorization";
    private static final String REQUEST_TIMER = "approval.management.request.duration";
    private static final String START_ATTRIBUTE =
        ApprovalManagementPermissionInterceptor.class.getName() + ".start";
    private static final String REQUIREMENT_ATTRIBUTE =
        ApprovalManagementPermissionInterceptor.class.getName() + ".requirement";

    private final boolean enforced;
    private final ApprovalResponsibilityResolver responsibilities;
    private final MeterRegistry meters;

    public ApprovalManagementPermissionInterceptor(
        boolean enforced,
        ApprovalResponsibilityResolver responsibilities,
        MeterRegistry meters
    ) {
        this.enforced = enforced;
        this.responsibilities = Objects.requireNonNull(
            responsibilities,
            "responsibilities must not be null"
        );
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
    }

    @Override
    public boolean preHandle(
        HttpServletRequest request,
        HttpServletResponse response,
        Object handler
    ) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        ApprovalManagementPermission permission = permission(handlerMethod);
        if (permission == null) {
            if (isManagementRequest(request)) {
                denyUndeclared(request);
            }
            return true;
        }
        ApprovalManagementPermission.Requirement requirement = permission.value();
        if (!enforced) {
            recordAuthorization(
                requirement.metricTag(),
                "bypassed",
                "bypassed",
                "none",
                resourceMetricTag(permission)
            );
            startObservation(request, requirement);
            return true;
        }
        if (!(request.getUserPrincipal() instanceof ApprovalPrincipal principal)) {
            deny(
                request,
                requirement,
                ApprovalManagementPermissionDeniedException.Reason.UNAUTHENTICATED,
                "unauthenticated",
                "none",
                resourceMetricTag(permission)
            );
            return false;
        }

        ApprovalResource resource;
        try {
            resource = resource(request, permission, principal);
        } catch (IllegalArgumentException exception) {
            deny(
                request,
                requirement,
                ApprovalManagementPermissionDeniedException.Reason.RESOURCE_CONTEXT_INVALID,
                "resource-context",
                "none",
                resourceMetricTag(permission)
            );
            return false;
        }

        ApprovalAuthorizationDecision decision = responsibilities.resolve(
            principal,
            requirement,
            resource
        );
        if (!decision.allowed()) {
            deny(
                request,
                requirement,
                reason(decision),
                decision.code().metricTag(),
                decision.roleMetricTag(),
                resource.level().metricTag()
            );
            return false;
        }
        recordAuthorization(
            requirement.metricTag(),
            "allowed",
            decision.code().metricTag(),
            decision.roleMetricTag(),
            resource.level().metricTag()
        );
        startObservation(request, requirement);
        return true;
    }

    @Override
    public void afterCompletion(
        HttpServletRequest request,
        HttpServletResponse response,
        Object handler,
        Exception exception
    ) {
        Object startValue = request.getAttribute(START_ATTRIBUTE);
        Object requirementValue = request.getAttribute(REQUIREMENT_ATTRIBUTE);
        if (!(startValue instanceof Long start)
            || !(requirementValue
                instanceof ApprovalManagementPermission.Requirement requirement)) {
            return;
        }
        String outcome;
        if (exception != null || response.getStatus() >= 500) {
            outcome = "server_error";
        } else if (response.getStatus() >= 400) {
            outcome = "client_error";
        } else {
            outcome = "success";
        }
        Timer.builder(REQUEST_TIMER)
            .tag("requirement", requirement.metricTag())
            .tag("outcome", outcome)
            .register(meters)
            .record(Duration.ofNanos(Math.max(0L, System.nanoTime() - start)));
    }

    private void deny(
        HttpServletRequest request,
        ApprovalManagementPermission.Requirement requirement,
        ApprovalManagementPermissionDeniedException.Reason reason,
        String decision,
        String role,
        String resourceScope
    ) {
        recordAuthorization(
            requirement.metricTag(),
            "denied",
            decision,
            role,
            resourceScope
        );
        LOGGER.warn(
            "event=APPROVAL_MANAGEMENT_ACCESS_DENIED tenantId={} operatorId={} "
                + "requestId={} requiredAuthority={} reason={} decision={} role={} scope={}",
            safeLogValue(request.getHeader("X-Tenant-Id")),
            operatorIdentity(request),
            safeLogValue(request.getHeader("X-Request-Id")),
            requirement.authority(),
            reason,
            decision,
            role,
            resourceScope
        );
        throw new ApprovalManagementPermissionDeniedException(requirement, reason);
    }

    private void denyUndeclared(HttpServletRequest request) {
        recordAuthorization(
            UNDECLARED_REQUIREMENT,
            "denied",
            "undeclared",
            "none",
            "undeclared"
        );
        LOGGER.error(
            "event=APPROVAL_MANAGEMENT_PERMISSION_UNDECLARED tenantId={} operatorId={} "
                + "requestId={} path={}",
            safeLogValue(request.getHeader("X-Tenant-Id")),
            operatorIdentity(request),
            safeLogValue(request.getHeader("X-Request-Id")),
            safeLogValue(requestPath(request))
        );
        throw new ApprovalManagementPermissionDeniedException(
            ApprovalManagementPermission.Requirement.READ,
            ApprovalManagementPermissionDeniedException.Reason.INSUFFICIENT_PERMISSION
        );
    }

    private void recordAuthorization(
        String requirement,
        String outcome,
        String decision,
        String role,
        String resourceScope
    ) {
        meters.counter(
            AUTHORIZATION_METRIC,
            "requirement",
            requirement,
            "outcome",
            outcome,
            "decision",
            decision,
            "role",
            role,
            "resource_scope",
            resourceScope
        ).increment();
    }

    private static ApprovalManagementPermission permission(HandlerMethod handlerMethod) {
        ApprovalManagementPermission method = AnnotatedElementUtils.findMergedAnnotation(
            handlerMethod.getMethod(),
            ApprovalManagementPermission.class
        );
        if (method != null) {
            return method;
        }
        return AnnotatedElementUtils.findMergedAnnotation(
            handlerMethod.getBeanType(),
            ApprovalManagementPermission.class
        );
    }

    private static ApprovalResource resource(
        HttpServletRequest request,
        ApprovalManagementPermission permission,
        ApprovalPrincipal principal
    ) {
        if (permission.resourceScope()
            == ApprovalManagementPermission.ResourceScope.TENANT) {
            return ApprovalResource.tenant(principal.tenantId());
        }
        String variableName = permission.departmentPathVariable().trim();
        if (variableName.isEmpty()) {
            throw new IllegalArgumentException(
                "department resource requires departmentPathVariable"
            );
        }
        Object value = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(value instanceof Map<?, ?> variables)) {
            throw new IllegalArgumentException("department path variables are unavailable");
        }
        Object departmentId = variables.get(variableName);
        if (!(departmentId instanceof String identifier) || identifier.isBlank()) {
            throw new IllegalArgumentException("department path variable is missing");
        }
        return ApprovalResource.department(principal.tenantId(), identifier);
    }

    private static ApprovalManagementPermissionDeniedException.Reason reason(
        ApprovalAuthorizationDecision decision
    ) {
        return switch (decision.code()) {
            case DENIED_TENANT_MISMATCH, DENIED_RESOURCE_SCOPE ->
                ApprovalManagementPermissionDeniedException.Reason.RESOURCE_SCOPE_DENIED;
            case DENIED_INSUFFICIENT_PERMISSION ->
                ApprovalManagementPermissionDeniedException.Reason.INSUFFICIENT_PERMISSION;
            case ALLOWED_DIRECT_AUTHORITY, ALLOWED_RESPONSIBILITY ->
                throw new IllegalArgumentException("allowed decision cannot be denied");
        };
    }

    private static boolean isManagementRequest(HttpServletRequest request) {
        String path = requestPath(request);
        return path.equals(MANAGEMENT_PATH) || path.startsWith(MANAGEMENT_PATH + '/');
    }

    private static String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private static String operatorIdentity(HttpServletRequest request) {
        if (request.getUserPrincipal() != null) {
            return safeLogValue(request.getUserPrincipal().getName());
        }
        return "missing";
    }

    private static String resourceMetricTag(ApprovalManagementPermission permission) {
        return permission.resourceScope().name().toLowerCase(java.util.Locale.ROOT);
    }

    private static void startObservation(
        HttpServletRequest request,
        ApprovalManagementPermission.Requirement requirement
    ) {
        request.setAttribute(START_ATTRIBUTE, System.nanoTime());
        request.setAttribute(REQUIREMENT_ATTRIBUTE, requirement);
    }

    private static String safeLogValue(String value) {
        if (value == null || value.isBlank()) {
            return "missing";
        }
        String safe = SAFE_LOG_VALUE.matcher(value.trim()).replaceAll("_");
        return safe.length() > MAX_LOG_VALUE_LENGTH
            ? safe.substring(0, MAX_LOG_VALUE_LENGTH)
            : safe;
    }
}
