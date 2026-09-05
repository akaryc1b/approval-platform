package io.github.akaryc1b.approval.config;

import io.micrometer.tracing.Tracer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Adds trace/span log correlation after the trusted approval request context is established. */
@Configuration(proxyBeanMethods = false)
public class ApprovalTraceLogCorrelationConfiguration {

    @Bean
    @ConditionalOnBean(Tracer.class)
    FilterRegistrationBean<ApprovalTraceLogCorrelationFilter> approvalTraceLogCorrelationFilter(
        Tracer tracer
    ) {
        FilterRegistrationBean<ApprovalTraceLogCorrelationFilter> registration =
            new FilterRegistrationBean<>(new ApprovalTraceLogCorrelationFilter(tracer));
        registration.setName("approvalTraceLogCorrelationFilter");
        registration.addUrlPatterns("/api/approval/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        return registration;
    }
}
