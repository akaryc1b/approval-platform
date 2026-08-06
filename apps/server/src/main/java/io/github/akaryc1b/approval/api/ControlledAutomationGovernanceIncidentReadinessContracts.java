package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceControlHealthContracts
    .CircuitHealth;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceControlHealthContracts
    .ControlHealthView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceControlHealthContracts
    .DriftHealth;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceControlHealthContracts
    .KillSwitchHealth;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceControlHealthContracts
    .PolicyWindowHealth;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceHistoryContracts
    .HistoryHealth;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceHistoryContracts.HistoryView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernancePlanContracts.Mode;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernancePlanContracts.Operation;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernancePlanContracts.ReviewPlan;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernancePlanContracts
    .RollbackMechanism;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernancePlanContracts.Status;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.OperationsView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.RuntimeState;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceUsageContracts.UsageHealth;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceUsageContracts.UsageView;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Closed P6-F composite incident-readiness projection over P6-A through P6-E evidence. */
public final class ControlledAutomationGovernanceIncidentReadinessContracts {

    public static final String VIEW_VERSION = "m6-f-p6-f-incident-readiness-v1";

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> OPERATOR_STEPS = Set.of(
        "AI_INCIDENT_STEP_CONFIRM_RUNTIME_DISABLED",
        "AI_INCIDENT_STEP_CONTINUE_READ_ONLY_MONITORING",
        "AI_INCIDENT_STEP_DO_NOT_AUTOMATICALLY_RETRY",
        "AI_INCIDENT_STEP_ESCALATE_RELEASE_OWNER",
        "AI_INCIDENT_STEP_REVIEW_DURABLE_HISTORY",
        "AI_INCIDENT_STEP_REVIEW_NON_EXECUTABLE_ROLLBACK_PLAN",
        "AI_INCIDENT_STEP_REVIEW_RETENTION_TOMBSTONES",
        "AI_INCIDENT_STEP_REVIEW_VERSION_HISTORY",
        "AI_INCIDENT_STEP_VERIFY_CONTROL_HEALTH",
        "AI_INCIDENT_STEP_VERIFY_READ_ONLY_GOVERNANCE_SNAPSHOT",
        "AI_INCIDENT_STEP_VERIFY_TENANT_USAGE"
    );

    private ControlledAutomationGovernanceIncidentReadinessContracts() {
    }

    public enum ReadinessState {
        RUNTIME_NOT_CONFIGURED,
        OBSERVATION_READY_ADVISORY_ONLY,
        ACTION_REQUIRED,
        INCIDENT_BLOCKED
    }

    public enum ControlPosture {
        NOT_CONFIGURED,
        HEALTHY,
        BLOCKED
    }

    public enum RollbackPosture {
        ALREADY_DISABLED,
        REVIEW_READY_MANUAL_RELEASE
    }

    public enum IncidentSignal {
        AI_PROVIDER_RUNTIME_NOT_CONFIGURED,
        AI_PROVIDER_RUNTIME_DRIFT_DETECTED,
        AI_PROVIDER_KILL_SWITCH_ADMISSION_DISABLED,
        AI_COST_POLICY_NOT_CURRENT,
        AI_SECRET_VERSION_NOT_CURRENT,
        AI_PROVIDER_CIRCUIT_OPEN,
        AI_PROVIDER_CIRCUIT_HALF_OPEN,
        AI_TENANT_RATE_WINDOW_SATURATED,
        AI_GLOBAL_RATE_WINDOW_SATURATED,
        AI_DURABLE_HISTORY_EMPTY,
        AI_DURABLE_HISTORY_VERSION_DRIFT_DETECTED,
        AI_RETENTION_TOMBSTONE_DUE
    }

