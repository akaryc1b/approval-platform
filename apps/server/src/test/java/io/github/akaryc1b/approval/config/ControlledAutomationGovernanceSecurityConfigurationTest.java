package io.github.akaryc1b.approval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.security.ControlledAutomationGovernanceRequestBoundaryFilter;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlledAutomationGovernanceSecurityConfigurationTest {

    @Test
    void rawGovernanceBoundaryRunsBeforeIdentityAndOnlyOnGovernancePaths() {
        var registration = new ControlledAutomationGovernanceSecurityConfiguration()
            .controlledAutomationGovernanceRequestBoundaryFilter(
                Clock.fixed(Instant.parse("2026-08-06T03:30:00Z"), ZoneOffset.UTC),
                new ObjectMapper().findAndRegisterModules()
            );

        assertNotNull(registration.getFilter());
        assertTrue(
            registration.getFilter()
                instanceof ControlledAutomationGovernanceRequestBoundaryFilter
        );
        assertEquals(
            ControlledAutomationGovernanceSecurityConfiguration.FILTER_ORDER,
            registration.getOrder()
        );
        assertTrue(registration.getOrder() < Ordered.HIGHEST_PRECEDENCE + 20);
        assertEquals(1, registration.getUrlPatterns().size());
        assertTrue(registration.getUrlPatterns().contains(
            ControlledAutomationGovernanceRequestBoundaryFilter.BASE_PATH + "/*"
        ));
    }
}
