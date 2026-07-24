package io.github.akaryc1b.approval.ai.core;

/** Contract-only sink for coordinated routing and usage evidence; no persistence in M6-D. */
@FunctionalInterface
public interface AiAdvisoryExecutionEvidenceSink {

    void record(AiAdvisoryExecutionEvidence evidence);

    static AiAdvisoryExecutionEvidenceSink noop() {
        return evidence -> {
        };
    }
}