    public record EvidenceReferences(
        String snapshotEvidenceHash,
        String controlHealthEvidenceHash,
        String usageEvidenceHash,
        String historyEvidenceHash,
        String rollbackPlanEvidenceHash
    ) {
        public EvidenceReferences {
            snapshotEvidenceHash = requireSha256(
                snapshotEvidenceHash,
                "snapshotEvidenceHash"
            );
            controlHealthEvidenceHash = requireSha256(
                controlHealthEvidenceHash,
                "controlHealthEvidenceHash"
            );
            usageEvidenceHash = requireSha256(usageEvidenceHash, "usageEvidenceHash");
            historyEvidenceHash = requireSha256(historyEvidenceHash, "historyEvidenceHash");
            rollbackPlanEvidenceHash = requireSha256(
                rollbackPlanEvidenceHash,
                "rollbackPlanEvidenceHash"
            );
        }
    }

    public record IncidentReadinessView(
        String viewVersion,
        Instant observedAt,
        Instant fromInclusive,
        Instant toExclusive,
        RuntimeState runtimeState,
        ReadinessState readinessState,
        ControlPosture controlPosture,
        UsageHealth usageHealth,
        HistoryHealth historyHealth,
        RollbackPosture rollbackPosture,
        EvidenceReferences evidenceReferences,
        List<IncidentSignal> incidentSignals,
        List<String> operatorStepCodes,
        List<String> rollbackOperatorStepCodes,
        List<String> blockerCodes,
        String actionWhitelistState,
        String p5Decision,
        boolean durableEvidenceAvailable,
        boolean processLocalUsageOnly,
        boolean incidentMutationAvailable,
        boolean providerInvocationAvailable,
        boolean rollbackExecutionAvailable,
        boolean commandExecutionAuthorized,
        boolean automaticRetryAuthorized,
        boolean notificationAutomationAvailable,
        boolean rawSecretExposed,
        String evidenceHash
    ) {
        public IncidentReadinessView {
            viewVersion = requireText(viewVersion, "viewVersion", 160);
            observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
            fromInclusive = Objects.requireNonNull(
                fromInclusive,
                "fromInclusive must not be null"
            );
            toExclusive = Objects.requireNonNull(
                toExclusive,
                "toExclusive must not be null"
            );
            if (!fromInclusive.isBefore(toExclusive) || toExclusive.isAfter(observedAt)) {
                throw new IllegalArgumentException(
                    "incident-readiness history window must be positive and not future-dated"
                );
            }
            runtimeState = Objects.requireNonNull(runtimeState, "runtimeState must not be null");
            readinessState = Objects.requireNonNull(
                readinessState,
                "readinessState must not be null"
            );
            controlPosture = Objects.requireNonNull(
                controlPosture,
                "controlPosture must not be null"
            );
            usageHealth = Objects.requireNonNull(usageHealth, "usageHealth must not be null");
            historyHealth = Objects.requireNonNull(
                historyHealth,
                "historyHealth must not be null"
            );
            rollbackPosture = Objects.requireNonNull(
                rollbackPosture,
                "rollbackPosture must not be null"
            );
            evidenceReferences = Objects.requireNonNull(
                evidenceReferences,
                "evidenceReferences must not be null"
            );
            incidentSignals = normalizeSignals(incidentSignals);
            operatorStepCodes = normalizeOperatorSteps(operatorStepCodes);
            rollbackOperatorStepCodes = normalizeCodes(
                rollbackOperatorStepCodes,
                "rollbackOperatorStepCode",
                8,
                true
            );
            blockerCodes = normalizeCodes(blockerCodes, "blockerCode", 32, false);
            actionWhitelistState = requireText(
                actionWhitelistState,
                "actionWhitelistState",
                160
            );
            p5Decision = requireText(p5Decision, "p5Decision", 160);
            if (!ControlledAutomationGovernanceReadContracts.EMPTY_ACTION_WHITELIST.equals(
                actionWhitelistState
            ) || !ControlledAutomationGovernanceReadContracts.P5_SKIPPED.equals(p5Decision)) {
                throw new IllegalArgumentException(
                    "P6-F must preserve the empty Action Whitelist and skipped P5-A"
                );
            }
            if (!durableEvidenceAvailable
                || !processLocalUsageOnly
                || incidentMutationAvailable
                || providerInvocationAvailable
                || rollbackExecutionAvailable
                || commandExecutionAuthorized
                || automaticRetryAuthorized
                || notificationAutomationAvailable
                || rawSecretExposed) {
                throw new IllegalArgumentException(
                    "P6-F readiness must remain evidence-only, manual and non-executing"
                );
            }
            validatePostures(
                runtimeState,
                readinessState,
                controlPosture,
                usageHealth,
                rollbackPosture,
                incidentSignals,
                rollbackOperatorStepCodes
            );
            evidenceHash = requireSha256(evidenceHash, "evidenceHash");
            String computed = evidence(
                viewVersion,
                observedAt,
                fromInclusive,
                toExclusive,
                runtimeState,
                readinessState,
                controlPosture,
                usageHealth,
                historyHealth,
                rollbackPosture,
                evidenceReferences,
                incidentSignals,
                operatorStepCodes,
                rollbackOperatorStepCodes,
                blockerCodes,
                actionWhitelistState,
                p5Decision
            );
            if (!evidenceHash.equals(computed)) {
                throw new IllegalArgumentException(
                    "evidenceHash must match the exact incident-readiness view"
                );
            }
        }

        public static IncidentReadinessView from(
            OperationsView snapshot,
            ControlHealthView controlHealth,
            UsageView usage,
            HistoryView history,
            ReviewPlan rollbackPlan
        ) {
            OperationsView exactSnapshot = Objects.requireNonNull(
                snapshot,
                "snapshot must not be null"
            );
            ControlHealthView exactControl = Objects.requireNonNull(
                controlHealth,
                "controlHealth must not be null"
            );
            UsageView exactUsage = Objects.requireNonNull(usage, "usage must not be null");
            HistoryView exactHistory = Objects.requireNonNull(
                history,
                "history must not be null"
            );
            ReviewPlan exactRollback = Objects.requireNonNull(
                rollbackPlan,
                "rollbackPlan must not be null"
            );
            validateSources(
                exactSnapshot,
                exactControl,
                exactUsage,
                exactHistory,
                exactRollback
            );
            List<IncidentSignal> signals = signals(
                exactSnapshot,
                exactControl,
                exactUsage,
                exactHistory
            );
            ReadinessState readiness = readiness(exactSnapshot.runtimeState(), signals);
            ControlPosture controlPosture = resolveControlPosture(
                exactSnapshot.runtimeState(),
                signals
            );
            RollbackPosture rollbackPosture = resolveRollbackPosture(exactRollback);
            List<String> operatorSteps = operatorSteps(readiness, signals);
            List<String> blockers = blockers(signals);
            EvidenceReferences references = new EvidenceReferences(
                exactSnapshot.evidenceHash(),
                exactControl.evidenceHash(),
                exactUsage.evidenceHash(),
                exactHistory.evidenceHash(),
                exactRollback.evidenceHash()
            );
            String hash = evidence(
                VIEW_VERSION,
                exactSnapshot.observedAt(),
                exactHistory.fromInclusive(),
                exactHistory.toExclusive(),
                exactSnapshot.runtimeState(),
                readiness,
                controlPosture,
                exactUsage.usageHealth(),
                exactHistory.historyHealth(),
                rollbackPosture,
                references,
                signals,
                operatorSteps,
                exactRollback.operatorStepCodes(),
                blockers,
                exactSnapshot.actionWhitelistState(),
                exactSnapshot.p5Decision()
            );
            return new IncidentReadinessView(
                VIEW_VERSION,
                exactSnapshot.observedAt(),
                exactHistory.fromInclusive(),
                exactHistory.toExclusive(),
                exactSnapshot.runtimeState(),
                readiness,
                controlPosture,
                exactUsage.usageHealth(),
                exactHistory.historyHealth(),
                rollbackPosture,
                references,
                signals,
                operatorSteps,
                exactRollback.operatorStepCodes(),
                blockers,
                exactSnapshot.actionWhitelistState(),
                exactSnapshot.p5Decision(),
                true,
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                hash
            );
        }
    }

