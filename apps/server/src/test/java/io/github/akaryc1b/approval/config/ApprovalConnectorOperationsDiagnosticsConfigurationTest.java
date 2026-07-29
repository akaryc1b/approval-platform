package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.connector.invocation.ConnectorInvocationObservationSink;
import io.github.akaryc1b.approval.connector.operations.BoundedConnectorOperationsDiagnosticsStore;
import io.github.akaryc1b.approval.connector.operations.ConnectorDiagnosticsPageTokenCodec;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsQueryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalConnectorOperationsDiagnosticsConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(
            ApprovalConnectorOperationsDiagnosticsConfiguration.class,
            TestDependencies.class
        );

    @Test
    void operationsDiagnosticsIsDisabledByDefault() {
        runner.run(context -> {
            assertFalse(context.containsBean("connectorOperationsDiagnosticsStore"));
            assertFalse(context.containsBean("connectorDiagnosticsPageTokenCodec"));
            assertFalse(context.containsBean("connectorOperationsObservationSink"));
        });
    }

    @Test
    void enabledConfigurationCreatesOnlyProcessLocalReadOnlyBeans() {
        runner.withPropertyValues(
            "approval.connector.operations-diagnostics.enabled=true",
            "approval.connector.operations-diagnostics.maximum-entries=32",
            "approval.connector.operations-diagnostics.maximum-entries-per-tenant=8"
        ).run(context -> {
            assertNotNull(context.getBean(BoundedConnectorOperationsDiagnosticsStore.class));
            assertNotNull(context.getBean(ConnectorDiagnosticsPageTokenCodec.class));
            assertNotNull(context.getBean(ConnectorOperationsDiagnosticsQueryService.class));
            assertEquals(1, context.getBeansOfType(ConnectorInvocationObservationSink.class).size());
        });
    }

    @Test
    void invalidCapacityFailsClosed() {
        runner.withPropertyValues(
            "approval.connector.operations-diagnostics.enabled=true",
            "approval.connector.operations-diagnostics.maximum-entries=2",
            "approval.connector.operations-diagnostics.maximum-entries-per-tenant=3"
        ).run(context -> assertTrue(context.getStartupFailure() != null));
    }

    @Test
    void unknownPropertyFailsClosed() {
        runner.withPropertyValues(
            "approval.connector.operations-diagnostics.enabled=true",
            "approval.connector.operations-diagnostics.unknown-authority=true"
        ).run(context -> assertTrue(context.getStartupFailure() != null));
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDependencies {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
