package io.github.akaryc1b.approval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.security.ApprovalIdentityContextFilter;
import io.github.akaryc1b.approval.security.ApprovalIdentityContextFilter.AuthenticationMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Production-fail-closed authentication and trusted approval request context. */
@Configuration(proxyBeanMethods = false)
public class ApprovalIdentitySecurityConfiguration {

    @Bean
    FilterRegistrationBean<ApprovalIdentityContextFilter> approvalIdentityContextFilter(
        @Value("${approval.security.identity.mode:principal}") String configuredMode,
        @Value(
            "${approval.security.management-permissions.trusted-header-name:"
                + "X-Approval-Trusted-Permissions}"
        ) String localPermissionHeader,
        Environment environment,
        Clock approvalClock,
        ObjectMapper objectMapper
    ) {
        AuthenticationMode mode = AuthenticationMode.parse(configuredMode);
        requireAllowedMode(mode, Set.of(environment.getActiveProfiles()));
        ApprovalIdentityContextFilter filter = new ApprovalIdentityContextFilter(
            mode,
            localPermissionHeader,
            approvalClock,
            objectMapper,
            () -> "approval-" + UUID.randomUUID()
        );
        FilterRegistrationBean<ApprovalIdentityContextFilter> registration =
            new FilterRegistrationBean<>(filter);
        registration.setName("approvalIdentityContextFilter");
        registration.addUrlPatterns("/api/approval/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    static void requireAllowedMode(AuthenticationMode mode, Set<String> activeProfiles) {
        if (mode != AuthenticationMode.LOCAL_HEADERS) {
            return;
        }
        Set<String> normalizedProfiles = activeProfiles == null
            ? Set.of()
            : activeProfiles.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        if (!normalizedProfiles.contains("local") && !normalizedProfiles.contains("test")) {
            throw new IllegalStateException(
                "local header identity mode requires the explicit local or test profile"
            );
        }
    }
}
