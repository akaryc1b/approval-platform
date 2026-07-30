package io.github.akaryc1b.approval.connector;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorCredentialResolver;
import io.github.akaryc1b.approval.connector.contract.ConnectorExecutionPort;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.ConnectorOutcome;
import io.github.akaryc1b.approval.connector.contract.ConnectorProviderBinding;
import io.github.akaryc1b.approval.connector.contract.ConnectorProviderRegistry;
import io.github.akaryc1b.approval.connector.contract.ConnectorProviderResult;
import io.github.akaryc1b.approval.connector.contract.ConnectorReconciliationRequest;
import io.github.akaryc1b.approval.connector.contract.ConnectorRequest;
import io.github.akaryc1b.approval.connector.contract.ConnectorResult;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;
import io.github.akaryc1b.approval.connector.contract.IdempotencyEvidence;
import io.github.akaryc1b.approval.connector.contract.ProviderDescriptor;
import io.github.akaryc1b.approval.connector.contract.ReconciliationDecision;
import io.github.akaryc1b.approval.connector.contract.ReconciliationStatus;
import io.github.akaryc1b.approval.connector.contract.TrustedConnectorExecutionContext;
import io.github.akaryc1b.approval.connector.testing.DeterministicCredentialResolver;
import io.github.akaryc1b.approval.connector.testing.DeterministicReconciliationPort;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorProviderResolutionContractsTest {

    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String PROVIDER_KEY = "registry-test";
    private static final String TENANT_ID = "tenant-1";
    private static final CredentialReference CREDENTIAL_REFERENCE =
        new CredentialReference(PROVIDER_KEY, "credential-1");

    @Test
    void registrySupportsMultipleTypedOperationsWithStableFingerprint() {
        ProviderDescriptor descriptor = descriptor(ProviderDescriptor.ProviderState.ENABLED);
        var notification = binding(
            descriptor,
            ConnectorOperation.NOTIFICATION_SEND,
            NotificationCommand.class,
            NotificationResponse.class,
            command -> new NotificationResponse("accepted:" + command.messageId())
        );
        var directory = binding(
            descriptor,
            ConnectorOperation.ORGANIZATION_READ,
            DirectoryQuery.class,
            DirectoryResult.class,
            query -> new DirectoryResult(query.keyword().length())
        );
        var first = new ConnectorProviderRegistry(List.of(notification, directory));
        var second = new ConnectorProviderRegistry(List.of(directory, notification));

        assertEquals(1, first.descriptors().size());
        assertEquals(first.registryFingerprint(), second.registryFingerprint());
        assertEquals(64, first.registryFingerprint().length());
        assertEquals(
            NotificationCommand.class,
            first.resolve(
                PROVIDER_KEY,
                ConnectorOperation.NOTIFICATION_SEND,
                NotificationCommand.class,
                NotificationResponse.class
            ).requestPayloadType()
        );
        assertEquals(
            DirectoryResult.class,
            first.resolve(
                PROVIDER_KEY,
                ConnectorOperation.ORGANIZATION_READ,
                DirectoryQuery.class,
                DirectoryResult.class
            ).responseType()
        );
    }

    @Test
    void registryRejectsDuplicateOperationsAndInconsistentDescriptors() {
        ProviderDescriptor firstDescriptor = descriptor(ProviderDescriptor.ProviderState.ENABLED);
        var first = notificationBinding(firstDescriptor);
        assertThrows(
            IllegalArgumentException.class,
            () -> new ConnectorProviderRegistry(List.of(first, first))
        );

        ProviderDescriptor secondDescriptor = new ProviderDescriptor(
            PROVIDER_KEY,
            ProviderDescriptor.ProviderType.TEST,
            "m6-a.v2-other",
            firstDescriptor.supportedCapabilities(),
            ProviderDescriptor.ProviderState.ENABLED,
            Map.of("mode", "other")
        );
        var directory = binding(
            secondDescriptor,
            ConnectorOperation.ORGANIZATION_READ,
            DirectoryQuery.class,
            DirectoryResult.class,
            query -> new DirectoryResult(1)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new ConnectorProviderRegistry(List.of(first, directory))
        );
    }

    @Test
    void registryFailsClosedForDisabledUnsupportedAndMismatchedBindings() {
        ProviderDescriptor disabled = descriptor(ProviderDescriptor.ProviderState.DISABLED);
        var disabledRegistry = new ConnectorProviderRegistry(List.of(
            notificationBinding(disabled)
        ));
        assertThrows(
            IllegalStateException.class,
            () -> disabledRegistry.resolve(
                PROVIDER_KEY,
                ConnectorOperation.NOTIFICATION_SEND,
                NotificationCommand.class,
                NotificationResponse.class
            )
        );

        ProviderDescriptor organizationOnly = new ProviderDescriptor(
            PROVIDER_KEY,
            ProviderDescriptor.ProviderType.TEST,
            "m6-a.v2",
            Set.of(ConnectorProvider.Capability.ORGANIZATION),
            ProviderDescriptor.ProviderState.ENABLED,
            Map.of()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> notificationBinding(organizationOnly)
        );

        var registry = new ConnectorProviderRegistry(List.of(
            notificationBinding(descriptor(ProviderDescriptor.ProviderState.ENABLED))
        ));
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.resolve(
                PROVIDER_KEY,
                ConnectorOperation.NOTIFICATION_SEND,
                DirectoryQuery.class,
                NotificationResponse.class
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.resolve(
                PROVIDER_KEY,
                ConnectorOperation.ORGANIZATION_READ,
                DirectoryQuery.class,
                DirectoryResult.class
            )
        );
    }

    @Test
    void providerBindingRejectsTrustedProviderAndOperationMismatches() {
        var binding = notificationBinding(
            descriptor(ProviderDescriptor.ProviderState.ENABLED)
        );
        var wrongContext = new TrustedConnectorExecutionContext(
            TENANT_ID,
            "other-provider",
            new CredentialReference("other-provider", "credential-1"),
            NOW
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> binding.execute(wrongContext, notificationRequest())
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> binding.execute(
                context(),
                request(
                    ConnectorOperation.ORGANIZATION_READ,
                    new NotificationCommand("message-1")
                )
            )
        );
    }

    @Test
    void credentialResolverRedactsAndZeroizesScopedMaterial() throws Exception {
        byte[] fixture = "fixture-credential-material".getBytes(StandardCharsets.UTF_8);
        try (var resolver = credentialResolver(fixture)) {
            byte[] escapedCopy = resolver.withCredential(
                context(),
                credential -> credential.withSecretBytes(bytes -> bytes)
            );

            assertArrayEquals(new byte[escapedCopy.length], escapedCopy);
            assertTrue(resolver.lastScopeClosed());
            assertFalse(resolver.toString().contains("fixture-credential-material"));
            assertEquals(
                "fixture-credential-material",
                new String(fixture, StandardCharsets.UTF_8)
            );
        }
    }

    @Test
    void credentialScopeCannotEscapeAndTenantBoundaryIsEnforced() throws Exception {
        byte[] fixture = "fixture-credential-material".getBytes(StandardCharsets.UTF_8);
        try (var resolver = credentialResolver(fixture)) {
            ConnectorCredentialResolver.ScopedCredential[] captured =
                new ConnectorCredentialResolver.ScopedCredential[1];
            resolver.withCredential(context(), credential -> {
                captured[0] = credential;
                assertTrue(credential.active());
                return credential.keyId();
            });

            assertFalse(captured[0].active());
            assertThrows(IllegalStateException.class, captured[0]::keyId);
            assertThrows(
                IllegalArgumentException.class,
                () -> resolver.withCredential(
                    new TrustedConnectorExecutionContext(
                        "tenant-2",
                        PROVIDER_KEY,
                        CREDENTIAL_REFERENCE,
                        NOW
                    ),
                    credential -> credential.keyId()
                )
            );
        }
    }

    @Test
    void onlyTimeoutAndUnknownResultsMayEnterReconciliation() {
        assertThrows(
            IllegalArgumentException.class,
            () -> reconciliationRequest(
                "idem-1",
                payloadHash(),
                ConnectorOutcome.SUCCESS,
                "provider-1"
            )
        );
    }

    @Test
    void confirmedSuccessProducesStableEvidenceWithoutRetry() {
        String hash = payloadHash();
        var port = reconciliationPort(List.of(
            DeterministicReconciliationPort.Fixture.confirmedSuccess(
                "idem-1",
                hash,
                "provider-1",
                new NotificationResponse("confirmed")
            )
        ));
        var request = reconciliationRequest(
            "idem-1",
            hash,
            ConnectorOutcome.TIMEOUT,
            "provider-1"
        );

        var first = port.reconcile(context(), request);
        var second = port.reconcile(context(), request);
        assertEquals(ReconciliationStatus.CONFIRMED_SUCCESS, first.status());
        assertEquals(ReconciliationDecision.COMPLETE_SUCCESS, first.decision());
        assertFalse(first.automaticRetryAllowed());
        assertEquals(first.evidence().evidenceHash(), second.evidence().evidenceHash());
        assertEquals(64, first.evidence().evidenceHash().length());
    }

    @Test
    void confirmedPermanentFailureCompletesWithoutRetry() {
        String hash = payloadHash();
        var port = reconciliationPort(List.of(
            DeterministicReconciliationPort.Fixture.confirmedPermanentFailure(
                "idem-1",
                hash,
                "provider-1"
            )
        ));

        var result = port.reconcile(context(), reconciliationRequest(
            "idem-1",
            hash,
            ConnectorOutcome.TIMEOUT,
            "provider-1"
        ));
        assertEquals(ReconciliationStatus.CONFIRMED_PERMANENT_FAILURE, result.status());
        assertEquals(ReconciliationDecision.COMPLETE_FAILURE, result.decision());
        assertFalse(result.automaticRetryAllowed());
    }

    @Test
    void stillUnknownRequiresFurtherReconciliationWithoutRetry() {
        String hash = payloadHash();
        var port = reconciliationPort(List.of(
            DeterministicReconciliationPort.Fixture.stillUnknown(
                "idem-1",
                hash,
                "provider-1"
            )
        ));

        var result = port.reconcile(context(), reconciliationRequest(
            "idem-1",
            hash,
            ConnectorOutcome.UNKNOWN,
            "provider-1"
        ));
        assertEquals(ReconciliationStatus.STILL_UNKNOWN, result.status());
        assertEquals(ReconciliationDecision.RECONCILE_AGAIN, result.decision());
        assertFalse(result.automaticRetryAllowed());
    }

    @Test
    void missingAndConflictingEvidenceFailClosed() {
        String hash = payloadHash();
        var port = reconciliationPort(List.of(
            DeterministicReconciliationPort.Fixture.confirmedSuccess(
                "idem-1",
                hash,
                "provider-1",
                new NotificationResponse("confirmed")
            )
        ));
        var missing = port.reconcile(context(), reconciliationRequest(
            "idem-missing",
            hash,
            ConnectorOutcome.UNKNOWN,
            null
        ));
        var conflict = port.reconcile(context(), reconciliationRequest(
            "idem-1",
            CanonicalPayloadHash.sha256Utf8("different-payload"),
            ConnectorOutcome.TIMEOUT,
            "provider-1"
        ));

        assertEquals(ReconciliationStatus.NOT_FOUND, missing.status());
        assertEquals(ReconciliationDecision.RECONCILE_AGAIN, missing.decision());
        assertEquals(ReconciliationStatus.CONFLICT, conflict.status());
        assertEquals(ReconciliationDecision.MANUAL_REVIEW, conflict.decision());
        assertFalse(missing.automaticRetryAllowed());
        assertFalse(conflict.automaticRetryAllowed());
        assertNotEquals(
            missing.evidence().evidenceHash(),
            conflict.evidence().evidenceHash()
        );
    }

    @Test
    void bindingWithoutReconciliationPortFailsClosed() {
        var binding = notificationBinding(
            descriptor(ProviderDescriptor.ProviderState.ENABLED)
        );
        assertThrows(
            IllegalStateException.class,
            () -> binding.reconcile(context(), reconciliationRequest(
                "idem-1",
                payloadHash(),
                ConnectorOutcome.UNKNOWN,
                null
            ))
        );
    }

    private static ProviderDescriptor descriptor(ProviderDescriptor.ProviderState state) {
        return new ProviderDescriptor(
            PROVIDER_KEY,
            ProviderDescriptor.ProviderType.TEST,
            "m6-a.v2",
            Set.of(
                ConnectorProvider.Capability.ORGANIZATION,
                ConnectorProvider.Capability.NOTIFICATION
            ),
            state,
            Map.of("adapter", "deterministic", "network", "disabled")
        );
    }

    private static ConnectorProviderBinding<NotificationCommand, NotificationResponse>
        notificationBinding(ProviderDescriptor descriptor) {
        return binding(
            descriptor,
            ConnectorOperation.NOTIFICATION_SEND,
            NotificationCommand.class,
            NotificationResponse.class,
            command -> new NotificationResponse("accepted:" + command.messageId())
        );
    }

    private static <P, R> ConnectorProviderBinding<P, R> binding(
        ProviderDescriptor descriptor,
        ConnectorOperation operation,
        Class<P> requestType,
        Class<R> responseType,
        Function<P, R> responseFactory
    ) {
        return new ConnectorProviderBinding<>(
            descriptor,
            operation,
            requestType,
            responseType,
            new SuccessfulExecutionPort<>(descriptor, responseFactory),
            null
        );
    }

    private static ConnectorRequest<NotificationCommand> notificationRequest() {
        return request(
            ConnectorOperation.NOTIFICATION_SEND,
            new NotificationCommand("message-1")
        );
    }

    private static <P> ConnectorRequest<P> request(
        ConnectorOperation operation,
        P payload
    ) {
        return new ConnectorRequest<>(
            "request-1",
            "trace-1",
            "idempotency-1",
            operation,
            CanonicalPayloadHash.sha256Utf8(payload.toString()),
            null,
            payload
        );
    }

    private static ConnectorReconciliationRequest reconciliationRequest(
        String idempotencyKey,
        String hash,
        ConnectorOutcome outcome,
        String providerRequestId
    ) {
        return new ConnectorReconciliationRequest(
            "reconciliation-1",
            "trace-1",
            "request-1",
            idempotencyKey,
            hash,
            ConnectorOperation.NOTIFICATION_SEND,
            outcome,
            providerRequestId
        );
    }

    private static DeterministicReconciliationPort<NotificationResponse>
        reconciliationPort(
            List<DeterministicReconciliationPort.Fixture<NotificationResponse>> fixtures
        ) {
        return new DeterministicReconciliationPort<>(
            descriptor(ProviderDescriptor.ProviderState.ENABLED),
            CLOCK,
            fixtures
        );
    }

    private static DeterministicCredentialResolver credentialResolver(byte[] fixture) {
        return new DeterministicCredentialResolver(
            TENANT_ID,
            CREDENTIAL_REFERENCE,
            "key-1",
            fixture
        );
    }

    private static TrustedConnectorExecutionContext context() {
        return new TrustedConnectorExecutionContext(
            TENANT_ID,
            PROVIDER_KEY,
            CREDENTIAL_REFERENCE,
            NOW
        );
    }

    private static String payloadHash() {
        return CanonicalPayloadHash.sha256Utf8("payload");
    }

    private record NotificationCommand(String messageId) {
    }

    private record NotificationResponse(String acknowledgement) {
    }

    private record DirectoryQuery(String keyword) {
    }

    private record DirectoryResult(int matches) {
    }

    private record SuccessfulExecutionPort<P, R>(
        ProviderDescriptor descriptor,
        Function<P, R> responseFactory
    ) implements ConnectorExecutionPort<P, R> {

        @Override
        public ConnectorResult<R> execute(
            TrustedConnectorExecutionContext context,
            ConnectorRequest<P> request
        ) {
            return ConnectorResult.success(
                responseFactory.apply(request.payload()),
                new ConnectorProviderResult(
                    "provider-request-1",
                    200,
                    context.requestedAt(),
                    Map.of("adapter", "test")
                ),
                new IdempotencyEvidence(
                    request.idempotencyKey(),
                    request.canonicalPayloadHash(),
                    IdempotencyEvidence.IdempotencyResult.FIRST_SEEN
                ),
                request.securityEvidence()
            );
        }
    }
}
