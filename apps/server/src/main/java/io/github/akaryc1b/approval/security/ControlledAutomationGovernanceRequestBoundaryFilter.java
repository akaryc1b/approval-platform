package io.github.akaryc1b.approval.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Fail-closed raw HTTP boundary for the GET-only M6-F governance endpoints.
 *
 * <p>This filter runs before identity resolution so duplicate or malformed tenant headers,
 * ambiguous query parameters, request bodies and method-override attempts cannot be normalized
 * into trusted server context. It performs no Provider, Secret, persistence, runtime-control or
 * command operation.</p>
 */
public final class ControlledAutomationGovernanceRequestBoundaryFilter
    extends OncePerRequestFilter {

    public static final String BASE_PATH = "/api/approval/management/ai-governance";

    private static final String TENANT_ID_HEADER = "X-Tenant-Id";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String HTTP_METHOD_OVERRIDE = "X-HTTP-Method-Override";
    private static final String METHOD_OVERRIDE = "X-Method-Override";
    private static final String TRANSFER_ENCODING = "Transfer-Encoding";
    private static final Duration MAXIMUM_WINDOW = Duration.ofDays(31);
    private static final Duration MAXIMUM_LOOKBACK = Duration.ofDays(3_650);
    private static final Pattern TENANT_ID = Pattern.compile(
        "[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}"
    );
    private static final Pattern CORRELATION_ID = Pattern.compile(
        "[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}"
    );
    private static final Set<String> OPERATIONS = Set.of("CANARY", "ROLLOUT", "ROLLBACK");
    private static final Map<String, EndpointRule> ENDPOINTS = Map.of(
        BASE_PATH + "/snapshot",
        EndpointRule.noParameters(),
        BASE_PATH + "/change-plan",
        EndpointRule.operationEndpoint(),
        BASE_PATH + "/control-health",
        EndpointRule.noParameters(),
        BASE_PATH + "/usage",
        EndpointRule.noParameters(),
        BASE_PATH + "/history",
        EndpointRule.historyWindowEndpoint(),
        BASE_PATH + "/incident-readiness",
        EndpointRule.historyWindowEndpoint()
    );

    private final Clock clock;
    private final ObjectMapper objectMapper;

    public ControlledAutomationGovernanceRequestBoundaryFilter(
        Clock clock,
        ObjectMapper objectMapper
    ) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.objectMapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper must not be null"
        );
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ENDPOINTS.containsKey(requestPath(request));
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            validate(request, ENDPOINTS.get(requestPath(request)));
        } catch (BoundaryFailure failure) {
            writeFailure(request, response, failure);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void validate(HttpServletRequest request, EndpointRule rule) {
        if (rule == null) {
            throw failure(
                HttpServletResponse.SC_NOT_FOUND,
                "AI_GOVERNANCE_ENDPOINT_NOT_FOUND",
                "AI governance endpoint is unavailable"
            );
        }
        if (!"GET".equals(request.getMethod())) {
            throw failure(
                HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                "AI_GOVERNANCE_METHOD_NOT_ALLOWED",
                "AI governance endpoints are GET-only"
            );
        }
        if (request.getHeader(HTTP_METHOD_OVERRIDE) != null
            || request.getHeader(METHOD_OVERRIDE) != null) {
            throw failure(
                HttpServletResponse.SC_BAD_REQUEST,
                "AI_GOVERNANCE_METHOD_OVERRIDE_REJECTED",
                "HTTP method override is not permitted"
            );
        }
        if (request.getContentLengthLong() > 0
            || request.getHeader(TRANSFER_ENCODING) != null) {
            throw failure(
                HttpServletResponse.SC_BAD_REQUEST,
                "AI_GOVERNANCE_BODY_NOT_ALLOWED",
                "AI governance GET requests cannot contain a body"
            );
        }

        requireCanonicalTenant(request);
        Map<String, String> parameters = exactParameters(request, rule.parameterNames());
        if (rule.operation()) {
            String operation = parameters.get("operation");
            if (!OPERATIONS.contains(operation)) {
                throw invalidQuery();
            }
        }
        if (rule.historyWindow()) {
            Instant from = canonicalInstant(parameters.get("from"));
            Instant to = canonicalInstant(parameters.get("to"));
            validateWindow(from, to);
        }
    }

    private void validateWindow(Instant from, Instant to) {
        Instant now = clock.instant();
        if (!from.isBefore(to)
            || Duration.between(from, to).compareTo(MAXIMUM_WINDOW) > 0
            || from.isBefore(now.minus(MAXIMUM_LOOKBACK))
            || to.isAfter(now)) {
            throw invalidQuery();
        }
    }

    private static Instant canonicalInstant(String value) {
        if (value == null
            || value.isBlank()
            || !value.equals(value.trim())
            || value.length() > 40) {
            throw invalidQuery();
        }
        try {
            Instant parsed = Instant.parse(value);
            if (!parsed.toString().equals(value)) {
                throw invalidQuery();
            }
            return parsed;
        } catch (RuntimeException invalid) {
            throw invalidQuery();
        }
    }

    private static void requireCanonicalTenant(HttpServletRequest request) {
        List<String> values = headerValues(request, TENANT_ID_HEADER);
        if (values.size() != 1 || !TENANT_ID.matcher(values.getFirst()).matches()) {
            throw failure(
                HttpServletResponse.SC_BAD_REQUEST,
                "AI_GOVERNANCE_TENANT_INVALID",
                "tenant context must be singular and canonical"
            );
        }
    }

    private static Map<String, String> exactParameters(
        HttpServletRequest request,
        Set<String> expectedNames
    ) {
        Set<String> actualNames = new LinkedHashSet<>();
        Enumeration<String> names = request.getParameterNames();
        while (names != null && names.hasMoreElements()) {
            actualNames.add(names.nextElement());
        }
        if (!actualNames.equals(expectedNames)) {
            throw invalidQuery();
        }
        java.util.LinkedHashMap<String, String> exact = new java.util.LinkedHashMap<>();
        for (String name : expectedNames) {
            String[] values = request.getParameterValues(name);
            if (values == null || values.length != 1) {
                throw invalidQuery();
            }
            exact.put(name, values[0]);
        }
        return Map.copyOf(exact);
    }

    private static List<String> headerValues(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        return values == null ? List.of() : Collections.list(values);
    }

    private void writeFailure(
        HttpServletRequest request,
        HttpServletResponse response,
        BoundaryFailure failure
    ) throws IOException {
        String requestId = safeRequestId(request.getHeader(REQUEST_ID_HEADER));
        response.setStatus(failure.status());
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader(REQUEST_ID_HEADER, requestId);
        response.setHeader(TRACE_ID_HEADER, requestId);
        objectMapper.writeValue(
            response.getWriter(),
            new BoundaryError(
                failure.code(),
                failure.getMessage(),
                false,
                requestId,
                clock.instant()
            )
        );
    }

    private static String safeRequestId(String supplied) {
        if (supplied != null && CORRELATION_ID.matcher(supplied).matches()) {
            return supplied;
        }
        return "approval-governance-" + UUID.randomUUID();
    }

    private static String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private static BoundaryFailure invalidQuery() {
        return failure(
            HttpServletResponse.SC_BAD_REQUEST,
            "AI_GOVERNANCE_QUERY_INVALID",
            "AI governance query is malformed or ambiguous"
        );
    }

    private static BoundaryFailure failure(int status, String code, String message) {
        return new BoundaryFailure(status, code, message);
    }

    private record EndpointRule(
        Set<String> parameterNames,
        boolean operation,
        boolean historyWindow
    ) {
        private EndpointRule {
            parameterNames = Set.copyOf(parameterNames);
        }

        private static EndpointRule noParameters() {
            return new EndpointRule(Set.of(), false, false);
        }

        private static EndpointRule operationEndpoint() {
            return new EndpointRule(Set.of("operation"), true, false);
        }

        private static EndpointRule historyWindowEndpoint() {
            return new EndpointRule(Set.of("from", "to"), false, true);
        }
    }

    private record BoundaryError(
        String code,
        String message,
        boolean retryable,
        String requestId,
        Instant occurredAt
    ) {
    }

    private static final class BoundaryFailure extends RuntimeException {
        private final int status;
        private final String code;

        private BoundaryFailure(int status, String code, String message) {
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
}
