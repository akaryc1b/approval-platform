package io.github.akaryc1b.approval.connector.contract;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable inputs for consolidating M6-A contract-foundation review evidence.
 */
public record ConnectorFoundationAcceptanceRequest(
    String foundationVersion,
    ConnectorProviderRegistry registry,
    List<ConnectorOperationContract<?, ?>> operationContracts,
    String selectionPolicyVersion,
    String compatibilityMatrixVersion,
    String orchestrationPolicyVersion,
    String admissionPolicyVersion,
    List<ConnectorExecutionAdmissionEvidence> admissionEvidence,
    Instant evaluatedAt
) {

    public ConnectorFoundationAcceptanceRequest {
        foundationVersion = ConnectorContractSupport.requireSafeIdentifier(
            foundationVersion,
            "foundationVersion"
        );
        registry = Objects.requireNonNull(registry, "registry must not be null");
        operationContracts = operationContracts == null ? List.of() : List.copyOf(operationContracts);
        if (operationContracts.isEmpty()
            || operationContracts.size() > 32
            || operationContracts.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                "operationContracts must contain between 1 and 32 values"
            );
        }
        selectionPolicyVersion = ConnectorContractSupport.requireSafeIdentifier(
            selectionPolicyVersion,
            "selectionPolicyVersion"
        );
        compatibilityMatrixVersion = ConnectorContractSupport.requireSafeIdentifier(
            compatibilityMatrixVersion,
            "compatibilityMatrixVersion"
        );
        orchestrationPolicyVersion = ConnectorContractSupport.requireSafeIdentifier(
            orchestrationPolicyVersion,
            "orchestrationPolicyVersion"
        );
        admissionPolicyVersion = ConnectorContractSupport.requireSafeIdentifier(
            admissionPolicyVersion,
            "admissionPolicyVersion"
        );
        admissionEvidence = admissionEvidence == null ? List.of() : List.copyOf(admissionEvidence);
        if (admissionEvidence.size() > 32 || admissionEvidence.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("admissionEvidence is invalid");
        }
        evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
    }
}
