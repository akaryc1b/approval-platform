package io.github.akaryc1b.approval.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Servlet-chain coverage for CI. Standalone JDK tests do not replace these integration cases. */
class OnlineEvaluationBusinessFilterTest {
    private static final String GENERATION = "a".repeat(32);
    private static final String PATH = "/api/approval/tasks/00000000-0000-4000-8000-000000000001/approve";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC);
    private final KeyPair keys;
    private final OnlineEvaluationBusinessFilter filter;
    OnlineEvaluationBusinessFilterTest() throws Exception {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        filter = new OnlineEvaluationBusinessFilter(new OnlineEvaluationBusinessTicket(
            Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()), GENERATION,
            Set.of("demo-employee", "demo-manager", "demo-finance-reviewer", "demo-finance-approver-a", "demo-finance-approver-b"), CLOCK));
    }
    private String proof(String path, String type, String digest, String key) throws Exception {
        byte[] message = String.join("\n", "AP-EVALUATION-BUSINESS-V1", "POST", path, GENERATION, "demo-manager",
            Long.toString(CLOCK.millis()), Long.toString(CLOCK.millis() + 10000), UUID.randomUUID().toString().replace("-", "").repeat(2),
            type, digest, key, "evaluation-test-request").getBytes(StandardCharsets.US_ASCII);
        var signer = Signature.getInstance("Ed25519"); signer.initSign(keys.getPrivate()); signer.update(message);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(message) + "."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    }
    private MockHttpServletRequest request() throws Exception {
        byte[] body = "{\"comment\":\"同意\"}".getBytes(StandardCharsets.UTF_8);
        var request = new MockHttpServletRequest("POST", PATH); request.setRemoteAddr("127.0.0.1");
        request.setContentType("application/json"); request.setContent(body);
        request.addHeader("Idempotency-Key", "approval-once"); request.addHeader("X-Request-Id", "evaluation-test-request");
        request.addHeader(OnlineEvaluationBusinessTicket.HEADER,
            proof(PATH, "application/json", OnlineEvaluationBusinessTicket.digest(body), "approval-once"));
        return request;
    }
    @Test void signedJsonPassesThroughTheExistingPrincipalFilterAndPreservesBody() throws Exception {
        var existing = new ApprovalIdentityContextFilter(ApprovalIdentityContextFilter.AuthenticationMode.PRINCIPAL,
            "X-Approval-Trusted-Permissions", CLOCK, new ObjectMapper(), () -> "generated-request");
        var response = new MockHttpServletResponse(); AtomicInteger called = new AtomicInteger();
        filter.doFilter(request(), response, (authenticated, result) -> existing.doFilter(authenticated, result, (raw, ignored) -> {
            HttpServletRequest actual = (HttpServletRequest) raw;
            assertInstanceOf(ApprovalPrincipal.class, actual.getUserPrincipal());
            var principal = (ApprovalPrincipal) actual.getUserPrincipal();
            assertEquals("demo-manager", principal.operatorId()); assertTrue(principal.authorities().isEmpty());
            assertEquals("demo-manager", actual.getHeader("X-Operator-Id"));
            assertEquals("approval-once", actual.getHeader("Idempotency-Key"));
            assertNull(actual.getHeader(OnlineEvaluationBusinessTicket.HEADER));
            assertEquals("{\"comment\":\"同意\"}", new String(actual.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            assertFalse(actual.isUserInRole("ADMIN")); called.incrementAndGet();
        }));
        assertEquals(1, called.get()); assertEquals(200, response.getStatus());
    }
    @Test void tamperedBodyOrIdempotencyCannotReachTheController() throws Exception {
        for (boolean body : new boolean[] {true, false}) {
            var request = request();
            if (body) request.setContent("{\"comment\":\"different\"}".getBytes(StandardCharsets.UTF_8));
            else { request.removeHeader("Idempotency-Key"); request.addHeader("Idempotency-Key", "different"); }
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (r, s) -> fail("tampered write reached controller"));
            assertEquals(401, response.getStatus());
        }
    }
    @Test void multipartPartsRemainReadableByTheExistingUploadController() throws Exception {
        byte[] bytes = "evaluation attachment".getBytes(StandardCharsets.UTF_8);
        var part = new MockPart("file", "invoice.txt", bytes);
        part.getHeaders().setContentType(org.springframework.http.MediaType.TEXT_PLAIN);
        var request = new MockHttpServletRequest("POST", "/api/approval/attachments");
        request.setRemoteAddr("127.0.0.1"); request.setContentType("multipart/form-data; boundary=TestBoundary");
        request.setContent(new byte[300]); request.addPart(part);
        request.addHeader("Idempotency-Key", "upload-once"); request.addHeader("X-Request-Id", "evaluation-test-request");
        request.addHeader(OnlineEvaluationBusinessTicket.HEADER, proof(request.getRequestURI(), "multipart/form-data",
            OnlineEvaluationBusinessTicket.uploadDigest("invoice.txt", "text/plain", bytes), "upload-once"));
        AtomicInteger called = new AtomicInteger(); var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (raw, ignored) -> {
            var actual = (HttpServletRequest) raw;
            assertEquals(1, actual.getParts().size());
            assertEquals("evaluation attachment", new String(actual.getPart("file").getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            called.incrementAndGet();
        });
        assertEquals(1, called.get()); assertEquals(200, response.getStatus());
    }
    @Test void browserIdentityAndManagementPathsRemainClosed() throws Exception {
        for (String header : Set.of("Cookie", "Authorization", "X-Tenant-Id", "X-Operator-Id", "X-Approval-Trusted-Permissions")) {
            var request = request(); request.addHeader(header, "untrusted"); var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (r, s) -> fail("identity override reached controller")); assertEquals(403, response.getStatus());
        }
        var request = request(); request.setRequestURI("/api/approval/definitions/purchase-payment/publish");
        var response = new MockHttpServletResponse(); filter.doFilter(request, response, (r, s) -> fail("management write reached controller"));
        assertEquals(404, response.getStatus());
    }
    @Test void privatePaymentTransportStillDelegatesSignatureValidationToTheExistingSandbox() throws Exception {
        var request = new MockHttpServletRequest("POST", "/payment-sandbox/v1/events"); request.setRemoteAddr("127.0.0.1");
        request.setContent("{}".getBytes(StandardCharsets.UTF_8)); request.addHeader("X-Trace-Id", "signed-callback-trace");
        var response = new MockHttpServletResponse(); AtomicInteger called = new AtomicInteger();
        filter.doFilter(request, response, (r, s) -> { assertNull(((HttpServletRequest) r).getUserPrincipal()); called.incrementAndGet(); });
        assertEquals(1, called.get());
        request.setRemoteAddr("172.18.0.2");
        filter.doFilter(request, new MockHttpServletResponse(), (r, s) -> fail("remote payment transport reached sandbox"));
    }
}
