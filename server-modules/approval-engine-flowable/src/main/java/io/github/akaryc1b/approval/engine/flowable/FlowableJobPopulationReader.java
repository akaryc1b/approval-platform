package io.github.akaryc1b.approval.engine.flowable;

import io.github.akaryc1b.approval.engine.EngineJobPopulationReader;
import org.flowable.engine.ManagementService;

import java.time.Clock;
import java.util.Objects;

/** Public Flowable count queries only. No native table access, job materialization or mutation. */
public final class FlowableJobPopulationReader implements EngineJobPopulationReader {
    private final ManagementService management;
    private final Clock clock;

    public FlowableJobPopulationReader(ManagementService management, Clock clock) {
        this.management = Objects.requireNonNull(management, "management must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Snapshot read() {
        var observedAt = clock.instant();
        // Four separate engine queries, not an atomic database snapshot. If any fails,
        // no partial sample is returned. The host engine owns pool/query timeouts.
        long executable = management.createJobQuery().count();
        long timer = management.createTimerJobQuery().count();
        long suspended = management.createSuspendedJobQuery().count();
        long deadLetter = management.createDeadLetterJobQuery().count();
        return new Snapshot(observedAt, executable, timer, suspended, deadLetter);
    }
}
