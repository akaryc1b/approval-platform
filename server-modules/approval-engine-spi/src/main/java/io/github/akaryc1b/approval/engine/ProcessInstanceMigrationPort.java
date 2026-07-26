package io.github.akaryc1b.approval.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Product-owned, one-instance migration boundary. Implementations may use only public engine APIs.
 * A returned dispatch is not verified migration completion.
 */
public interface ProcessInstanceMigrationPort {

    MigrationDispatchResult migrateOne(MigrationCommand command);

    record MigrationCommand(
        String tenantId,
        UUID approvalInstanceId,
        UUID attemptId,
        String engineInstanceId,
        String sourceEngineDefinitionId,
        String targetEngineDeploymentId,
        String targetEngineDefinitionId,
        List<ActivityMapping> activityMappings
    ) {
        public MigrationCommand {
            tenantId = requireText(tenantId, "tenantId", 128);
            approvalInstanceId = Objects.requireNonNull(
                approvalInstanceId,
                "approvalInstanceId must not be null"
            );
            attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
            engineInstanceId = requireText(engineInstanceId, "engineInstanceId", 256);
            sourceEngineDefinitionId = requireText(
                sourceEngineDefinitionId,
                "sourceEngineDefinitionId",
                256
            );
            targetEngineDeploymentId = requireText(
                targetEngineDeploymentId,
                "targetEngineDeploymentId",
                128
            );
            targetEngineDefinitionId = requireText(
                targetEngineDefinitionId,
                "targetEngineDefinitionId",
                256
            );
            if (sourceEngineDefinitionId.equals(targetEngineDefinitionId)) {
                throw new IllegalArgumentException("source and target definitions must be distinct");
            }
            activityMappings = canonicalMappings(activityMappings);
        }
    }

    record ActivityMapping(String sourceActivityId, String targetActivityId) {
        public ActivityMapping {
            sourceActivityId = requireText(sourceActivityId, "sourceActivityId", 128);
            targetActivityId = requireText(targetActivityId, "targetActivityId", 128);
        }
    }

    record MigrationDispatchResult(
        DispatchDisposition disposition,
        boolean engineCallAttempted,
        boolean engineCallReturned,
        BoundedRuntimeSnapshot preDispatchSnapshot,
        List<String> validationCodes,
        String boundedSummary
    ) {
        public MigrationDispatchResult {
            disposition = Objects.requireNonNull(disposition, "disposition must not be null");
            preDispatchSnapshot = Objects.requireNonNull(
                preDispatchSnapshot,
                "preDispatchSnapshot must not be null"
            );
            validationCodes = canonicalCodes(validationCodes);
            boundedSummary = optionalText(boundedSummary, "boundedSummary", 1000);
            if (disposition == DispatchDisposition.CALL_RETURNED_AWAITING_VERIFICATION
                && (!engineCallAttempted || !engineCallReturned)) {
                throw new IllegalArgumentException("returned dispatch requires one returned engine call");
            }
            if (disposition == DispatchDisposition.PRE_DISPATCH_REJECTED && engineCallAttempted) {
                throw new IllegalArgumentException("pre-dispatch rejection cannot attempt migration");
            }
            if (engineCallReturned && !engineCallAttempted) {
                throw new IllegalArgumentException("engine call cannot return before it is attempted");
            }
        }
    }

