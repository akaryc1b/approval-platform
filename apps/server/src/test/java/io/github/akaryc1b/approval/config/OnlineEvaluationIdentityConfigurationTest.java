package io.github.akaryc1b.approval.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.akaryc1b.approval.security.OnlineEvaluationReadIdentityFilter;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OnlineEvaluationIdentityConfigurationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockEnvironment environment() {
        var environment = new MockEnvironment(); environment.setActiveProfiles("online-demo");
        environment.setProperty("server.address", "127.0.0.1");
        environment.setProperty("approval.security.identity.mode", "principal");
        environment.setProperty("management.endpoints.web.exposure.include", "health");
        return environment;
    }
    private JsonNode scenario() throws Exception {
        try (var input = new ClassPathResource("demo/purchase-payment-golden-path.json").getInputStream()) {
            return mapper.readTree(input);
        }
    }
    @Test void usesCanonicalBusinessActorsAndRegistersBeforeExistingIdentityFilter() throws Exception {
        assertEquals(Set.of("demo-employee", "demo-manager", "demo-finance-reviewer",
            "demo-finance-approver-a", "demo-finance-approver-b"),
            OnlineEvaluationIdentityConfiguration.canonicalActors(scenario()));
        var environment = environment();
        environment.setProperty("approval.evaluation.public-key", Base64.getEncoder().encodeToString(
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded()));
        environment.setProperty("approval.evaluation.generation", "a".repeat(32));
        var registration = new OnlineEvaluationIdentityConfiguration().onlineEvaluationReadIdentity(environment, mapper, Clock.systemUTC());
        assertInstanceOf(OnlineEvaluationReadIdentityFilter.class, registration.getFilter());
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 10, registration.getOrder());
        assertEquals(Set.of("/*"), Set.copyOf(registration.getUrlPatterns()));
        assertFalse(registration.isAsyncSupported());
    }
    @Test void inactiveProfileDoesNotInstallAnyNewIdentityAuthority() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("production");
            context.register(OnlineEvaluationIdentityConfiguration.class); context.refresh();
            assertFalse(context.containsBean("onlineEvaluationReadIdentity"));
        }
    }
    @Test void missingKeyOrGenerationCannotStartTheOptInFilter() {
        assertThrows(IllegalStateException.class, () -> new OnlineEvaluationIdentityConfiguration()
            .onlineEvaluationReadIdentity(environment(), mapper, Clock.systemUTC()));
    }
    @Test void localTestProductionAndMixedProfilesCannotEnableThisBridge() {
        for (String[] profiles : new String[][] { {}, {"local"}, {"test"}, {"production"},
            {"online-demo", "local"}, {"online-demo", "test"}, {"online-demo", "production"} }) {
            var env = environment(); env.setActiveProfiles(profiles);
            assertThrows(IllegalStateException.class, () -> OnlineEvaluationIdentityConfiguration.requireConfiguration(env));
        }
    }
    @Test void configurationCannotWidenIdentityNetworkOrManagementExposure() {
        for (String[] pair : new String[][] { {"server.address", "0.0.0.0"},
            {"approval.security.identity.mode", "local-headers"},
            {"approval.security.management-permissions.enforced", "false"},
            {"approval.security.management-permissions.trusted-header-name", "X-Other"},
            {"management.endpoints.web.exposure.include", "health,env"}, {"server.servlet.context-path", "/other"} }) {
            var env = environment(); env.setProperty(pair[0], pair[1]);
            assertThrows(IllegalStateException.class, () -> OnlineEvaluationIdentityConfiguration.requireConfiguration(env));
        }
        for (String property : Set.of("approval.connector.generic.enabled", "approval.connector.generic.dispatch.enabled",
            "approval.connector.invocation.enabled", "approval.connector.secret-material.enabled",
            "approval.migration.execution.enabled", "approval.migration.worker.enabled",
            "approval.migration.orchestration.enabled", "flowable.async-executor-activate")) {
            var env = environment(); env.setProperty(property, "true");
            assertThrows(IllegalStateException.class, () -> OnlineEvaluationIdentityConfiguration.requireConfiguration(env));
        }
    }
    @Test void alteredScenarioCannotSupplyAnAdministrativeOrDifferentTenantIdentity() throws Exception {
        JsonNode altered = scenario();
        ((ObjectNode) altered.path("tenant")).put("id", "different-tenant");
        assertThrows(IllegalStateException.class, () -> OnlineEvaluationIdentityConfiguration.canonicalActors(altered));
        JsonNode admin = scenario();
        ((ObjectNode) admin.path("assigneeRules").path("initiatorUserId")).put("value", "demo-admin");
        assertThrows(IllegalStateException.class, () -> OnlineEvaluationIdentityConfiguration.canonicalActors(admin));
        JsonNode privileged = scenario();
        for (JsonNode user : privileged.path("directory").path("users")) if (user.path("id").asText().equals("demo-employee")) {
            ((ObjectNode) user).putArray("roleCodes").add("APPROVAL_ADMIN");
        }
        assertThrows(IllegalStateException.class, () -> OnlineEvaluationIdentityConfiguration.canonicalActors(privileged));
    }
}
