package io.github.akaryc1b.approval.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;

/** Private workflow bridge. Only the pre-existing controllers execute business operations. */
public final class OnlineEvaluationBusinessFilter extends OncePerRequestFilter {
    private final OnlineEvaluationBusinessTicket verifier;

    public OnlineEvaluationBusinessFilter(OnlineEvaluationBusinessTicket verifier) {
        this.verifier = java.util.Objects.requireNonNull(verifier);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        response.setHeader("Cache-Control", "no-store");
        if (request.getDispatcherType() != DispatcherType.REQUEST || !"127.0.0.1".equals(request.getRemoteAddr())
            || !request.getContextPath().isEmpty() || request.getUserPrincipal() != null) {
            reject(response, 403); return;
        }
        String path = request.getRequestURI();
        // This never bypasses the existing HMAC verifier or its exact-event authorization.
        // The browser gateway does not forward this route at all.
        if ("POST".equals(request.getMethod()) && "/payment-sandbox/v1/events".equals(path)
            && request.getQueryString() == null && request.getContentLengthLong() >= 0
            && request.getContentLengthLong() <= 65_536) {
            chain.doFilter(request, response); return;
        }
        for (String name : Set.of("Authorization", "Cookie", "X-Tenant-Id", "X-Operator-Id",
            "X-Approval-Trusted-Permissions", "X-Trace-Id", "Content-Encoding")) {
            if (request.getHeader(name) != null) { reject(response, 403); return; }
        }
        if ("GET".equals(request.getMethod()) && "/actuator/health".equals(path) && request.getQueryString() == null
            && request.getContentLengthLong() <= 0 && request.getHeader("Transfer-Encoding") == null) {
            chain.doFilter(request, response); return;
        }
        String target = path + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        OnlineEvaluationBusinessTicket.Route route;
        try { route = OnlineEvaluationBusinessTicket.route(request.getMethod(), target); }
        catch (RuntimeException invalid) { reject(response, 404); return; }
        byte[] cached = null;
        String contentType;
        String digest;
        String idempotency;
        OnlineEvaluationBusinessTicket.Identity identity;
        try {
            String proof = single(request, OnlineEvaluationBusinessTicket.HEADER, true);
            String requestId = single(request, "X-Request-Id", true);
            idempotency = single(request, "Idempotency-Key", route != OnlineEvaluationBusinessTicket.Route.READ);
            if (route == OnlineEvaluationBusinessTicket.Route.READ) {
                require(request.getContentLengthLong() <= 0 && request.getHeader("Transfer-Encoding") == null
                    && request.getContentType() == null && idempotency == null);
                cached = new byte[0]; digest = OnlineEvaluationBusinessTicket.digest(cached); contentType = ""; idempotency = "";
            } else if (route == OnlineEvaluationBusinessTicket.Route.UPLOAD) {
                require(request.getContentLengthLong() > 0 && request.getContentLengthLong() <= OnlineEvaluationBusinessTicket.MAX_BODY_BYTES
                    && single(request, "content-type", true).matches("multipart/form-data;\\s*boundary=(?:[A-Za-z0-9'()+_,./:=?-]{1,70}|\"[A-Za-z0-9'()+_,./:=?-]{1,70}\")"));
                // Servlet parts are cached by the container. Do not consume the raw
                // multipart stream and leave Spring's multipart resolver an empty body.
                var parts = request.getParts(); require(parts.size() == 1);
                Part part = parts.iterator().next(); require("file".equals(part.getName()) && part.getSize() <= OnlineEvaluationBusinessTicket.MAX_BODY_BYTES - 4096);
                byte[] bytes;
                try (var stream = part.getInputStream()) { bytes = stream.readNBytes(OnlineEvaluationBusinessTicket.MAX_BODY_BYTES + 1); }
                digest = OnlineEvaluationBusinessTicket.uploadDigest(part.getSubmittedFileName(), part.getContentType(), bytes);
                contentType = "multipart/form-data";
            } else {
                require(request.getContentLengthLong() > 0 && request.getContentLengthLong() <= 65_536
                    && single(request, "content-type", true).matches("(?i)application/json(?:;\\s*charset=utf-8)?"));
                cached = request.getInputStream().readNBytes(65_537); require(cached.length <= 65_536);
                digest = OnlineEvaluationBusinessTicket.digest(cached); contentType = "application/json";
            }
            identity = verifier.verify(proof, request.getMethod(), target, contentType, digest, idempotency, requestId);
        } catch (RuntimeException invalid) { reject(response, 401); return; }
        catch (IOException | ServletException invalid) { reject(response, 400); return; }
        ApprovalPrincipal principal = ApprovalPrincipal.active("demo-purchase-payment", identity.actorId(), Set.of(), identity.expiresAt());
        chain.doFilter(new AuthenticatedRequest(request, principal, cached), response);
    }

    private static String single(HttpServletRequest request, String header, boolean required) {
        var values = Collections.list(request.getHeaders(header));
        require(values.size() <= 1 && (!required || values.size() == 1));
        return values.isEmpty() ? null : values.get(0);
    }
    private static final class AuthenticatedRequest extends HttpServletRequestWrapper {
        private final ApprovalPrincipal principal;
        private final byte[] cached;
        private AuthenticatedRequest(HttpServletRequest request, ApprovalPrincipal principal, byte[] cached) {
            super(request); this.principal = principal; this.cached = cached;
        }
        @Override public Principal getUserPrincipal() { return principal; }
        @Override public String getRemoteUser() { return principal.operatorId(); }
        @Override public String getAuthType() { return "EVALUATION_SIGNED_BUSINESS"; }
        @Override public boolean isUserInRole(String role) { return false; }
        @Override public String getHeader(String name) {
            return OnlineEvaluationBusinessTicket.HEADER.equalsIgnoreCase(name) ? null : super.getHeader(name);
        }
        @Override public Enumeration<String> getHeaders(String name) {
            return OnlineEvaluationBusinessTicket.HEADER.equalsIgnoreCase(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }
        @Override public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(Collections.list(super.getHeaderNames()).stream()
                .filter(name -> !OnlineEvaluationBusinessTicket.HEADER.equalsIgnoreCase(name)).toList());
        }
        @Override public ServletInputStream getInputStream() throws IOException {
            if (cached == null) return super.getInputStream();
            ByteArrayInputStream input = new ByteArrayInputStream(cached);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("ASYNC_NOT_SUPPORTED"); }
            };
        }
        @Override public BufferedReader getReader() throws IOException {
            return cached == null ? super.getReader() : new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
    private static void require(boolean value) { if (!value) throw new SecurityException("EVALUATION_BUSINESS_REJECTED"); }
    private static void reject(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status); response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"EVALUATION_BUSINESS_REJECTED\"}");
    }
}
