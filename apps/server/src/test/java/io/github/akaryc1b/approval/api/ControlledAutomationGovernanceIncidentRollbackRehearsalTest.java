package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.UseCase;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistorySummary;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistoryWindow;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.OutcomeCount;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.UseCaseCount;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.VersionStability;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesAdvisoryProvider;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProductionRuntimeFactory;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProductionRuntimeFactory
    .RuntimeControlSnapshot;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProductionRuntimeFactory.RuntimeProfile;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesRuntimeUsageLedger.UsageSnapshot;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesTransportControls;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceControlHealthContracts
    .ControlHealthView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceHistoryContracts.HistoryView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceIncidentReadinessContracts
    .IncidentReadinessView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceIncidentReadinessContracts
    .IncidentSignal;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceIncidentReadinessContracts
    .ReadinessState;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceIncidentReadinessContracts
    .RollbackPosture;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernancePlanContracts.Operation;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernancePlanContracts.ReviewPlan;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.InventoryEntry;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.OperationsView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.RuntimeControls;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceUsageContracts.UsageHealth;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceUsageContracts.UsageView;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlledAutomationGovernanceIncidentRollbackRehearsalTest {

    private static final Instant NOW = Instant.parse("2026-08-06T07:00:00Z");
    private static final Instant FROM = NOW.minusSeconds(3_600);
    private static final String TENANT_HASH = OpenAiResponsesProtocol.sha256Utf8(
        "tenant\ntenant-a"
    );

    @Test
    void scenario1RuntimeNotConfiguredIsAlreadyDisabledAndRequiresNoReleaseAction() {
        OperationsView snapshot = OperationsView.disabled(NOW, inventory());
        IncidentReadinessView view = IncidentReadinessView.from(
            snapshot,
            ControlHealthView.disabled(snapshot),
            UsageView.disabled(snapshot),
            HistoryView.from(snapshot, HistorySummary.empty(window())),
            ReviewPlan.preview(Operation.ROLLBACK, snapshot)
        );

        assertEquals(ReadinessState.RUNTIME_NOT_CONFIGURED, view.readinessState());
        assertEquals(RollbackPosture.ALREADY_DISABLED, view.rollbackPosture());
        assertTrue(view.incidentSignals().contains(
            IncidentSignal.AI_PROVIDER_RUNTIME_NOT_CONFIGURED
        ));
        assertTrue(view.operatorStepCodes().contains(
            "AI_INCIDENT_STEP_CONFIRM_RUNTIME_DISABLED"
        ));
        assertTrue(view.rollbackOperatorStepCodes().contains(
            "AI_ROLLBACK_STEP_NO_ACTION_REQUIRED_RUNTIME_ALREADY_DISABLED"
        ));
        assertManualOnly(view);
    }

    @Test
    void scenario2HealthyRuntimeRemainsAdvisoryOnlyWithEmptyWhitelistAndNoReauth() {
        RuntimeProfile profile = profile();
        OpenAiResponsesProductionRuntimeFactory factory = factory(profile);
        OperationsView snapshot = configuredSnapshot(profile);
        IncidentReadinessView view = IncidentReadinessView.from(
            snapshot,
            ControlHealthView.configured(snapshot, factory.controlSnapshot()),
            UsageView.configured(snapshot, factory.usageSnapshot("tenant-a")),
            HistoryView.from(snapshot, HistorySummary.empty(window())),
            ReviewPlan.preview(Operation.ROLLBACK, snapshot)
        );

        assertEquals(
            ReadinessState.OBSERVATION_READY_ADVISORY_ONLY,
            view.readinessState()
        );
        assertEquals(
            ControlledAutomationGovernanceReadContracts.EMPTY_ACTION_WHITELIST,
            view.actionWhitelistState()
        );
        assertEquals(
            ControlledAutomationGovernanceReadContracts.P5_SKIPPED,
            view.p5Decision()
        );
        assertTrue(view.blockerCodes().contains(
            "AI_PRODUCTION_REAUTHENTICATION_UNAVAILABLE"
        ));
        assertTrue(view.operatorStepCodes().contains(
            "AI_INCIDENT_STEP_CONTINUE_READ_ONLY_MONITORING"
        ));
        assertManualOnly(view);
    }

    @Test
    void scenario3CircuitOpenIsIncidentBlockedWithManualRollbackReviewOnly() {
        assertCircuitBlocked(
            OpenAiResponsesTransportControls.CircuitBreaker.State.OPEN,
            IncidentSignal.AI_PROVIDER_CIRCUIT_OPEN
        );
    }

    @Test
    void scenario4CircuitHalfOpenIsNotHealthyAndDoesNotInitiateAProbe() {
        assertCircuitBlocked(
            OpenAiResponsesTransportControls.CircuitBreaker.State.HALF_OPEN,
            IncidentSignal.AI_PROVIDER_CIRCUIT_HALF_OPEN
        );
    }

    @Test
    void scenario5TenantRateSaturationDoesNotResetLimitsOrInvokeProvider() {
        RuntimeProfile profile = profile();
        OperationsView snapshot = configuredSnapshot(profile);
        UsageView usage = UsageView.configured(
            snapshot,
            usage(10, false, "tenant-saturated")
        );
        IncidentReadinessView view = view(
            snapshot,
            control(profile, OpenAiResponsesTransportControls.CircuitBreaker.State.CLOSED),
            usage,
            HistorySummary.empty(window())
        );

        assertEquals(ReadinessState.INCIDENT_BLOCKED, view.readinessState());
        assertEquals(UsageHealth.TENANT_RATE_WINDOW_SATURATED, view.usageHealth());
        assertTrue(view.incidentSignals().contains(
            IncidentSignal.AI_TENANT_RATE_WINDOW_SATURATED
        ));
        assertEquals(10, usage.tenantUsage().committedRequests());
        assertEquals(10, usage.tenantUsage().requestLimit());
        assertEquals(0, usage.tenantUsage().remainingRequests());
        assertManualOnly(view);
    }

    @Test
    void scenario6GlobalRateSaturationExposesOnlyTheBooleanGlobalPosture() {
        RuntimeProfile profile = profile();
        OperationsView snapshot = configuredSnapshot(profile);
        UsageView usage = UsageView.configured(
            snapshot,
            usage(1, true, "global-saturated")
        );
        IncidentReadinessView view = view(
            snapshot,
            control(profile, OpenAiResponsesTransportControls.CircuitBreaker.State.CLOSED),
            usage,
            HistorySummary.empty(window())
        );

        assertEquals(ReadinessState.INCIDENT_BLOCKED, view.readinessState());
        assertEquals(UsageHealth.GLOBAL_RATE_WINDOW_SATURATED, view.usageHealth());
        assertTrue(view.incidentSignals().contains(
            IncidentSignal.AI_GLOBAL_RATE_WINDOW_SATURATED
        ));
        assertTrue(usage.tenantUsage().globalSaturated());
        assertFalse(usage.globalExactUsageExposed());
        assertFalse(usage.otherTenantUsageExposed());
        assertManualOnly(view);
    }

    @Test
    void scenario7VersionDriftRequiresReviewWithoutRestoringOrMutatingRuntime() {
        RuntimeProfile profile = profile();
        OperationsView snapshot = configuredSnapshot(profile);
        IncidentReadinessView view = view(
            snapshot,
            control(profile, OpenAiResponsesTransportControls.CircuitBreaker.State.CLOSED),
            UsageView.configured(snapshot, usage(0, false, "version-drift")),
            versionDriftHistory()
        );

        assertEquals(ReadinessState.ACTION_REQUIRED, view.readinessState());
        assertTrue(view.incidentSignals().contains(
            IncidentSignal.AI_DURABLE_HISTORY_VERSION_DRIFT_DETECTED
        ));
        assertTrue(view.operatorStepCodes().contains(
            "AI_INCIDENT_STEP_REVIEW_VERSION_HISTORY"
        ));
        assertManualOnly(view);
    }

    @Test
    void scenario8RetentionDueRequiresManualTombstoneReviewWithoutScheduler() {
        RuntimeProfile profile = profile();
        OperationsView snapshot = configuredSnapshot(profile);
        IncidentReadinessView view = view(
            snapshot,
            control(profile, OpenAiResponsesTransportControls.CircuitBreaker.State.CLOSED),
            UsageView.configured(snapshot, usage(0, false, "retention-due")),
            retentionDueHistory()
        );

        assertEquals(ReadinessState.ACTION_REQUIRED, view.readinessState());
        assertTrue(view.incidentSignals().contains(
            IncidentSignal.AI_RETENTION_TOMBSTONE_DUE
        ));
        assertTrue(view.operatorStepCodes().contains(
            "AI_INCIDENT_STEP_REVIEW_RETENTION_TOMBSTONES"
        ));
        assertManualOnly(view);
    }

    private static void assertCircuitBlocked(
        OpenAiResponsesTransportControls.CircuitBreaker.State state,
        IncidentSignal signal
    ) {
        RuntimeProfile profile = profile();
        OpenAiResponsesProductionRuntimeFactory factory = factory(profile);
        OperationsView snapshot = configuredSnapshot(profile);
        long generationBefore = factory.controlSnapshot().circuitGeneration();
        IncidentReadinessView view = view(
            snapshot,
            control(profile, state),
            UsageView.configured(snapshot, factory.usageSnapshot("tenant-a")),
            HistorySummary.empty(window())
        );

        assertEquals(ReadinessState.INCIDENT_BLOCKED, view.readinessState());
        assertTrue(view.incidentSignals().contains(signal));
        assertTrue(view.operatorStepCodes().contains(
            "AI_INCIDENT_STEP_VERIFY_CONTROL_HEALTH"
        ));
        assertTrue(view.operatorStepCodes().contains(
            "AI_INCIDENT_STEP_REVIEW_NON_EXECUTABLE_ROLLBACK_PLAN"
        ));
        assertTrue(view.operatorStepCodes().contains(
            "AI_INCIDENT_STEP_DO_NOT_AUTOMATICALLY_RETRY"
        ));
        assertEquals(generationBefore, factory.controlSnapshot().circuitGeneration());
        assertManualOnly(view);
    }

    private static IncidentReadinessView view(
        OperationsView snapshot,
        ControlHealthView control,
        UsageView usage,
        HistorySummary history
    ) {
        return IncidentReadinessView.from(
            snapshot,
            control,
            usage,
            HistoryView.from(snapshot, history),
            ReviewPlan.preview(Operation.ROLLBACK, snapshot)
        );
    }

    private static void assertManualOnly(IncidentReadinessView view) {
        assertTrue(view.durableEvidenceAvailable());
        assertTrue(view.processLocalUsageOnly());
        assertFalse(view.incidentMutationAvailable());
        assertFalse(view.providerInvocationAvailable());
        assertFalse(view.rollbackExecutionAvailable());
        assertFalse(view.commandExecutionAuthorized());
        assertFalse(view.automaticRetryAuthorized());
        assertFalse(view.notificationAutomationAvailable());
        assertFalse(view.rawSecretExposed());
    }

    private static OperationsView configuredSnapshot(RuntimeProfile profile) {
        return OperationsView.configured(NOW, inventory(), controls(profile));
    }

    private static ControlHealthView control(
        RuntimeProfile profile,
        OpenAiResponsesTransportControls.CircuitBreaker.State state
    ) {
        OpenAiResponsesProductionRuntimeFactory factory = factory(profile);
        OperationsView snapshot = configuredSnapshot(profile);
        RuntimeControlSnapshot current = factory.controlSnapshot();
        RuntimeControlSnapshot requested = new RuntimeControlSnapshot(
            current.observedAt(),
            current.killSwitchEnabled(),
            current.killSwitchGeneration(),
            current.killSwitchEvidenceHash(),
            current.costPolicyEvidenceHash(),
            current.costPolicyEffectiveFrom(),
            current.costPolicyExpiresAt(),
            current.secretVersionEvidenceHash(),
            current.secretVersionEffectiveFrom(),
            current.secretVersionExpiresAt(),
            current.perTenantRateLimit(),
            current.globalRateLimit(),
            current.rateWindowSeconds(),
            current.circuitFailureThreshold(),
            current.circuitOpenSeconds(),
            current.maximumRequestMicros(),
            state,
            state == OpenAiResponsesTransportControls.CircuitBreaker.State.CLOSED
                ? current.circuitGeneration()
                : current.circuitGeneration() + 1,
            false,
            false
        );
        return ControlHealthView.configured(snapshot, requested);
    }

    private static UsageSnapshot usage(
        int committedRequests,
        boolean globalSaturated,
        String seed
    ) {
        long committedMicros = committedRequests * 100_000L;
        return new UsageSnapshot(
            NOW,
            TENANT_HASH,
            NOW,
            NOW.plusSeconds(60),
            committedRequests,
            10,
            100,
            committedMicros,
            10_000_000,
            100_000_000,
            globalSaturated,
            true,
            false,
            false,
            OpenAiResponsesProtocol.sha256Utf8(seed)
        );
    }

    private static HistorySummary versionDriftHistory() {
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
            outcomes(1, 1),
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
    }

    private static HistorySummary retentionDueHistory() {
        return new HistorySummary(
            window(),
            1,
            1,
            0,
            1,
            1,
            1,
            0,
            0,
            1,
            NOW.minusSeconds(300),
            NOW.minusSeconds(300),
            outcomes(1, 0),
            List.of(
                new UseCaseCount(
                    UseCase.SUMMARY,
                    1,
                    1,
                    1,
                    1,
                    VersionStability.SINGLE_VERSION_BUNDLE
                ),
                UseCaseCount.empty(UseCase.MATERIAL_COMPLETENESS),
                UseCaseCount.empty(UseCase.RISK_REVIEW)
            )
        );
    }

    private static List<OutcomeCount> outcomes(long success, long rejected) {
        return Arrays.stream(AiOutcomeClassification.values())
            .map(classification -> new OutcomeCount(
                classification,
                classification == AiOutcomeClassification.SUCCESS
                    ? success
                    : classification == AiOutcomeClassification.REJECTED
                        ? rejected
                        : 0
            ))
            .toList();
    }

    private static OpenAiResponsesProductionRuntimeFactory factory(RuntimeProfile profile) {
        return new OpenAiResponsesProductionRuntimeFactory(
            profile,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static RuntimeProfile profile() {
        return new RuntimeProfile(
            "openai-key-v1",
            NOW.minusSeconds(3_600),
            NOW.plusSeconds(3_600),
            "secret-policy-v1",
            7,
            "kill-switch-policy-v1",
            "cost-policy-v1",
            NOW.minusSeconds(3_600),
            NOW.plusSeconds(3_600),
            1,
            1,
            1_000_000,
            10,
            100,
            Duration.ofSeconds(60),
            3,
            Duration.ofSeconds(60)
        );
    }

    private static RuntimeControls controls(RuntimeProfile profile) {
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

    private static HistoryWindow window() {
        return new HistoryWindow("tenant-a", FROM, NOW, NOW);
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