    private static void validateSources(
        OperationsView snapshot,
        ControlHealthView control,
        UsageView usage,
        HistoryView history,
        ReviewPlan rollback
    ) {
        String hash = snapshot.evidenceHash();
        if (!control.sourceSnapshotEvidenceHash().equals(hash)
            || !usage.sourceSnapshotEvidenceHash().equals(hash)
            || !history.sourceSnapshotEvidenceHash().equals(hash)
            || !rollback.sourceSnapshotHash().equals(hash)) {
            throw new IllegalArgumentException(
                "all P6-F components must bind to the exact same P6-A snapshot"
            );
        }
        if (control.runtimeState() != snapshot.runtimeState()
            || usage.runtimeState() != snapshot.runtimeState()
            || history.currentRuntimeState() != snapshot.runtimeState()
            || rollback.sourceRuntimeState() != snapshot.runtimeState()) {
            throw new IllegalArgumentException(
                "all P6-F components must report the same runtime state"
            );
        }
        if (!history.observedAt().equals(snapshot.observedAt())
            || !rollback.observedAt().equals(snapshot.observedAt())) {
            throw new IllegalArgumentException(
                "history and rollback evidence must use the snapshot observation time"
            );
        }
        if (rollback.operation() != Operation.ROLLBACK
            || rollback.mode() != Mode.NON_EXECUTABLE_REVIEW_ONLY
            || rollback.status() != Status.REVIEW_READY
            || rollback.plannedTrafficPercent() != 0
            || rollback.applyAuthorized()
            || rollback.rollbackMechanism() == RollbackMechanism.NONE) {
            throw new IllegalArgumentException(
                "P6-F requires the exact non-executable P6-B rollback review"
            );
        }
    }

