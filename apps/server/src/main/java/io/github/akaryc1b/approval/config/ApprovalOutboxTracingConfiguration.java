package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.observability.OutboxTracing;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Reuses the host tracer without enabling an exporter, creating a worker or touching the database. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "approval.observability.outbox-tracing", name = "enabled", havingValue = "true")
public class ApprovalOutboxTracingConfiguration {
    @Bean
    OutboxTracing outboxTracing(ObjectProvider<Tracer> tracers) {
        return new OutboxTracing(tracers::getIfAvailable);
    }
}
