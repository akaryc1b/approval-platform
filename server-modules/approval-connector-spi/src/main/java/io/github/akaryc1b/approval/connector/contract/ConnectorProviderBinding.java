package io.github.akaryc1b.approval.connector.contract;

import java.util.Objects;

/**
 * Typed binding for one provider operation in the server-side provider registry.
 */
public record ConnectorProviderBinding<P, R>(
    ProviderDescriptor descriptor,
    ConnectorOperation operation,
    Class<P> requestPayloadType,
    Class<R> responseType,
    ConnectorExecutionPort<P, R> executionPort,
    ConnectorReconciliationPort<R> reconciliationPort
) {

    public ConnectorProviderBinding {
        descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
        operation = Objects.requireNonNull(operation, "operation must not be null");
        requestPayloadType = Objects.requireNonNull(
            requestPayloadType,
            "requestPayloadType must not be null"
        );
        responseType = Objects.requireNonNull(responseType, "responseType must not be null");
        executionPort = Objects.requireNonNull(executionPort, "executionPort must not be null");
        if (!descriptor.supportedCapabilities().contains(operation.requiredCapability())) {
            throw new IllegalArgumentException(
                "provider descriptor does not support binding operation " + operation.name()
            );
        }
        requireSameDescriptor(descriptor, executionPort.descriptor(), "executionPort");
        if (reconciliationPort != null) {
            requireSameDescriptor(
                descriptor,
                reconciliationPort.descriptor(),
                "reconciliationPort"
            );
        }
    }

    public boolean supportsReconciliation() {
        return reconciliationPort != null;
    }

    public ConnectorResult<R> execute(
        TrustedConnectorExecutionContext context,
        ConnectorRequest<P> request
    ) {
        requireContextProvider(context);
        Objects.requireNonNull(request, "request must not be null");
        if (request.operation() != operation) {
            throw new IllegalArgumentException("request operation does not match provider binding");
        }
        if (!requestPayloadType.isInstance(request.payload())) {
            throw new IllegalArgumentException("request payload type does not match provider binding");
        }
        descriptor.requireEnabledCapability(operation.requiredCapability());
        ConnectorResult<R> result = executionPort.execute(context, request);
        if (result.value() != null && !responseType.isInstance(result.value())) {
            throw new IllegalStateException("provider returned an unexpected response type");
        }
        return result;
    }

    public ConnectorReconciliationResult<R> reconcile(
        TrustedConnectorExecutionContext context,
        ConnectorReconciliationRequest request
    ) {
        requireContextProvider(context);
        Objects.requireNonNull(request, "request must not be null");
        if (request.originalOperation() != operation) {
            throw new IllegalArgumentException(
                "reconciliation operation does not match provider binding"
            );
        }
        descriptor.requireEnabledCapability(operation.requiredCapability());
        if (reconciliationPort == null) {
            throw new IllegalStateException("provider operation does not support reconciliation");
        }
        ConnectorReconciliationResult<R> result = reconciliationPort.reconcile(context, request);
        if (result.value() != null && !responseType.isInstance(result.value())) {
            throw new IllegalStateException(
                "reconciliation returned an unexpected response type"
            );
        }
        return result;
    }

    public String canonicalRegistration() {
        return descriptor.canonicalJson()
            + "|operation=" + operation.name()
            + "|request=" + requestPayloadType.getName()
            + "|response=" + responseType.getName()
            + "|reconciliation=" + supportsReconciliation();
    }

    private void requireContextProvider(TrustedConnectorExecutionContext context) {
        Objects.requireNonNull(context, "context must not be null");
        if (!descriptor.providerKey().equals(context.providerKey())) {
            throw new IllegalArgumentException("trusted context targets another provider");
        }
    }

    private static void requireSameDescriptor(
        ProviderDescriptor expected,
        ProviderDescriptor actual,
        String source
    ) {
        Objects.requireNonNull(actual, source + " descriptor must not be null");
        if (!expected.canonicalJson().equals(actual.canonicalJson())) {
            throw new IllegalArgumentException(source + " descriptor does not match binding");
        }
    }
}
