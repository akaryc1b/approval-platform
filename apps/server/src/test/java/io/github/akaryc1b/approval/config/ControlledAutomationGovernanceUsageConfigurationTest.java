package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProductionRuntimeFactory;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceUsageContracts.UsageHealth;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlledAutomationGovernanceUsageConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-08-05T11:40:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void disabledSharedRuntimeProducesBlockedUsageWithoutTenantEvidence() {
        var configuration = new ControlledAutomationGovernanceConfiguration();
        var runtime = ApprovalAssistanceProductionRuntime.disabled();
        var snapshotSource = configuration.controlledAutomationGovernanceSnapshotSource(
            runtime,
            CLOCK
        );
        var usageSource = configuration.controlledAutomationGovernanceUsageSource(
            runtime,
            snapshotSource
        );

        var view = usageSource.current("tenant-a");

        assertEquals(UsageHealth.NOT_CONFIGURED, view.usageHealth());
        assertNull(view.tenantUsage());
        assertTrue(view.blockerCodes().contains("AI_PROVIDER_RUNTIME_NOT_CONFIGURED"));
        assertFalse(view.globalExactUsageExposed());
        assertFalse(view.otherTenantUsageExposed());
    }

    @Test
    void configuredSharedRuntimeProducesZeroTenantUsageWithoutCreatingWork() {
        var configuration = new ControlledAutomationGovernanceConfiguration();
        var factory = new OpenAiResponsesProductionRuntimeFactory(profile(), CLOCK);
        var runtime = ApprovalAssistanceProductionRuntime.configured(factory);
        var snapshotSource = configuration.controlledAutomationGovernanceSnapshotSource(
            runtime,
            CLOCK
        );
        var usageSource = configuration.controlledAutomationGovernanceUsageSource(
            runtime,
            snapshotSource
        );

        var view = usageSource.current("tenant-a");

        assertEquals(UsageHealth.WITHIN_DERIVED_ENVELOPE, view.usageHealth());
        assertEquals(0, view.tenantUsage().committedRequests());
        assertEquals(10, view.tenantUsage().requestLimit());
        assertEquals(10, view.tenantUsage().remainingRequests());
        assertEquals(10_000_000, view.tenantUsage().derivedEnvelopeMicros());
        assertFalse(view.tenantUsage().durable());
        assertFalse(view.tenantUsage().actualProviderCost());
        assertFalse(view.providerInvocationAvailable());
        assertFalse(view.commandExecutionAuthorized());
    }

    private static OpenAiResponsesProductionRuntimeFactory.RuntimeProfile profile() {
        return new OpenAiResponsesProductionRuntimeFactory.RuntimeProfile(
            "key-v1",
            NOW.minusSeconds(60),
            NOW.plusSeconds(3_600),
            "secret-policy-v1",
            7,
            "kill-switch-policy-v1",
            "cost-v1",
            NOW.minusSeconds(60),
            NOW.plusSeconds(3_600),
            1,
            2,
            1_000_000,
            10,
            100,
            Duration.ofSeconds(60),
            3,
            Duration.ofSeconds(60)
        );
    }
}
