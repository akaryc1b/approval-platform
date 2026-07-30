package io.github.akaryc1b.approval.connector.contract;

import java.util.Objects;

/**
 * Binds one closed connector operation to exact provider-neutral payload types.
 */
public record ConnectorOperationContract<P, R>(
    String contractKey,
    ConnectorOperation operation,
    Class<P> requestPayloadType,
    Class<R> responseType
) {

    public ConnectorOperationContract {
        contractKey = ConnectorContractSupport.requireSafeIdentifier(contractKey, "contractKey");
        operation = Objects.requireNonNull(operation, "operation must not be null");
        requestPayloadType = Objects.requireNonNull(
            requestPayloadType,
            "requestPayloadType must not be null"
        );
        responseType = Objects.requireNonNull(responseType, "responseType must not be null");
    }

    public String canonicalValue() {
        return contractKey
            + "|operation=" + operation.name()
            + "|request=" + requestPayloadType.getName()
            + "|response=" + responseType.getName();
    }
}
