package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.connector.credential.CredentialBindingCatalog;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialSource;
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenCoordinator;
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenEndpointPort;
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenKillSwitch;
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenPolicy;
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenRouteGate;
import io.github.akaryc1b.approval.connector.dingtalk.token.TenantConnectorRouteTokenGate;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteRevalidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApprovalDingTalkTokenProperties.class)
@ConditionalOnProperty(
    prefix = "approval.connector.dingtalk-token",
    name = "enabled",
    havingValue = "true"
)
public class ApprovalDingTalkTokenConfiguration {

    @Bean
    DingTalkTokenPolicy dingTalkTokenPolicy(ApprovalDingTalkTokenProperties properties) {
        return properties.toPolicy();
    }

    @Bean
    @ConditionalOnMissingBean(DingTalkTokenRouteGate.class)
    DingTalkTokenRouteGate dingTalkTokenRouteGate(
        TenantConnectorRouteRevalidator revalidator
    ) {
        return new TenantConnectorRouteTokenGate(revalidator);
    }

    @Bean(destroyMethod = "close")
    DingTalkTokenCoordinator dingTalkTokenCoordinator(
        CredentialBindingCatalog credentialCatalog,
        CredentialMaterialSource materialSource,
        DingTalkTokenRouteGate routeGate,
        DingTalkTokenKillSwitch killSwitch,
        DingTalkTokenEndpointPort endpointPort,
        DingTalkTokenPolicy policy,
        Clock clock
    ) {
        return new DingTalkTokenCoordinator(
            credentialCatalog,
            materialSource,
            routeGate,
            killSwitch,
            endpointPort,
            policy,
            clock
        );
    }
}
