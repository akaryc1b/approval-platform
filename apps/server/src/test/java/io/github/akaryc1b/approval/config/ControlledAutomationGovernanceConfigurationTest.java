package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceControlHealthContracts
    .CircuitHealth;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceControlHealthContracts
    .DriftHealth;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.ActivationState;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.RuntimeState;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlledAutomationGovernanceConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-08-05T09:20:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void absentProductionRuntimeProducesBlockedReadOnlySnapshot() {
        var runtime = runtime(new MockEnvironment());
        var source = new ControlledAutomationGovernanceConfiguration()
            .controlledAutomationGovernanceSnapshotSource(runtime, CLOCK);

        var view = source.current();

        assertEquals(RuntimeState.NOT_CONFIGURED, view.runtimeState());
        assertEquals(ActivationState.BLOCKED, view.activationState());
        assertNull(view.controls());
        assertEquals(3, view.inventory().size());
        assertFalse(view.providerMutationAvailable());
        assertFalse(view.commandExecutionAuthorized());
    }

    @Test
    void completeProductionProfileProducesExactAdvisoryOnlyControlEvidence() {
        var runtime = runtime(completeEnvironment());
        var source = new ControlledAutomationGovernanceConfiguration()
            .controlledAutomationGovernanceSnapshotSource(runtime, CLOCK);

        var view = source.current();

        assertEquals(RuntimeState.CONFIGURED_ADVISORY_ONLY, view.runtimeState());
        assertEquals(ActivationState.ADVISORY_ONLY, view.activationState());
        assertEquals(7, view.controls().killSwitchGeneration());
        assertEquals(10, view.controls().perTenantRateLimit());
        assertEquals(100, view.controls().globalRateLimit());
        assertEquals(60, view.controls().rateWindowSeconds());
        assertEquals(3, view.controls().circuitFailureThreshold());
        assertEquals(60, view.controls().circuitOpenSeconds());
        assertEquals(1_000_000, view.controls().maximumRequestMicros());
        assertEquals(64, view.controls().killSwitchEvidenceHash().length());
        assertEquals(64, view.controls().costPolicyEvidenceHash().length());
        assertEquals(
            OpenAiResponsesProtocol.sha256Utf8("key-v1"),
            view.controls().secretVersionEvidenceHash()
        );
        assertTrue(view.inventory().stream().allMatch(entry ->
            entry.versions().provider().providerId().equals("openai-responses")
                && entry.versions().model().modelId().equals("gpt-5-mini")
                && entry.versions().policy().version().equals("p6-e-v1")
        ));
        assertFalse(view.rawSecretExposed());
        assertFalse(view.automaticRetryAuthorized());
    }

    @Test
    void controlHealthUsesTheSameSharedRuntimeAsTheGenerationPath() {
        var productionRuntime = runtime(completeEnvironment());
        var configuration = new ControlledAutomationGovernanceConfiguration();
        var snapshotSource = configuration.controlledAutomationGovernanceSnapshotSource(
            productionRuntime,
            CLOCK
        );
        var healthSource = configuration.controlledAutomationGovernanceControlHealthSource(
            productionRuntime,
            snapshotSource
        );

        var health = healthSource.current();

        assertEquals(RuntimeState.CONFIGURED_ADVISORY_ONLY, health.runtimeState());
        assertEquals(
            DriftHealth.EXACT_FROZEN_PROFILE,
            health.runtimeEvidence().driftHealth()
        );
        assertEquals(CircuitHealth.CLOSED, health.runtimeEvidence().circuitHealth());
        assertEquals(1, health.runtimeEvidence().circuitGeneration());
        assertFalse(health.runtimeEvidence().rateUsageExposed());
        assertFalse(health.runtimeEvidence().budgetConsumptionExposed());
    }

    private static ApprovalAssistanceProductionRuntime runtime(
        MockEnvironment environment
    ) {
        return new ApprovalAssistanceProductionRuntime(
            ApprovalAssistanceProductionConfiguration.runtime(environment, CLOCK)
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
