package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.api.ApprovalManagementPermissionInterceptor;
import io.github.akaryc1b.approval.security.ApprovalResponsibilityResolver;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class ApprovalManagementPermissionConfiguration implements WebMvcConfigurer {

    private final ApprovalManagementPermissionInterceptor interceptor;

    public ApprovalManagementPermissionConfiguration(
        @Value("${approval.security.management-permissions.enforced:true}") boolean enforced,
        ApprovalResponsibilityResolver responsibilityResolver,
        MeterRegistry meterRegistry
    ) {
        interceptor = new ApprovalManagementPermissionInterceptor(
            enforced,
            responsibilityResolver,
            meterRegistry
        );
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).order(-100);
    }
}
