package io.github.akaryc1b.approval.connector;

import io.github.akaryc1b.approval.connector.ConnectorProvider.Capability;
import io.github.akaryc1b.approval.connector.contract.CanonicalConnectorPayload;
import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorInvocationAuthorizationEvidence;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationContract;
import io.github.akaryc1b.approval.connector.contract.ConnectorOrchestrationPlan;
import io.github.akaryc1b.approval.connector.contract.ConnectorOrchestrationPlanningRequest;
import io.github.akaryc1b.approval.connector.contract.ConnectorProviderBinding;
import io.github.akaryc1b.approval.connector.contract.ConnectorProviderCompatibilityEntry;
import io.github.akaryc1b.approval.connector.contract.ConnectorProviderCompatibilityReport;
import io.github.akaryc1b.approval.connector.contract.ConnectorProviderCompatibilityStatus;
import io.github.akaryc1b.approval.connector.contract.ConnectorProviderRegistry;
import io.github.akaryc1b.approval.connector.contract.ConnectorProviderSelection;
import io.github.akaryc1b.approval.connector.contract.ConnectorProviderSelectionEvidence;
import io.github.akaryc1b.approval.connector.contract.ConnectorProviderSelectionRequest;
import io.github.akaryc1b.approval.connector.contract.ConnectorProviderSelectionStatus;
import io.github.akaryc1b.approval.connector.contract.ConnectorRequest;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;
import io.github.akaryc1b.approval.connector.contract.DeterministicConnectorOrchestrationPlanner;
import io.github.akaryc1b.approval.connector.contract.DeterministicConnectorProviderCompatibilityMatrix;
import io.github.akaryc1b.approval.connector.contract.DeterministicConnectorProviderSelector;
import io.github.akaryc1b.approval.connector.contract.ProviderDescriptor;
import io.github.akaryc1b.approval.connector.contract.TrustedConnectorExecutionContext;
import io.github.akaryc1b.approval.connector.testing.DeterministicTypedConnectorPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorCompatibilityOrchestrationContractsTest {

    private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String PROTOCOL = "m6-a.v1";

    @Test
    void compatibilityMatrixClassifiesEveryClosedStatus() {
        var compatible = messageBinding(descriptor(
            "compatible", PROTOCOL, ProviderDescriptor.ProviderState.ENABLED,
            Set.of(Capability.NOTIFICATION)
        ));
        var disabled = messageBinding(descriptor(
            "disabled", PROTOCOL, ProviderDescriptor.ProviderState.DISABLED,
            Set.of(Capability.NOTIFICATION)
        ));
        var capabilityUnsupported = organizationBinding(descriptor(
            "capability-unsupported", PROTOCOL, ProviderDescriptor.ProviderState.ENABLED,
            Set.of(Capability.ORGANIZATION)
        ));
        var operationUnregistered = organizationBinding(descriptor(
            "operation-unregistered", PROTOCOL, ProviderDescriptor.ProviderState.ENABLED,
            Set.of(Capability.ORGANIZATION, Capability.NOTIFICATION)
        ));
        var typeMismatch = otherMessageBinding(descriptor(
            "type-mismatch", PROTOCOL, ProviderDescriptor.ProviderState.ENABLED,
            Set.of(Capability.NOTIFICATION)
        ));
        var protocolMismatch = messageBinding(descriptor(
            "protocol-mismatch", "m6-a.v2", ProviderDescriptor.ProviderState.ENABLED,
            Set.of(Capability.NOTIFICATION)
        ));
        var registry = new ConnectorProviderRegistry(List.of(
            compatible, disabled, capabilityUnsupported, operationUnregistered,
            typeMismatch, protocolMismatch
        ));
        var report = matrix(registry).evaluate(
            contract(),
            Set.of("compatible", "disabled", "capability-unsupported",
                "operation-unregistered", "type-mismatch", "protocol-mismatch", "unknown"),
            PROTOCOL,
            "compatibility.v1"
        );
        Map<String, ConnectorProviderCompatibilityStatus> statuses = report.entries().stream()
            .collect(Collectors.toMap(
                ConnectorProviderCompatibilityEntry::providerKey,
                ConnectorProviderCompatibilityEntry::status
            ));
        assertEquals(ConnectorProviderCompatibilityStatus.COMPATIBLE, statuses.get("compatible"));
        assertEquals(ConnectorProviderCompatibilityStatus.PROVIDER_DISABLED, statuses.get("disabled"));
        assertEquals(ConnectorProviderCompatibilityStatus.CAPABILITY_UNSUPPORTED,
            statuses.get("capability-unsupported"));
        assertEquals(ConnectorProviderCompatibilityStatus.OPERATION_UNREGISTERED,
            statuses.get("operation-unregistered"));
        assertEquals(ConnectorProviderCompatibilityStatus.CONTRACT_TYPE_MISMATCH,
            statuses.get("type-mismatch"));
        assertEquals(ConnectorProviderCompatibilityStatus.PROTOCOL_MISMATCH,
            statuses.get("protocol-mismatch"));
        assertEquals(ConnectorProviderCompatibilityStatus.PROVIDER_UNKNOWN, statuses.get("unknown"));
    }

    @Test
    void compatibilityReportOrderingAndHashAreStable() {
        var providerA = messageBinding(descriptor(
            "provider-a", PROTOCOL, ProviderDescriptor.ProviderState.ENABLED,
            Set.of(Capability.NOTIFICATION)
        ));
        var providerB = messageBinding(descriptor(
            "provider-b", PROTOCOL, ProviderDescriptor.ProviderState.ENABLED,
            Set.of(Capability.NOTIFICATION)
        ));
        var registry = new ConnectorProviderRegistry(List.of(providerB, providerA));
        var first = matrix(registry).evaluate(
            contract(), new LinkedHashSet<>(List.of("provider-b", "provider-a")),
            PROTOCOL, "compatibility.v1"
        );
        var second = matrix(registry).evaluate(
            contract(), new LinkedHashSet<>(List.of("provider-a", "provider-b")),
            PROTOCOL, "compatibility.v1"
        );
        assertEquals(List.of("provider-a", "provider-b"), first.compatibleProviderKeys());
        assertEquals(first.entries(), second.entries());
        assertEquals(first.evidenceHash(), second.evidenceHash());
        assertEquals(CanonicalPayloadHash.sha256Utf8(contract().canonicalValue()),
            first.contractFingerprint());
    }

    @Test
    void registryFindBindingIsNonMutatingAndExact() {
        Fixture fixture = fixture("provider-a");
        assertTrue(fixture.registry().findBinding(
            "provider-a", ConnectorOperation.NOTIFICATION_SEND).isPresent());
        assertTrue(fixture.registry().findBinding(
            "provider-a", ConnectorOperation.ORGANIZATION_READ).isEmpty());
        assertTrue(fixture.registry().findBinding(
            "missing", ConnectorOperation.NOTIFICATION_SEND).isEmpty());
    }

    @Test
    void authorizationEvidenceIsDeterministicAndBounded() {
        Fixture fixture = fixture("provider-a");
        var second = authorization(
            fixture, "tenant-a", "provider-a", "request-a", "idempotency-a",
            fixture.request().canonicalPayloadHash(), NOW.minusSeconds(30), NOW.plusSeconds(300)
        );
        assertEquals(fixture.authorization().evidenceHash(), second.evidenceHash());
        assertTrue(second.validAt(second.authorizedAt()));
        assertTrue(second.validAt(second.expiresAt()));
        assertFalse(second.validAt(second.authorizedAt().minusNanos(1)));
        assertFalse(second.validAt(second.expiresAt().plusNanos(1)));
    }

    @Test
    void authorizationRejectsInvalidValidityWindow() {
        Fixture fixture = fixture("provider-a");
        assertThrows(IllegalArgumentException.class, () -> authorization(
            fixture, "tenant-a", "provider-a", "request-a", "idempotency-a",
            fixture.request().canonicalPayloadHash(), NOW, NOW
        ));
        assertThrows(IllegalArgumentException.class, () -> authorization(
            fixture, "tenant-a", "provider-a", "request-a", "idempotency-a",
            fixture.request().canonicalPayloadHash(), NOW, NOW.plusSeconds(601)
        ));
    }

    @Test
    void plannerProducesDeterministicEvidenceWithoutExecutingAdapter() {
        Fixture fixture = fixture("provider-a");
        ConnectorOrchestrationPlan first = plan(fixture);
        ConnectorOrchestrationPlan second = plan(fixture);
        assertEquals(first.planHash(), second.planHash());
        assertEquals(0, fixture.port().invocationCount());
        assertEquals(fixture.registry().registryFingerprint(), first.registryFingerprint());
        assertEquals(fixture.selection().evidence().evidenceHash(), first.selectionEvidenceHash());
        assertEquals(fixture.compatibility().evidenceHash(), first.compatibilityEvidenceHash());
        assertEquals(fixture.authorization().evidenceHash(), first.authorizationEvidenceHash());
        assertFalse(first.canonicalEvidence().contains("credential-a"));
    }

    @Test
    void plannerRejectsNonSelectedDecision() {
        Fixture fixture = fixture("provider-a");
        var evidence = new ConnectorProviderSelectionEvidence(
            "selection.v1", fixture.registry().registryFingerprint(), contract().contractKey(),
            List.of("provider-a"), List.of(), null, PROTOCOL, null,
            ConnectorProviderSelectionStatus.NO_ELIGIBLE_PROVIDER
        );
        var selection = new ConnectorProviderSelection<TestPayload, TestResponse>(
            ConnectorProviderSelectionStatus.NO_ELIGIBLE_PROVIDER, null, evidence
        );
        assertThrows(IllegalArgumentException.class, () -> new DeterministicConnectorOrchestrationPlanner()
            .plan(new ConnectorOrchestrationPlanningRequest<>(
                fixture.registry(), contract(), selection, fixture.compatibility(),
                fixture.authorization(), fixture.context(), fixture.request()
            )));
    }

    @Test
    void plannerRejectsStaleRegistryFingerprint() {
        Fixture fixture = fixture("provider-a");
        var old = fixture.selection().evidence();
        var stale = new ConnectorProviderSelectionEvidence(
            old.policyVersion(), "0".repeat(64), old.contractKey(), old.allowedProviderKeys(),
            old.eligibleProviderKeys(), old.preferredProviderKey(), old.requiredProtocolVersion(),
            old.selectedProviderKey(), old.status()
        );
        var selection = new ConnectorProviderSelection<>(
            ConnectorProviderSelectionStatus.SELECTED, fixture.binding(), stale
        );
        var authorization = authorizationForSelection(fixture, stale, NOW.minusSeconds(30), NOW.plusSeconds(300));
        assertThrows(IllegalArgumentException.class, () -> new DeterministicConnectorOrchestrationPlanner()
            .plan(new ConnectorOrchestrationPlanningRequest<>(
                fixture.registry(), contract(), selection, fixture.compatibility(), authorization,
                fixture.context(), fixture.request()
            )));
    }

    @Test
    void plannerRejectsNonCompatibleSelectedProvider() {
        Fixture fixture = fixture("provider-a");
        var incompatible = new ConnectorProviderCompatibilityReport(
            "compatibility.v1", fixture.registry().registryFingerprint(), contract().contractKey(),
            CanonicalPayloadHash.sha256Utf8(contract().canonicalValue()), PROTOCOL,
            List.of(new ConnectorProviderCompatibilityEntry(
                "provider-a", PROTOCOL, ConnectorProviderCompatibilityStatus.PROTOCOL_MISMATCH
            ))
        );
        assertThrows(IllegalArgumentException.class, () -> new DeterministicConnectorOrchestrationPlanner()
            .plan(fixture.planningRequest(incompatible, fixture.authorization(), fixture.request())));
    }

    @Test
    void plannerRejectsAuthorizationTenantAndProviderMismatch() {
        Fixture fixture = fixture("provider-a");
        var wrongTenant = authorization(
            fixture, "tenant-b", "provider-a", "request-a", "idempotency-a",
            fixture.request().canonicalPayloadHash(), NOW.minusSeconds(30), NOW.plusSeconds(300)
        );
        var wrongProvider = authorization(
            fixture, "tenant-a", "provider-b", "request-a", "idempotency-a",
            fixture.request().canonicalPayloadHash(), NOW.minusSeconds(30), NOW.plusSeconds(300)
        );
        assertThrows(IllegalArgumentException.class, () -> planner(fixture, wrongTenant, fixture.request()));
        assertThrows(IllegalArgumentException.class, () -> planner(fixture, wrongProvider, fixture.request()));
    }

    @Test
    void plannerRejectsAuthorizationRequestAndIdempotencyMismatch() {
        Fixture fixture = fixture("provider-a");
        var wrongRequest = authorization(
            fixture, "tenant-a", "provider-a", "request-b", "idempotency-a",
            fixture.request().canonicalPayloadHash(), NOW.minusSeconds(30), NOW.plusSeconds(300)
        );
        var wrongIdempotency = authorization(
            fixture, "tenant-a", "provider-a", "request-a", "idempotency-b",
            fixture.request().canonicalPayloadHash(), NOW.minusSeconds(30), NOW.plusSeconds(300)
        );
        assertThrows(IllegalArgumentException.class, () -> planner(fixture, wrongRequest, fixture.request()));
        assertThrows(IllegalArgumentException.class,
            () -> planner(fixture, wrongIdempotency, fixture.request()));
    }

    @Test
    void plannerRejectsExpiredAuthorization() {
        Fixture fixture = fixture("provider-a");
        var expired = authorization(
            fixture, "tenant-a", "provider-a", "request-a", "idempotency-a",
            fixture.request().canonicalPayloadHash(), NOW.minusSeconds(600), NOW.minusSeconds(1)
        );
        assertThrows(IllegalArgumentException.class, () -> planner(fixture, expired, fixture.request()));
    }

    @Test
    void plannerRejectsCanonicalPayloadHashMismatch() {
        Fixture fixture = fixture("provider-a");
        TestPayload payload = new TestPayload("different");
        var mismatch = new ConnectorRequest<>(
            fixture.request().requestId(), fixture.request().traceId(),
            fixture.request().idempotencyKey(), fixture.request().operation(),
            fixture.request().canonicalPayloadHash(), null, payload
        );
        assertNotEquals(payload.canonicalPayloadHash(), mismatch.canonicalPayloadHash());
        assertThrows(IllegalArgumentException.class,
            () -> planner(fixture, fixture.authorization(), mismatch));
    }

    @Test
    void plannerRejectsCompatibilityContractFingerprintMismatch() {
        Fixture fixture = fixture("provider-a");
        var mismatch = new ConnectorProviderCompatibilityReport(
            "compatibility.v1", fixture.registry().registryFingerprint(), contract().contractKey(),
            "f".repeat(64), PROTOCOL,
            List.of(new ConnectorProviderCompatibilityEntry(
                "provider-a", PROTOCOL, ConnectorProviderCompatibilityStatus.COMPATIBLE
            ))
        );
        assertThrows(IllegalArgumentException.class, () -> new DeterministicConnectorOrchestrationPlanner()
            .plan(fixture.planningRequest(mismatch, fixture.authorization(), fixture.request())));
    }

    @Test
    void planNeverAuthorizesAutomaticExecutionOrRetry() {
        ConnectorOrchestrationPlan plan = plan(fixture("provider-a"));
        assertFalse(plan.automaticExecutionAllowed());
        assertFalse(plan.automaticRetryAllowed());
        assertTrue(plan.requiresExplicitExecution());
    }

    private static Fixture fixture(String providerKey) {
        ProviderDescriptor descriptor = descriptor(
            providerKey, PROTOCOL, ProviderDescriptor.ProviderState.ENABLED,
            Set.of(Capability.NOTIFICATION)
        );
        var port = new DeterministicTypedConnectorPort<TestPayload, TestResponse>(
            descriptor, CLOCK, payload -> new TestResponse("accepted:" + payload.value())
        );
        var binding = new ConnectorProviderBinding<>(
            descriptor, ConnectorOperation.NOTIFICATION_SEND,
            TestPayload.class, TestResponse.class, port, null
        );
        var registry = new ConnectorProviderRegistry(List.of(binding));
        var selection = new DeterministicConnectorProviderSelector().select(
            registry,
            new ConnectorProviderSelectionRequest<>(
                contract(), Set.of(providerKey), null, PROTOCOL, "selection.v1"
            )
        );
        var compatibility = matrix(registry).evaluate(
            contract(), Set.of(providerKey), PROTOCOL, "compatibility.v1"
        );
        TestPayload payload = new TestPayload("hello");
        var request = new ConnectorRequest<>(
            "request-a", "trace-a", "idempotency-a",
            ConnectorOperation.NOTIFICATION_SEND, payload.canonicalPayloadHash(), null, payload
        );
        var context = new TrustedConnectorExecutionContext(
            "tenant-a", providerKey, new CredentialReference(providerKey, "credential-a"), NOW
        );
        Fixture partial = new Fixture(
            registry, binding, port, selection, compatibility, context, request, null
        );
        var authorization = authorization(
            partial, "tenant-a", providerKey, "request-a", "idempotency-a",
            request.canonicalPayloadHash(), NOW.minusSeconds(30), NOW.plusSeconds(300)
        );
        return new Fixture(
            registry, binding, port, selection, compatibility, context, request, authorization
        );
    }

    private static ConnectorInvocationAuthorizationEvidence authorization(
        Fixture fixture,
        String tenantId,
        String providerKey,
        String requestId,
        String idempotencyKey,
        String payloadHash,
        Instant authorizedAt,
        Instant expiresAt
    ) {
        return authorizationForSelection(
            fixture, fixture.selection().evidence(), tenantId, providerKey, requestId,
            idempotencyKey, payloadHash, authorizedAt, expiresAt
        );
    }

    private static ConnectorInvocationAuthorizationEvidence authorizationForSelection(
        Fixture fixture,
        ConnectorProviderSelectionEvidence selectionEvidence,
        Instant authorizedAt,
        Instant expiresAt
    ) {
        return authorizationForSelection(
            fixture, selectionEvidence, fixture.context().tenantId(), fixture.context().providerKey(),
            fixture.request().requestId(), fixture.request().idempotencyKey(),
            fixture.request().canonicalPayloadHash(), authorizedAt, expiresAt
        );
    }

    private static ConnectorInvocationAuthorizationEvidence authorizationForSelection(
        Fixture fixture,
        ConnectorProviderSelectionEvidence selectionEvidence,
        String tenantId,
        String providerKey,
        String requestId,
        String idempotencyKey,
        String payloadHash,
        Instant authorizedAt,
        Instant expiresAt
    ) {
        return new ConnectorInvocationAuthorizationEvidence(
            "authorization-a", "authorization.v1", tenantId, providerKey,
            contract().contractKey(), contract().operation(), requestId, idempotencyKey,
            payloadHash, selectionEvidence.evidenceHash(), authorizedAt, expiresAt
        );
    }

    private static ConnectorOrchestrationPlan plan(Fixture fixture) {
        return new DeterministicConnectorOrchestrationPlanner().plan(fixture.planningRequest());
    }

    private static ConnectorOrchestrationPlan planner(
        Fixture fixture,
        ConnectorInvocationAuthorizationEvidence authorization,
        ConnectorRequest<TestPayload> request
    ) {
        return new DeterministicConnectorOrchestrationPlanner().plan(
            fixture.planningRequest(fixture.compatibility(), authorization, request)
        );
    }

    private static ConnectorOperationContract<TestPayload, TestResponse> contract() {
        return new ConnectorOperationContract<>(
            "notification.test.v1", ConnectorOperation.NOTIFICATION_SEND,
            TestPayload.class, TestResponse.class
        );
    }

    private static DeterministicConnectorProviderCompatibilityMatrix matrix(
        ConnectorProviderRegistry registry
    ) {
        return new DeterministicConnectorProviderCompatibilityMatrix(registry);
    }

    private static ProviderDescriptor descriptor(
        String providerKey,
        String protocol,
        ProviderDescriptor.ProviderState state,
        Set<Capability> capabilities
    ) {
        return new ProviderDescriptor(
            providerKey, ProviderDescriptor.ProviderType.TEST, protocol,
            capabilities, state, Map.of("adapter", "deterministic")
        );
    }

    private static ConnectorProviderBinding<TestPayload, TestResponse> messageBinding(
        ProviderDescriptor descriptor
    ) {
        return new ConnectorProviderBinding<>(
            descriptor, ConnectorOperation.NOTIFICATION_SEND,
            TestPayload.class, TestResponse.class,
            new DeterministicTypedConnectorPort<>(
                descriptor, CLOCK, payload -> new TestResponse(payload.value())
            ),
            null
        );
    }

    private static ConnectorProviderBinding<OtherPayload, OtherResponse> otherMessageBinding(
        ProviderDescriptor descriptor
    ) {
        return new ConnectorProviderBinding<>(
            descriptor, ConnectorOperation.NOTIFICATION_SEND,
            OtherPayload.class, OtherResponse.class,
            new DeterministicTypedConnectorPort<>(
                descriptor, CLOCK, payload -> new OtherResponse(payload.value())
            ),
            null
        );
    }

    private static ConnectorProviderBinding<OtherPayload, OtherResponse> organizationBinding(
        ProviderDescriptor descriptor
    ) {
        return new ConnectorProviderBinding<>(
            descriptor, ConnectorOperation.ORGANIZATION_READ,
            OtherPayload.class, OtherResponse.class,
            new DeterministicTypedConnectorPort<>(
                descriptor, CLOCK, payload -> new OtherResponse(payload.value())
            ),
            null
        );
    }

    private record TestPayload(String value) implements CanonicalConnectorPayload {
        @Override
        public String canonicalPayload() {
            return "value=" + value;
        }
    }

    private record TestResponse(String value) {
    }

    private record OtherPayload(String value) {
    }

    private record OtherResponse(String value) {
    }

    private record Fixture(
        ConnectorProviderRegistry registry,
        ConnectorProviderBinding<TestPayload, TestResponse> binding,
        DeterministicTypedConnectorPort<TestPayload, TestResponse> port,
        ConnectorProviderSelection<TestPayload, TestResponse> selection,
        ConnectorProviderCompatibilityReport compatibility,
        TrustedConnectorExecutionContext context,
        ConnectorRequest<TestPayload> request,
        ConnectorInvocationAuthorizationEvidence authorization
    ) {
        private ConnectorOrchestrationPlanningRequest<TestPayload, TestResponse> planningRequest() {
            return planningRequest(compatibility, authorization, request);
        }

        private ConnectorOrchestrationPlanningRequest<TestPayload, TestResponse> planningRequest(
            ConnectorProviderCompatibilityReport report,
            ConnectorInvocationAuthorizationEvidence evidence,
            ConnectorRequest<TestPayload> connectorRequest
        ) {
            return new ConnectorOrchestrationPlanningRequest<>(
                registry, contract(), selection, report, evidence, context, connectorRequest
            );
        }
    }
}
