package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesAdvisoryProvider;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesRuntimeUsageLedger;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.InventoryEntry;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.OperationsView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.RuntimeControls;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceUsageContracts.CostBasis;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceUsageContracts.UsageHealth;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceUsageContracts.UsageView;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlledAutomationGovernanceUsageContractsTest {

    private static final Instant NOW = Instant.parse("2026-08-05T11:30:00Z");
    private static final String TENANT_HASH = OpenAiResponsesProtocol.sha256Utf8("tenant-a");
    private static final String KILL_HASH = OpenAiResponsesProtocol.sha256Utf8("kill");
    private static final String COST_HASH = OpenAiResponsesProtocol.sha256Utf8("cost");
    private static final String SECRET_HASH = OpenAiResponsesProtocol.sha256Utf8("secret");

    @Test
    void disabledRuntimeCarriesNoUsageAndRemainsBlocked() {
        UsageView view = UsageView.disabled(OperationsView.disabled(NOW, inventory()));

        assertEquals(UsageHealth.NOT_CONFIGURED, view.usageHealth());
        assertNull(view.tenantUsage());
        assertTrue(view.blockerCodes().contains("AI_PROVIDER_RUNTIME_NOT_CONFIGURED"));
        assertFalse(view.globalExactUsageExposed());
        assertFalse(view.otherTenantUsageExposed());
        assertFalse(view.commandExecutionAuthorized());
    }

    @Test
    void configuredRuntimeReportsTenantOnlyCommittedUpperBounds() {
        OpenAiResponsesRuntimeUsageLedger ledger = ledger(3, 10);
        ledger.recordDispatched(TENANT_HASH, NOW, 250);

        UsageView view = UsageView.configured(
            configuredSource(),
            ledger.snapshot(TENANT_HASH, NOW)
        );

        assertEquals(UsageHealth.WITHIN_DERIVED_ENVELOPE, view.usageHealth());
        assertEquals(1, view.tenantUsage().committedRequests());
        assertEquals(2, view.tenantUsage().remainingRequests());
        assertEquals(250, view.tenantUsage().committedUpperBoundMicros());
        assertEquals(3_000, view.tenantUsage().derivedEnvelopeMicros());
        assertEquals(
            CostBasis.ADMISSION_ESTIMATE_UPPER_BOUND_NOT_ACTUAL_PROVIDER_BILLING,
            view.tenantUsage().costBasis()
        );
        assertTrue(view.blockerCodes().contains("AI_USAGE_HISTORY_NOT_DURABLE"));
        assertTrue(
            view.blockerCodes().contains("AI_USAGE_ACTUAL_PROVIDER_COST_NOT_AVAILABLE")
        );
        assertFalse(view.globalExactUsageExposed());
        assertFalse(view.otherTenantUsageExposed());
        assertFalse(view.usageMutationAvailable());
        assertFalse(view.providerInvocationAvailable());
        assertFalse(view.rawSecretExposed());
        assertEquals(64, view.evidenceHash().length());
    }

    @Test
    void tenantAndGlobalSaturationProduceOnlyClosedFailClosedStates() {
        OpenAiResponsesRuntimeUsageLedger tenantLedger = ledger(1, 2);
        tenantLedger.recordDispatched(TENANT_HASH, NOW, 100);
        UsageView tenantView = UsageView.configured(
            configuredSource(),
            tenantLedger.snapshot(TENANT_HASH, NOW)
        );
        assertEquals(
            UsageHealth.TENANT_RATE_WINDOW_SATURATED,
            tenantView.usageHealth()
        );
        assertTrue(
            tenantView.blockerCodes().contains("AI_TENANT_RATE_WINDOW_SATURATED")
        );

        OpenAiResponsesRuntimeUsageLedger globalLedger = ledger(2, 2);
        globalLedger.recordDispatched(TENANT_HASH, NOW, 100);
        globalLedger.recordDispatched(
            OpenAiResponsesProtocol.sha256Utf8("tenant-b"),
            NOW,
            100
        );
        UsageView globalView = UsageView.configured(
            configuredSource(),
            globalLedger.snapshot(TENANT_HASH, NOW)
        );
        assertEquals(
            UsageHealth.GLOBAL_RATE_WINDOW_SATURATED,
            globalView.usageHealth()
        );
        assertTrue(
            globalView.blockerCodes().contains("AI_GLOBAL_RATE_WINDOW_SATURATED")
        );
    }

    @Test
    void tenantUsageRejectsDurabilityActualBillingOrIncoherentCounts() {
        String hash = OpenAiResponsesProtocol.sha256Utf8("usage");
        assertThrows(
            IllegalArgumentException.class,
            () -> new ControlledAutomationGovernanceUsageContracts.TenantUsage(
                NOW,
                NOW.plusSeconds(60),
                1,
                2,
                1,
                100,
                2_000,
                1_900,
                false,
                false,
                true,
                true,
                true,
                CostBasis.ADMISSION_ESTIMATE_UPPER_BOUND_NOT_ACTUAL_PROVIDER_BILLING,
                hash
            )
        );
    }

    private static OpenAiResponsesRuntimeUsageLedger ledger(
        int tenantLimit,
        int globalLimit
    ) {
        return new OpenAiResponsesRuntimeUsageLedger(
            tenantLimit,
            globalLimit,
            10,
            Duration.ofSeconds(60),
            1_000
        );
    }

    private static OperationsView configuredSource() {
        return OperationsView.configured(
            NOW,
            inventory(),
            new RuntimeControls(
                7,
                KILL_HASH,
                COST_HASH,
                SECRET_HASH,
                10,
                100,
                60,
                3,
                60,
                1_000_000
            )
        );
    }

    private static List<InventoryEntry> inventory() {
        AiVersionReferences.PolicyVersion policy = new AiVersionReferences.PolicyVersion(
            "approval-assistance-production",
            "p6-e-v1",
            OpenAiResponsesProtocol.sha256Utf8(
                "approval-assistance-production-policy/p6-e-v1/advisory-only"
            )
        );
        return List.of(
            entry(AiCapability.APPROVAL_SUMMARY, policy),
            entry(AiCapability.MATERIAL_COMPLETENESS, policy),
            entry(AiCapability.RISK_SIGNALS, policy)
        );
    }

    private static InventoryEntry entry(
        AiCapability capability,
        AiVersionReferences.PolicyVersion policy
    ) {
        return new InventoryEntry(
            capability,
            new AiVersionReferences(
                OpenAiResponsesAdvisoryProvider.providerVersion(),
                OpenAiResponsesAdvisoryProvider.modelVersion(),
                OpenAiResponsesAdvisoryProvider.promptVersion(capability),
                AiVersionReferences.KnowledgeSourceVersion.none(),
                policy,
                OpenAiResponsesAdvisoryProvider.outputSchemaVersion()
            )
        );
    }
}