    /** Bounded, value-free pre-dispatch snapshot owned by the server. */
    record BoundedRuntimeSnapshot(
        boolean runtimePresent,
        String observedEngineDefinitionId,
        String observedEngineDeploymentId,
        boolean suspended,
        List<String> activeActivityIds,
        List<String> activeTaskDefinitionKeys,
        long executableJobCount,
        long timerJobCount,
        long suspendedJobCount,
        long deadLetterJobCount,
        boolean truncated,
        String snapshotHash
    ) {
        public BoundedRuntimeSnapshot {
            observedEngineDefinitionId = optionalText(
                observedEngineDefinitionId,
                "observedEngineDefinitionId",
                256
            );
            observedEngineDeploymentId = optionalText(
                observedEngineDeploymentId,
                "observedEngineDeploymentId",
                128
            );
            activeActivityIds = canonicalKeys(activeActivityIds, "activeActivityIds", 64);
            activeTaskDefinitionKeys = canonicalKeys(
                activeTaskDefinitionKeys,
                "activeTaskDefinitionKeys",
                64
            );
            requireNonNegative(executableJobCount, "executableJobCount");
            requireNonNegative(timerJobCount, "timerJobCount");
            requireNonNegative(suspendedJobCount, "suspendedJobCount");
            requireNonNegative(deadLetterJobCount, "deadLetterJobCount");
            snapshotHash = requireHash(snapshotHash, "snapshotHash");
            if (!runtimePresent && (observedEngineDefinitionId != null
                || observedEngineDeploymentId != null || suspended
                || !activeActivityIds.isEmpty() || !activeTaskDefinitionKeys.isEmpty()
                || executableJobCount != 0 || timerJobCount != 0
                || suspendedJobCount != 0 || deadLetterJobCount != 0)) {
                throw new IllegalArgumentException("missing runtime snapshot cannot retain runtime evidence");
            }
        }
    }

    enum DispatchDisposition {
        PRE_DISPATCH_REJECTED,
        ENGINE_REJECTED,
        CALL_RETURNED_AWAITING_VERIFICATION
    }

    /**
     * Raised only when a migration call may have occurred but no authoritative response is available.
     * Callers must persist durable UNKNOWN and must not retry automatically.
     */
    final class AmbiguousMigrationDispatchException extends RuntimeException {
        private final boolean engineCallMayHaveOccurred;
        private final String stableCode;

        public AmbiguousMigrationDispatchException(
            String stableCode,
            String message,
            boolean engineCallMayHaveOccurred,
            Throwable cause
        ) {
            super(requireText(message, "message", 1000), cause);
            this.stableCode = requireText(stableCode, "stableCode", 96);
            this.engineCallMayHaveOccurred = engineCallMayHaveOccurred;
        }

        public boolean engineCallMayHaveOccurred() {
            return engineCallMayHaveOccurred;
        }

        public String stableCode() {
            return stableCode;
        }
    }

    private static List<ActivityMapping> canonicalMappings(List<ActivityMapping> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > 64) {
            throw new IllegalArgumentException("activityMappings exceeds maximum size 64");
        }
        ArrayList<ActivityMapping> result = new ArrayList<>(values);
        result.forEach(value -> Objects.requireNonNull(value, "activityMapping must not be null"));
        result.sort((left, right) -> {
            int source = left.sourceActivityId().compareTo(right.sourceActivityId());
            return source == 0
                ? left.targetActivityId().compareTo(right.targetActivityId())
                : source;
        });
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        for (ActivityMapping value : result) {
            if (!sources.add(value.sourceActivityId())) {
                throw new IllegalArgumentException("activityMappings contains duplicate source activity");
            }
        }
        return List.copyOf(result);
    }

    private static List<String> canonicalCodes(List<String> values) {
        return canonicalKeys(values, "validationCodes", 64);
    }

    private static List<String> canonicalKeys(List<String> values, String name, int maximumItems) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > maximumItems) {
            throw new IllegalArgumentException(name + " exceeds maximum size " + maximumItems);
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            normalized.add(requireText(value, name, 128));
        }
        ArrayList<String> sorted = new ArrayList<>(normalized);
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static String requireHash(String value, String name) {
        String normalized = requireText(value, name, 64);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return normalized;
    }

    private static String optionalText(String value, String name, int maximum) {
        return value == null || value.isBlank() ? null : requireText(value, name, maximum);
    }

    private static String requireText(String value, String name, int maximum) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " is blank or exceeds maximum length " + maximum);
        }
        return normalized;
    }
}