    private static List<IncidentSignal> signals(
        OperationsView snapshot,
        ControlHealthView control,
        UsageView usage,
        HistoryView history
    ) {
        List<IncidentSignal> signals = new ArrayList<>();
        if (snapshot.runtimeState() == RuntimeState.NOT_CONFIGURED) {
            signals.add(IncidentSignal.AI_PROVIDER_RUNTIME_NOT_CONFIGURED);
        } else {
            var runtime = Objects.requireNonNull(
                control.runtimeEvidence(),
                "configured control health requires runtime evidence"
            );
            if (runtime.driftHealth() == DriftHealth.DRIFT_DETECTED) {
                signals.add(IncidentSignal.AI_PROVIDER_RUNTIME_DRIFT_DETECTED);
            }
            if (runtime.killSwitchHealth() == KillSwitchHealth.ADMISSION_DISABLED) {
                signals.add(IncidentSignal.AI_PROVIDER_KILL_SWITCH_ADMISSION_DISABLED);
            }
            if (runtime.costPolicyHealth() != PolicyWindowHealth.CURRENT) {
                signals.add(IncidentSignal.AI_COST_POLICY_NOT_CURRENT);
            }
            if (runtime.secretVersionHealth() != PolicyWindowHealth.CURRENT) {
                signals.add(IncidentSignal.AI_SECRET_VERSION_NOT_CURRENT);
            }
            if (runtime.circuitHealth() == CircuitHealth.OPEN) {
                signals.add(IncidentSignal.AI_PROVIDER_CIRCUIT_OPEN);
            } else if (runtime.circuitHealth() == CircuitHealth.HALF_OPEN) {
                signals.add(IncidentSignal.AI_PROVIDER_CIRCUIT_HALF_OPEN);
            }
        }
        if (usage.usageHealth() == UsageHealth.TENANT_RATE_WINDOW_SATURATED) {
            signals.add(IncidentSignal.AI_TENANT_RATE_WINDOW_SATURATED);
        } else if (usage.usageHealth() == UsageHealth.GLOBAL_RATE_WINDOW_SATURATED) {
            signals.add(IncidentSignal.AI_GLOBAL_RATE_WINDOW_SATURATED);
        }
        if (history.historyHealth() == HistoryHealth.EMPTY) {
            signals.add(IncidentSignal.AI_DURABLE_HISTORY_EMPTY);
        }
        if (history.historyHealth() == HistoryHealth.VERSION_DRIFT_DETECTED
            || history.historyHealth() == HistoryHealth.VERSION_DRIFT_AND_RETENTION_DUE) {
            signals.add(IncidentSignal.AI_DURABLE_HISTORY_VERSION_DRIFT_DETECTED);
        }
        if (history.historyHealth() == HistoryHealth.RETENTION_ACTION_DUE
            || history.historyHealth() == HistoryHealth.VERSION_DRIFT_AND_RETENTION_DUE) {
            signals.add(IncidentSignal.AI_RETENTION_TOMBSTONE_DUE);
        }
        return normalizeSignals(signals);
    }

