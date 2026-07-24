package io.github.akaryc1b.approval.domain.migration;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Closed lifecycle for immutable M5-C migration plans. */
public final class ApprovalMigrationPlanProtocol {

    private static final Set<PlanStatus> TERMINAL = EnumSet.of(
        PlanStatus.EXPIRED,
        PlanStatus.CANCELLED,
        PlanStatus.CONSUMED
    );

    private ApprovalMigrationPlanProtocol() {
    }

    public static void requireTransition(PlanStatus from, PlanStatus to) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        boolean permitted = switch (from) {
            case PROPOSED -> to == PlanStatus.AUTHORIZED
                || to == PlanStatus.EXPIRED
                || to == PlanStatus.CANCELLED;
            case AUTHORIZED -> to == PlanStatus.EXPIRED
                || to == PlanStatus.CANCELLED
                || to == PlanStatus.CONSUMED;
            case EXPIRED, CANCELLED, CONSUMED -> false;
        };
        if (!permitted) {
            throw new IllegalArgumentException("migration plan transition is not permitted");
        }
    }

    public static boolean terminal(PlanStatus status) {
        return TERMINAL.contains(Objects.requireNonNull(status, "status must not be null"));
    }

    public enum PlanStatus {
        PROPOSED,
        AUTHORIZED,
        EXPIRED,
        CANCELLED,
        CONSUMED
    }

    public enum ExpectedInstanceStatus {
        RUNNING
    }
}
