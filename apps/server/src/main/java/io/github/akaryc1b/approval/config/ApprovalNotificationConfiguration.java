package io.github.akaryc1b.approval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalNotificationService;
import io.github.akaryc1b.approval.application.NotificationAwareAuditEventSink;
import io.github.akaryc1b.approval.application.port.ApprovalConnectorNotificationSender;
import io.github.akaryc1b.approval.application.port.ApprovalEmailNotificationSender;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.port.OrganizationConnector;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalNotificationStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ApprovalNotificationConfiguration {

    @Bean
    ApprovalNotificationStore approvalNotificationStore(
        DataSource dataSource,
        ObjectMapper approvalPersistenceObjectMapper,
        PlatformTransactionManager transactionManager
    ) {
        return new JdbcApprovalNotificationStore(
            dataSource,
            approvalPersistenceObjectMapper,
            transactionManager
        );
    }

    @Bean
    ApprovalConnectorNotificationSender approvalConnectorNotificationSender(
        ObjectProvider<OrganizationConnector> organizationConnectors,
        Clock approvalClock
    ) {
        OrganizationConnector connector = organizationConnectors.getIfAvailable();
        if (connector == null) {
            return ApprovalConnectorNotificationSender.unavailable();
        }
        return intent -> {
            String connectorKey = intent.metadata().getOrDefault("connectorKey", "generic-rest");
            OrganizationConnector.NotificationDeliveryResult result = connector.sendNotification(
                new ConnectorContext(
                    connectorKey,
                    intent.tenantId(),
                    "notification-" + intent.intentId(),
                    null,
                    approvalClock.instant()
                ),
                new OrganizationConnector.UserNotification(
                    intent.recipientId(),
                    intent.eventType().name(),
                    intent.title(),
                    intent.body(),
                    intent.metadata(),
                    intent.businessEventKey()
                )
            );
            return result.successful()
                ? ApprovalConnectorNotificationSender.DeliveryResult.delivered(
                    result.providerMessageId()
                )
                : ApprovalConnectorNotificationSender.DeliveryResult.failed(
                    result.retryable(),
                    result.errorCode(),
                    result.errorMessage()
                );
        };
    }

    @Bean
    ApprovalNotificationService approvalNotificationService(
        ApprovalNotificationStore approvalNotificationStore,
        ApprovalProjectionStore approvalProjectionStore,
        ApprovalConnectorNotificationSender approvalConnectorNotificationSender,
        ObjectProvider<ApprovalEmailNotificationSender> emailSenders,
        Clock approvalClock
    ) {
        return new ApprovalNotificationService(
            approvalNotificationStore,
            approvalProjectionStore,
            approvalConnectorNotificationSender,
            emailSenders.getIfAvailable(ApprovalEmailNotificationSender::unavailable),
            approvalClock,
            UUID::randomUUID
        );
    }

    @Bean
    @Primary
    AuditEventSink notificationAwareAuditEventSink(
        @Qualifier("auditEventSink") AuditEventSink delegate,
        ApprovalNotificationService approvalNotificationService
    ) {
        return new NotificationAwareAuditEventSink(delegate, approvalNotificationService);
    }

    @Bean
    ApprovalNotificationScheduler approvalNotificationScheduler(
        ApprovalNotificationService approvalNotificationService
    ) {
        return new ApprovalNotificationScheduler(approvalNotificationService);
    }

    static final class ApprovalNotificationScheduler {

        private final ApprovalNotificationService service;
        private final String workerId = "notification-worker-" + UUID.randomUUID();

        ApprovalNotificationScheduler(ApprovalNotificationService service) {
            this.service = service;
        }

        @Scheduled(fixedDelayString = "${approval.notifications.delivery-delay-ms:5000}")
        void deliver() {
            service.processDue(50, workerId);
        }
    }
}
