package io.github.akaryc1b.approval.connector.dingtalk.http;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorCredentialResolver.SecretBytesUse;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;
import io.github.akaryc1b.approval.connector.contract.TrustedConnectorExecutionContext;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingCatalog;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingDescriptor;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingState;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialSource;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialType;
import io.github.akaryc1b.approval.connector.credential.DingTalkCredentialProfile;
import io.github.akaryc1b.approval.connector.credential.ServerOwnedCredentialResolver;
import io.github.akaryc1b.approval.connector.dingtalk.DingTalkTransportRequest;
import io.github.akaryc1b.approval.connector.dingtalk.DingTalkTransportResponse;

import java.net.InetAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class DingTalkProductionTransportTestFixtures {

    static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");
    static final CredentialReference REFERENCE = new CredentialReference(
        "dingtalk",
        "credential:test-only-production-transport"
    );
    static final String TOKEN_TEXT = "test-token+one";

    private DingTalkProductionTransportTestFixtures() {
    }

    static DingTalkProductionTransport transport(
        CredentialBindingDescriptor descriptor,
        FixtureMaterialSource source,
        DingTalkHttpSender sender,
        DingTalkEndpointPolicy policy
    ) {
        DingTalkCredentialPlanSource plans = (context, operation) ->
            DingTalkCredentialProfile.plan(descriptor, operation);
        return new DingTalkProductionTransport(
            resolver(descriptor, source),
            plans,
            policy,
            sender,
            clock()
        );
    }

    static ServerOwnedCredentialResolver resolver(
        CredentialBindingDescriptor descriptor,
        CredentialMaterialSource source
    ) {
        CredentialBindingCatalog catalog = reference -> Optional.of(descriptor);
        return new ServerOwnedCredentialResolver(catalog, source, clock());
    }

    static DingTalkEndpointPolicy publicPolicy() throws Exception {
        return new DingTalkEndpointPolicy(host -> new InetAddress[] {
            InetAddress.getByAddress(host, new byte[] {8, 8, 8, 8})
        });
    }

    static CredentialBindingDescriptor descriptor(CredentialBindingState state) {
        return new CredentialBindingDescriptor(
            REFERENCE,
            "tenant-test",
            "dingtalk",
            CredentialMaterialType.ACCESS_TOKEN,
            "key-test",
            "version-1",
            state,
            NOW.minusSeconds(60),
            NOW.plusSeconds(600),
            Set.of(ConnectorOperation.ORGANIZATION_READ, ConnectorOperation.IDENTITY_RESOLVE),
            "policy-1",
            Map.of("ownerClass", "platform-security")
        );
    }

    static TrustedConnectorExecutionContext context() {
        return new TrustedConnectorExecutionContext("tenant-test", "dingtalk", REFERENCE, NOW);
    }

    static DingTalkTransportRequest openRequest() {
        return new DingTalkTransportRequest(
            DingTalkTransportRequest.ApiFamily.OPEN_API_V1,
            DingTalkTransportRequest.HttpMethod.POST,
            "/v1.0/contact/users/search",
            Map.of("Content-Type", "application/json"),
            "{\"queryWord\":\"test\",\"offset\":0,\"size\":1}",
            Duration.ofSeconds(5)
        );
    }

    static DingTalkTransportRequest legacyRequest() {
        return new DingTalkTransportRequest(
            DingTalkTransportRequest.ApiFamily.LEGACY_OAPI,
            DingTalkTransportRequest.HttpMethod.POST,
            "/topapi/v2/user/get",
            Map.of("Content-Type", "application/json"),
            "{\"language\":\"zh_CN\",\"userid\":\"test-user\"}",
            Duration.ofSeconds(5)
        );
    }

    static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    static boolean allZero(byte[] value) {
        return value != null && Arrays.equals(value, new byte[value.length]);
    }

    static final class RecordingSender implements DingTalkHttpSender {

        int invocationCount;
        URI uri;
        Map<String, String> headers;
        DingTalkTransportResponse response = DingTalkTransportResponse.responded(
            200,
            "test-request-id",
            "{}",
            NOW
        );

        @Override
        public DingTalkTransportResponse send(
            URI target,
            Map<String, String> requestHeaders,
            String body,
            Duration timeout
        ) {
            invocationCount++;
            uri = target;
            headers = requestHeaders;
            return response;
        }
    }

    static final class FixtureMaterialSource implements CredentialMaterialSource {

        private final byte[] fixture;
        int openCount;
        int secretUseCount;
        int closeCount;
        boolean materialZeroized;

        FixtureMaterialSource(byte[] fixture) {
            this.fixture = fixture.clone();
        }

        @Override
        public MaterialScope openMaterial(
            CredentialReference reference,
            String expectedKeyId,
            String expectedVersionId
        ) {
            openCount++;
            return new Scope(fixture.clone());
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
                return CanonicalPayloadHash.sha256Utf8("test-production-transport-source");
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
                    materialZeroized = allZero(material);
                    active = false;
                    closeCount++;
                }
            }

            private void requireActive() {
                if (!active) {
                    throw new IllegalStateException("test material scope is closed");
                }
            }
        }
    }
}