    private static ReadinessState readiness(
        RuntimeState runtimeState,
        List<IncidentSignal> signals
    ) {
        if (runtimeState == RuntimeState.NOT_CONFIGURED) {
            return ReadinessState.RUNTIME_NOT_CONFIGURED;
        }
        if (signals.stream().anyMatch(ControlledAutomationGovernanceIncidentReadinessContracts
            ::critical)) {
            return ReadinessState.INCIDENT_BLOCKED;
        }
        if (signals.contains(IncidentSignal.AI_PROVIDER_KILL_SWITCH_ADMISSION_DISABLED)
            || signals.contains(
                IncidentSignal.AI_DURABLE_HISTORY_VERSION_DRIFT_DETECTED
            )
            || signals.contains(IncidentSignal.AI_RETENTION_TOMBSTONE_DUE)) {
            return ReadinessState.ACTION_REQUIRED;
        }
        return ReadinessState.OBSERVATION_READY_ADVISORY_ONLY;
    }

    private static boolean critical(IncidentSignal signal) {
        return switch (signal) {
            case AI_PROVIDER_RUNTIME_DRIFT_DETECTED,
                 AI_COST_POLICY_NOT_CURRENT,
                 AI_SECRET_VERSION_NOT_CURRENT,
                 AI_PROVIDER_CIRCUIT_OPEN,
                 AI_PROVIDER_CIRCUIT_HALF_OPEN,
                 AI_TENANT_RATE_WINDOW_SATURATED,
                 AI_GLOBAL_RATE_WINDOW_SATURATED -> true;
            default -> false;
        };
    }

    private static ControlPosture resolveControlPosture(
        RuntimeState runtimeState,
        List<IncidentSignal> signals
    ) {
        if (runtimeState == RuntimeState.NOT_CONFIGURED) {
            return ControlPosture.NOT_CONFIGURED;
        }
        boolean blocked = signals.stream().anyMatch(signal -> switch (signal) {
            case AI_PROVIDER_RUNTIME_DRIFT_DETECTED,
                 AI_PROVIDER_KILL_SWITCH_ADMISSION_DISABLED,
                 AI_COST_POLICY_NOT_CURRENT,
                 AI_SECRET_VERSION_NOT_CURRENT,
                 AI_PROVIDER_CIRCUIT_OPEN,
                 AI_PROVIDER_CIRCUIT_HALF_OPEN -> true;
            default -> false;
        });
        return blocked ? ControlPosture.BLOCKED : ControlPosture.HEALTHY;
    }

    private static RollbackPosture resolveRollbackPosture(ReviewPlan rollback) {
        return rollback.rollbackMechanism() == RollbackMechanism.ALREADY_DISABLED
            ? RollbackPosture.ALREADY_DISABLED
            : RollbackPosture.REVIEW_READY_MANUAL_RELEASE;
    }

