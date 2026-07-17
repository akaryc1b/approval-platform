package io.github.akaryc1b.approval.ruoyi5.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.host.security.DefaultHostRequestVerifier;
import io.github.akaryc1b.approval.host.security.HmacSha256HostSignatureVerifier;
import io.github.akaryc1b.approval.host.security.HostConnectorProperties;
import io.github.akaryc1b.approval.host.security.HostRequestVerifier;
import io.github.akaryc1b.approval.host.security.HostSignatureVerifier;
import io.github.akaryc1b.approval.host.security.ReplayGuard;
import io.github.akaryc1b.approval.host.security.TenantSecretResolver;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.mapper.SysUserPostMapper;
import org.dromara.system.service.ISysDeptService;
import org.dromara.system.service.ISysPostService;
import org.dromara.system.service.ISysRoleService;
import org.dromara.system.service.ISysTenantService;
import org.dromara.system.service.ISysUserService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@AutoConfiguration
@ConditionalOnClass(LoginHelper.class)
@ConditionalOnProperty(prefix = "approval.host", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ApprovalHostProperties.class)
public class Ruoyi5ApprovalHostAutoConfiguration {

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
        return new Ruoyi5TenantSecretResolver(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    HostSignatureVerifier approvalHostSignatureVerifier() {
        return new HmacSha256HostSignatureVerifier();
    }

    @Bean
    @ConditionalOnMissingBean
    ReplayGuard approvalHostReplayGuard() {
        return new Ruoyi5RedisReplayGuard();
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
    Ruoyi5ApprovalHostService ruoyi5ApprovalHostService(
        ApprovalHostProperties properties,
        ISysUserService userService,
        ISysDeptService deptService,
        ISysRoleService roleService,
        ISysPostService postService,
        ISysTenantService tenantService,
        SysUserPostMapper userPostMapper
    ) {
        return new Ruoyi5ApprovalHostService(
            properties,
            userService,
            deptService,
            roleService,
            postService,
            tenantService,
            userPostMapper
        );
    }

    @Bean
    Ruoyi5ApprovalHostController ruoyi5ApprovalHostController(
        HostRequestVerifier requestVerifier,
        ObjectMapper objectMapper,
        Ruoyi5ApprovalHostService service
    ) {
        return new Ruoyi5ApprovalHostController(requestVerifier, objectMapper, service);
    }

    @Bean
    Ruoyi5HostExceptionHandler ruoyi5HostExceptionHandler() {
        return new Ruoyi5HostExceptionHandler();
    }
}
