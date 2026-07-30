package io.github.akaryc1b.approval.connector.dingtalk;

import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.TrustedConnectorExecutionContext;

import java.util.Objects;

/**
 * DingTalk transport boundary. Captured fixtures use the context-free method; production transport
 * must override the trusted context and operation-bound method.
 */
@FunctionalInterface
public interface DingTalkTransport {

    DingTalkTransportResponse exchange(DingTalkTransportRequest request);

    default DingTalkTransportResponse exchange(
        TrustedConnectorExecutionContext context,
        ConnectorOperation operation,
        DingTalkTransportRequest request
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        return exchange(Objects.requireNonNull(request, "request must not be null"));
    }

    default TransportMode mode() {
        return TransportMode.CAPTURED;
    }

    enum TransportMode {
        CAPTURED,
        PRODUCTION
    }
}
