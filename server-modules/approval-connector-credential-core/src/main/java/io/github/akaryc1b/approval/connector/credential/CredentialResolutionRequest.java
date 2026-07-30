package io.github.akaryc1b.approval.connector.credential;

import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.TrustedConnectorExecutionContext;

import java.util.Objects;

public record CredentialResolutionRequest(
    TrustedConnectorExecutionContext context,
    ConnectorOperation operation,
    CredentialMaterialType expectedCredentialType,
    String expectedKeyId,
    String expectedVersionId
) {

    public CredentialResolutionRequest {
        context = Objects.requireNonNull(context, "context must not be null");
        operation = Objects.requireNonNull(operation, "operation must not be null");
        expectedCredentialType = Objects.requireNonNull(
            expectedCredentialType,
            "expectedCredentialType must not be null"
        );
        expectedKeyId = CredentialContractSupport.requireIdentifier(expectedKeyId, "expectedKeyId");
        expectedVersionId = CredentialContractSupport.requireIdentifier(
            expectedVersionId,
            "expectedVersionId"
        );
    }
}
