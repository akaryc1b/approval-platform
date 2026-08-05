package io.github.akaryc1b.approval.ai.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiServerRequestContextTest {

    @Test
    void platformMaximumTenantIdIsAccepted() {
        String tenantId = "t".repeat(128);

        AiServerRequestContext context = new AiServerRequestContext(
            tenantId,
            "operator-a",
            "request-a",
            "trace-a"
        );

        assertEquals(tenantId, context.tenantId());
    }

    @Test
    void tenantIdAbovePlatformMaximumIsRejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiServerRequestContext(
                "t".repeat(129),
                "operator-a",
                "request-a",
                "trace-a"
            )
        );
    }
}
