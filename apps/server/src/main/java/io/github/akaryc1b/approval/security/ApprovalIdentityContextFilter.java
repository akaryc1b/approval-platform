package io.github.akaryc1b.approval.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.security.ApprovalPrincipal.AccountStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.Principal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Establishes server-owned identity, tenant and correlation headers for approval APIs. */
public final class ApprovalIdentityContextFilter extends OncePerRequestFilter {

    public static final String TENANT_ID_HEADER = "X-Tenant-Id";
    public static final String OPERATOR_ID_HEADER = "X-Operator-Id";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final String APPROVAL_API_PATH = "/api/approval";
    private static final Pattern CORRELATION_ID = Pattern.compile(
        "[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}"
    );
    private static final Pattern AUTHORITY = Pattern.compile("[a-z][a-z0-9.-]{2,127}");
    private static final int MAX_PERMISSION_HEADER_LENGTH = 4_096;
    private static final int MAX_AUTHORITIES = 64;

    private final AuthenticationMode mode;
    private final String localPermissionHeader;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final Supplier<String> requestIdGenerator;

    public ApprovalIdentityContextFilter(
        AuthenticationMode mode,
        String localPermissionHeader,
        Clock clock,
        ObjectMapper objectMapper,
        Supplier<String> requestIdGenerator
    ) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        this.localPermissionHeader = requireText(
            localPermissionHeader,
            "localPermissionHeader"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.requestIdGenerator = Objects.requireNonNull(
            requestIdGenerator,
            "requestIdGenerator must not be null"
        );
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = requestPath(request);
        return !path.equals(APPROVAL_API_PATH) && !path.startsWith(APPROVAL_API_PATH + '/');
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String generatedRequestId = generatedRequestId();
        String requestId;
        String traceId;
        ApprovalPrincipal principal;
        try {
            requestId = correlationId(
                request.getHeader(REQUEST_ID_HEADER),
                generatedRequestId,
                "APPROVAL_REQUEST_ID_INVALID",
                "requestId is malformed"
            );
            traceId = correlationId(
                request.getHeader(TRACE_ID_HEADER),
                requestId,
                "APPROVAL_TRACE_ID_INVALID",
                "traceId is malformed"
            );
            principal = resolvePrincipal(request);
            validatePrincipal(principal);
            validateTenantClaim(request, principal);
        } catch (ApprovalSecurityFailure failure) {
            writeFailure(response, failure, generatedRequestId);
            return;
        }

        TrustedApprovalRequest trusted = new TrustedApprovalRequest(
            request,
            principal,
            requestId,
            traceId,
            localPermissionHeader
        );
        response.setHeader(REQUEST_ID_HEADER, requestId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        MDC.put("requestId", requestId);
        MDC.put("traceId", traceId);
        MDC.put("operatorId", principal.operatorId());
        try {
            filterChain.doFilter(trusted, response);
        } finally {
            MDC.remove("operatorId");
            MDC.remove("traceId");
            MDC.remove("requestId");
        }
    }

    private ApprovalPrincipal resolvePrincipal(HttpServletRequest request) {
        if (mode == AuthenticationMode.PRINCIPAL) {
            Principal requestPrincipal = request.getUserPrincipal();
            if (requestPrincipal instanceof ApprovalPrincipal approvalPrincipal) {
                return approvalPrincipal;
            }
            throw failure(
                HttpServletResponse.SC_UNAUTHORIZED,
                "APPROVAL_AUTHENTICATION_REQUIRED",
                "authenticated approval principal is required"
            );
        }
        String tenantId = request.getHeader(TENANT_ID_HEADER);
        if (tenantId == null || tenantId.isBlank()) {
            throw failure(
                HttpServletResponse.SC_UNAUTHORIZED,
                "APPROVAL_TENANT_CONTEXT_REQUIRED",
                "tenant context is required"
            );
        }
        String operatorId = request.getHeader(OPERATOR_ID_HEADER);
        if (operatorId == null || operatorId.isBlank()) {
            throw failure(
                HttpServletResponse.SC_UNAUTHORIZED,
                "APPROVAL_AUTHENTICATION_REQUIRED",
                "local operator identity is required"
            );
        }
        try {
            return ApprovalPrincipal.active(
                tenantId,
                operatorId,
                parseAuthorities(request.getHeader(localPermissionHeader)),
                null
            );
        } catch (IllegalArgumentException exception) {
            throw failure(
                HttpServletResponse.SC_UNAUTHORIZED,
                "APPROVAL_AUTHENTICATION_REQUIRED",
                "local approval identity is malformed"
            );
        }
    }

    private void validatePrincipal(ApprovalPrincipal principal) {
        if (principal.accountStatus() == AccountStatus.DISABLED) {
            throw failure(
                HttpServletResponse.SC_FORBIDDEN,
                "APPROVAL_PRINCIPAL_DISABLED",
                "approval principal is disabled"
            );
        }
        if (principal.isExpiredAt(clock.instant())) {
            throw failure(
                HttpServletResponse.SC_UNAUTHORIZED,
                "APPROVAL_SESSION_EXPIRED",
                "approval session has expired"
            );
        }
    }

    private static void validateTenantClaim(
        HttpServletRequest request,
        ApprovalPrincipal principal
    ) {
        String suppliedTenant = request.getHeader(TENANT_ID_HEADER);
        if (suppliedTenant != null && !suppliedTenant.isBlank()
            && !principal.tenantId().equals(suppliedTenant.trim())) {
            throw failure(
                HttpServletResponse.SC_NOT_FOUND,
                "APPROVAL_TENANT_CONTEXT_MISMATCH",
                "approval resource was not found"
            );
        }
    }

    private String generatedRequestId() {
        String generated = Objects.requireNonNull(
            requestIdGenerator.get(),
            "generated requestId must not be null"
        ).trim();
        if (!CORRELATION_ID.matcher(generated).matches()) {
            throw new IllegalStateException("generated requestId is malformed");
        }
        return generated;
    }

    private static String correlationId(
        String supplied,
        String fallback,
        String code,
        String message
    ) {
        if (supplied == null || supplied.isBlank()) {
            return fallback;
        }
        String normalized = supplied.trim();
        if (!CORRELATION_ID.matcher(normalized).matches()) {
            throw failure(HttpServletResponse.SC_BAD_REQUEST, code, message);
        }
        return normalized;
    }

    private void writeFailure(
        HttpServletResponse response,
        ApprovalSecurityFailure failure,
        String requestId
    ) throws IOException {
        response.setStatus(failure.status());
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setHeader(REQUEST_ID_HEADER, requestId);
        response.setHeader(TRACE_ID_HEADER, requestId);
        objectMapper.writeValue(
            response.getWriter(),
            new ApprovalSecurityError(
                failure.code(),
                failure.getMessage(),
                failure.status(),
                false,
                requestId
            )
        );
    }

    private static Set<String> parseAuthorities(String header) {
        if (header == null || header.isBlank()) {
            return Set.of();
        }
        if (header.length() > MAX_PERMISSION_HEADER_LENGTH) {
            throw new IllegalArgumentException("permission header is too long");
        }
        String[] values = header.split(",", -1);
        if (values.length > MAX_AUTHORITIES) {
            throw new IllegalArgumentException("too many authorities");
        }
        Set<String> authorities = new LinkedHashSet<>();
        for (String value : values) {
            String authority = value.trim();
            if (!AUTHORITY.matcher(authority).matches()) {
                throw new IllegalArgumentException("authority is malformed");
            }
            authorities.add(authority);
        }
        return Set.copyOf(authorities);
    }

    private static String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private static ApprovalSecurityFailure failure(int status, String code, String message) {
        return new ApprovalSecurityFailure(status, code, message);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    public enum AuthenticationMode {
        PRINCIPAL,
        LOCAL_HEADERS;

        public static AuthenticationMode parse(String value) {
            return valueOf(requireText(value, "authenticationMode")
                .replace('-', '_')
                .toUpperCase(Locale.ROOT));
        }
    }

    private record ApprovalSecurityError(
        String code,
        String message,
        int status,
        boolean retryable,
        String requestId
    ) {
    }

    private static final class ApprovalSecurityFailure extends RuntimeException {

        private final int status;
        private final String code;

        private ApprovalSecurityFailure(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        private int status() {
            return status;
        }

        private String code() {
            return code;
        }
    }

    private static final class TrustedApprovalRequest extends HttpServletRequestWrapper {

        private final ApprovalPrincipal principal;
        private final String requestId;
        private final String traceId;
        private final String localPermissionHeader;

        private TrustedApprovalRequest(
            HttpServletRequest request,
            ApprovalPrincipal principal,
            String requestId,
            String traceId,
            String localPermissionHeader
        ) {
            super(request);
            this.principal = principal;
            this.requestId = requestId;
            this.traceId = traceId;
            this.localPermissionHeader = localPermissionHeader;
        }

        @Override
        public Principal getUserPrincipal() {
            return principal;
        }

        @Override
        public String getRemoteUser() {
            return principal.operatorId();
        }

        @Override
        public boolean isUserInRole(String role) {
            return principal.hasAuthority(role);
        }

        @Override
        public String getHeader(String name) {
            if (TENANT_ID_HEADER.equalsIgnoreCase(name)) {
                return principal.tenantId();
            }
            if (OPERATOR_ID_HEADER.equalsIgnoreCase(name)) {
                return principal.operatorId();
            }
            if (REQUEST_ID_HEADER.equalsIgnoreCase(name)) {
                return requestId;
            }
            if (TRACE_ID_HEADER.equalsIgnoreCase(name)) {
                return traceId;
            }
            if (localPermissionHeader.equalsIgnoreCase(name)) {
                return null;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            String trusted = getHeader(name);
            if (isTrustedHeader(name)) {
                return trusted == null
                    ? Collections.emptyEnumeration()
                    : Collections.enumeration(List.of(trusted));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>();
            Enumeration<String> original = super.getHeaderNames();
            while (original != null && original.hasMoreElements()) {
                String name = original.nextElement();
                if (!isTrustedHeader(name)) {
                    names.add(name);
                }
            }
            names.add(TENANT_ID_HEADER);
            names.add(OPERATOR_ID_HEADER);
            names.add(REQUEST_ID_HEADER);
            names.add(TRACE_ID_HEADER);
            return Collections.enumeration(new ArrayList<>(names));
        }

        private boolean isTrustedHeader(String name) {
            return TENANT_ID_HEADER.equalsIgnoreCase(name)
                || OPERATOR_ID_HEADER.equalsIgnoreCase(name)
                || REQUEST_ID_HEADER.equalsIgnoreCase(name)
                || TRACE_ID_HEADER.equalsIgnoreCase(name)
                || localPermissionHeader.equalsIgnoreCase(name);
        }
    }
}
