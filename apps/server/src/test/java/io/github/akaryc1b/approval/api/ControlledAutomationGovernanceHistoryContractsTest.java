package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.UseCase;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistorySummary;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistoryWindow;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.OutcomeCount;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.UseCaseCount;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.VersionStability;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesAdvisoryProvider;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceHistoryContracts.HistoryHealth;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceHistoryContracts.HistoryView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.InventoryEntry;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.OperationsView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlledAutomationGovernanceHistoryContractsTest {

    private static final Instant NOW = Instant.parse("2026-08-06T00:45:00Z");

    @Test
    void emptyDurableHistoryRemainsAvailableWhenRuntimeIsDisabled() {
        OperationsView source = OperationsView.disabled(NOW, inventory());
        HistoryView view = HistoryView.from(
            source,
            HistorySummary.empty(window())
        );

        assertEquals(HistoryHealth.EMPTY, view.historyHealth());
        assertEquals(0, view.totalEvidence());
        assertNull(view.earliestRecordedAt());
        assertTrue(view.durableHistory());
        assertTrue(view.crossProcessHistory());
        assertTrue(view.blockerCodes().contains("AI_DURABLE_HISTORY_EMPTY"));
        assertTrue(view.blockerCodes().contains("AI_PROVIDER_RUNTIME_NOT_CONFIGURED"));
        assertFalse(view.actualProviderCostAvailable());
        assertFalse(view.historyMutationAvailable());
        assertFalse(view.providerInvocationAvailable());
        assertFalse(view.commandExecutionAuthorized());
        assertFalse(view.rawSecretExposed());
        assertEquals(64, view.evidenceHash().length());
    }

    @Test
    void stableHistoryExposesOnlyTenantAggregateEvidence() {
        HistoryView view = HistoryView.from(
            OperationsView.configured(NOW, inventory(), controls()),
            stableSummary()
        );

        assertEquals(HistoryHealth.STABLE, view.historyHealth());
        assertEquals(2, view.totalEvidence());
        assertEquals(2, view.providerInvocationCount());
        assertEquals(1, view.advisoryResultCount());
        assertFalse(view.exactGlobalUsageExposed());
        assertFalse(view.otherTenantHistoryExposed());
        assertFalse(view.costUpperBoundHistoryAvailable());
        assertTrue(
            view.blockerCodes().contains(
                "AI_DURABLE_HISTORY_ACTUAL_PROVIDER_COST_NOT_AVAILABLE"
            )
        );
    }

    @Test
    void versionDriftAndRetentionDueProduceFailClosedHealth() {
        HistorySummary source = stableSummary();
        HistorySummary drifted = new HistorySummary(
            source.window(),
            source.totalEvidence(),
            2,
            0,
            source.providerInvocationCount(),
            source.providerAttemptCount(),
            source.advisoryResultCount(),
            0,
            0,
            1,
            source.earliestRecordedAt(),
            source.latestRecordedAt(),
            source.outcomeCounts(),
            List.of(
                new UseCaseCount(
                    UseCase.SUMMARY,
                    2,
                    2,
                    1,
                    2,
                    VersionStability.MULTIPLE_VERSION_BUNDLES
                ),
                UseCaseCount.empty(UseCase.MATERIAL_COMPLETENESS),
                UseCaseCount.empty(UseCase.RISK_REVIEW)
            )
        );

        HistoryView view = HistoryView.from(
            OperationsView.configured(NOW, inventory(), controls()),
            drifted
        );

        assertEquals(
            HistoryHealth.VERSION_DRIFT_AND_RETENTION_DUE,
            view.historyHealth()
        );
        assertTrue(
            view.blockerCodes().contains("AI_DURABLE_HISTORY_VERSION_DRIFT_DETECTED")
        );
        assertTrue(view.blockerCodes().contains("AI_RETENTION_TOMBSTONE_DUE"));
    }

    private static HistorySummary stableSummary() {
        return new HistorySummary(
            window(),
            2,
            2,
            0,
            2,
            2,
            1,
            0,
            0,
            0,
            NOW.minusSeconds(1_200),
            NOW.minusSeconds(300),
            outcomes(),
            List.of(
                new UseCaseCount(
                    UseCase.SUMMARY,
                    2,
                    2,
                    1,
                    1,
                    VersionStability.SINGLE_VERSION_BUNDLE
                ),
                UseCaseCount.empty(UseCase.MATERIAL_COMPLETENESS),
                UseCaseCount.empty(UseCase.RISK_REVIEW)
            )
        );
    }

    private static HistoryWindow window() {
        return new HistoryWindow(
            "tenant-a",
            NOW.minusSeconds(3_600),
            NOW,
            NOW
        );
    }

    private static List<OutcomeCount> outcomes() {
        return Arrays.stream(AiOutcomeClassification.values())
            .map(classification -> new OutcomeCount(
                classification,
                classification == AiOutcomeClassification.SUCCESS
                    ? 1
                    : classification == AiOutcomeClassification.REJECTED
                        ? 1
                        : 0
            ))
            .toList();
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

    private static ControlledAutomationGovernanceReadContracts.RuntimeControls controls() {
        return new ControlledAutomationGovernanceReadContracts.RuntimeControls(
            7,
            OpenAiResponsesProtocol.sha256Utf8("kill"),
            OpenAiResponsesProtocol.sha256Utf8("cost"),
            OpenAiResponsesProtocol.sha256Utf8("secret"),
            10,
            100,
            60,
            3,
            60,
            1_000_000
        );
    }
}