    private static List<String> operatorSteps(
        ReadinessState readiness,
        List<IncidentSignal> signals
    ) {
        List<String> steps = new ArrayList<>(List.of(
            "AI_INCIDENT_STEP_VERIFY_READ_ONLY_GOVERNANCE_SNAPSHOT",
            "AI_INCIDENT_STEP_VERIFY_CONTROL_HEALTH",
            "AI_INCIDENT_STEP_VERIFY_TENANT_USAGE",
            "AI_INCIDENT_STEP_REVIEW_DURABLE_HISTORY",
            "AI_INCIDENT_STEP_DO_NOT_AUTOMATICALLY_RETRY"
        ));
        if (readiness == ReadinessState.RUNTIME_NOT_CONFIGURED) {
            steps.add("AI_INCIDENT_STEP_CONFIRM_RUNTIME_DISABLED");
        } else if (readiness == ReadinessState.OBSERVATION_READY_ADVISORY_ONLY) {
            steps.add("AI_INCIDENT_STEP_CONTINUE_READ_ONLY_MONITORING");
        } else {
            steps.add("AI_INCIDENT_STEP_REVIEW_NON_EXECUTABLE_ROLLBACK_PLAN");
            steps.add("AI_INCIDENT_STEP_ESCALATE_RELEASE_OWNER");
        }
        if (signals.contains(
            IncidentSignal.AI_DURABLE_HISTORY_VERSION_DRIFT_DETECTED
        )) {
            steps.add("AI_INCIDENT_STEP_REVIEW_VERSION_HISTORY");
        }
        if (signals.contains(IncidentSignal.AI_RETENTION_TOMBSTONE_DUE)) {
            steps.add("AI_INCIDENT_STEP_REVIEW_RETENTION_TOMBSTONES");
        }
        return normalizeOperatorSteps(steps);
    }

    private static List<String> blockers(List<IncidentSignal> signals) {
        List<String> blockers = new ArrayList<>(List.of(
            "AI_ACTUAL_PROVIDER_COST_NOT_AVAILABLE",
            "AI_AUTOMATION_ACTION_WHITELIST_EMPTY",
            "AI_CONTROL_MUTATION_NOT_AVAILABLE",
            "AI_DURABLE_COST_UPPER_BOUND_HISTORY_NOT_AVAILABLE",
            "AI_INCIDENT_RESPONSE_MANUAL_RELEASE_ONLY",
            "AI_PRODUCTION_REAUTHENTICATION_UNAVAILABLE"
        ));
        signals.forEach(signal -> blockers.add(signal.name()));
        return normalizeCodes(blockers, "blockerCode", 32, false);
    }

    private static void validatePostures(
        RuntimeState runtimeState,
        ReadinessState readinessState,
        ControlPosture controlPosture,
        UsageHealth usageHealth,
        RollbackPosture rollbackPosture,
        List<IncidentSignal> signals,
        List<String> rollbackSteps
    ) {
        ReadinessState expectedReadiness = readiness(runtimeState, signals);
        ControlPosture expectedControl = resolveControlPosture(runtimeState, signals);
        if (readinessState != expectedReadiness || controlPosture != expectedControl) {
            throw new IllegalArgumentException(
                "readiness and control posture must match the exact incident signals"
            );
        }
        if (runtimeState == RuntimeState.NOT_CONFIGURED) {
            if (usageHealth != UsageHealth.NOT_CONFIGURED
                || rollbackPosture != RollbackPosture.ALREADY_DISABLED
                || !signals.contains(IncidentSignal.AI_PROVIDER_RUNTIME_NOT_CONFIGURED)) {
                throw new IllegalArgumentException(
                    "disabled runtime readiness must remain explicitly not configured"
                );
            }
        } else if (usageHealth == UsageHealth.NOT_CONFIGURED
            || rollbackPosture != RollbackPosture.REVIEW_READY_MANUAL_RELEASE) {
            throw new IllegalArgumentException(
                "configured runtime readiness requires usage and manual rollback review"
            );
        }
        if (rollbackSteps.isEmpty()) {
            throw new IllegalArgumentException(
                "P6-F must preserve the P6-B rollback operator steps"
            );
        }
    }

