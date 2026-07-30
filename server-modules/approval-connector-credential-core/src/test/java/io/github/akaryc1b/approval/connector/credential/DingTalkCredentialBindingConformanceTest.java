package io.github.akaryc1b.approval.connector.credential;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorCredentialResolver.SecretBytesUse;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;
import io.github.akaryc1b.approval.connector.contract.TrustedConnectorExecutionContext;
import io.github.akaryc1b.approval.connector.dingtalk.DingTalkTransport;
import io.github.akaryc1b.approval.connector.dingtalk.DingTalkTransportRequest;
import io.github.akaryc1b.approval.connector.dingtalk.DingTalkTransportResponse;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DingTalkCredentialBindingConformanceTest {

    private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");
    private static final CredentialReference REFERENCE = new CredentialReference(
        "dingtalk",
        "credential:test-only-dingtalk"
    );

    @Test
    void profileProducesOnlyNonSecretBindingEvidenceForSupportedOperations() {
        CredentialBindingDescriptor descriptor = descriptor(
            REFERENCE,
            "tenant-test",
            CredentialBindingState.ACTIVE,
            NOW.minusSeconds(60),
            NOW.plusSeconds(600)
        );

        CapturedCredentialBindingPlan directory = DingTalkCredentialProfile.plan(
            descriptor,
            ConnectorOperation.ORGANIZATION_READ
        );
        CapturedCredentialBindingPlan identity = DingTalkCredentialProfile.plan(
            descriptor,
            ConnectorOperation.IDENTITY_RESOLVE
        );

        assertEquals(CredentialMaterialType.ACCESS_TOKEN, directory.credentialType());
        assertEquals(CredentialMaterialType.ACCESS_TOKEN, identity.credentialType());
        assertFalse(directory.credentialMaterialPresent());
        assertFalse(directory.absoluteEndpointPresent());
        assertFalse(directory.productionTransportEnabled());
        assertFalse(directory.canonicalJson().contains(REFERENCE.referenceId()));
        assertEquals(directory.planHash(), directory.planHash());
    }

    @Test
    void successfulScopeUseDoesNotPlaceMaterialIntoCapturedRequest() {
        FixtureMaterialSource source = new FixtureMaterialSource();
        CountingTransport transport = new CountingTransport();
        CredentialBindingDescriptor descriptor = descriptor(
            REFERENCE,
            "tenant-test",
            CredentialBindingState.ACTIVE,
            NOW.minusSeconds(60),
            NOW.plusSeconds(600)
        );
        ServerOwnedCredentialResolver resolver = resolver(descriptor, source);

        resolver.useCredential(request(ConnectorOperation.ORGANIZATION_READ), credential -> {
            credential.useSecretBytes(bytes -> assertFalse(allZero(bytes)));
            DingTalkTransportRequest captured = capturedRequest();
            assertFalse(captured.credentialMaterialPresent());
            assertFalse(captured.absoluteEndpointPresent());
            assertTrue(captured.requiresExternalCredentialBinding());
            transport.exchange(captured);
        });

        assertEquals(1, source.secretUseCount);
        assertTrue(source.closed);
        assertEquals(1, transport.invocationCount);
        assertFalse(transport.lastRequest.canonicalRequest().contains(FixtureMaterialSource.FIXTURE_TEXT));
    }

    @Test
    void bindingFailuresPreventTransportInvocation() {
        assertNoTransport(
            descriptor(
                new CredentialReference("feishu", "credential:test-only-feishu"),
                "tenant-test",
                CredentialBindingState.ACTIVE,
                NOW.minusSeconds(60),
                NOW.plusSeconds(600)
            ),
            request(ConnectorOperation.ORGANIZATION_READ),
            CredentialResolutionStatus.PROVIDER_MISMATCH
        );
        assertNoTransport(
            descriptor(
                new CredentialReference("dingtalk", "credential:test-only-other"),
                "tenant-test",
                CredentialBindingState.ACTIVE,
                NOW.minusSeconds(60),
                NOW.plusSeconds(600)
            ),
            request(ConnectorOperation.ORGANIZATION_READ),
            CredentialResolutionStatus.REFERENCE_MISMATCH
        );
        assertNoTransport(
            descriptor(
                REFERENCE,
                "tenant-test",
                CredentialBindingState.REVOKED,
                NOW.minusSeconds(60),
                NOW.plusSeconds(600)
            ),
            request(ConnectorOperation.ORGANIZATION_READ),
            CredentialResolutionStatus.CREDENTIAL_REVOKED
        );
        assertNoTransport(
            descriptor(
                REFERENCE,
                "tenant-test",
                CredentialBindingState.ACTIVE,
                NOW.minusSeconds(600),
                NOW
            ),
            request(ConnectorOperation.ORGANIZATION_READ),
            CredentialResolutionStatus.EXPIRED
        );
        assertNoTransport(
            descriptor(
                REFERENCE,
                "tenant-test",
                CredentialBindingState.ACTIVE,
                NOW.minusSeconds(60),
                NOW.plusSeconds(600)
            ),
            request(ConnectorOperation.NOTIFICATION_SEND),
            CredentialResolutionStatus.OPERATION_NOT_ALLOWED
        );
    }

    @Test
    void profileRejectsUnsupportedOperationBeforeAnyTransportCall() {
        CountingTransport transport = new CountingTransport();
        CredentialBindingDescriptor descriptor = descriptor(
            REFERENCE,
            "tenant-test",
            CredentialBindingState.ACTIVE,
            NOW.minusSeconds(60),
            NOW.plusSeconds(600)
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> DingTalkCredentialProfile.plan(descriptor, ConnectorOperation.NOTIFICATION_SEND)
        );
        assertEquals(0, transport.invocationCount);
    }

    private static void assertNoTransport(
        CredentialBindingDescriptor descriptor,
        CredentialResolutionRequest request,
        CredentialResolutionStatus expected
    ) {
        FixtureMaterialSource source = new FixtureMaterialSource();
        CountingTransport transport = new CountingTransport();
        CredentialResolutionException exception = assertThrows(
            CredentialResolutionException.class,
            () -> resolver(descriptor, source).useCredential(request, credential -> {
                credential.useSecretBytes(bytes -> { });
                transport.exchange(capturedRequest());
            })
        );

        assertEquals(expected, exception.evidence().status());
        assertEquals(0, transport.invocationCount);
        assertEquals(0, source.secretUseCount);
    }

    private static ServerOwnedCredentialResolver resolver(
        CredentialBindingDescriptor descriptor,
        CredentialMaterialSource source
    ) {
        CredentialBindingCatalog catalog = reference -> Optional.of(descriptor);
        return new ServerOwnedCredentialResolver(
            catalog,
            source,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static CredentialResolutionRequest request(ConnectorOperation operation) {
        return new CredentialResolutionRequest(
            new TrustedConnectorExecutionContext("tenant-test", "dingtalk", REFERENCE, NOW),
            operation,
            CredentialMaterialType.ACCESS_TOKEN,
            "key-test",
            "version-1"
        );
    }

    private static CredentialBindingDescriptor descriptor(
        CredentialReference reference,
        String tenantId,
        CredentialBindingState state,
        Instant notBefore,
        Instant expiresAt
    ) {
        return new CredentialBindingDescriptor(
            reference,
            tenantId,
            reference.providerKey(),
            CredentialMaterialType.ACCESS_TOKEN,
            "key-test",
            "version-1",
            state,
            notBefore,
            expiresAt,
            Set.of(ConnectorOperation.IDENTITY_RESOLVE, ConnectorOperation.ORGANIZATION_READ),
            "policy-1",
            Map.of("providerClass", "captured-dingtalk")
        );
    }

    private static DingTalkTransportRequest capturedRequest() {
        return new DingTalkTransportRequest(
            DingTalkTransportRequest.ApiFamily.OPEN_API_V1,
            DingTalkTransportRequest.HttpMethod.POST,
            "/v1.0/contact/users/search",
            Map.of("Content-Type", "application/json"),
            "{\"queryWord\":\"test-user\",\"offset\":0,\"size\":1}",
            Duration.ofSeconds(5)
        );
    }

    private static boolean allZero(byte[] value) {
        return value != null && Arrays.equals(value, new byte[value.length]);
    }

    private static final class CountingTransport implements DingTalkTransport {
        private int invocationCount;
        private DingTalkTransportRequest lastRequest;

        @Override
        public DingTalkTransportResponse exchange(DingTalkTransportRequest request) {
            invocationCount++;
            lastRequest = request;
            return DingTalkTransportResponse.responded(
                200,
                "test-provider-request",
                "{\"code\":0,\"result\":{}}",
                NOW
            );
        }
    }

    private static final class FixtureMaterialSource implements CredentialMaterialSource {
        private static final String FIXTURE_TEXT = "test-material-not-a-real-access-token";
        private static final byte[] FIXTURE = FIXTURE_TEXT.getBytes();
        private int secretUseCount;
        private boolean closed;

        @Override
        public MaterialScope openMaterial(
            CredentialReference reference,
            String expectedKeyId,
            String expectedVersionId
        ) {
            return new Scope(FIXTURE.clone());
        }

        private final class Scope implements MaterialScope {
            private final byte[] material;
            private boolean active = true;

            private Scope(byte[] material) {
                this.material = material;
            }

            @Override
            public String keyId() {
                requireActive();
                return "key-test";
            }

            @Override
            public String versionId() {
                requireActive();
                return "version-1";
            }

            @Override
            public String sourceEvidenceHash() {
                requireActive();
                return CanonicalPayloadHash.sha256Utf8("test-dingtalk-source-version-1");
            }

            @Override
            public void useSecretBytes(SecretBytesUse use) {
                requireActive();
                secretUseCount++;
                byte[] copy = material.clone();
                try {
                    use.accept(copy);
                } finally {
                    Arrays.fill(copy, (byte) 0);
                }
            }

            @Override
            public boolean active() {
                return active;
            }

            @Override
            public void close() {
                if (active) {
                    Arrays.fill(material, (byte) 0);
                    active = false;
                    closed = true;
                }
            }

            private void requireActive() {
                if (!active) {
                    throw new IllegalStateException("material scope is closed");
                }
            }
        }
    }
}
