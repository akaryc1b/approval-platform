package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.security.ApprovalResponsibilityResolver;
import io.github.akaryc1b.approval.security.CachingApprovalResponsibilityResolver;
import io.github.akaryc1b.approval.security.DefaultApprovalResponsibilityResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class ApprovalResponsibilityConfiguration {

    @Bean
    ApprovalResponsibilityResolver approvalResponsibilityResolver(Clock approvalClock) {
        DefaultApprovalResponsibilityResolver resolver =
            new DefaultApprovalResponsibilityResolver(approvalClock);
        return new CachingApprovalResponsibilityResolver(
            resolver,
            approvalClock,
            Duration.ofSeconds(30),
            4_096
        );
    }
}
