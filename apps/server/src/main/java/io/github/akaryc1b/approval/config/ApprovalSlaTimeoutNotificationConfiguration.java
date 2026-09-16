package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.port.ApprovalSlaActionStateRecorder;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaTimeoutEventRecorder;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector.CallbackReceipt;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector.DeliveryStatus;
import io.github.akaryc1b.approval.connector.port.OrganizationConnector;
import io.github.akaryc1b.approval.integration.outbox.BusinessCallbackResolver;
import io.github.akaryc1b.approval.integration.outbox.OutboxRepository;
import io.github.akaryc1b.approval.integration.outbox.SlaTimeoutNotificationConnector;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaTimeoutEventRecorder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;

/** Opt-in only. Reuses the original SLA worker and Outbox dispatcher; no extra sender or scheduler. */
@Configuration(proxyBeanMethods = false)
public class ApprovalSlaTimeoutNotificationConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "approval.sla.operational-notifications", name = "enabled", havingValue = "true")
    ApprovalSlaTimeoutEventRecorder approvalSlaTimeoutEventRecorder(DataSource dataSource,
        PlatformTransactionManager transactionManager, ApprovalSlaExecutionStore executionStore,
        ApprovalSlaActionStateRecorder actionState, OutboxRepository outbox,
        GenericConnectorProperties properties, Clock approvalClock) {
        requireDelivery(properties);
        return new JdbcApprovalSlaTimeoutEventRecorder(dataSource, transactionManager,
            executionStore, actionState, outbox, properties.getConnectorKey(), approvalClock);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "approval.connector.generic", name = "enabled", havingValue = "true")
    BusinessCallbackResolver slaTimeoutBusinessCallbackResolver(
        @Qualifier("businessCallbackResolver") BusinessCallbackResolver original,
        OrganizationConnector organization, GenericConnectorProperties properties, Clock approvalClock,
        @Value("${approval.sla.operational-notifications.enabled:false}") boolean enabled) {
        SlaTimeoutNotificationConnector.routeKey(properties.getConnectorKey());
        if (enabled) requireDelivery(properties);
        // Reserve the key even after disabling: never fall through to a payment callback.
        BusinessCallbackConnector notification = enabled
            ? new SlaTimeoutNotificationConnector(organization, properties.getConnectorKey(), approvalClock)
            : (context, event) -> new CallbackReceipt(DeliveryStatus.PERMANENT_FAILURE, null, 0,
                approvalClock.instant(), "SLA_NOTIFICATION_DISABLED");
        return key -> SlaTimeoutNotificationConnector.ROUTE.equals(key) ? notification : original.resolve(key);
    }

    private static void requireDelivery(GenericConnectorProperties properties) {
        if (!properties.isEnabled() || !properties.getDispatch().isEnabled()) {
            throw new IllegalStateException("SLA operational notifications require the existing generic connector and Outbox dispatcher");
        }
        SlaTimeoutNotificationConnector.routeKey(properties.getConnectorKey());
    }
}
