package io.github.akaryc1b.approval.observability;

import org.junit.jupiter.api.Test;

class EngineJobPopulationMonitorTest {
    @Test
    void sharedLifecycleAndFailureChecks() throws Exception {
        EngineJobPopulationMonitorChecks.runAll();
    }
}
