package io.github.akaryc1b.approval.connector;

import io.github.akaryc1b.approval.connector.ConnectorProvider.Capability;
import io.github.akaryc1b.approval.connector.contract.*;
import io.github.akaryc1b.approval.connector.testing.DeterministicTypedConnectorPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConnectorExecutionAdmissionAcceptanceContractsTest {

    private static final Instant NOW = Instant.parse("2026-07-24T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String PROTOCOL = "m6-a.v1";
    private static final String POLICY = "admission.v1";

    @Test
    void exactCurrentEvidenceIsAdmittedWithoutExecutingAdapter() {
        Inputs inputs = inputs();
        ConnectorExecutionAdmission admission = admit(inputs);
        assertEquals(ConnectorExecutionAdmissionStatus.ADMITTED, admission.status());
        assertTrue(admission.admitted());
        assertFalse(admission.automaticExecutionAllowed());
        assertFalse(admission.automaticRetryAllowed());
        assertTrue(admission.requiresExplicitInvocation());
        assertEquals(0, inputs.port.invocationCount());
    }

    @Test
    void admissionRejectsCheckBeforePlanTime() {
        Inputs inputs = inputs();
        inputs.checkedAt = NOW.minusNanos(1);
        assertStatus(ConnectorExecutionAdmissionStatus.PLAN_TIME_INVALID, inputs);
    }

    @Test
    void admissionClassifiesRegistryStaleness() {
        Inputs inputs = inputs();
        inputs.registry = new ConnectorProviderRegistry(List.of(inputs.binding, binding("provider-b")));
        assertStatus(ConnectorExecutionAdmissionStatus.REGISTRY_STALE, inputs);
    }

    @Test
    void admissionClassifiesContractMismatch() {
        Inputs inputs = inputs();
        inputs.contract = new ConnectorOperationContract<>(
            "notification.test.v2", ConnectorOperation.NOTIFICATION_SEND,
            TestPayload.class, TestResponse.class
        );
        assertStatus(ConnectorExecutionAdmissionStatus.CONTRACT_MISMATCH, inputs);
    }

    @Test
    void admissionClassifiesSelectionMismatch() {
        Inputs inputs = inputs();
        ConnectorProviderSelectionEvidence old = inputs.selection.evidence();
        ConnectorProviderSelectionEvidence changed = new ConnectorProviderSelectionEvidence(
            "selection.v2", old.registryFingerprint(), old.contractKey(),
            old.allowedProviderKeys(), old.eligibleProviderKeys(), old.preferredProviderKey(),
            old.requiredProtocolVersion(), old.selectedProviderKey(), old.status()
        );
        inputs.selection = new ConnectorProviderSelection<>(
            ConnectorProviderSelectionStatus.SELECTED, inputs.binding, changed
        );
        assertStatus(ConnectorExecutionAdmissionStatus.SELECTION_MISMATCH, inputs);
    }

    @Test
    void admissionClassifiesCompatibilityMismatch() {
        Inputs inputs = inputs();
        ConnectorProviderCompatibilityReport old = inputs.compatibility;
        inputs.compatibility = new ConnectorProviderCompatibilityReport(
            "compatibility.v2", old.registryFingerprint(), old.contractKey(),
            old.contractFingerprint(), old.requiredProtocolVersion(), old.entries()
        );
        assertStatus(ConnectorExecutionAdmissionStatus.COMPATIBILITY_MISMATCH, inputs);
    }

    @Test
    void admissionClassifiesBindingUnavailableWithoutReturningBinding() {
        Inputs inputs = inputs();
        ConnectorReconciliationPort<TestResponse> reconciliation = new ConnectorReconciliationPort<>() {
            @Override public ProviderDescriptor descriptor() { return inputs.binding.descriptor(); }
            @Override public Object reconcile(TrustedConnectorExecutionContext c, Object r) {
                throw new UnsupportedOperationException("test only");
            }
        };
        ConnectorProviderBinding<TestPayload, TestResponse> changed = new ConnectorProviderBinding<>(
            inputs.binding.descriptor(), inputs.binding.operation(), TestPayload.class,
            TestResponse.class, inputs.port, reconciliation
        );
        inputs.selection = new ConnectorProviderSelection<>(
            ConnectorProviderSelectionStatus.SELECTED, changed, inputs.selection.evidence()
        );
        assertStatus(ConnectorExecutionAdmissionStatus.BINDING_UNAVAILABLE, inputs);
        assertEquals(0, inputs.port.invocationCount());
    }

    @Test
    void admissionClassifiesTrustedContextMismatch() {
        Inputs inputs = inputs();
        inputs.context = context("tenant-b", "credential-a");
        assertStatus(ConnectorExecutionAdmissionStatus.TRUSTED_CONTEXT_MISMATCH, inputs);
    }

    @Test
    void admissionClassifiesCredentialReferenceMismatch() {
        Inputs inputs = inputs();
        inputs.context = context("tenant-a", "credential-b");
        ConnectorExecutionAdmission admission = admit(inputs);
        assertEquals(ConnectorExecutionAdmissionStatus.CREDENTIAL_MISMATCH, admission.status());
        assertFalse(admission.evidence().canonicalEvidence().contains("credential-b"));
    }

    @Test
    void admissionClassifiesRequestMismatch() {
        Inputs inputs = inputs();
        inputs.request = new ConnectorRequest<>(
            "request-b", inputs.request.traceId(), inputs.request.idempotencyKey(),
            inputs.request.operation(), inputs.request.canonicalPayloadHash(), null,
            inputs.request.payload()
        );
        assertStatus(ConnectorExecutionAdmissionStatus.REQUEST_MISMATCH, inputs);
    }

    @Test
    void admissionClassifiesAuthorizationMismatch() {
        Inputs inputs = inputs();
        ConnectorInvocationAuthorizationEvidence old = inputs.authorization;
        inputs.authorization = new ConnectorInvocationAuthorizationEvidence(
            "authorization-b", old.authorizationPolicyVersion(), old.tenantId(), old.providerKey(),
            old.contractKey(), old.operation(), old.requestId(), old.idempotencyKey(),
            old.canonicalPayloadHash(), old.selectionEvidenceHash(), old.authorizedAt(), old.expiresAt()
        );
        assertStatus(ConnectorExecutionAdmissionStatus.AUTHORIZATION_MISMATCH, inputs);
    }

    @Test
    void admissionClassifiesExpiredAuthorization() {
        Inputs inputs = inputs();
        inputs.authorization = authorization(
            inputs.selection.evidence(), NOW.minusSeconds(600), NOW.minusSeconds(1)
        );
        inputs.plan = copyPlan(inputs.plan, inputs.authorization.evidenceHash());
        assertStatus(ConnectorExecutionAdmissionStatus.AUTHORIZATION_EXPIRED, inputs);
    }

    @Test
    void admissionEvidenceHashIsDeterministic() {
        Inputs inputs = inputs();
        ConnectorExecutionAdmission first = admit(inputs);
        ConnectorExecutionAdmission second = admit(inputs);
        assertEquals(first.evidence(), second.evidence());
        assertEquals(first.evidence().evidenceHash(), second.evidence().evidenceHash());
        assertNotEquals(inputs.plan.planHash(), first.evidence().evidenceHash());
    }

    @Test
    void foundationEvidenceIsReviewReadyButNeverGrantsAcceptance() {
        Inputs inputs = inputs();
        ConnectorFoundationAcceptanceEvidence evidence = evaluator().evaluate(
            acceptance(inputs.registry, allContracts(), List.of(admit(inputs).evidence()))
        );
        assertEquals(
            ConnectorFoundationAcceptanceStatus.READY_FOR_FORMAL_ACCEPTANCE_REVIEW,
            evidence.status()
        );
        assertTrue(evidence.readyForFormalAcceptanceReview());
        assertFalse(evidence.formalAcceptanceGranted());
        assertFalse(evidence.productionEnabled());
        assertFalse(evidence.automaticExecutionEnabled());
        assertFalse(evidence.automaticRetryEnabled());
        assertTrue(evidence.requiresExplicitFormalAcceptance());
        assertEquals(EnumSet.allOf(ConnectorFoundationBlockedCapability.class),
            evidence.blockedCapabilities());
    }

    @Test
    void foundationEvidenceFailsClosedForIncompleteOrStaleEvidence() {
        Inputs inputs = inputs();
        ConnectorExecutionAdmissionEvidence admitted = admit(inputs).evidence();
        assertEquals(ConnectorFoundationAcceptanceStatus.INCOMPLETE_CONTRACT_COVERAGE,
            evaluator().evaluate(acceptance(
                inputs.registry, List.of(contract()), List.of(admitted))).status());

        Inputs rejectedInputs = inputs();
        rejectedInputs.checkedAt = NOW.minusNanos(1);
        assertEquals(ConnectorFoundationAcceptanceStatus.INCOMPLETE_ADMISSION_EVIDENCE,
            evaluator().evaluate(acceptance(
                inputs.registry, allContracts(), List.of(admit(rejectedInputs).evidence()))).status());

        ConnectorExecutionAdmissionEvidence stale = new ConnectorExecutionAdmissionEvidence(
            admitted.admissionPolicyVersion(), admitted.status(), admitted.planHash(),
            "0".repeat(64), admitted.contractFingerprint(), admitted.selectionEvidenceHash(),
            admitted.compatibilityEvidenceHash(), admitted.authorizationEvidenceHash(),
            admitted.trustedContextEvidenceHash(), admitted.requestEvidenceHash(),
            admitted.credentialReferenceHash(), admitted.checkedAt()
        );
        assertEquals(ConnectorFoundationAcceptanceStatus.REGISTRY_EVIDENCE_MISMATCH,
            evaluator().evaluate(acceptance(
                inputs.registry, allContracts(), List.of(stale))).status());
    }

    private static Inputs inputs() {
        ConnectorProviderBinding<TestPayload, TestResponse> binding = binding("provider-a");
        ConnectorProviderRegistry registry = new ConnectorProviderRegistry(List.of(binding));
        ConnectorProviderSelection<TestPayload, TestResponse> selection =
            new DeterministicConnectorProviderSelector().select(
                registry,
                new ConnectorProviderSelectionRequest<>(
                    contract(), Set.of("provider-a"), null, PROTOCOL, "selection.v1"
                )
            );
        ConnectorProviderCompatibilityReport compatibility =
            new DeterministicConnectorProviderCompatibilityMatrix(registry).evaluate(
                contract(), Set.of("provider-a"), PROTOCOL, "compatibility.v1"
            );
        TestPayload payload = new TestPayload("hello");
        ConnectorRequest<TestPayload> request = new ConnectorRequest<>(
            "request-a", "trace-a", "idempotency-a",
            ConnectorOperation.NOTIFICATION_SEND, payload.canonicalPayloadHash(), null, payload
        );
        TrustedConnectorExecutionContext context = context("tenant-a", "credential-a");
        ConnectorInvocationAuthorizationEvidence authorization = authorization(
            selection.evidence(), NOW.minusSeconds(30), NOW.plusSeconds(300)
        );
        ConnectorOrchestrationPlan plan = new DeterministicConnectorOrchestrationPlanner().plan(
            new ConnectorOrchestrationPlanningRequest<>(
                registry, contract(), selection, compatibility, authorization, context, request
            )
        );
        return new Inputs(
            registry, binding,
            (DeterministicTypedConnectorPort<TestPayload, TestResponse>) binding.executionPort(),
            contract(), selection, compatibility, authorization, context, request, plan,
            NOW.plusSeconds(1)
        );
    }

    private static ConnectorExecutionAdmission admit(Inputs i) {
        return new DeterministicConnectorExecutionAdmissionPolicy(POLICY).admit(
            new ConnectorExecutionAdmissionRequest<>(
                i.plan, i.registry, i.contract, i.selection, i.compatibility,
                i.authorization, i.context, i.request, i.checkedAt
            )
        );
    }

    private static void assertStatus(ConnectorExecutionAdmissionStatus expected, Inputs inputs) {
        assertEquals(expected, admit(inputs).status());
    }

    private static ConnectorProviderBinding<TestPayload, TestResponse> binding(String providerKey) {
        ProviderDescriptor descriptor = new ProviderDescriptor(
            providerKey, ProviderDescriptor.ProviderType.TEST, PROTOCOL,
            Set.of(Capability.NOTIFICATION), ProviderDescriptor.ProviderState.ENABLED,
            Map.of("adapter", "deterministic")
        );
        DeterministicTypedConnectorPort<TestPayload, TestResponse> port =
            new DeterministicTypedConnectorPort<>(
                descriptor, CLOCK, payload -> new TestResponse("accepted:" + payload.value())
            );
        return new ConnectorProviderBinding<>(
            descriptor, ConnectorOperation.NOTIFICATION_SEND,
            TestPayload.class, TestResponse.class, port, null
        );
    }

    private static TrustedConnectorExecutionContext context(String tenant, String credential) {
        return new TrustedConnectorExecutionContext(
            tenant, "provider-a", new CredentialReference("provider-a", credential), NOW
        );
    }

    private static ConnectorInvocationAuthorizationEvidence authorization(
        ConnectorProviderSelectionEvidence selection,
        Instant authorizedAt,
        Instant expiresAt
    ) {
        return new ConnectorInvocationAuthorizationEvidence(
            "authorization-a", "authorization.v1", "tenant-a", "provider-a",
            contract().contractKey(), contract().operation(), "request-a", "idempotency-a",
            new TestPayload("hello").canonicalPayloadHash(), selection.evidenceHash(),
            authorizedAt, expiresAt
        );
    }

    private static ConnectorOperationContract<TestPayload, TestResponse> contract() {
        return new ConnectorOperationContract<>(
            "notification.test.v1", ConnectorOperation.NOTIFICATION_SEND,
            TestPayload.class, TestResponse.class
        );
    }

    private static List<ConnectorOperationContract<?, ?>> allContracts() {
        return List.of(
            generic("organization.read.v1", ConnectorOperation.ORGANIZATION_READ),
            generic("identity.resolve.v1", ConnectorOperation.IDENTITY_RESOLVE),
            contract(),
            generic("external.todo.create.v1", ConnectorOperation.EXTERNAL_TODO_CREATE),
            generic("external.todo.update.v1", ConnectorOperation.EXTERNAL_TODO_UPDATE),
            generic("business.callback.deliver.v1", ConnectorOperation.BUSINESS_CALLBACK_DELIVER)
        );
    }

    private static ConnectorOperationContract<Object, Object> generic(
        String key,
        ConnectorOperation operation
    ) {
        return new ConnectorOperationContract<>(key, operation, Object.class, Object.class);
    }

    private static ConnectorFoundationAcceptanceRequest acceptance(
        ConnectorProviderRegistry registry,
        List<ConnectorOperationContract<?, ?>> contracts,
        List<ConnectorExecutionAdmissionEvidence> admissions
    ) {
        return new ConnectorFoundationAcceptanceRequest(
            "m6-a.foundation.v1", registry, contracts, "selection.v1", "compatibility.v1",
            "orchestration.v1", POLICY, admissions, NOW.plusSeconds(2)
        );
    }

    private static DeterministicConnectorFoundationAcceptanceEvaluator evaluator() {
        return new DeterministicConnectorFoundationAcceptanceEvaluator();
    }

    private static ConnectorOrchestrationPlan copyPlan(
        ConnectorOrchestrationPlan source,
        String authorizationHash
    ) {
        return new ConnectorOrchestrationPlan(
            source.tenantId(), source.providerKey(), source.contractKey(), source.operation(),
            source.requestId(), source.traceId(), source.idempotencyKey(),
            source.canonicalPayloadHash(), source.credentialReferenceHash(),
            source.registryFingerprint(), source.compatibilityEvidenceHash(),
            source.selectionEvidenceHash(), authorizationHash, source.plannedAt()
        );
    }

    private record TestPayload(String value) implements CanonicalConnectorPayload {
        @Override public String canonicalPayload() { return "value=" + value; }
    }

    private record TestResponse(String value) {}

    private static final class Inputs {
        private ConnectorProviderRegistry registry;
        private final ConnectorProviderBinding<TestPayload, TestResponse> binding;
        private final DeterministicTypedConnectorPort<TestPayload, TestResponse> port;
        private ConnectorOperationContract<TestPayload, TestResponse> contract;
        private ConnectorProviderSelection<TestPayload, TestResponse> selection;
        private ConnectorProviderCompatibilityReport compatibility;
        private ConnectorInvocationAuthorizationEvidence authorization;
        private TrustedConnectorExecutionContext context;
        private ConnectorRequest<TestPayload> request;
        private ConnectorOrchestrationPlan plan;
        private Instant checkedAt;

        private Inputs(
            ConnectorProviderRegistry registry,
            ConnectorProviderBinding<TestPayload, TestResponse> binding,
            DeterministicTypedConnectorPort<TestPayload, TestResponse> port,
            ConnectorOperationContract<TestPayload, TestResponse> contract,
            ConnectorProviderSelection<TestPayload, TestResponse> selection,
            ConnectorProviderCompatibilityReport compatibility,
            ConnectorInvocationAuthorizationEvidence authorization,
            TrustedConnectorExecutionContext context,
            ConnectorRequest<TestPayload> request,
            ConnectorOrchestrationPlan plan,
            Instant checkedAt
        ) {
            this.registry = registry;
            this.binding = binding;
            this.port = port;
            this.contract = contract;
            this.selection = selection;
            this.compatibility = compatibility;
            this.authorization = authorization;
            this.context = context;
            this.request = request;
            this.plan = plan;
            this.checkedAt = checkedAt;
        }
    }
}
