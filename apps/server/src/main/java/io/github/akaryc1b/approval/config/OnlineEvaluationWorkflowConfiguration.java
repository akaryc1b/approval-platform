package io.github.akaryc1b.approval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.security.OnlineEvaluationBusinessFilter;
import io.github.akaryc1b.approval.security.OnlineEvaluationBusinessTicket;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Clock;
import java.util.Set;

/** Explicit opt-in for the bounded purchase-payment workflow, never a production profile. */
@Configuration(proxyBeanMethods = false)
@Profile("online-demo")
@ConditionalOnProperty(name = "approval.evaluation.workflow-enabled", havingValue = "true")
public class OnlineEvaluationWorkflowConfiguration {
    @Bean
    @DependsOn("onlineEvaluationDatabaseMigration")
    FilterRegistrationBean<OnlineEvaluationBusinessFilter> onlineEvaluationBusinessIdentity(
        Environment env, ObjectMapper mapper, Clock approvalClock
    ) throws IOException {
        requireConfiguration(env);
        try (var input = new ClassPathResource("demo/purchase-payment-golden-path.json").getInputStream()) {
            var actors = OnlineEvaluationIdentityConfiguration.canonicalActors(mapper.readTree(input));
            var verifier = new OnlineEvaluationBusinessTicket(env.getRequiredProperty("approval.evaluation.public-key"),
                env.getRequiredProperty("approval.evaluation.generation"), actors, approvalClock);
            var registration = new FilterRegistrationBean<>(new OnlineEvaluationBusinessFilter(verifier));
            registration.setName("onlineEvaluationBusinessIdentity"); registration.addUrlPatterns("/*");
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10); registration.setAsyncSupported(false);
            return registration;
        }
    }
    public static void requireConfiguration(Environment env) {
        require(Set.of(env.getActiveProfiles()).equals(Set.of("online-demo")));
        for (String[] pair : new String[][] {
            {"server.address", "127.0.0.1"}, {"server.servlet.context-path", ""},
            {"approval.security.identity.mode", "principal"},
            {"approval.security.management-permissions.enforced", "true"},
            {"approval.security.management-permissions.trusted-header-name", "X-Approval-Trusted-Permissions"},
            {"management.endpoints.web.exposure.include", "health"},
            {"approval.connector.generic.enabled", "true"}, {"approval.connector.generic.dispatch.enabled", "true"},
            {"approval.connector.generic.connector-key", "demo-directory"},
            {"approval.connector.generic.host-base-uri", "http://127.0.0.1:8080"},
            {"approval.connector.generic.callback-uri", "http://127.0.0.1:8080/payment-sandbox/v1/events"},
            {"approval.demo.purchase-payment.enabled", "true"}, {"approval.demo.purchase-payment.sandbox.enabled", "true"},
            {"approval.demo.purchase-payment.sandbox.control-file", "/tmp/evaluation-payment-control"},
            {"approval.demo.purchase-payment.sandbox.status-file", "/tmp/evaluation-payment-status.json"},
            {"approval.demo.purchase-payment.sandbox.event-allowlist-file", "/tmp/evaluation-payment-events"}
        }) require(pair[1].equals(env.getProperty(pair[0])));
        for (String property : Set.of("approval.connector.invocation.enabled", "approval.connector.secret-material.enabled",
            "approval.connector.dingtalk-token.enabled", "approval.connector.tenant-routing.enabled",
            "approval.migration.execution.enabled", "approval.migration.worker.enabled",
            "approval.migration.orchestration.enabled", "flowable.async-executor-activate")) {
            require("false".equals(env.getProperty(property, "false")));
        }
        require(env.getRequiredProperty("approval.connector.generic.secret").matches("[0-9a-f]{64}"));
        require(env.getRequiredProperty("approval.connector.generic.key-id").matches("evaluation-[0-9a-f]{32}"));
    }
    private static void require(boolean value) {
        if (!value) throw new IllegalStateException("INVALID_ONLINE_DEMO_WORKFLOW_CONFIGURATION");
    }
}
