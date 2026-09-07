package io.github.akaryc1b.approval.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OnlineEvaluationReadIdentityFilterTest {
    private static final String GENERATION = "a".repeat(32);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC);
    private final KeyPair keys;
    private final OnlineEvaluationReadTicket verifier;
    private final OnlineEvaluationReadIdentityFilter filter;

    OnlineEvaluationReadIdentityFilterTest() throws Exception {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        verifier = new OnlineEvaluationReadTicket(Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()),
            GENERATION, Set.of("demo-employee", "demo-manager"), CLOCK);
        filter = new OnlineEvaluationReadIdentityFilter(verifier, "demo-purchase-payment");
    }

    private String ticket(String actor, long issued, long expires) throws Exception {
        String nonce = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        byte[] bytes = String.join("\n", "AP-EVALUATION-READ-V1", "GET", OnlineEvaluationReadTicket.PATH,
            GENERATION, actor, Long.toString(issued), Long.toString(expires), nonce).getBytes(StandardCharsets.US_ASCII);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keys.getPrivate()); signature.update(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + "."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    }

    private String ticket() throws Exception { return ticket("demo-employee", CLOCK.millis(), CLOCK.millis() + 10_000); }
    private MockHttpServletRequest request(String proof) {
        var request = new MockHttpServletRequest("GET", OnlineEvaluationReadTicket.PATH);
        request.setRemoteAddr("127.0.0.1");
        if (proof != null) request.addHeader(OnlineEvaluationReadTicket.HEADER, proof);
        return request;
    }
    private void rejected(MockHttpServletRequest request, int status) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (unused, ignored) -> fail("rejected request reached business chain"));
        assertEquals(status, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("{\"error\":\"EVALUATION_READ_REJECTED\"}", response.getContentAsString());
    }

    @Test void signedReadUsesTheExistingPrincipalFilterWithoutHeaderOrManagementAuthority() throws Exception {
        var existing = new ApprovalIdentityContextFilter(ApprovalIdentityContextFilter.AuthenticationMode.PRINCIPAL,
            "X-Approval-Trusted-Permissions", CLOCK, new ObjectMapper(), () -> "evaluation-test-request");
        var response = new MockHttpServletResponse();
        AtomicInteger invoked = new AtomicInteger();
        filter.doFilter(request(ticket()), response, (authenticated, result) ->
            existing.doFilter(authenticated, result, (trusted, ignored) -> {
                HttpServletRequest actual = (HttpServletRequest) trusted;
                assertInstanceOf(ApprovalPrincipal.class, actual.getUserPrincipal());
                ApprovalPrincipal principal = (ApprovalPrincipal) actual.getUserPrincipal();
                assertEquals("demo-purchase-payment", principal.tenantId());
                assertEquals("demo-employee", principal.operatorId());
                assertTrue(principal.authorities().isEmpty()); assertTrue(principal.responsibilities().isEmpty());
                assertEquals(CLOCK.instant().plusSeconds(10), principal.sessionExpiresAt());
                assertEquals("demo-purchase-payment", actual.getHeader("X-Tenant-Id"));
                assertEquals("demo-employee", actual.getHeader("X-Operator-Id"));
                assertEquals("evaluation-test-request", actual.getHeader("X-Request-Id"));
                assertNull(actual.getHeader(OnlineEvaluationReadTicket.HEADER));
                assertFalse(Collections.list(actual.getHeaderNames()).stream()
                    .anyMatch(OnlineEvaluationReadTicket.HEADER::equalsIgnoreCase));
                assertFalse(actual.isUserInRole("ADMIN")); invoked.incrementAndGet();
            }));
        assertEquals(1, invoked.get()); assertEquals(200, response.getStatus());
    }

    @Test void unsignedDefaultPrincipalModeRemainsUnauthorized() throws Exception {
        var existing = new ApprovalIdentityContextFilter(ApprovalIdentityContextFilter.AuthenticationMode.PRINCIPAL,
            "X-Approval-Trusted-Permissions", CLOCK, new ObjectMapper(), () -> "evaluation-test-request");
        var response = new MockHttpServletResponse();
        existing.doFilter(request(ticket()), response, (r, s) -> fail("ticket alone must not change default mode"));
        assertEquals(401, response.getStatus());
    }

    @Test void unsignedDuplicateTamperedAndReplayedProofsFail() throws Exception {
        rejected(request(null), 401); rejected(request("malformed"), 401);
        var duplicate = request(ticket()); duplicate.addHeader(OnlineEvaluationReadTicket.HEADER, ticket());
        rejected(duplicate, 401);
        String proof = ticket();
        filter.doFilter(request(proof), new MockHttpServletResponse(), (r, s) -> {});
        rejected(request(proof), 401);
    }

    @Test void clientIdentityAndSessionMaterialAreNotTrusted() throws Exception {
        for (String name : Set.of("Authorization", "Cookie", "X-Tenant-Id", "X-Operator-Id",
            "X-Approval-Trusted-Permissions", "X-Request-Id", "X-Trace-Id")) {
            var request = request(ticket()); request.addHeader(name, "untrusted"); rejected(request, 403);
        }
        var principal = request(ticket()); principal.setUserPrincipal(() -> "demo-admin"); rejected(principal, 403);
    }

    @Test void allWritesQueriesNonliteralPathsAndManagementRoutesStayClosed() throws Exception {
        for (String method : Set.of("POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD")) {
            var request = request(ticket()); request.setMethod(method); rejected(request, 404);
        }
        for (String path : Set.of("/api/approval/definitions", "/actuator/env", "/api/approval/tasks/pending/",
            "/api/approval/tasks%2Fpending", "/api/approval/tasks/pending;anything", "/api/approval/tasks/../tasks/pending")) {
            var request = request(ticket()); request.setRequestURI(path); rejected(request, 404);
        }
        var query = request(ticket()); query.setQueryString("limit=100"); rejected(query, 404);
    }

    @Test void remoteConnectionsBodiesAndNonrootContextsFail() throws Exception {
        var remote = request(ticket()); remote.setRemoteAddr("172.18.0.2"); rejected(remote, 403);
        var body = request(ticket()); body.setContent("body".getBytes(StandardCharsets.UTF_8)); rejected(body, 403);
        var chunked = request(ticket()); chunked.addHeader("Transfer-Encoding", "chunked"); rejected(chunked, 403);
        var encoded = request(ticket()); encoded.addHeader("Content-Encoding", "gzip"); rejected(encoded, 403);
        var context = request(ticket()); context.setContextPath("/other"); rejected(context, 403);
    }

    @Test void onlyThePrivateHealthEndpointMayPassWithoutAPrincipal() throws Exception {
        var request = request(null); request.setRequestURI("/actuator/health");
        AtomicInteger called = new AtomicInteger();
        filter.doFilter(request, new MockHttpServletResponse(), (r, s) -> {
            assertNull(((HttpServletRequest) r).getUserPrincipal()); called.incrementAndGet();
        });
        assertEquals(1, called.get());
    }

    @Test void adminExpiredAndFutureSignedActorsCannotObtainAPrincipal() throws Exception {
        rejected(request(ticket("demo-admin", CLOCK.millis(), CLOCK.millis() + 1000)), 401);
        rejected(request(ticket("demo-employee", CLOCK.millis() - 1000, CLOCK.millis())), 401);
        rejected(request(ticket("demo-employee", CLOCK.millis() + 1, CLOCK.millis() + 1000)), 401);
    }

    @Test void concurrentJavaVerificationAdmitsExactlyOneUse() throws Exception {
        String proof = ticket();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var work = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) work.add(executor.submit(() -> {
                try { start.await(); verifier.verify(proof, "GET", OnlineEvaluationReadTicket.PATH); accepted.incrementAndGet(); }
                catch (SecurityException expected) { /* Replays must be rejected. */ }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            }));
            start.countDown(); for (var result : work) result.get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, accepted.get());
    }
}
