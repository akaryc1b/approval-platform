package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.integration.outbox.OutboxDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.Objects;
import java.util.UUID;

@Component
@ConditionalOnBean(OutboxDispatcher.class)
public class OutboxDispatchScheduler {

    private final OutboxDispatcher dispatcher;
    private final GenericConnectorProperties properties;
    private final String workerId;

    public OutboxDispatchScheduler(
        OutboxDispatcher dispatcher,
        GenericConnectorProperties properties
    ) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.workerId = ManagementFactory.getRuntimeMXBean().getName()
            + ':'
            + UUID.randomUUID();
    }

    @Scheduled(fixedDelayString = "${approval.connector.generic.dispatch.fixed-delay:PT5S}")
    public void dispatch() {
        if (properties.getDispatch().isEnabled()) {
            dispatcher.dispatchBatch(properties.getDispatch().getBatchSize(), workerId);
        }
    }
}
