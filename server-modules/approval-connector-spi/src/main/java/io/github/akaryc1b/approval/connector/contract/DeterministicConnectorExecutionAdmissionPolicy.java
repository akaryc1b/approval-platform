package io.github.akaryc1b.approval.connector.contract;

import java.util.Objects;

/**
 * Deterministic, no-network execution-admission revalidation.
 *
 * <p>The policy returns evidence only. It never exposes an executable binding, invokes a provider,
 * resolves credential material, or authorizes automatic execution or retry.</p>
 */
public final class DeterministicConnectorExecutionAdmissionPolicy
    implements ConnectorExecutionAdmissionPolicy {

    private final String admissionPolicyVersion;

    public DeterministicConnectorExecutionAdmissionPolicy(String admissionPolicyVersion) {
        this.admissionPolicyVersion = ConnectorContractSupport.requireSafeIdentifier(
            admissionPolicyVersion,
            "admissionPolicyVersion"
        );
    }

    @Override
    public <P, R> ConnectorExecutionAdmission admit(
        ConnectorExecutionAdmissionRequest<P, R> admissionRequest
    ) {
        admissionRequest = Objects.requireNonNull(
            admissionRequest,
            "admissionRequest must not be null"
        );
        ConnectorOrchestrationPlan plan = admissionRequest.plan();
        ConnectorProviderRegistry registry = admissionRequest.registry();
        ConnectorOperationContract<P, R> contract = admissionRequest.contract();
        ConnectorProviderSelection<P, R> selection = admissionRequest.selection();
        ConnectorProviderCompatibilityReport compatibility =
            admissionRequest.compatibilityReport();
        ConnectorInvocationAuthorizationEvidence authorization =
            admissionRequest.authorizationEvidence();
        TrustedConnectorExecutionContext context = admissionRequest.context();
        ConnectorRequest<P> request = admissionRequest.request();

        String contractFingerprint = CanonicalPayloadHash.sha256Utf8(contract.canonicalValue());
        String selectionHash = selection.evidence().evidenceHash();
        String compatibilityHash = compatibility.evidenceHash();
        String authorizationHash = authorization.evidenceHash();
        String credentialHash = credentialReferenceHash(context.credentialReference());
        String contextHash = trustedContextEvidenceHash(context, credentialHash);
        String requestHash = requestEvidenceHash(request);

        ConnectorExecutionAdmissionStatus status = classify(
            admissionRequest,
            contractFingerprint,
            selectionHash,
            compatibilityHash,
            authorizationHash,
            credentialHash
        );
        ConnectorExecutionAdmissionEvidence evidence = new ConnectorExecutionAdmissionEvidence(
            admissionPolicyVersion,
            status,
            plan.planHash(),
            registry.registryFingerprint(),
            contractFingerprint,
            selectionHash,
            compatibilityHash,
            authorizationHash,
            contextHash,
            requestHash,
            credentialHash,
            admissionRequest.checkedAt()
        );
        return new ConnectorExecutionAdmission(status, evidence);
    }

    private static <P, R> ConnectorExecutionAdmissionStatus classify(
        ConnectorExecutionAdmissionRequest<P, R> admissionRequest,
        String contractFingerprint,
        String selectionHash,
        String compatibilityHash,
        String authorizationHash,
        String credentialHash
    ) {
        ConnectorOrchestrationPlan plan = admissionRequest.plan();
        ConnectorProviderRegistry registry = admissionRequest.registry();
        ConnectorOperationContract<P, R> contract = admissionRequest.contract();
        ConnectorProviderSelection<P, R> selection = admissionRequest.selection();
        ConnectorProviderCompatibilityReport compatibility =
            admissionRequest.compatibilityReport();
        ConnectorInvocationAuthorizationEvidence authorization =
            admissionRequest.authorizationEvidence();
        TrustedConnectorExecutionContext context = admissionRequest.context();
        ConnectorRequest<P> request = admissionRequest.request();

        if (admissionRequest.checkedAt().isBefore(plan.plannedAt())) {
            return ConnectorExecutionAdmissionStatus.PLAN_TIME_INVALID;
        }
        if (!plan.registryFingerprint().equals(registry.registryFingerprint())) {
            return ConnectorExecutionAdmissionStatus.REGISTRY_STALE;
        }
        if (!plan.contractKey().equals(contract.contractKey())
            || plan.operation() != contract.operation()
            || !contractFingerprint.equals(compatibility.contractFingerprint())
            || !contract.contractKey().equals(compatibility.contractKey())) {
            return ConnectorExecutionAdmissionStatus.CONTRACT_MISMATCH;
        }
        if (!selection.selected()
            || !plan.selectionEvidenceHash().equals(selectionHash)
            || !registry.registryFingerprint().equals(selection.evidence().registryFingerprint())
            || !contract.contractKey().equals(selection.evidence().contractKey())
            || !plan.providerKey().equals(selection.evidence().selectedProviderKey())) {
            return ConnectorExecutionAdmissionStatus.SELECTION_MISMATCH;
        }
        if (!plan.compatibilityEvidenceHash().equals(compatibilityHash)
            || !registry.registryFingerprint().equals(compatibility.registryFingerprint())
            || compatibility.findEntry(plan.providerKey()).filter(
                ConnectorProviderCompatibilityEntry::compatible
            ).isEmpty()) {
            return ConnectorExecutionAdmissionStatus.COMPATIBILITY_MISMATCH;
        }
        if (!bindingAvailable(registry, contract, selection, plan.providerKey())) {
            return ConnectorExecutionAdmissionStatus.BINDING_UNAVAILABLE;
        }
        if (!plan.tenantId().equals(context.tenantId())
            || !plan.providerKey().equals(context.providerKey())
            || !plan.plannedAt().equals(context.requestedAt())) {
            return ConnectorExecutionAdmissionStatus.TRUSTED_CONTEXT_MISMATCH;
        }
        if (!plan.credentialReferenceHash().equals(credentialHash)) {
            return ConnectorExecutionAdmissionStatus.CREDENTIAL_MISMATCH;
        }
        if (!requestMatchesPlan(plan, contract, request)) {
            return ConnectorExecutionAdmissionStatus.REQUEST_MISMATCH;
        }
        if (!plan.authorizationEvidenceHash().equals(authorizationHash)
            || !authorizationMatches(
                authorization,
                plan,
                contract,
                selection.evidence(),
                context,
                request
            )) {
            return ConnectorExecutionAdmissionStatus.AUTHORIZATION_MISMATCH;
        }
        if (!authorization.validAt(admissionRequest.checkedAt())) {
            return ConnectorExecutionAdmissionStatus.AUTHORIZATION_EXPIRED;
        }
        return ConnectorExecutionAdmissionStatus.ADMITTED;
    }

    private static <P, R> boolean bindingAvailable(
        ConnectorProviderRegistry registry,
        ConnectorOperationContract<P, R> contract,
        ConnectorProviderSelection<P, R> selection,
        String providerKey
    ) {
        try {
            ConnectorProviderBinding<P, R> current = registry.resolve(
                providerKey,
                contract.operation(),
                contract.requestPayloadType(),
                contract.responseType()
            );
            return current.canonicalRegistration().equals(
                selection.requireBinding().canonicalRegistration()
            );
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return false;
        }
    }

    private static <P, R> boolean requestMatchesPlan(
        ConnectorOrchestrationPlan plan,
        ConnectorOperationContract<P, R> contract,
        ConnectorRequest<P> request
    ) {
        if (!plan.requestId().equals(request.requestId())
            || !Objects.equals(plan.traceId(), request.traceId())
            || !plan.idempotencyKey().equals(request.idempotencyKey())
            || plan.operation() != request.operation()
            || contract.operation() != request.operation()
            || !plan.canonicalPayloadHash().equals(request.canonicalPayloadHash())
            || !contract.requestPayloadType().isInstance(request.payload())) {
            return false;
        }
        return !(request.payload() instanceof CanonicalConnectorPayload payload)
            || request.canonicalPayloadHash().equals(payload.canonicalPayloadHash());
    }

    private static <P, R> boolean authorizationMatches(
        ConnectorInvocationAuthorizationEvidence authorization,
        ConnectorOrchestrationPlan plan,
        ConnectorOperationContract<P, R> contract,
        ConnectorProviderSelectionEvidence selection,
        TrustedConnectorExecutionContext context,
        ConnectorRequest<P> request
    ) {
        return context.tenantId().equals(authorization.tenantId())
            && plan.providerKey().equals(authorization.providerKey())
            && contract.contractKey().equals(authorization.contractKey())
            && contract.operation() == authorization.operation()
            && request.requestId().equals(authorization.requestId())
            && request.idempotencyKey().equals(authorization.idempotencyKey())
            && request.canonicalPayloadHash().equals(authorization.canonicalPayloadHash())
            && selection.evidenceHash().equals(authorization.selectionEvidenceHash());
    }

    private static String credentialReferenceHash(CredentialReference reference) {
        return CanonicalPayloadHash.sha256Utf8(
            reference.providerKey() + "\n" + reference.referenceId()
        );
    }

    private static String trustedContextEvidenceHash(
        TrustedConnectorExecutionContext context,
        String credentialHash
    ) {
        return CanonicalPayloadHash.sha256Utf8(
            "tenantId=" + context.tenantId()
                + "\nproviderKey=" + context.providerKey()
                + "\ncredentialReferenceHash=" + credentialHash
                + "\nrequestedAt=" + context.requestedAt()
        );
    }

    private static String requestEvidenceHash(ConnectorRequest<?> request) {
        return CanonicalPayloadHash.sha256Utf8(
            "requestId=" + request.requestId()
                + "\ntraceId=" + optional(request.traceId())
                + "\nidempotencyKey=" + request.idempotencyKey()
                + "\noperation=" + request.operation().name()
                + "\ncanonicalPayloadHash=" + request.canonicalPayloadHash()
        );
    }

    private static String optional(String value) {
        return value == null ? "" : value;
    }
}
