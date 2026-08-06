package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistoryWindow;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesAdvisoryProvider;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProductionRuntimeFactory;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesTransportControls;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceControlHealthContracts
    .ControlHealthView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceControlHealthSource;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceHistoryContracts.HistoryView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceHistorySource;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.InventoryEntry;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.OperationsView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.RuntimeControls;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceSnapshotSource;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceUsageContracts.UsageView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceUsageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Composition-root wiring for P6 read-only AI governance operations. */
@Configuration(proxyBeanMethods = false)
public class ControlledAutomationGovernanceConfiguration {

    private static final AiVersionReferences.PolicyVersion POLICY_VERSION =
        new AiVersionReferences.PolicyVersion(
            "approval-assistance-production",
            "p6-e-v1",
            OpenAiResponsesProtocol.sha256Utf8(
                "approval-assistance-production-policy/p6-e-v1/advisory-only"
            )
        );

    @Bean
    ControlledAutomationGovernanceSnapshotSource controlledAutomationGovernanceSnapshotSource(
        ApprovalAssistanceProductionRuntime productionRuntime,
        Clock approvalClock
    ) {
        Optional<OpenAiResponsesProductionRuntimeFactory> runtime = productionRuntime.factory();
        List<InventoryEntry> inventory = inventory();
        if (runtime.isEmpty()) {
            return () -> OperationsView.disabled(approvalClock.instant(), inventory);
        }
        RuntimeControls controls = controls(runtime.orElseThrow().profile());
        return () -> OperationsView.configured(
            approvalClock.instant(),
            inventory,
            controls
        );
    }

    @Bean
    ControlledAutomationGovernanceControlHealthSource
        controlledAutomationGovernanceControlHealthSource(
            ApprovalAssistanceProductionRuntime productionRuntime,
            ControlledAutomationGovernanceSnapshotSource snapshotSource
        ) {
        return () -> {
            OperationsView snapshot = snapshotSource.current();
            return productionRuntime.factory()
                .map(factory -> ControlHealthView.configured(
                    snapshot,
                    factory.controlSnapshot()
                ))
                .orElseGet(() -> ControlHealthView.disabled(snapshot));
        };
    }

    @Bean
    ControlledAutomationGovernanceUsageSource controlledAutomationGovernanceUsageSource(
        ApprovalAssistanceProductionRuntime productionRuntime,
        ControlledAutomationGovernanceSnapshotSource snapshotSource
    ) {
        return trustedTenantId -> {
            OperationsView snapshot = snapshotSource.current();
            return productionRuntime.factory()
                .map(factory -> UsageView.configured(
                    snapshot,
                    factory.usageSnapshot(trustedTenantId)
                ))
                .orElseGet(() -> UsageView.disabled(snapshot));
        };
    }

    @Bean
    ControlledAutomationGovernanceHistorySource controlledAutomationGovernanceHistorySource(
        ApprovalAssistanceGovernanceHistoryQuery historyQuery,
        ControlledAutomationGovernanceSnapshotSource snapshotSource,
        Clock approvalClock
    ) {
        return (trustedTenantId, fromInclusive, toExclusive) -> {
            Instant observedAt = approvalClock.instant();
            var summary = historyQuery.summarize(new HistoryWindow(
                trustedTenantId,
                fromInclusive,
                toExclusive,
                observedAt
            ));
            return HistoryView.from(snapshotSource.current(), summary);
        };
    }

    static List<InventoryEntry> inventory() {
        return List.of(
            inventory(AiCapability.APPROVAL_SUMMARY),
            inventory(AiCapability.MATERIAL_COMPLETENESS),
            inventory(AiCapability.RISK_SIGNALS)
        );
    }

    private static InventoryEntry inventory(AiCapability capability) {
        return new InventoryEntry(
            capability,
            new AiVersionReferences(
                OpenAiResponsesAdvisoryProvider.providerVersion(),
                OpenAiResponsesAdvisoryProvider.modelVersion(),
                OpenAiResponsesAdvisoryProvider.promptVersion(capability),
                AiVersionReferences.KnowledgeSourceVersion.none(),
                POLICY_VERSION,
                OpenAiResponsesAdvisoryProvider.outputSchemaVersion()
            )
        );
    }

    private static RuntimeControls controls(
        OpenAiResponsesProductionRuntimeFactory.RuntimeProfile profile
    ) {
        var killSwitch = new OpenAiResponsesTransportControls.KillSwitchSnapshot(
            OpenAiResponsesProtocol.PROVIDER_ID,
            OpenAiResponsesProtocol.PROVIDER_VERSION,
            profile.killSwitchGeneration(),
            true,
            profile.killSwitchPolicyRevision()
        );
        var costPolicy = new OpenAiResponsesTransportControls.CostPolicy(
            profile.costPolicyVersion(),
            OpenAiResponsesProtocol.MODEL_SNAPSHOT,
            profile.inputMicrosPerConservativeToken(),
            profile.outputMicrosPerToken(),
            profile.maximumRequestMicros(),
            profile.costPolicyEffectiveFrom(),
            profile.costPolicyExpiresAt()
        );
        return new RuntimeControls(
            killSwitch.generation(),
            killSwitch.evidenceHash(),
            costPolicy.evidenceHash(),
            OpenAiResponsesProtocol.sha256Utf8(profile.secretVersionReference()),
            profile.perTenantRateLimit(),
            profile.globalRateLimit(),
            profile.rateWindow().toSeconds(),
            profile.circuitFailureThreshold(),
            profile.circuitOpenDuration().toSeconds(),
            profile.maximumRequestMicros()
        );
    }
}
