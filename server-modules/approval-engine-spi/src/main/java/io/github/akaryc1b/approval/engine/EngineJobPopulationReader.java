package io.github.akaryc1b.approval.engine;

import java.time.Instant;
import java.util.Objects;

/** Internal, deployment-wide queue observations, not an approval or tenant-facing API. */
@FunctionalInterface
public interface EngineJobPopulationReader {
    Snapshot read();

    /** Counts may be read sequentially; never infer a consistent total across queues. */
    record Snapshot(Instant observedAt, long executable, long timer, long suspended, long deadLetter) {
        public Snapshot {
            Objects.requireNonNull(observedAt, "observedAt must not be null");
            if (executable < 0 || timer < 0 || suspended < 0 || deadLetter < 0) {
                throw new IllegalArgumentException("engine job counts must not be negative");
            }
        }
    }
}
