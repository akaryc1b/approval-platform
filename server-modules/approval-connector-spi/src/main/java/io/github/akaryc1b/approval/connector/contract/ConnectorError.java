package io.github.akaryc1b.approval.connector.contract;

import java.util.Map;
import java.util.Objects;

/**
 * Bounded provider failure evidence safe for logs and API responses.
 */
public record ConnectorError(
    String code,
    ProviderFailureClass failureClass,
    String message,
    Map<String, String> details
) {

    public ConnectorError {
        code = ConnectorContractSupport.requireCode(code, "code");
        failureClass = Objects.requireNonNull(failureClass, "failureClass must not be null");
        message = ConnectorSecretRedactor.redact(
            ConnectorContractSupport.requireText(message, "message", 512)
        );
        details = ConnectorContractSupport.boundedMetadata(
            ConnectorSecretRedactor.redactDetails(details),
            "details",
            8,
            64,
            256,
            false
        );
    }
}
