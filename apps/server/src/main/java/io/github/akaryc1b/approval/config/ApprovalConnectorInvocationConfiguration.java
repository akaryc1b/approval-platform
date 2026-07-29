package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenCoordinator;
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenKillSwitch;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .DingTalkReadOnlyDispatchPort;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .DingTalkTokenRequestSource;
import io.github.akaryc1b.approval.connector.invocation.GovernedReadOnlyConnectorInvocationCoordinator;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteResolver;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteRevalidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApprovalConnectorInvocationProperties.class)
@ConditionalOnProperty(
    prefix = "approval.connector.invocation",
    name = "enabled",
    havingValue = "true"
)
public class ApprovalConnectorInvocationConfiguration {

    @Bean(destroyMethod = "close")
    GovernedReadOnlyConnectorInvocationCoordinator governedReadOnlyConnectorInvocationCoordinator(
        TenantConnectorRouteResolver routeResolver,
        TenantConnectorRouteRevalidator routeRevalidator,
        DingTalkTokenCoordinator tokenCoordinator,
        DingTalkTokenKillSwitch killSwitch,
        DingTalkTokenRequestSource tokenRequestSource,
        DingTalkReadOnlyDispatchPort dispatchPort,
        ApprovalConnectorInvocationProperties properties,
        Clock clock
    ) {
        return new GovernedReadOnlyConnectorInvocationCoordinator(
            routeResolver,
            routeRevalidator,
            tokenCoordinator,
            killSwitch,
            tokenRequestSource,
            dispatchPort,
            properties.toPolicy(),
            clock
        );
    }
}
