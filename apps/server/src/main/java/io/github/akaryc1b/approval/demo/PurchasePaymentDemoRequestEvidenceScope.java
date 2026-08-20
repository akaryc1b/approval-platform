package io.github.akaryc1b.approval.demo;

import io.github.akaryc1b.approval.domain.context.RequestContext;
import org.slf4j.MDC;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Establishes and restores the request evidence normally owned by the HTTP identity filter.
 */
final class PurchasePaymentDemoRequestEvidenceScope {

    private static final String OPERATOR_ID = "operatorId";
    private static final String REQUEST_ID = "requestId";
    private static final String TRACE_ID = "traceId";

    private PurchasePaymentDemoRequestEvidenceScope() {
    }

    static <T> T call(RequestContext context, Supplier<T> action) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            MDC.put(OPERATOR_ID, context.operatorId());
            MDC.put(REQUEST_ID, context.requestId());
            if (context.traceId() == null) {
                MDC.remove(TRACE_ID);
            } else {
                MDC.put(TRACE_ID, context.traceId());
            }
            return action.get();
        } finally {
            MDC.clear();
            if (previous != null && !previous.isEmpty()) {
                MDC.setContextMap(previous);
            }
        }
    }
}
