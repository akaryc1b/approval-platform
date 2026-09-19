package io.github.akaryc1b.approval.observability;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

class WorkflowPopulationMonitorTest {
    @TestFactory
    Stream<DynamicTest> cachedPopulationAndJdbcProtocol() {
        return WorkflowPopulationMonitorChecks.cases().entrySet().stream()
            .map(entry -> DynamicTest.dynamicTest(entry.getKey(), entry.getValue()::run));
    }
}
