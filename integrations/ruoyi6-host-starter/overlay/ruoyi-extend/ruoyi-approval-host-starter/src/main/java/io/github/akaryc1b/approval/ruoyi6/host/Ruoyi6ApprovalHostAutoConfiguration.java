package io.github.akaryc1b.approval.ruoyi6.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.host.security.DefaultHostRequestVerifier;
import io.github.akaryc1b.approval.host.security.HmacSha256HostSignatureVerifier;
import io.github.akaryc1b.approval.host.security.HostConnectorProperties;
import io.github.akaryc1b.approval.host.security.HostRequestVerifier;
import io.github.akaryc1b.approval.host.security.HostSignatureVerifier;
import io.github.akaryc1b.approval.host.security.ReplayGuard;
import io.github.akaryc1b.approval.host.security.TenantSecretResolver;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.security.config.SecurityConfig;
import org.dromara.system.mapper.SysUserPostMapper;
import org.dromara.system.service.ISysDeptService;
import org.dromara.system.service.ISysPostService;
import org.dromara.system.service.ISysRoleService;
import org.dromara.system.service.ISysUserService;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@AutoConfiguration
@AutoConfigureBefore(SecurityConfig.class)
@ConditionalOnClass(LoginHelper.class)
@ConditionalOnProperty(prefix = "approval.host", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ApprovalHostProperties.class)
public class Ruoyi6ApprovalHostAutoConfiguration {

    @Bean
    static BeanPostProcessor approvalHostSecurityExclusionPostProcessor() {
        return new Ruoyi6SecurityExclusionPostProcessor();
    }

    @Bean
    @ConditionalOnMissingBean
    Clock approvalHostClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    HostConnectorProperties approvalHostSecurityProperties(ApprovalHostProperties properties) {
        return new HostConnectorProperties(
            properties.getSource(),
            properties.getAllowedClockSkew(),
            properties.getNonceTtl()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    TenantSecretResolver approvalHostTenantSecretResolver(ApprovalHostProperties properties) {
        return new Ruoyi6TenantSecretResolver(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    HostSignatureVerifier approvalHostSignatureVerifier() {
        return new HmacSha256HostSignatureVerifier();
    }

    @Bean
    @ConditionalOnMissingBean
    ReplayGuard approvalHostReplayGuard() {
        return new Ruoyi6RedisReplayGuard();
    }

    @Bean
    @ConditionalOnMissingBean
    Ruoyi6TenantBridge approvalHostTenantBridge(ApprovalHostProperties properties) {
        return new SingleTenantBridge(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    HostRequestVerifier approvalHostRequestVerifier(
        HostConnectorProperties properties,
        TenantSecretResolver secretResolver,
        HostSignatureVerifier signatureVerifier,
        ReplayGuard replayGuard,
        Clock clock
    ) {
        return new DefaultHostRequestVerifier(
            properties,
            secretResolver,
            signatureVerifier,
            replayGuard,
            clock
        );
    }

    @Bean
    Ruoyi6ApprovalHostService ruoyi6ApprovalHostService(
        ApprovalHostProperties properties,
        ISysUserService userService,
        ISysDeptService deptService,
        ISysRoleService roleService,
        ISysPostService postService,
        SysUserPostMapper userPostMapper
    ) {
        return new Ruoyi6ApprovalHostService(
            properties,
            userService,
            deptService,
            roleService,
            postService,
            userPostMapper
        );
    }

    @Bean
    Ruoyi6ApprovalHostController ruoyi6ApprovalHostController(
        HostRequestVerifier requestVerifier,
        ObjectMapper objectMapper,
        Ruoyi6TenantBridge tenantBridge,
        Ruoyi6ApprovalHostService service
    ) {
        return new Ruoyi6ApprovalHostController(
            requestVerifier,
            objectMapper,
            tenantBridge,
            service
        );
    }

    @Bean
    Ruoyi6HostExceptionHandler ruoyi6HostExceptionHandler() {
        return new Ruoyi6HostExceptionHandler();
    }
}
