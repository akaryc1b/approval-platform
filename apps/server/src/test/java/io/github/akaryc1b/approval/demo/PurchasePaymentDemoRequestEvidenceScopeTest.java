package io.github.akaryc1b.approval.demo;

import io.github.akaryc1b.approval.domain.context.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PurchasePaymentDemoRequestEvidenceScopeTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void exposesExactRequestEvidenceAndRestoresPreviousContext() {
        MDC.put("operatorId", "previous-operator");
        MDC.put("existing", "previous-value");
        RequestContext context = new RequestContext(
            "demo-tenant",
            "demo-employee",
            "demo-request-1",
            "demo-idempotency-1",
            "demo-trace-1"
        );

        String evidence = PurchasePaymentDemoRequestEvidenceScope.call(
            context,
            () -> MDC.get("operatorId")
                + '|'
                + MDC.get("requestId")
                + '|'
                + MDC.get("traceId")
        );

        assertEquals("demo-employee|demo-request-1|demo-trace-1", evidence);
        assertEquals("previous-operator", MDC.get("operatorId"));
        assertEquals("previous-value", MDC.get("existing"));
        assertNull(MDC.get("requestId"));
        assertNull(MDC.get("traceId"));
    }

    @Test
    void restoresEmptyContextWhenApplicationAuthorityFails() {
        RequestContext context = new RequestContext(
            "demo-tenant",
            "demo-employee",
            "demo-request-2",
            "demo-idempotency-2",
            null
        );

        assertThrows(
            IllegalStateException.class,
            () -> PurchasePaymentDemoRequestEvidenceScope.call(
                context,
                () -> {
                    throw new IllegalStateException("expected failure");
                }
            )
        );

        assertNull(MDC.get("operatorId"));
        assertNull(MDC.get("requestId"));
        assertNull(MDC.get("traceId"));
    }
}
