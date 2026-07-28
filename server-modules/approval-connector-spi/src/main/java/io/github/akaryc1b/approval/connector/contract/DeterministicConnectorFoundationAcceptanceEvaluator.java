package io.github.akaryc1b.approval.connector.contract;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Consolidates deterministic evidence for formal M6-A acceptance review without enabling production.
 */
public final class DeterministicConnectorFoundationAcceptanceEvaluator {

    public ConnectorFoundationAcceptanceEvidence evaluate(
        ConnectorFoundationAcceptanceRequest request
    ) {
        ConnectorFoundationAcceptanceStatus status = classify(request);
        List<String> contractFingerprints = request.operationContracts().stream()
            .map(contract -> CanonicalPayloadHash.sha256Utf8(contract.canonicalValue()))
            .toList();
        List<String> admissionHashes = request.admissionEvidence().stream()
            .map(ConnectorExecutionAdmissionEvidence::evidenceHash)
            .toList();
        return new ConnectorFoundationAcceptanceEvidence(
            request.foundationVersion(),
            status,
            request.registry().registryFingerprint(),
            contractFingerprints,
            request.selectionPolicyVersion(),
            request.compatibilityMatrixVersion(),
            request.orchestrationPolicyVersion(),
            request.admissionPolicyVersion(),
            admissionHashes,
            EnumSet.allOf(ConnectorFoundationBlockedCapability.class),
            request.evaluatedAt()
        );
    }

    private static ConnectorFoundationAcceptanceStatus classify(
        ConnectorFoundationAcceptanceRequest request
    ) {
        Set<ConnectorOperation> operations = EnumSet.noneOf(ConnectorOperation.class);
        Set<String> contractKeys = new HashSet<>();
        for (ConnectorOperationContract<?, ?> contract : request.operationContracts()) {
            if (!operations.add(contract.operation()) || !contractKeys.add(contract.contractKey())) {
                return ConnectorFoundationAcceptanceStatus.INCOMPLETE_CONTRACT_COVERAGE;
            }
        }
        if (!operations.equals(EnumSet.allOf(ConnectorOperation.class))) {
            return ConnectorFoundationAcceptanceStatus.INCOMPLETE_CONTRACT_COVERAGE;
        }
        if (request.admissionEvidence().isEmpty()
            || request.admissionEvidence().stream().anyMatch(evidence ->
                evidence.status() != ConnectorExecutionAdmissionStatus.ADMITTED
                    || !request.admissionPolicyVersion().equals(evidence.admissionPolicyVersion())
                    || evidence.checkedAt().isAfter(request.evaluatedAt()))) {
            return ConnectorFoundationAcceptanceStatus.INCOMPLETE_ADMISSION_EVIDENCE;
        }
        if (request.admissionEvidence().stream().anyMatch(evidence ->
            !request.registry().registryFingerprint().equals(evidence.registryFingerprint()))) {
            return ConnectorFoundationAcceptanceStatus.REGISTRY_EVIDENCE_MISMATCH;
        }
        return ConnectorFoundationAcceptanceStatus.READY_FOR_FORMAL_ACCEPTANCE_REVIEW;
    }
}
