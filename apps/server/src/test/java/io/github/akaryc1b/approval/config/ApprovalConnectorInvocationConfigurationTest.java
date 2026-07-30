package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .InvocationPolicy;
import io.github.akaryc1b.approval.connector.invocation.GovernedReadOnlyConnectorInvocationCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalConnectorInvocationConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ApprovalConnectorInvocationConfiguration.class);

    @Test
    void invocationCoordinatorIsDisabledByDefault() {
        runner.run(context -> {
            assertFalse(context.containsBean("governedReadOnlyConnectorInvocationCoordinator"));
            assertEquals(
                0,
                context.getBeansOfType(
                    GovernedReadOnlyConnectorInvocationCoordinator.class
                ).size()
            );
        });
    }

    @Test
    void enablingWithoutEveryServerOwnedDependencyFailsClosed() {
        runner.withPropertyValues(
            "approval.connector.invocation.enabled=true"
        ).run(context -> assertTrue(context.getStartupFailure() != null));
    }

    @Test
    void unknownPropertiesFailClosed() {
        runner.withPropertyValues(
            "approval.connector.invocation.enabled=true",
            "approval.connector.invocation.unknown-authority=true"
        ).run(context -> assertTrue(context.getStartupFailure() != null));
    }

    @Test
    void propertiesCreateOnlyABoundedReadOnlyPolicy() {
        ApprovalConnectorInvocationProperties properties =
            new ApprovalConnectorInvocationProperties();
        properties.setPolicyVersion("connector-invocation-policy-v2");
        properties.setMaximumRequestBytes(8_192);
        properties.setMaximumResponseBytes(32_768);
        properties.setTimeout(Duration.ofSeconds(3));
        properties.setKillSwitchRevision("kill-switch-v2");
        properties.setTokenPolicyVersion("token-policy-v2");

        InvocationPolicy policy = properties.toPolicy();

        assertEquals("connector-invocation-policy-v2", policy.policyVersion());
        assertEquals(8_192, policy.maximumRequestBytes());
        assertEquals(32_768, policy.maximumResponseBytes());
        assertEquals(Duration.ofSeconds(3), policy.timeout());
    }
}
