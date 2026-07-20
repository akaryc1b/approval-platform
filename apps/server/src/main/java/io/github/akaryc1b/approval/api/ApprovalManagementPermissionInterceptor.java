package io.github.akaryc1b.approval.api;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Fail-closed management authorization with low-cardinality observability. */
public final class ApprovalManagementPermissionInterceptor implements HandlerInterceptor {

    static final String TRUSTED_PERMISSION_HEADER = "X-Approval-Trusted-Permissions";

    private static final Logger LOGGER = LoggerFactory.getLogger(
        ApprovalManagementPermissionInterceptor.class
    );
    private static final Pattern AUTHORITY = Pattern.compile("[a-z][a-z0-9.-]{2,63}");
    private static final Pattern SAFE_LOG_VALUE = Pattern.compile("[^A-Za-z0-9_.:@-]");
    private static final int MAX_HEADER_LENGTH = 2_048;
    private static final int MAX_AUTHORITIES = 32;
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
    private final AuthoritySource authoritySource;
    private final String trustedPermissionHeader;
    private final MeterRegistry meters;

    public ApprovalManagementPermissionInterceptor(
        boolean enforced,
        AuthoritySource authoritySource,
        String trustedPermissionHeader,
        MeterRegistry meters
    ) {
        this.enforced = enforced;
        this.authoritySource = Objects.requireNonNull(authoritySource);
        this.trustedPermissionHeader = requireText(
            trustedPermissionHeader,
            "trustedPermissionHeader"
        );
        this.meters = Objects.requireNonNull(meters);
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
            recordAuthorization(requirement, "bypassed");
            startObservation(request, requirement);
            return true;
        }
        boolean allowed = switch (authoritySource) {
            case PRINCIPAL -> principalAllows(request, requirement);
            case TRUSTED_HEADER -> trustedHeaderAllows(request, requirement);
        };
        if (!allowed) {
            deny(
                request,
                requirement,
                ApprovalManagementPermissionDeniedException.Reason.INSUFFICIENT_PERMISSION
            );
        }
        recordAuthorization(requirement, "allowed");
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
            || !(requirementValue instanceof ApprovalManagementPermission.Requirement requirement)) {
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

    private boolean principalAllows(
        HttpServletRequest request,
        ApprovalManagementPermission.Requirement requirement
    ) {
        if (request.getUserPrincipal() == null) {
            deny(
                request,
                requirement,
                ApprovalManagementPermissionDeniedException.Reason.UNAUTHENTICATED
            );
        }
        return request.isUserInRole(ApprovalManagementPermission.ADMIN_AUTHORITY)
            || request.isUserInRole(requirement.authority());
    }

    private boolean trustedHeaderAllows(
        HttpServletRequest request,
        ApprovalManagementPermission.Requirement requirement
    ) {
        String header = request.getHeader(trustedPermissionHeader);
        if (header == null || header.isBlank()) {
            deny(
                request,
                requirement,
                ApprovalManagementPermissionDeniedException.Reason.MISSING_TRUSTED_HEADER
            );
        }
        Set<String> authorities;
        try {
            authorities = parseAuthorities(header);
        } catch (IllegalArgumentException exception) {
            deny(
                request,
                requirement,
                ApprovalManagementPermissionDeniedException.Reason.MALFORMED_TRUSTED_HEADER
            );
            return false;
        }
        return authorities.contains(ApprovalManagementPermission.ADMIN_AUTHORITY)
            || authorities.contains(requirement.authority());
    }

    private void deny(
        HttpServletRequest request,
        ApprovalManagementPermission.Requirement requirement,
        ApprovalManagementPermissionDeniedException.Reason reason
    ) {
        recordAuthorization(requirement, "denied");
        LOGGER.warn(
            "event=APPROVAL_MANAGEMENT_ACCESS_DENIED tenantId={} operatorId={} "
                + "requestId={} requiredAuthority={} reason={}",
            safeLogValue(request.getHeader("X-Tenant-Id")),
            operatorIdentity(request),
            safeLogValue(request.getHeader("X-Request-Id")),
            requirement.authority(),
            reason
        );
        throw new ApprovalManagementPermissionDeniedException(requirement, reason);
    }

    private void denyUndeclared(HttpServletRequest request) {
        recordAuthorization(UNDECLARED_REQUIREMENT, "denied");
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

    private String operatorIdentity(HttpServletRequest request) {
        if (authoritySource == AuthoritySource.PRINCIPAL
            && request.getUserPrincipal() != null) {
            return safeLogValue(request.getUserPrincipal().getName());
        }
        return safeLogValue(request.getHeader("X-Operator-Id"));
    }

    private void recordAuthorization(
        ApprovalManagementPermission.Requirement requirement,
        String outcome
    ) {
        recordAuthorization(requirement.metricTag(), outcome);
    }

    private void recordAuthorization(String requirement, String outcome) {
        meters.counter(
            AUTHORIZATION_METRIC,
            "requirement",
            requirement,
            "outcome",
            outcome
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

    private static Set<String> parseAuthorities(String header) {
        if (header.length() > MAX_HEADER_LENGTH) {
            throw new IllegalArgumentException("permission header is too long");
        }
        String[] values = header.split(",", -1);
        if (values.length > MAX_AUTHORITIES) {
            throw new IllegalArgumentException("too many permissions");
        }
        Set<String> authorities = new LinkedHashSet<>();
        for (String value : values) {
            String authority = value.trim();
            if (!AUTHORITY.matcher(authority).matches()) {
                throw new IllegalArgumentException("malformed permission");
            }
            authorities.add(authority);
        }
        return Set.copyOf(authorities);
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

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    public enum AuthoritySource {
        PRINCIPAL,
        TRUSTED_HEADER;

        public static AuthoritySource parse(String value) {
            return valueOf(requireText(value, "authoritySource")
                .replace('-', '_')
                .toUpperCase(Locale.ROOT));
        }
    }
}
