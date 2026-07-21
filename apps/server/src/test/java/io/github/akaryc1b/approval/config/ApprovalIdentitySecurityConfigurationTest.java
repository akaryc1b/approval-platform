package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.security.ApprovalIdentityContextFilter.AuthenticationMode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalIdentitySecurityConfigurationTest {

    @Test
    void localHeaderModeRequiresExplicitDevelopmentProfile() {
        assertThrows(
            IllegalStateException.class,
            () -> ApprovalIdentitySecurityConfiguration.requireAllowedMode(
                AuthenticationMode.LOCAL_HEADERS,
                Set.of()
            )
        );
        assertThrows(
            IllegalStateException.class,
            () -> ApprovalIdentitySecurityConfiguration.requireAllowedMode(
                AuthenticationMode.LOCAL_HEADERS,
                Set.of("prod")
            )
        );
        assertDoesNotThrow(() -> ApprovalIdentitySecurityConfiguration.requireAllowedMode(
            AuthenticationMode.LOCAL_HEADERS,
            Set.of("local")
        ));
        assertDoesNotThrow(() -> ApprovalIdentitySecurityConfiguration.requireAllowedMode(
            AuthenticationMode.LOCAL_HEADERS,
            Set.of("test")
        ));
    }

    @Test
    void principalModeIsAllowedForEveryProfile() {
        assertDoesNotThrow(() -> ApprovalIdentitySecurityConfiguration.requireAllowedMode(
            AuthenticationMode.PRINCIPAL,
            Set.of("prod")
        ));
    }
}
