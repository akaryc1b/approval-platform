package io.github.akaryc1b.approval.domain.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestContextTest {

    @Test
    void requiresIdentityAndIdempotencyFields() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new RequestContext("tenant-a", "user-1", "request-1", " ", null)
        );
    }

    @Test
    void normalizesBlankTraceId() {
        var context = new RequestContext(
            "tenant-a",
            "user-1",
            "request-1",
            "idempotency-1",
            " "
        );

        assertEquals("tenant-a", context.tenantId());
        assertNull(context.traceId());
    }
}
