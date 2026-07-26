package io.github.akaryc1b.approval.domain.migration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Bounded, redacted, value-free public-engine readback used by D4 and D6. */
public record ApprovalMigrationEngineSnapshot(
    boolean readSucceeded,
    String readFailureCode,
    boolean runtimePresent,
    String runtimeEngineDefinitionId,
    String runtimeEngineDeploymentId,
    boolean suspended,
    List<String> activeActivityIds,
    List<DefinitionEvidence> executions,
    List<TaskEvidence> activeTasks,
    List<JobEvidence> jobs,
    List<SubscriptionEvidence> subscriptions,
    List<String> allowlistedVariableHashes,
    List<String> identityLinkHashes,
    boolean historyPresent,
    String historicEngineDefinitionId,
    Instant historicEndTime,
    String boundedDeleteReason,
    List<TaskEvidence> historicTasks,
    boolean truncated,
    String snapshotHash
) {
    private static final int MAX_ITEMS = 64;

    public ApprovalMigrationEngineSnapshot {
        readFailureCode = ApprovalMigrationRules.optionalText(readFailureCode, "readFailureCode", 96);
        runtimeEngineDefinitionId = ApprovalMigrationRules.optionalText(
            runtimeEngineDefinitionId,
            "runtimeEngineDefinitionId",
            256
        );
        runtimeEngineDeploymentId = ApprovalMigrationRules.optionalText(
            runtimeEngineDeploymentId,
            "runtimeEngineDeploymentId",
            256
        );
        activeActivityIds = canonicalText(activeActivityIds, "activeActivityIds");
        executions = canonicalEvidence(executions, "executions");
        activeTasks = canonicalEvidence(activeTasks, "activeTasks");
        jobs = canonicalEvidence(jobs, "jobs");
        subscriptions = canonicalEvidence(subscriptions, "subscriptions");
        allowlistedVariableHashes = canonicalHashes(
            allowlistedVariableHashes,
            "allowlistedVariableHashes"
        );
        identityLinkHashes = canonicalHashes(identityLinkHashes, "identityLinkHashes");
        historicEngineDefinitionId = ApprovalMigrationRules.optionalText(
            historicEngineDefinitionId,
            "historicEngineDefinitionId",
            256
        );
        boundedDeleteReason = ApprovalMigrationRules.optionalText(
            boundedDeleteReason,
            "boundedDeleteReason",
            512
        );
        historicTasks = canonicalEvidence(historicTasks, "historicTasks");
        snapshotHash = ApprovalMigrationRules.requireHash(snapshotHash, "snapshotHash");

        if (readSucceeded != (readFailureCode == null)) {
            throw new IllegalArgumentException("engine snapshot read outcome is inconsistent");
        }
        if (!readSucceeded && (runtimePresent || historyPresent || suspended
            || runtimeEngineDefinitionId != null || runtimeEngineDeploymentId != null
            || historicEngineDefinitionId != null || historicEndTime != null
            || boundedDeleteReason != null || !activeActivityIds.isEmpty()
            || !executions.isEmpty() || !activeTasks.isEmpty() || !jobs.isEmpty()
            || !subscriptions.isEmpty() || !allowlistedVariableHashes.isEmpty()
            || !identityLinkHashes.isEmpty() || !historicTasks.isEmpty())) {
            throw new IllegalArgumentException("failed engine read cannot retain untrusted partial evidence");
        }
        if (!runtimePresent && (suspended || runtimeEngineDefinitionId != null
            || runtimeEngineDeploymentId != null || !activeActivityIds.isEmpty()
            || !executions.isEmpty() || !activeTasks.isEmpty() || !jobs.isEmpty()
            || !subscriptions.isEmpty() || !allowlistedVariableHashes.isEmpty()
            || !identityLinkHashes.isEmpty())) {
            throw new IllegalArgumentException("missing runtime cannot retain runtime evidence");
        }
        if (!historyPresent && (historicEngineDefinitionId != null || historicEndTime != null
            || boundedDeleteReason != null || !historicTasks.isEmpty())) {
            throw new IllegalArgumentException("missing history cannot retain historic evidence");
        }
    }

    public static ApprovalMigrationEngineSnapshot readFailure(String stableCode, String snapshotHash) {
        return new ApprovalMigrationEngineSnapshot(
            false,
            stableCode,
            false,
            null,
            null,
            false,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            false,
            null,
            null,
            null,
            List.of(),
            false,
            snapshotHash
        );
    }

    public record DefinitionEvidence(String kind, String identity, String engineDefinitionId) {
        public DefinitionEvidence {
            kind = ApprovalMigrationRules.requireText(kind, "kind", 48);
            identity = ApprovalMigrationRules.requireText(identity, "identity", 256);
            engineDefinitionId = ApprovalMigrationRules.optionalText(
                engineDefinitionId,
                "engineDefinitionId",
                256
            );
        }
    }

    public record TaskEvidence(
        String taskIdHash,
        String taskDefinitionKey,
        String engineDefinitionId,
        boolean suspended
    ) {
        public TaskEvidence {
            taskIdHash = ApprovalMigrationRules.requireHash(taskIdHash, "taskIdHash");
            taskDefinitionKey = ApprovalMigrationRules.requireText(
                taskDefinitionKey,
                "taskDefinitionKey",
                128
            );
            engineDefinitionId = ApprovalMigrationRules.optionalText(
                engineDefinitionId,
                "engineDefinitionId",
                256
            );
        }
    }

    public record JobEvidence(
        String jobIdHash,
        String jobKind,
        String jobState,
        String engineDefinitionId,
        String activityId
    ) {
        public JobEvidence {
            jobIdHash = ApprovalMigrationRules.requireHash(jobIdHash, "jobIdHash");
            jobKind = ApprovalMigrationRules.requireText(jobKind, "jobKind", 48);
            jobState = ApprovalMigrationRules.requireText(jobState, "jobState", 48);
            engineDefinitionId = ApprovalMigrationRules.optionalText(
                engineDefinitionId,
                "engineDefinitionId",
                256
            );
            activityId = ApprovalMigrationRules.optionalText(activityId, "activityId", 128);
        }
    }

    public record SubscriptionEvidence(
        String subscriptionIdHash,
        String eventType,
        String activityId,
        String engineDefinitionId
    ) {
        public SubscriptionEvidence {
            subscriptionIdHash = ApprovalMigrationRules.requireHash(
                subscriptionIdHash,
                "subscriptionIdHash"
            );
            eventType = ApprovalMigrationRules.requireText(eventType, "eventType", 96);
            activityId = ApprovalMigrationRules.optionalText(activityId, "activityId", 128);
            engineDefinitionId = ApprovalMigrationRules.optionalText(
                engineDefinitionId,
                "engineDefinitionId",
                256
            );
        }
    }

    private static List<String> canonicalText(List<String> values, String name) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > MAX_ITEMS) {
            throw new IllegalArgumentException(name + " exceeds maximum size " + MAX_ITEMS);
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            normalized.add(ApprovalMigrationRules.requireText(value, name, 128));
        }
        ArrayList<String> sorted = new ArrayList<>(normalized);
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    private static List<String> canonicalHashes(List<String> values, String name) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > MAX_ITEMS) {
            throw new IllegalArgumentException(name + " exceeds maximum size " + MAX_ITEMS);
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            normalized.add(ApprovalMigrationRules.requireHash(value, name));
        }
        ArrayList<String> sorted = new ArrayList<>(normalized);
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    private static <T> List<T> canonicalEvidence(List<T> values, String name) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > MAX_ITEMS) {
            throw new IllegalArgumentException(name + " exceeds maximum size " + MAX_ITEMS);
        }
        ArrayList<T> result = new ArrayList<>(values);
        result.forEach(value -> Objects.requireNonNull(value, name + " contains null evidence"));
        result.sort((left, right) -> left.toString().compareTo(right.toString()));
        return List.copyOf(result);
    }
}
