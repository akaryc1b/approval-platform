package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenCoordinator;
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalDingTalkTokenConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ApprovalDingTalkTokenConfiguration.class);

    @Test
    void tokenLifecycleIsDisabledByDefault() {
        runner.run(context -> {
            assertFalse(context.containsBean("dingTalkTokenPolicy"));
            assertFalse(context.containsBean("dingTalkTokenRouteGate"));
            assertFalse(context.containsBean("dingTalkTokenCoordinator"));
            assertEquals(0, context.getBeansOfType(DingTalkTokenCoordinator.class).size());
        });
    }

    @Test
    void enablingWithoutEveryServerOwnedDependencyFailsClosed() {
        runner.withPropertyValues(
            "approval.connector.dingtalk-token.enabled=true"
        ).run(context -> assertTrue(context.getStartupFailure() != null));
    }

    @Test
    void propertiesCreateOnlyABoundedPolicy() {
        ApprovalDingTalkTokenProperties properties = new ApprovalDingTalkTokenProperties();
        properties.setPolicyVersion("policy-v2");
        properties.setRefreshBeforeExpiry(Duration.ofSeconds(20));
        properties.setMinimumValidity(Duration.ofSeconds(10));
        properties.setMaximumLifetime(Duration.ofMinutes(10));
        properties.setSingleFlightWait(Duration.ofSeconds(2));
        properties.setMaximumEntries(32);

        DingTalkTokenPolicy policy = properties.toPolicy();
        assertEquals("policy-v2", policy.policyVersion());
        assertEquals(32, policy.maximumEntries());
    }
}
