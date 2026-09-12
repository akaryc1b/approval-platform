package io.github.akaryc1b.approval.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.security.OnlineEvaluationReadIdentityFilter;
import io.github.akaryc1b.approval.security.OnlineEvaluationReadTicket;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Private read-only evaluation profile, disjoint from local/test/production identity modes. */
@Configuration(proxyBeanMethods = false)
@Profile("online-demo")
@ConditionalOnProperty(name = "approval.evaluation.workflow-enabled", havingValue = "false", matchIfMissing = true)
public class OnlineEvaluationIdentityConfiguration {
    @Bean
    @DependsOn("onlineEvaluationDatabaseMigration")
    FilterRegistrationBean<OnlineEvaluationReadIdentityFilter> onlineEvaluationReadIdentity(
        Environment environment, ObjectMapper mapper, Clock approvalClock
    ) throws IOException {
        requireConfiguration(environment);
        JsonNode scenario;
        try (var input = new ClassPathResource("demo/purchase-payment-golden-path.json").getInputStream()) {
            scenario = mapper.readTree(input);
        }
        Set<String> actors = canonicalActors(scenario);
        var verifier = new OnlineEvaluationReadTicket(
            environment.getRequiredProperty("approval.evaluation.public-key"),
            environment.getRequiredProperty("approval.evaluation.generation"), actors, approvalClock);
        var registration = new FilterRegistrationBean<>(new OnlineEvaluationReadIdentityFilter(
            verifier, scenario.path("tenant").path("id").asText()));
        registration.setName("onlineEvaluationReadIdentity");
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setAsyncSupported(false);
        return registration;
    }

    public static void requireConfiguration(Environment environment) {
        require(Set.of(environment.getActiveProfiles()).equals(Set.of("online-demo")));
        require("127.0.0.1".equals(environment.getProperty("server.address")));
        require("principal".equals(environment.getProperty("approval.security.identity.mode")));
        require("true".equals(environment.getProperty("approval.security.management-permissions.enforced", "true")));
        require("X-Approval-Trusted-Permissions".equals(environment.getProperty(
            "approval.security.management-permissions.trusted-header-name", "X-Approval-Trusted-Permissions")));
        require("health".equals(environment.getProperty("management.endpoints.web.exposure.include")));
        require(environment.getProperty("server.servlet.context-path", "").isEmpty());
        for (String property : Set.of("approval.connector.generic.enabled", "approval.connector.generic.dispatch.enabled",
            "approval.connector.invocation.enabled", "approval.connector.secret-material.enabled",
            "approval.migration.execution.enabled", "approval.migration.worker.enabled",
            "approval.migration.orchestration.enabled", "flowable.async-executor-activate")) {
            require("false".equals(environment.getProperty(property, "false")));
        }
    }

    static Set<String> canonicalActors(JsonNode scenario) {
        require(scenario.path("schemaVersion").asInt() == 1
            && "demo-purchase-payment".equals(scenario.path("tenant").path("id").asText())
            && scenario.path("directory").path("users").isArray() && scenario.path("expectedWorkflow").isArray());
        var users = new HashMap<String, JsonNode>();
        for (JsonNode user : scenario.path("directory").path("users")) {
            String id = user.path("id").asText();
            require(!id.isEmpty() && users.putIfAbsent(id, user) == null && user.path("roleCodes").isArray());
        }
        var actors = new LinkedHashSet<String>();
        actors.add(scenario.path("assigneeRules").path("initiatorUserId").path("value").asText());
        for (JsonNode step : scenario.path("expectedWorkflow")) {
            require(step.path("actorIds").isArray() && !step.path("actorIds").isEmpty());
            for (JsonNode actor : step.path("actorIds")) actors.add(actor.asText());
        }
        require(actors.size() == 5);
        for (String id : actors) {
            require(id.matches("demo-[a-z0-9-]{1,58}") && !id.contains("admin") && users.containsKey(id));
            for (JsonNode role : users.get(id).path("roleCodes")) {
                require(role.isTextual() && !role.asText().toUpperCase(Locale.ROOT).contains("ADMIN")
                    && !role.asText().contains("*"));
            }
        }
        return Set.copyOf(actors);
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalStateException("INVALID_ONLINE_DEMO_READ_CONFIGURATION");
    }
}
