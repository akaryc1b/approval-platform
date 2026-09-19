package io.github.akaryc1b.approval.application.port;

import java.time.Instant;
import java.util.Objects;

/** Private deployment-wide observations, never an authorization or tenant-facing query. */
@FunctionalInterface
public interface ApprovalWorkflowPopulationReader {
    Snapshot read();

    /** Covered includes paused SLA targets; overdue is a subset of covered and active. */
    record Snapshot(Instant observedAt, long activeProcesses, long coveredProcesses, long overdueProcesses,
                    long activeTasks, long coveredTasks, long overdueTasks) {
        public Snapshot {
            Objects.requireNonNull(observedAt, "observedAt must not be null");
            if (!valid(activeProcesses, coveredProcesses, overdueProcesses)
                || !valid(activeTasks, coveredTasks, overdueTasks)) {
                throw new IllegalArgumentException("invalid workflow population snapshot");
            }
        }

        private static boolean valid(long active, long covered, long overdue) {
            return active >= 0 && covered >= 0 && covered <= active && overdue >= 0 && overdue <= covered;
        }
    }
}