    private static List<IncidentSignal> normalizeSignals(List<IncidentSignal> source) {
        List<IncidentSignal> signals = source == null
            ? List.of()
            : source.stream()
                .map(signal -> Objects.requireNonNull(signal, "incidentSignal must not be null"))
                .distinct()
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .toList();
        if (signals.size() > IncidentSignal.values().length) {
            throw new IllegalArgumentException("incidentSignals must be bounded");
        }
        return signals;
    }

    private static List<String> normalizeOperatorSteps(List<String> source) {
        List<String> steps = normalizeCodes(source, "operatorStepCode", 16, false);
        if (steps.stream().anyMatch(step -> !OPERATOR_STEPS.contains(step))) {
            throw new IllegalArgumentException(
                "operatorStepCodes must use the closed P6-F runbook"
            );
        }
        return steps;
    }

    private static List<String> normalizeCodes(
        List<String> source,
        String name,
        int maximum,
        boolean permitEmpty
    ) {
        List<String> codes = source == null
            ? List.of()
            : source.stream()
                .map(code -> requireText(code, name, 180))
                .distinct()
                .sorted()
                .toList();
        if ((!permitEmpty && codes.isEmpty()) || codes.size() > maximum) {
            throw new IllegalArgumentException(name + " list must be coherent and bounded");
        }
        return codes;
    }

    private static String evidence(
        String viewVersion,
        Instant observedAt,
        Instant fromInclusive,
        Instant toExclusive,
        RuntimeState runtimeState,
        ReadinessState readinessState,
        ControlPosture controlPosture,
        UsageHealth usageHealth,
        HistoryHealth historyHealth,
        RollbackPosture rollbackPosture,
        EvidenceReferences references,
        List<IncidentSignal> signals,
        List<String> operatorSteps,
        List<String> rollbackSteps,
        List<String> blockerCodes,
        String actionWhitelistState,
        String p5Decision
    ) {
        StringBuilder canonical = new StringBuilder("m6-f-p6-f-incident-readiness\n");
        append(canonical, viewVersion);
        append(canonical, observedAt.toString());
        append(canonical, fromInclusive.toString());
        append(canonical, toExclusive.toString());
        append(canonical, runtimeState.name());
        append(canonical, readinessState.name());
        append(canonical, controlPosture.name());
        append(canonical, usageHealth.name());
        append(canonical, historyHealth.name());
        append(canonical, rollbackPosture.name());
        append(canonical, references.snapshotEvidenceHash());
        append(canonical, references.controlHealthEvidenceHash());
        append(canonical, references.usageEvidenceHash());
        append(canonical, references.historyEvidenceHash());
        append(canonical, references.rollbackPlanEvidenceHash());
        normalizeSignals(signals).forEach(signal -> append(canonical, signal.name()));
        normalizeOperatorSteps(operatorSteps).forEach(step -> append(canonical, step));
        normalizeCodes(rollbackSteps, "rollbackOperatorStepCode", 8, true)
            .forEach(step -> append(canonical, step));
        normalizeCodes(blockerCodes, "blockerCode", 32, false)
            .forEach(blocker -> append(canonical, blocker));
        append(canonical, actionWhitelistState);
        append(canonical, p5Decision);
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    canonical.toString().getBytes(StandardCharsets.UTF_8)
                )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static void append(StringBuilder canonical, String value) {
        String exact = Objects.requireNonNull(value, "canonical value must not be null");
        canonical.append(exact.length()).append(':').append(exact).append('\n');
    }

    private static String requireSha256(String value, String name) {
        String normalized = requireText(value, name, 64);
        if (!SHA256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be lowercase SHA-256");
        }
        return normalized;
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()
            || !value.equals(value.trim())
            || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be canonical and bounded");
        }
        return value;
    }
}
