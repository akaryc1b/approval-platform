package io.github.akaryc1b.approval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ConnectorPurchasePaymentAssigneeResolver;
import io.github.akaryc1b.approval.application.port.ApprovalBusinessEventOutbox;
import io.github.akaryc1b.approval.application.port.PurchasePaymentAssigneeResolver;
import io.github.akaryc1b.approval.connector.generic.GenericRestBusinessCallbackConnector;
import io.github.akaryc1b.approval.connector.generic.GenericRestHostEndpoint;
import io.github.akaryc1b.approval.connector.generic.GenericRestHostEndpointResolver;
import io.github.akaryc1b.approval.connector.generic.GenericRestOrganizationConnector;
import io.github.akaryc1b.approval.connector.generic.GenericRestTransport;
import io.github.akaryc1b.approval.connector.generic.GenericWebhookEndpoint;
import io.github.akaryc1b.approval.connector.generic.GenericWebhookEndpointResolver;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector;
import io.github.akaryc1b.approval.connector.port.OrganizationConnector;
import io.github.akaryc1b.approval.integration.jdbc.JdbcOutboxRepository;
import io.github.akaryc1b.approval.integration.outbox.BusinessCallbackResolver;
import io.github.akaryc1b.approval.integration.outbox.OutboxDispatcher;
import io.github.akaryc1b.approval.integration.outbox.OutboxRepository;
import io.github.akaryc1b.approval.integration.retry.ExponentialBackoffRetryPolicy;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalBusinessEventOutbox;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Arrays;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(GenericConnectorProperties.class)
public class GenericConnectorConfiguration {

    @Bean
    ApplicationRunner validateGenericConnectorProperties(GenericConnectorProperties properties) {
        return arguments -> properties.validateEnabled();
    }

    @Bean
    OutboxRepository outboxRepository(
        DataSource dataSource,
        ObjectMapper approvalPersistenceObjectMapper
    ) {
        return new JdbcOutboxRepository(dataSource, approvalPersistenceObjectMapper);
    }

    @Bean
    ApprovalBusinessEventOutbox approvalBusinessEventOutbox(OutboxRepository repository) {
        return new JdbcApprovalBusinessEventOutbox(repository, UUID::randomUUID);
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "approval.connector.generic",
        name = "enabled",
        havingValue = "true"
    )
    GenericRestHostEndpointResolver genericRestHostEndpointResolver(
        GenericConnectorProperties properties
    ) {
        return context -> {
            requireConnectorKey(properties, context.connectorKey());
            byte[] secret = properties.secretBytes();
            try {
                return new GenericRestHostEndpoint(
                    properties.getHostBaseUri(),
                    properties.getKeyId(),
                    secret,
                    properties.getTimeout(),
                    properties.getHeaders()
                );
            } finally {
                Arrays.fill(secret, (byte) 0);
            }
        };
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "approval.connector.generic",
        name = "enabled",
        havingValue = "true"
    )
    OrganizationConnector organizationConnector(
        GenericRestHostEndpointResolver endpointResolver,
        ObjectMapper approvalPersistenceObjectMapper
    ) {
        return new GenericRestOrganizationConnector(new GenericRestTransport(
            endpointResolver,
            approvalPersistenceObjectMapper
        ));
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "approval.connector.generic",
        name = "enabled",
        havingValue = "true"
    )
    PurchasePaymentAssigneeResolver connectorPurchasePaymentAssigneeResolver(
        OrganizationConnector organizationConnector,
        Clock approvalClock
    ) {
        return new ConnectorPurchasePaymentAssigneeResolver(
            organizationConnector,
            approvalClock
        );
    }

    @Bean
    @ConditionalOnMissingBean(PurchasePaymentAssigneeResolver.class)
    PurchasePaymentAssigneeResolver unavailablePurchasePaymentAssigneeResolver() {
        return (context, rules) -> {
            throw new PurchasePaymentAssigneeResolver.AssigneeResolutionException(
                "ASSIGNEE_RESOLVER_UNAVAILABLE",
                "approval.connector.generic.enabled must be true to resolve approval rules"
            );
        };
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "approval.connector.generic",
        name = "enabled",
        havingValue = "true"
    )
    GenericWebhookEndpointResolver genericWebhookEndpointResolver(
        GenericConnectorProperties properties
    ) {
        return context -> {
            requireConnectorKey(properties, context.connectorKey());
            byte[] secret = properties.secretBytes();
            try {
                return new GenericWebhookEndpoint(
                    properties.getCallbackUri(),
                    properties.getKeyId(),
                    secret,
                    properties.getTimeout(),
                    properties.getHeaders()
                );
            } finally {
                Arrays.fill(secret, (byte) 0);
            }
        };
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "approval.connector.generic",
        name = "enabled",
        havingValue = "true"
    )
    BusinessCallbackConnector businessCallbackConnector(
        GenericWebhookEndpointResolver endpointResolver
    ) {
        return new GenericRestBusinessCallbackConnector(endpointResolver);
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "approval.connector.generic",
        name = "enabled",
        havingValue = "true"
    )
    BusinessCallbackResolver businessCallbackResolver(
        GenericConnectorProperties properties,
        BusinessCallbackConnector connector
    ) {
        return connectorKey -> {
            requireConnectorKey(properties, connectorKey);
            return connector;
        };
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "approval.connector.generic",
        name = "enabled",
        havingValue = "true"
    )
    OutboxDispatcher outboxDispatcher(
        OutboxRepository repository,
        BusinessCallbackResolver callbackResolver,
        GenericConnectorProperties properties,
        Clock approvalClock
    ) {
        GenericConnectorProperties.Dispatch dispatch = properties.getDispatch();
        return new OutboxDispatcher(
            repository,
            callbackResolver,
            new ExponentialBackoffRetryPolicy(
                dispatch.getInitialDelay(),
                dispatch.getMaximumDelay(),
                dispatch.getMaximumAttempts(),
                dispatch.getJitterRatio()
            ),
            approvalClock,
            dispatch.getLeaseDuration()
        );
    }

    private static void requireConnectorKey(
        GenericConnectorProperties properties,
        String connectorKey
    ) {
        if (!properties.getConnectorKey().equals(connectorKey)) {
            throw new IllegalArgumentException("unsupported connector key " + connectorKey);
        }
    }
}
