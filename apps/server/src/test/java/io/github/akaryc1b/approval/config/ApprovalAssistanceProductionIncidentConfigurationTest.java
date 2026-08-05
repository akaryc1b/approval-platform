package io.github.akaryc1b.approval.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalAssistanceProductionIncidentConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-08-04T06:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void emergencyDisableIgnoresBrokenProviderSettingsAndCreatesNoRuntime() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("APPROVAL_AI_OPENAI_ENABLED", "false")
            .withProperty("OPENAI_API_KEY_VERSION", " padded ")
            .withProperty("APPROVAL_AI_OPENAI_SECRET_EXPIRES_AT", "not-an-instant")
            .withProperty("APPROVAL_AI_OPENAI_GLOBAL_RATE_LIMIT", "0");

        var runtime = ApprovalAssistanceProductionConfiguration.runtime(environment, CLOCK);

        assertTrue(runtime.isEmpty());
    }

    @Test
    void expiredSecretVersionBlocksRuntimeActivation() {
        MockEnvironment environment = completeEnvironment("key-v1")
            .withProperty(
                "APPROVAL_AI_OPENAI_SECRET_EXPIRES_AT",
                NOW.toString()
            );

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> ApprovalAssistanceProductionConfiguration.runtime(environment, CLOCK)
        );

        assertEquals(
            "AI production version policy is not currently valid",
            failure.getMessage()
        );
    }

    @Test
    void futureCostPolicyBlocksRuntimeActivation() {
        MockEnvironment environment = completeEnvironment("key-v1")
            .withProperty(
                "APPROVAL_AI_OPENAI_COST_POLICY_EFFECTIVE_FROM",
                NOW.plusSeconds(1).toString()
            );

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> ApprovalAssistanceProductionConfiguration.runtime(environment, CLOCK)
        );

        assertEquals(
            "AI production version policy is not currently valid",
            failure.getMessage()
        );
    }

    @Test
    void secretRotationRequiresAChangedExactVersionReference() {
        var first = ApprovalAssistanceProductionConfiguration.runtime(
            completeEnvironment("key-v1"),
            CLOCK
        ).orElseThrow();
        var rotated = ApprovalAssistanceProductionConfiguration.runtime(
            completeEnvironment("key-v2"),
            CLOCK
        ).orElseThrow();

        assertEquals("key-v1", first.profile().secretVersionReference());
        assertEquals("key-v2", rotated.profile().secretVersionReference());
        assertTrue(
            !first.profile().secretVersionReference().equals(
                rotated.profile().secretVersionReference()
            )
        );
    }

    private static MockEnvironment completeEnvironment(String secretVersion) {
        return new MockEnvironment()
            .withProperty("APPROVAL_AI_OPENAI_ENABLED", "true")
            .withProperty("OPENAI_API_KEY_VERSION", secretVersion)
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
