package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.connector.invocation.ConnectorInvocationObservationSink;
import io.github.akaryc1b.approval.connector.invocation.GovernedReadOnlyConnectorInvocationCoordinator;
import io.github.akaryc1b.approval.connector.operations.BoundedConnectorOperationsDiagnosticsStore;
import io.github.akaryc1b.approval.connector.operations.ConnectorDiagnosticsPageTokenCodec;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsQueryService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.security.SecureRandom;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApprovalConnectorOperationsDiagnosticsProperties.class)
@ConditionalOnProperty(
    prefix = "approval.connector.operations-diagnostics",
    name = "enabled",
    havingValue = "true"
)
public class ApprovalConnectorOperationsDiagnosticsConfiguration {

    @Bean
    BoundedConnectorOperationsDiagnosticsStore connectorOperationsDiagnosticsStore(
        ApprovalConnectorOperationsDiagnosticsProperties properties
    ) {
        return new BoundedConnectorOperationsDiagnosticsStore(
            properties.getMaximumEntries(),
            properties.getMaximumEntriesPerTenant()
        );
    }

    @Bean(destroyMethod = "close")
    ConnectorDiagnosticsPageTokenCodec connectorDiagnosticsPageTokenCodec() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        try {
            return new ConnectorDiagnosticsPageTokenCodec(key);
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
    }

    @Bean
    ConnectorOperationsDiagnosticsQueryService connectorOperationsDiagnosticsQueryService(
        BoundedConnectorOperationsDiagnosticsStore store,
        ConnectorDiagnosticsPageTokenCodec tokenCodec,
        ApprovalConnectorOperationsDiagnosticsProperties properties,
        java.time.Clock clock
    ) {
        return new ConnectorOperationsDiagnosticsQueryService(
            store,
            tokenCodec,
            clock,
            properties.getMaximumResponseBytes()
        );
    }

    @Bean
    @Primary
    ConnectorInvocationObservationSink connectorOperationsObservationSink(
        BoundedConnectorOperationsDiagnosticsStore store,
        MeterRegistry meters
    ) {
        return new MicrometerConnectorOperationsObservationSink(store, meters);
    }

    @Bean
    @ConditionalOnBean(GovernedReadOnlyConnectorInvocationCoordinator.class)
    ObservedReadOnlyConnectorInvocationService observedReadOnlyConnectorInvocationService(
        GovernedReadOnlyConnectorInvocationCoordinator coordinator,
        ConnectorInvocationObservationSink observationSink,
        Clock clock
    ) {
        return new ObservedReadOnlyConnectorInvocationService(
            coordinator,
            observationSink,
            clock
        );
    }
}
