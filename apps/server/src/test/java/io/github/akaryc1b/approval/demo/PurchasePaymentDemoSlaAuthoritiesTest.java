package io.github.akaryc1b.approval.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.security.ApprovalIdentityContextFilter;
import io.github.akaryc1b.approval.security.ApprovalIdentityContextFilter.AuthenticationMode;
import io.github.akaryc1b.approval.security.ApprovalPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PurchasePaymentDemoSlaAuthoritiesTest {

    private static final String CALENDARS = "/api/approval/management/sla/calendars";
    private static final String PERMISSION_HEADER = "X-Approval-Trusted-Permissions";

    @Test
    void requiresCompletedSeedExactTenantActorAndBoundedSlaPath() throws Exception {
        var scenario = scenario();
        var state = new PurchasePaymentDemoSeedState();
        var provider = new PurchasePaymentDemoSlaAuthorities(scenario, state);
        assertTrue(provider.authorities(scenario.tenantId(), scenario.administratorId(), CALENDARS, "GET").isEmpty());
        state.record(new PurchasePaymentDemoSeedState.SeedEvidence(
            scenario.tenantId(), scenario.request().businessKey(), UUID.randomUUID(),
            InstanceStatus.RUNNING, "purchase-payment", "engine-demo", List.of(), List.of(), Instant.now()
        ));
        Set<String> expected = Set.of("approval.management.sla.read");
        assertEquals(expected, provider.authorities(scenario.tenantId(), scenario.administratorId(), CALENDARS, "GET"));
        assertEquals(Set.of("approval.management.sla.publish"), provider.authorities(scenario.tenantId(), scenario.administratorId(),
            "/api/approval/management/sla/policies/" + UUID.randomUUID() + "/versions/1/publish", "POST"));
        for (String actor : new String[] { "demo-employee", "unknown", "demo-admin-extra" }) {
            assertTrue(provider.authorities(scenario.tenantId(), actor, CALENDARS, "GET").isEmpty());
        }
        assertTrue(provider.authorities("wrong-tenant", scenario.administratorId(), CALENDARS, "GET").isEmpty());
        for (String path : new String[] { CALENDARS + "-other", "/api/approval/management/sla/executions",
            "/api/approval/management/audit", "/api/approval/process-design-drafts/from-published",
            CALENDARS + "/../executions", CALENDARS + "/%2e%2e/executions",
            CALENDARS + ";ignored", CALENDARS + "//", CALENDARS + "/arbitrary",
            CALENDARS + "/" + UUID.randomUUID() + "/versions/0",
            CALENDARS + "/" + UUID.randomUUID() + "/versions/1/unknown" }) {
            assertTrue(provider.authorities(scenario.tenantId(), scenario.administratorId(), path, "GET").isEmpty());
        }
        for (boolean forgeHeader : new boolean[] { false, true }) {
            var request = new MockHttpServletRequest("GET", CALENDARS);
            request.addHeader("X-Tenant-Id", scenario.tenantId());
            request.addHeader("X-Operator-Id", scenario.administratorId());
            if (forgeHeader) {
                request.addHeader(PERMISSION_HEADER, "approval.management.admin");
            }
            var trusted = new AtomicReference<HttpServletRequest>();
            new ApprovalIdentityContextFilter(AuthenticationMode.LOCAL_HEADERS, PERMISSION_HEADER, Clock.systemUTC(),
                new ObjectMapper(), () -> "test-sla-request", provider)
                .doFilter(request, new MockHttpServletResponse(), (filtered, response) ->
                    trusted.set((HttpServletRequest) filtered));
            var principal = (ApprovalPrincipal) trusted.get().getUserPrincipal();
            assertEquals(expected, principal.authorities());
            assertNull(trusted.get().getHeader(PERMISSION_HEADER));
        }
    }

    @Test
    void unrelatedSeedEvidenceCannotAuthorizeTheConfiguredAdministrator() throws Exception {
        var scenario = scenario();
        var state = new PurchasePaymentDemoSeedState();
        state.record(new PurchasePaymentDemoSeedState.SeedEvidence(
            "another-tenant", scenario.request().businessKey(), UUID.randomUUID(),
            InstanceStatus.RUNNING, "purchase-payment", "engine-demo", List.of(), List.of(), Instant.now()
        ));
        var provider = new PurchasePaymentDemoSlaAuthorities(scenario, state);
        assertTrue(provider.authorities(scenario.tenantId(), scenario.administratorId(), CALENDARS, "GET").isEmpty());
    }

    @Test
    void productionPrincipalNeverConsultsLocalProvider() throws Exception {
        var principal = ApprovalPrincipal.active("tenant-a", "operator-a", Set.of(), null);
        var request = new MockHttpServletRequest("GET", CALENDARS);
        request.setUserPrincipal(principal);
        var trusted = new AtomicReference<HttpServletRequest>();
        new ApprovalIdentityContextFilter(AuthenticationMode.PRINCIPAL, PERMISSION_HEADER, Clock.systemUTC(),
            new ObjectMapper(), () -> "test-principal-request", (tenant, operator, path, method) -> {
                throw new AssertionError("principal mode must not consult a local provider");
            }).doFilter(request, new MockHttpServletResponse(), (filtered, response) ->
                trusted.set((HttpServletRequest) filtered));
        assertSame(principal, trusted.get().getUserPrincipal());
        assertTrue(principal.authorities().isEmpty());
    }

    @Test
    void designCapabilitiesAreExactMethodPathAndNeverGeneralAdministrator() throws Exception {
        var scenario = scenario();
        var state = new PurchasePaymentDemoSeedState();
        state.record(new PurchasePaymentDemoSeedState.SeedEvidence(
            scenario.tenantId(), scenario.request().businessKey(), UUID.randomUUID(),
            InstanceStatus.RUNNING, "purchase-payment", "engine-demo", List.of(), List.of(), Instant.now()
        ));
        var provider = new PurchasePaymentDemoSlaAuthorities(scenario, state);
        String id = UUID.randomUUID().toString();
        String form = "/api/approval/form-design-drafts";
        String process = "/api/approval/process-design-drafts";
        String[][] allowed = {
            { "GET", form, "read" }, { "POST", form, "design" },
            { "GET", form + "/" + id, "read" }, { "PUT", form + "/" + id, "design" },
            { "POST", form + "/" + id + "/validate", "design" },
            { "POST", form + "/" + id + "/preview", "read" },
            { "GET", form + "/" + id + "/contexts", "read" },
            { "POST", form + "/" + id + "/publish", "publish" },
            { "GET", process, "read" }, { "POST", process, "design" },
            { "GET", process + "/" + id, "read" }, { "PUT", process + "/" + id, "design" },
            { "POST", process + "/" + id + "/validate", "design" },
            { "POST", process + "/" + id + "/simulate", "design" },
            { "POST", process + "/" + id + "/publish", "publish" },
            { "POST", "/api/approval/preflight/publication", "publish" }
        };
        for (String[] value : allowed) {
            assertEquals(Set.of("approval.management." + value[2]), provider.authorities(
                scenario.tenantId(), scenario.administratorId(), value[1], value[0]));
            assertTrue(provider.authorities("other-tenant", scenario.administratorId(), value[1], value[0]).isEmpty());
            assertTrue(provider.authorities(scenario.tenantId(), "demo-employee", value[1], value[0]).isEmpty());
            for (String method : new String[] { "DELETE", "PATCH", "OPTIONS", "HEAD", "get", "" }) {
                assertTrue(provider.authorities(scenario.tenantId(), scenario.administratorId(), value[1], method).isEmpty());
            }
            for (String suffix : new String[] { ";ignored", "/", "/../publish", "/%2e%2e/publish" }) {
                assertTrue(provider.authorities(scenario.tenantId(), scenario.administratorId(), value[1] + suffix, value[0]).isEmpty());
            }
        }
        for (String path : new String[] { form + "/from-published", form + "/" + id + "/archive",
            process + "/from-published", process + "/" + id + "/archive",
            process + "/" + id + "/batch-simulate", "/api/approval/preflight/deployment",
            "/api/approval/artifact-transfer/imports", "/api/approval/management/audit",
            "/api/approval/release-packages/isolated/1/deployment" }) {
            for (String method : new String[] { "GET", "POST", "PUT" }) {
                assertTrue(provider.authorities(scenario.tenantId(), scenario.administratorId(), path, method).isEmpty());
            }
        }
        for (String root : new String[] { form, process }) {
            assertTrue(provider.authorities(scenario.tenantId(), scenario.administratorId(), root + "/" + id + "/publish", "GET").isEmpty());
            assertTrue(provider.authorities(scenario.tenantId(), scenario.administratorId(), root + "/" + id, "POST").isEmpty());
            assertTrue(provider.authorities(scenario.tenantId(), scenario.administratorId(), root, "PUT").isEmpty());
        }
    }

    private static PurchasePaymentDemoScenario scenario() throws Exception {
        return PurchasePaymentDemoScenario.load(new ObjectMapper().findAndRegisterModules(),
            new ClassPathResource("demo/purchase-payment-golden-path.json"),
            new ClassPathResource("demo/purchase-payment-demo-seed.json"));
    }
}
