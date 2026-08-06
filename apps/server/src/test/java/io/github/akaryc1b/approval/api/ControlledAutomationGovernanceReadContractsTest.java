package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesAdvisoryProvider;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.ActivationState;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.CanaryState;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.CircuitPosture;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.DriftState;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.InventoryEntry;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.OperationsView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.RollbackState;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.RolloutState;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.RuntimeControls;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.RuntimeState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlledAutomationGovernanceReadContractsTest {

    private static final Instant NOW = Instant.parse("2026-08-05T09:00:00Z");

    @Test
    void disabledRuntimeRemainsBlockedNonExecutingAndEvidenceBound() {
        OperationsView view = OperationsView.disabled(NOW, inventory());

        assertEquals(RuntimeState.NOT_CONFIGURED, view.runtimeState());
        assertEquals(ActivationState.BLOCKED, view.activationState());
        assertEquals(CanaryState.NOT_CONFIGURED, view.canaryState());
        assertEquals(DriftState.NOT_OBSERVED, view.driftState());
        assertEquals(RolloutState.BLOCKED, view.rolloutState());
        assertEquals(RollbackState.ALREADY_DISABLED, view.rollbackState());
        assertEquals(CircuitPosture.NOT_AVAILABLE, view.circuitPosture());
        assertEquals(3, view.inventory().size());
        assertNull(view.controls());
        assertFalse(view.providerMutationAvailable());
        assertFalse(view.canaryMutationAvailable());
        assertFalse(view.rollbackMutationAvailable());
        assertFalse(view.commandExecutionAuthorized());
        assertFalse(view.automaticRetryAuthorized());
        assertFalse(view.rawSecretExposed());
        assertEquals(64, view.evidenceHash().length());
        assertTrue(view.blockerCodes().contains("AI_PROVIDER_RUNTIME_NOT_CONFIGURED"));
        assertEquals(
            view.evidenceHash(),
            OperationsView.disabled(NOW, inventory()).evidenceHash()
        );
    }

    @Test
    void configuredRuntimeExposesOnlyExactControlEvidenceAndAdvisoryPosture() {
        OperationsView view = OperationsView.configured(NOW, inventory(), controls());

        assertEquals(RuntimeState.CONFIGURED_ADVISORY_ONLY, view.runtimeState());
        assertEquals(ActivationState.ADVISORY_ONLY, view.activationState());
        assertEquals(DriftState.EXACT_FROZEN_PROFILE, view.driftState());
        assertEquals(RolloutState.ADVISORY_ONLY, view.rolloutState());
        assertEquals(RollbackState.DISABLE_RUNTIME_FLAG, view.rollbackState());
        assertEquals(CircuitPosture.LIVE_STATE_NOT_EXPOSED, view.circuitPosture());
        assertEquals(7, view.controls().killSwitchGeneration());
        assertEquals(10, view.controls().perTenantRateLimit());
        assertEquals(100, view.controls().globalRateLimit());
        assertTrue(view.blockerCodes().contains("AI_AUTOMATION_ACTION_WHITELIST_EMPTY"));
        assertTrue(view.blockerCodes().contains("AI_LIVE_CIRCUIT_STATE_NOT_EXPOSED"));
        assertEquals(
            ControlledAutomationGovernanceReadContracts.EMPTY_ACTION_WHITELIST,
            view.actionWhitelistState()
        );
        assertEquals(
            ControlledAutomationGovernanceReadContracts.P5_SKIPPED,
            view.p5Decision()
        );
    }

    @Test
    void reconstructionCannotGrantProviderMutationOrCommandAuthority() {
        OperationsView view = OperationsView.configured(NOW, inventory(), controls());

        assertThrows(
            IllegalArgumentException.class,
            () -> new OperationsView(
                view.snapshotVersion(),
                view.observedAt(),
                view.runtimeState(),
                view.activationState(),
                view.canaryState(),
                view.driftState(),
                view.rolloutState(),
                view.rollbackState(),
                view.circuitPosture(),
                view.inventory(),
                view.controls(),
                view.blockerCodes(),
                view.actionWhitelistState(),
                view.p5Decision(),
                true,
                false,
                false,
                true,
                false,
                false,
                view.evidenceHash()
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

    private static RuntimeControls controls() {
        return new RuntimeControls(
            7,
            hash("kill-switch"),
            hash("cost-policy"),
            hash("secret-version"),
            10,
            100,
            60,
            3,
            60,
            1_000_000
        );
    }

    private static String hash(String value) {
        return OpenAiResponsesProtocol.sha256Utf8(value);
    }
}
