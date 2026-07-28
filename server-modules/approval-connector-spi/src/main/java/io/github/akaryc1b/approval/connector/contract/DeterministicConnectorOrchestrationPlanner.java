package io.github.akaryc1b.approval.connector.contract;

import java.util.Objects;

/**
 * Deterministic, no-network orchestration planner.
 *
 * <p>The planner validates registry, compatibility, selection, authorization, request and trusted
 * context evidence. It never invokes a connector adapter and never authorizes automatic retry.</p>
 */
public final class DeterministicConnectorOrchestrationPlanner
    implements ConnectorOrchestrationPlanner {

    @Override
    public <P, R> ConnectorOrchestrationPlan plan(
        ConnectorOrchestrationPlanningRequest<P, R> planningRequest
    ) {
        planningRequest = Objects.requireNonNull(
            planningRequest,
            "planningRequest must not be null"
        );
        ConnectorProviderRegistry registry = planningRequest.registry();
        ConnectorOperationContract<P, R> contract = planningRequest.contract();
        ConnectorProviderSelection<P, R> selection = planningRequest.selection();
        ConnectorProviderCompatibilityReport compatibility = planningRequest.compatibilityReport();
        ConnectorInvocationAuthorizationEvidence authorization =
            planningRequest.authorizationEvidence();
        TrustedConnectorExecutionContext context = planningRequest.context();
        ConnectorRequest<P> request = planningRequest.request();

        if (!selection.selected()) {
            throw new IllegalArgumentException("provider selection must be SELECTED");
        }
        ConnectorProviderBinding<P, R> selectedBinding = selection.requireBinding();
        String selectedProviderKey = selectedBinding.descriptor().providerKey();
        ConnectorProviderSelectionEvidence selectionEvidence = selection.evidence();

        requireEquals(
            registry.registryFingerprint(),
            selectionEvidence.registryFingerprint(),
            "selection registry fingerprint"
        );
        requireEquals(
            contract.contractKey(),
            selectionEvidence.contractKey(),
            "selection contract key"
        );
        requireEquals(
            selectedProviderKey,
            selectionEvidence.selectedProviderKey(),
            "selection provider key"
        );
        requireEquals(selectedProviderKey, context.providerKey(), "trusted context provider key");
        requireBindingMatchesContract(selectedBinding, contract);
        ConnectorProviderBinding<P, R> currentBinding = registry.resolve(
            selectedProviderKey,
            contract.operation(),
            contract.requestPayloadType(),
            contract.responseType()
        );
        requireEquals(
            currentBinding.canonicalRegistration(),
            selectedBinding.canonicalRegistration(),
            "selected binding registration"
        );

        requireEquals(
            registry.registryFingerprint(),
            compatibility.registryFingerprint(),
            "compatibility registry fingerprint"
        );
        requireEquals(
            contract.contractKey(),
            compatibility.contractKey(),
            "compatibility contract key"
        );
        requireEquals(
            CanonicalPayloadHash.sha256Utf8(contract.canonicalValue()),
            compatibility.contractFingerprint(),
            "compatibility contract fingerprint"
        );
        requireEquals(
            selectionEvidence.requiredProtocolVersion(),
            compatibility.requiredProtocolVersion(),
            "required protocol version"
        );
        ConnectorProviderCompatibilityEntry compatibilityEntry = compatibility
            .findEntry(selectedProviderKey)
            .orElseThrow(() -> new IllegalArgumentException(
                "compatibility report does not contain selected provider"
            ));
        if (!compatibilityEntry.compatible()) {
            throw new IllegalArgumentException("selected provider is not compatible");
        }

        if (request.operation() != contract.operation()) {
            throw new IllegalArgumentException("request operation does not match contract");
        }
        if (!contract.requestPayloadType().isInstance(request.payload())) {
            throw new IllegalArgumentException("request payload type does not match contract");
        }
        if (request.payload() instanceof CanonicalConnectorPayload canonicalPayload
            && !request.canonicalPayloadHash().equals(canonicalPayload.canonicalPayloadHash())) {
            throw new IllegalArgumentException(
                "request payload hash does not match canonical payload evidence"
            );
        }

        requireAuthorization(
            authorization,
            context,
            contract,
            selectedProviderKey,
            request,
            selectionEvidence
        );

        String credentialReferenceHash = CanonicalPayloadHash.sha256Utf8(
            context.credentialReference().providerKey()
                + "\n"
                + context.credentialReference().referenceId()
        );
        return new ConnectorOrchestrationPlan(
            context.tenantId(),
            selectedProviderKey,
            contract.contractKey(),
            contract.operation(),
            request.requestId(),
            request.traceId(),
            request.idempotencyKey(),
            request.canonicalPayloadHash(),
            credentialReferenceHash,
            registry.registryFingerprint(),
            compatibility.evidenceHash(),
            selectionEvidence.evidenceHash(),
            authorization.evidenceHash(),
            context.requestedAt()
        );
    }

    private static <P, R> void requireBindingMatchesContract(
        ConnectorProviderBinding<P, R> binding,
        ConnectorOperationContract<P, R> contract
    ) {
        if (binding.operation() != contract.operation()
            || !binding.requestPayloadType().equals(contract.requestPayloadType())
            || !binding.responseType().equals(contract.responseType())) {
            throw new IllegalArgumentException("selected binding does not match operation contract");
        }
    }

    private static <P, R> void requireAuthorization(
        ConnectorInvocationAuthorizationEvidence authorization,
        TrustedConnectorExecutionContext context,
        ConnectorOperationContract<P, R> contract,
        String selectedProviderKey,
        ConnectorRequest<P> request,
        ConnectorProviderSelectionEvidence selectionEvidence
    ) {
        requireEquals(context.tenantId(), authorization.tenantId(), "authorization tenant");
        requireEquals(
            selectedProviderKey,
            authorization.providerKey(),
            "authorization provider"
        );
        requireEquals(
            contract.contractKey(),
            authorization.contractKey(),
            "authorization contract key"
        );
        if (authorization.operation() != contract.operation()) {
            throw new IllegalArgumentException("authorization operation does not match contract");
        }
        requireEquals(request.requestId(), authorization.requestId(), "authorization request ID");
        requireEquals(
            request.idempotencyKey(),
            authorization.idempotencyKey(),
            "authorization idempotency key"
        );
        requireEquals(
            request.canonicalPayloadHash(),
            authorization.canonicalPayloadHash(),
            "authorization payload hash"
        );
        requireEquals(
            selectionEvidence.evidenceHash(),
            authorization.selectionEvidenceHash(),
            "authorization selection evidence hash"
        );
        if (!authorization.validAt(context.requestedAt())) {
            throw new IllegalArgumentException("connector authorization evidence is not valid");
        }
    }

    private static void requireEquals(String expected, String actual, String name) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalArgumentException(name + " does not match");
        }
    }
}
