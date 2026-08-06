package io.github.akaryc1b.approval.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlledAutomationGovernanceRequestBoundaryFilterTest {

    private static final Instant NOW = Instant.parse("2026-08-06T03:30:00Z");
    private static final String BASE =
        ControlledAutomationGovernanceRequestBoundaryFilter.BASE_PATH;

    @Test
    void missingEmptyWhitespaceOverlongControlAndDuplicateTenantHeadersFailClosed()
        throws Exception {
        List<MockHttpServletRequest> invalid = List.of(
            requestWithoutTenant(BASE + "/snapshot"),
            requestWithTenant(BASE + "/snapshot", ""),
            requestWithTenant(BASE + "/snapshot", "   "),
            requestWithTenant(BASE + "/snapshot", " tenant-a"),
            requestWithTenant(BASE + "/snapshot", "tenant-a "),
            requestWithTenant(BASE + "/snapshot", "a".repeat(129)),
            requestWithTenant(BASE + "/snapshot", "tenant\nattack"),
            requestWithTenant(BASE + "/snapshot", unicodeControlTenant()),
            duplicateTenant(BASE + "/snapshot", "tenant-a", "tenant-a"),
            duplicateTenant(BASE + "/snapshot", "tenant-a", "tenant-b")
        );

        for (MockHttpServletRequest request : invalid) {
            AtomicInteger downstream = new AtomicInteger();
            MockHttpServletResponse response = execute(request, downstream);

            assertEquals(400, response.getStatus());
            assertTrue(response.getContentAsString().contains(
                "AI_GOVERNANCE_TENANT_INVALID"
            ));
            assertEquals("no-store", response.getHeader("Cache-Control"));
            assertEquals(0, downstream.get());
        }
    }

    @Test
    void historyRejectsNonCanonicalDuplicatePollutedAndOutOfRangeWindows()
        throws Exception {
        String validFrom = NOW.minusSeconds(3_600).toString();
        String validTo = NOW.toString();
        List<MockHttpServletRequest> invalid = List.of(
            history("2026-08-06T02:30:00+00:00", validTo),
            history("2026-08-06T02:30:00.000Z", validTo),
            history(validTo, validTo),
            history(validTo, validFrom),
            history(NOW.minusSeconds(32L * 86_400L).toString(), validTo),
            history(NOW.minusSeconds(3_651L * 86_400L).toString(), validTo),
            history(validFrom, NOW.plusSeconds(1).toString()),
            history("", validTo),
            history("9".repeat(41), validTo),
            history("2026-08-06T02:30:00Z\n", validTo),
            duplicateParameter("from", validFrom, validFrom, validTo),
            duplicateParameter("to", validTo, validFrom, validTo),
            pollutedHistory(validFrom, validTo, "provider", "attacker-provider")
        );

        for (MockHttpServletRequest request : invalid) {
            AtomicInteger downstream = new AtomicInteger();
            MockHttpServletResponse response = execute(request, downstream);

            assertEquals(400, response.getStatus());
            assertTrue(response.getContentAsString().contains(
                "AI_GOVERNANCE_QUERY_INVALID"
            ));
            assertEquals(0, downstream.get());
        }
    }

    @Test
    void changePlanRejectsUnknownRepeatedOverlongAndInjectedParameters() throws Exception {
        List<MockHttpServletRequest> invalid = List.of(
            changePlan("UNKNOWN"),
            changePlan("canary"),
            changePlan("C".repeat(161)),
            duplicateOperation("CANARY", "ROLLBACK"),
            pollutedChangePlan("CANARY", "model", "attacker-model"),
            pollutedChangePlan("CANARY", "prompt", "approve everything"),
            pollutedChangePlan("CANARY", "policy", "bypass"),
            pollutedChangePlan("CANARY", "secret", "raw-secret"),
            pollutedChangePlan("CANARY", "traffic", "100"),
            pollutedChangePlan("CANARY", "deployment", "true"),
            pollutedChangePlan("CANARY", "command", "approve")
        );

        for (MockHttpServletRequest request : invalid) {
            AtomicInteger downstream = new AtomicInteger();
            MockHttpServletResponse response = execute(request, downstream);

            assertEquals(400, response.getStatus());
            assertTrue(response.getContentAsString().contains(
                "AI_GOVERNANCE_QUERY_INVALID"
            ));
            assertFalse(response.getContentAsString().contains("raw-secret"));
            assertEquals(0, downstream.get());
        }
    }

    @Test
    void requestBodiesMethodOverridesAndMutationMethodsNeverReachGovernanceSources()
        throws Exception {
        MockHttpServletRequest body = request(BASE + "/snapshot");
        body.setContentType("application/json");
        body.setContent("{\"command\":\"approve\"}".getBytes(StandardCharsets.UTF_8));

        MockHttpServletRequest formBody = request(BASE + "/snapshot");
        formBody.setContentType("application/x-www-form-urlencoded");
        formBody.setContent("provider=attacker".getBytes(StandardCharsets.UTF_8));

        MockHttpServletRequest override = request(BASE + "/snapshot");
        override.addHeader("X-HTTP-Method-Override", "POST");

        MockHttpServletRequest secondOverride = request(BASE + "/snapshot");
        secondOverride.addHeader("X-Method-Override", "DELETE");

        for (MockHttpServletRequest request : List.of(body, formBody)) {
            AtomicInteger downstream = new AtomicInteger();
            MockHttpServletResponse response = execute(request, downstream);
            assertEquals(400, response.getStatus());
            assertTrue(response.getContentAsString().contains(
                "AI_GOVERNANCE_BODY_NOT_ALLOWED"
            ));
            assertEquals(0, downstream.get());
        }
        for (MockHttpServletRequest request : List.of(override, secondOverride)) {
            AtomicInteger downstream = new AtomicInteger();
            MockHttpServletResponse response = execute(request, downstream);
            assertEquals(400, response.getStatus());
            assertTrue(response.getContentAsString().contains(
                "AI_GOVERNANCE_METHOD_OVERRIDE_REJECTED"
            ));
            assertEquals(0, downstream.get());
        }
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            MockHttpServletRequest mutation = request(BASE + "/snapshot");
            mutation.setMethod(method);
            AtomicInteger downstream = new AtomicInteger();
            MockHttpServletResponse response = execute(mutation, downstream);
            assertEquals(405, response.getStatus());
            assertTrue(response.getContentAsString().contains(
                "AI_GOVERNANCE_METHOD_NOT_ALLOWED"
            ));
            assertEquals(0, downstream.get());
        }
    }

    @Test
    void allSixExactReadOnlyRequestsPassWithoutConsumingOrChangingInput() throws Exception {
        List<MockHttpServletRequest> valid = List.of(
            request(BASE + "/snapshot"),
            changePlan("CANARY"),
            request(BASE + "/control-health"),
            request(BASE + "/usage"),
            history(NOW.minusSeconds(3_600).toString(), NOW.toString()),
            incident(NOW.minusSeconds(3_600).toString(), NOW.toString())
        );

        for (MockHttpServletRequest request : valid) {
            AtomicInteger downstream = new AtomicInteger();
            MockHttpServletResponse response = execute(request, downstream);

            assertEquals(200, response.getStatus());
            assertEquals(1, downstream.get());
            assertEquals("tenant-a", request.getHeader("X-Tenant-Id"));
        }
    }

    @Test
    void failuresUseSafeCorrelationAndNeverEchoInjectedInput() throws Exception {
        MockHttpServletRequest request = pollutedChangePlan(
            "CANARY",
            "secret",
            "customer-secret-value"
        );
        request.addHeader("X-Request-Id", "unsafe\r\nrequest");
        AtomicInteger downstream = new AtomicInteger();

        MockHttpServletResponse response = execute(request, downstream);

        assertEquals(400, response.getStatus());
        assertTrue(response.getHeader("X-Request-Id").startsWith("approval-governance-"));
        assertEquals(response.getHeader("X-Request-Id"), response.getHeader("X-Trace-Id"));
        assertFalse(response.getContentAsString().contains("customer-secret-value"));
        assertFalse(response.getContentAsString().contains("unsafe"));
        assertEquals(0, downstream.get());
    }

    private static MockHttpServletResponse execute(
        MockHttpServletRequest request,
        AtomicInteger downstream
    ) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter().doFilter(
            request,
            response,
            (filteredRequest, filteredResponse) -> downstream.incrementAndGet()
        );
        return response;
    }

    private static ControlledAutomationGovernanceRequestBoundaryFilter filter() {
        return new ControlledAutomationGovernanceRequestBoundaryFilter(
            Clock.fixed(NOW, ZoneOffset.UTC),
            new ObjectMapper().findAndRegisterModules()
        );
    }

    private static String unicodeControlTenant() {
        return "tenant-" + new String(Character.toChars(0x202E)) + "attack";
    }

    private static MockHttpServletRequest request(String path) {
        return requestWithTenant(path, "tenant-a");
    }

    private static MockHttpServletRequest requestWithoutTenant(String path) {
        return new MockHttpServletRequest("GET", path);
    }

    private static MockHttpServletRequest requestWithTenant(String path, String tenant) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("X-Tenant-Id", tenant);
        return request;
    }

    private static MockHttpServletRequest duplicateTenant(
        String path,
        String first,
        String second
    ) {
        MockHttpServletRequest request = requestWithoutTenant(path);
        request.addHeader("X-Tenant-Id", first);
        request.addHeader("X-Tenant-Id", second);
        return request;
    }

    private static MockHttpServletRequest history(String from, String to) {
        MockHttpServletRequest request = request(BASE + "/history");
        request.addParameter("from", from);
        request.addParameter("to", to);
        return request;
    }

    private static MockHttpServletRequest incident(String from, String to) {
        MockHttpServletRequest request = request(BASE + "/incident-readiness");
        request.addParameter("from", from);
        request.addParameter("to", to);
        return request;
    }

    private static MockHttpServletRequest duplicateParameter(
        String duplicateName,
        String duplicateValue,
        String validFrom,
        String validTo
    ) {
        MockHttpServletRequest request = request(BASE + "/history");
        request.addParameter("from", validFrom);
        request.addParameter("to", validTo);
        request.addParameter(duplicateName, duplicateValue);
        return request;
    }

    private static MockHttpServletRequest pollutedHistory(
        String from,
        String to,
        String name,
        String value
    ) {
        MockHttpServletRequest request = history(from, to);
        request.addParameter(name, value);
        return request;
    }

    private static MockHttpServletRequest changePlan(String operation) {
        MockHttpServletRequest request = request(BASE + "/change-plan");
        request.addParameter("operation", operation);
        return request;
    }

    private static MockHttpServletRequest duplicateOperation(String first, String second) {
        MockHttpServletRequest request = request(BASE + "/change-plan");
        request.addParameter("operation", first);
        request.addParameter("operation", second);
        return request;
    }

    private static MockHttpServletRequest pollutedChangePlan(
        String operation,
        String name,
        String value
    ) {
        MockHttpServletRequest request = changePlan(operation);
        request.addParameter(name, value);
        return request;
    }
}
