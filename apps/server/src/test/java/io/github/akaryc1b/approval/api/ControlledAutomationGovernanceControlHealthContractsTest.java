package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesAdvisoryProvider;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProductionRuntimeFactory
    .RuntimeControlSnapshot;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesTransportControls.CircuitBreaker;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceControlHealthContracts
    .BudgetHealth;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceControlHealthContracts
    .CircuitHealth;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceControlHealthContracts
    .ControlHealthView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceControlHealthContracts
    .DriftHealth;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceControlHealthContracts
    .PolicyWindowHealth;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceControlHealthContracts
    .RateHealth;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts
    .InventoryEntry;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts
    .OperationsView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts
    .RuntimeControls;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlledAutomationGovernanceControlHealthContractsTest {

    private static final Instant NOW = Instant.parse("2026-08-05T10:40:00Z");
    private static final String KILL_HASH = OpenAiResponsesProtocol.sha256Utf8("kill");
    private static final String COST_HASH = OpenAiResponsesProtocol.sha256Utf8("cost");
    private static final String SECRET_HASH = OpenAiResponsesProtocol.sha256Utf8("secret");

    @Test
    void disabledRuntimeRemainsBlockedAndCarriesNoControlEvidence() {
        ControlHealthView view = ControlHealthView.disabled(
            OperationsView.disabled(NOW, inventory())
        );

        assertNull(view.runtimeEvidence());
        assertTrue(view.blockerCodes().contains("AI_PROVIDER_RUNTIME_NOT_CONFIGURED"));
        assertFalse(view.providerMutationAvailable());
        assertFalse(view.controlMutationAvailable());
        assertFalse(view.commandExecutionAuthorized());
        assertEquals(64, view.evidenceHash().length());
    }

    @Test
    void configuredRuntimeReportsExactCurrentClosedMetadataOnlyHealth() {
        ControlHealthView view = ControlHealthView.configured(
            configuredSource(),
            runtimeSnapshot(100, CircuitBreaker.State.CLOSED, 1, false)
        );

        var evidence = view.runtimeEvidence();
        assertEquals(DriftHealth.EXACT_FROZEN_PROFILE, evidence.driftHealth());
        assertEquals(PolicyWindowHealth.CURRENT, evidence.costPolicyHealth());
        assertEquals(PolicyWindowHealth.CURRENT, evidence.secretVersionHealth());
        assertEquals(CircuitHealth.CLOSED, evidence.circuitHealth());
        assertEquals(RateHealth.CONFIGURED_USAGE_NOT_EXPOSED, evidence.rateHealth());
        assertEquals(
            BudgetHealth.REQUEST_CAP_ONLY_CONSUMPTION_NOT_EXPOSED,
            evidence.budgetHealth()
        );
        assertTrue(view.blockerCodes().contains("AI_RATE_USAGE_NOT_EXPOSED"));
        assertTrue(view.blockerCodes().contains("AI_BUDGET_CONSUMPTION_NOT_AVAILABLE"));
        assertFalse(evidence.rateUsageExposed());
        assertFalse(evidence.budgetConsumptionExposed());
        assertFalse(view.rawSecretExposed());
    }

    @Test
    void mismatchedSharedRuntimeIsReportedAsDriftWithoutMutationAuthority() {
        ControlHealthView view = ControlHealthView.configured(
            configuredSource(),
            runtimeSnapshot(101, CircuitBreaker.State.CLOSED, 2, false)
        );

        assertEquals(
            DriftHealth.DRIFT_DETECTED,
            view.runtimeEvidence().driftHealth()
        );
        assertTrue(
            view.blockerCodes().contains("AI_PROVIDER_RUNTIME_DRIFT_DETECTED")
        );
        assertFalse(view.controlMutationAvailable());
    }

    @Test
    void expiredPoliciesAndOpenCircuitFailClosed() {
        ControlHealthView view = ControlHealthView.configured(
            configuredSource(),
            runtimeSnapshot(100, CircuitBreaker.State.OPEN, 9, true)
        );

        assertEquals(
            PolicyWindowHealth.EXPIRED,
            view.runtimeEvidence().costPolicyHealth()
        );
        assertEquals(
            PolicyWindowHealth.EXPIRED,
            view.runtimeEvidence().secretVersionHealth()
        );
        assertEquals(CircuitHealth.OPEN, view.runtimeEvidence().circuitHealth());
        assertTrue(view.blockerCodes().contains("AI_COST_POLICY_NOT_CURRENT"));
        assertTrue(view.blockerCodes().contains("AI_SECRET_VERSION_NOT_CURRENT"));
        assertTrue(view.blockerCodes().contains("AI_PROVIDER_CIRCUIT_OPEN"));
    }

    @Test
    void runtimeEvidenceRejectsUsageOrConsumptionExposure() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new ControlledAutomationGovernanceControlHealthContracts.RuntimeEvidence(
                DriftHealth.EXACT_FROZEN_PROFILE,
                ControlledAutomationGovernanceControlHealthContracts.KillSwitchHealth
                    .ADMISSION_ENABLED,
                PolicyWindowHealth.CURRENT,
                PolicyWindowHealth.CURRENT,
                CircuitHealth.CLOSED,
                7,
                1,
                KILL_HASH,
                COST_HASH,
                SECRET_HASH,
                10,
                100,
                60,
                3,
                60,
                1_000_000,
                RateHealth.CONFIGURED_USAGE_NOT_EXPOSED,
                BudgetHealth.REQUEST_CAP_ONLY_CONSUMPTION_NOT_EXPOSED,
                true,
                false
            )
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

    private static RuntimeControlSnapshot runtimeSnapshot(
        int globalRateLimit,
        CircuitBreaker.State state,
        long generation,
        boolean expired
    ) {
        Instant effective = expired ? NOW.minusSeconds(3_600) : NOW.minusSeconds(60);
        Instant expires = expired ? NOW.minusSeconds(1) : NOW.plusSeconds(3_600);
        return new RuntimeControlSnapshot(
            NOW,
            true,
            7,
            KILL_HASH,
            COST_HASH,
            effective,
            expires,
            SECRET_HASH,
            effective,
            expires,
            10,
            globalRateLimit,
            60,
            3,
            60,
            1_000_000,
            state,
            generation,
            false,
            false
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
