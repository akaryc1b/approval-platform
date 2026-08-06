package io.github.akaryc1b.approval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.security.ControlledAutomationGovernanceRequestBoundaryFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.time.Clock;

/** Raw-request fail-closed boundary for the read-only M6-F governance API. */
@Configuration(proxyBeanMethods = false)
public class ControlledAutomationGovernanceSecurityConfiguration {

    public static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Bean
    FilterRegistrationBean<ControlledAutomationGovernanceRequestBoundaryFilter>
        controlledAutomationGovernanceRequestBoundaryFilter(
            Clock approvalClock,
            ObjectMapper objectMapper
        ) {
        ControlledAutomationGovernanceRequestBoundaryFilter filter =
            new ControlledAutomationGovernanceRequestBoundaryFilter(
                approvalClock,
                objectMapper
            );
        FilterRegistrationBean<ControlledAutomationGovernanceRequestBoundaryFilter>
            registration = new FilterRegistrationBean<>(filter);
        registration.setName("controlledAutomationGovernanceRequestBoundaryFilter");
        registration.addUrlPatterns(
            ControlledAutomationGovernanceRequestBoundaryFilter.BASE_PATH + "/*"
        );
        registration.setOrder(FILTER_ORDER);
        return registration;
    }
}
