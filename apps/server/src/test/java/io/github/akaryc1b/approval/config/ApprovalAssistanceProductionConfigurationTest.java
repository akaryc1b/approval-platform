package io.github.akaryc1b.approval.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalAssistanceProductionConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-08-04T06:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void runtimeIsDisabledByDefaultWithoutReadingAnyProviderConfiguration() {
        var runtime = ApprovalAssistanceProductionConfiguration.runtime(
            new MockEnvironment(),
            CLOCK
        );

        assertTrue(runtime.isEmpty());
    }

    @Test
    void enabledRuntimeFailsClosedWhenExactSecretVersionIsMissing() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("APPROVAL_AI_OPENAI_ENABLED", "true");

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> ApprovalAssistanceProductionConfiguration.runtime(environment, CLOCK)
        );

        assertEquals(
            "Missing or invalid AI production setting: OPENAI_API_KEY_VERSION",
            failure.getMessage()
        );
    }

    @Test
    void completeServerOwnedProfileCreatesRuntimeWithoutApiKeyMaterial() {
        MockEnvironment environment = completeEnvironment();

        var runtime = ApprovalAssistanceProductionConfiguration.runtime(environment, CLOCK);

        assertTrue(runtime.isPresent());
        assertEquals(
            "key-v1",
            runtime.orElseThrow().profile().secretVersionReference()
        );
        assertEquals(7, runtime.orElseThrow().profile().killSwitchGeneration());
    }

    @Test
    void nonCanonicalEnableFlagFailsClosed() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("APPROVAL_AI_OPENAI_ENABLED", "TRUE");

        assertThrows(
            IllegalStateException.class,
            () -> ApprovalAssistanceProductionConfiguration.runtime(environment, CLOCK)
        );
    }

    private static MockEnvironment completeEnvironment() {
        return new MockEnvironment()
            .withProperty("APPROVAL_AI_OPENAI_ENABLED", "true")
            .withProperty("OPENAI_API_KEY_VERSION", "key-v1")
            .withProperty(
                "APPROVAL_AI_OPENAI_SECRET_EFFECTIVE_FROM",
                NOW.minusSeconds(60).toString()
            )
            .withProperty(
                "APPROVAL_AI_OPENAI_SECRET_EXPIRES_AT",
                NOW.plusSeconds(3_600).toString()
            )
            .withProperty(
                "APPROVAL_AI_OPENAI_SECRET_POLICY_REVISION",
                "secret-policy-v1"
            )
            .withProperty("APPROVAL_AI_OPENAI_KILL_SWITCH_GENERATION", "7")
            .withProperty(
                "APPROVAL_AI_OPENAI_KILL_SWITCH_POLICY_REVISION",
                "kill-switch-policy-v1"
            )
            .withProperty("APPROVAL_AI_OPENAI_COST_POLICY_VERSION", "cost-v1")
            .withProperty(
                "APPROVAL_AI_OPENAI_COST_POLICY_EFFECTIVE_FROM",
                NOW.minusSeconds(60).toString()
            )
            .withProperty(
                "APPROVAL_AI_OPENAI_COST_POLICY_EXPIRES_AT",
                NOW.plusSeconds(3_600).toString()
            )
            .withProperty("APPROVAL_AI_OPENAI_INPUT_MICROS_PER_TOKEN", "1")
            .withProperty("APPROVAL_AI_OPENAI_OUTPUT_MICROS_PER_TOKEN", "2")
            .withProperty("APPROVAL_AI_OPENAI_MAX_REQUEST_MICROS", "1000000")
            .withProperty("APPROVAL_AI_OPENAI_TENANT_RATE_LIMIT", "10")
            .withProperty("APPROVAL_AI_OPENAI_GLOBAL_RATE_LIMIT", "100")
            .withProperty("APPROVAL_AI_OPENAI_RATE_WINDOW_SECONDS", "60")
            .withProperty("APPROVAL_AI_OPENAI_CIRCUIT_FAILURE_THRESHOLD", "3")
            .withProperty("APPROVAL_AI_OPENAI_CIRCUIT_OPEN_SECONDS", "60");
    }
}
