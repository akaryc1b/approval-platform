package io.github.akaryc1b.approval.connector.dingtalk.http;

import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingDescriptor;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingState;
import io.github.akaryc1b.approval.connector.dingtalk.DingTalkTransport;
import io.github.akaryc1b.approval.connector.dingtalk.DingTalkTransportRequest;
import io.github.akaryc1b.approval.connector.dingtalk.DingTalkTransportResponse;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Map;

import static io.github.akaryc1b.approval.connector.dingtalk.http.DingTalkProductionTransportTestFixtures.TOKEN_TEXT;
import static io.github.akaryc1b.approval.connector.dingtalk.http.DingTalkProductionTransportTestFixtures.context;
import static io.github.akaryc1b.approval.connector.dingtalk.http.DingTalkProductionTransportTestFixtures.descriptor;
import static io.github.akaryc1b.approval.connector.dingtalk.http.DingTalkProductionTransportTestFixtures.legacyRequest;
import static io.github.akaryc1b.approval.connector.dingtalk.http.DingTalkProductionTransportTestFixtures.openRequest;
import static io.github.akaryc1b.approval.connector.dingtalk.http.DingTalkProductionTransportTestFixtures.publicPolicy;
import static io.github.akaryc1b.approval.connector.dingtalk.http.DingTalkProductionTransportTestFixtures.transport;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DingTalkProductionTransportPolicyTest {

    @Test
    void openApiUsesOfficialHostAndHeaderWithinOneCredentialScope() throws Exception {
        CredentialBindingDescriptor descriptor = descriptor(CredentialBindingState.ACTIVE);
        var source = new DingTalkProductionTransportTestFixtures.FixtureMaterialSource(
            TOKEN_TEXT.getBytes()
        );
        var sender = new DingTalkProductionTransportTestFixtures.RecordingSender();
        DingTalkProductionTransport production = transport(
            descriptor,
            source,
            sender,
            publicPolicy()
        );
        DingTalkTransportRequest request = openRequest();

        DingTalkTransportResponse response = production.exchange(
            context(),
            ConnectorOperation.ORGANIZATION_READ,
            request
        );

        assertEquals(DingTalkTransport.TransportMode.PRODUCTION, production.mode());
        assertEquals(DingTalkTransportResponse.State.RESPONDED, response.state());
        assertEquals("api.dingtalk.com", sender.uri.getHost());
        assertEquals("/v1.0/contact/users/search", sender.uri.getPath());
        assertEquals(TOKEN_TEXT, sender.headers.get(DingTalkEndpointPolicy.ACCESS_TOKEN_HEADER));
        assertEquals(1, sender.invocationCount);
        assertEquals(1, source.openCount);
        assertEquals(1, source.secretUseCount);
        assertEquals(1, source.closeCount);
        assertTrue(source.materialZeroized);
        assertFalse(request.canonicalRequest().contains(TOKEN_TEXT));
    }

    @Test
    void legacyOapiUsesOfficialHostAndPercentEncodedQuery() throws Exception {
        CredentialBindingDescriptor descriptor = descriptor(CredentialBindingState.ACTIVE);
        var source = new DingTalkProductionTransportTestFixtures.FixtureMaterialSource(
            TOKEN_TEXT.getBytes()
        );
        var sender = new DingTalkProductionTransportTestFixtures.RecordingSender();
        DingTalkProductionTransport production = transport(
            descriptor,
            source,
            sender,
            publicPolicy()
        );

        production.exchange(context(), ConnectorOperation.IDENTITY_RESOLVE, legacyRequest());

        assertEquals("oapi.dingtalk.com", sender.uri.getHost());
        assertEquals("access_token=test-token%2Bone", sender.uri.getRawQuery());
        assertFalse(sender.headers.containsKey(DingTalkEndpointPolicy.ACCESS_TOKEN_HEADER));
        assertEquals(1, sender.invocationCount);
        assertTrue(source.materialZeroized);
    }

    @Test
    void contextFreeUnlistedAndOperationMismatchedPathsFailBeforeCredentialUse() throws Exception {
        CredentialBindingDescriptor descriptor = descriptor(CredentialBindingState.ACTIVE);
        var source = new DingTalkProductionTransportTestFixtures.FixtureMaterialSource(
            TOKEN_TEXT.getBytes()
        );
        var sender = new DingTalkProductionTransportTestFixtures.RecordingSender();
        DingTalkProductionTransport production = transport(
            descriptor,
            source,
            sender,
            publicPolicy()
        );

        assertThrows(UnsupportedOperationException.class, () -> production.exchange(openRequest()));
        DingTalkTransportRequest unlisted = new DingTalkTransportRequest(
            DingTalkTransportRequest.ApiFamily.OPEN_API_V1,
            DingTalkTransportRequest.HttpMethod.POST,
            "/v1.0/contact/users/unlisted",
            Map.of("Content-Type", "application/json"),
            "{}",
            Duration.ofSeconds(5)
        );
        assertThrows(
            DingTalkTransportPolicyException.class,
            () -> production.exchange(
                context(),
                ConnectorOperation.ORGANIZATION_READ,
                unlisted
            )
        );
        assertThrows(
            DingTalkTransportPolicyException.class,
            () -> production.exchange(
                context(),
                ConnectorOperation.IDENTITY_RESOLVE,
                openRequest()
            )
        );

        assertEquals(0, sender.invocationCount);
        assertEquals(0, source.openCount);
        assertEquals(0, source.secretUseCount);
    }

    @Test
    void privateAndDocumentationAddressesAreRejectedBeforeSecretResolution() throws Exception {
        for (byte[] address : new byte[][] {
            new byte[] {10, 0, 0, 1},
            new byte[] {(byte) 192, 0, 2, 1}
        }) {
            CredentialBindingDescriptor descriptor = descriptor(CredentialBindingState.ACTIVE);
            var source = new DingTalkProductionTransportTestFixtures.FixtureMaterialSource(
                TOKEN_TEXT.getBytes()
            );
            var sender = new DingTalkProductionTransportTestFixtures.RecordingSender();
            DingTalkEndpointPolicy policy = new DingTalkEndpointPolicy(host -> new InetAddress[] {
                InetAddress.getByAddress(host, address)
            });
            DingTalkProductionTransport production = transport(
                descriptor,
                source,
                sender,
                policy
            );

            assertThrows(
                DingTalkTransportPolicyException.class,
                () -> production.exchange(
                    context(),
                    ConnectorOperation.ORGANIZATION_READ,
                    openRequest()
                )
            );
            assertEquals(0, source.openCount);
            assertEquals(0, sender.invocationCount);
        }
    }

    @Test
    void dnsFailureReturnsUnknownWithoutOpeningCredential() {
        CredentialBindingDescriptor descriptor = descriptor(CredentialBindingState.ACTIVE);
        var source = new DingTalkProductionTransportTestFixtures.FixtureMaterialSource(
            TOKEN_TEXT.getBytes()
        );
        var sender = new DingTalkProductionTransportTestFixtures.RecordingSender();
        DingTalkEndpointPolicy policy = new DingTalkEndpointPolicy(host -> {
            throw new UnknownHostException("test DNS unavailable");
        });
        DingTalkProductionTransport production = transport(descriptor, source, sender, policy);

        DingTalkTransportResponse response = production.exchange(
            context(),
            ConnectorOperation.ORGANIZATION_READ,
            openRequest()
        );

        assertEquals(DingTalkTransportResponse.State.UNKNOWN, response.state());
        assertEquals(0, source.openCount);
        assertEquals(0, sender.invocationCount);
    }
}
