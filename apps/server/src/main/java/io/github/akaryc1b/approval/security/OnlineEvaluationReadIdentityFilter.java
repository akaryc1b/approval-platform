package io.github.akaryc1b.approval.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.Principal;
import java.util.Collections;
import java.util.Set;

/** Opt-in private read bridge; the existing principal filter still owns approval headers. */
public final class OnlineEvaluationReadIdentityFilter extends OncePerRequestFilter {
    private final OnlineEvaluationReadTicket verifier;
    private final String tenantId;

    public OnlineEvaluationReadIdentityFilter(OnlineEvaluationReadTicket verifier, String tenantId) {
        this.verifier = java.util.Objects.requireNonNull(verifier);
        if (!"demo-purchase-payment".equals(tenantId)) {
            throw new IllegalArgumentException("CANONICAL_EVALUATION_TENANT_REQUIRED");
        }
        this.tenantId = tenantId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        if (request.getDispatcherType() != DispatcherType.REQUEST
            || !"127.0.0.1".equals(request.getRemoteAddr()) || !request.getContextPath().isEmpty()) {
            reject(response, 403); return;
        }
        String path = request.getRequestURI();
        if (!"GET".equals(request.getMethod()) || request.getQueryString() != null
            || !(OnlineEvaluationReadTicket.PATH.equals(path) || "/actuator/health".equals(path))) {
            reject(response, 404); return;
        }
        if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null
            || request.getHeader("Content-Encoding") != null || request.getUserPrincipal() != null) {
            reject(response, 403); return;
        }
        for (String name : Set.of("Authorization", "Cookie", "X-Tenant-Id", "X-Operator-Id",
            "X-Approval-Trusted-Permissions", "X-Request-Id", "X-Trace-Id")) {
            if (request.getHeader(name) != null) { reject(response, 403); return; }
        }
        if ("/actuator/health".equals(path)) { chain.doFilter(request, response); return; }
        var tickets = Collections.list(request.getHeaders(OnlineEvaluationReadTicket.HEADER));
        if (tickets.size() != 1) { reject(response, 401); return; }
        OnlineEvaluationReadTicket.Identity identity;
        try { identity = verifier.verify(tickets.getFirst(), request.getMethod(), path); }
        catch (SecurityException exception) { reject(response, 401); return; }
        ApprovalPrincipal principal = ApprovalPrincipal.active(tenantId, identity.actorId(), Set.of(), identity.expiresAt());
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public String getHeader(String name) {
                return OnlineEvaluationReadTicket.HEADER.equalsIgnoreCase(name) ? null : super.getHeader(name);
            }
            @Override public java.util.Enumeration<String> getHeaders(String name) {
                return OnlineEvaluationReadTicket.HEADER.equalsIgnoreCase(name)
                    ? Collections.emptyEnumeration() : super.getHeaders(name);
            }
            @Override public java.util.Enumeration<String> getHeaderNames() {
                return Collections.enumeration(Collections.list(super.getHeaderNames()).stream()
                    .filter(name -> !OnlineEvaluationReadTicket.HEADER.equalsIgnoreCase(name)).toList());
            }
            @Override public Principal getUserPrincipal() { return principal; }
            @Override public String getRemoteUser() { return principal.operatorId(); }
            @Override public String getAuthType() { return "EVALUATION_SIGNED_READ"; }
            @Override public boolean isUserInRole(String role) { return false; }
        }, response);
    }

    private static void reject(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"EVALUATION_READ_REJECTED\"}");
    }
}
