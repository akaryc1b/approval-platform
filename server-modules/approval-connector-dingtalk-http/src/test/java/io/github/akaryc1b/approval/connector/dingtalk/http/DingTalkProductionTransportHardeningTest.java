package io.github.akaryc1b.approval.connector.dingtalk.http;

import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingDescriptor;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingState;
import io.github.akaryc1b.approval.connector.dingtalk.DingTalkTransportResponse;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

import static io.github.akaryc1b.approval.connector.dingtalk.http.DingTalkProductionTransportTestFixtures.NOW;
import static io.github.akaryc1b.approval.connector.dingtalk.http.DingTalkProductionTransportTestFixtures.TOKEN_TEXT;
import static io.github.akaryc1b.approval.connector.dingtalk.http.DingTalkProductionTransportTestFixtures.context;
import static io.github.akaryc1b.approval.connector.dingtalk.http.DingTalkProductionTransportTestFixtures.descriptor;
import static io.github.akaryc1b.approval.connector.dingtalk.http.DingTalkProductionTransportTestFixtures.legacyRequest;
import static io.github.akaryc1b.approval.connector.dingtalk.http.DingTalkProductionTransportTestFixtures.openRequest;
import static io.github.akaryc1b.approval.connector.dingtalk.http.DingTalkProductionTransportTestFixtures.transport;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DingTalkProductionTransportHardeningTest {

    @Test
    void specialPurposeIpv6AddressesAreRejectedBeforeCredentialResolution() throws Exception {
        for (byte[] address : new byte[][] {
            ipv6(0x2001, 0x0002, 0, 0, 0, 0, 0, 1),
            ipv6(0x2002, 0x0a00, 0x0001, 0, 0, 0, 0, 1),
            ipv6(0x3ffe, 0, 0, 0, 0, 0, 0, 1),
            ipv6(0x3fff, 0, 0, 0, 0, 0, 0, 1)
        }) {
            CredentialBindingDescriptor binding = descriptor(CredentialBindingState.ACTIVE);
            var source = new DingTalkProductionTransportTestFixtures.FixtureMaterialSource(
                TOKEN_TEXT.getBytes(StandardCharsets.US_ASCII)
            );
            var sender = new DingTalkProductionTransportTestFixtures.RecordingSender();
            DingTalkEndpointPolicy policy = policy(address);
            DingTalkProductionTransport production = transport(binding, source, sender, policy);

            assertThrows(
                DingTalkTransportPolicyException.class,
                () -> production.exchange(
                    context(),
                    ConnectorOperation.ORGANIZATION_READ,
                    openRequest()
                )
            );
            assertEquals(0, source.openCount);
            assertEquals(0, source.secretUseCount);
            assertEquals(0, sender.invocationCount);
        }
    }

    @Test
    void globalUnicastIpv6AddressRemainsAllowed() throws Exception {
        CredentialBindingDescriptor binding = descriptor(CredentialBindingState.ACTIVE);
        var source = new DingTalkProductionTransportTestFixtures.FixtureMaterialSource(
            TOKEN_TEXT.getBytes(StandardCharsets.US_ASCII)
        );
        var sender = new DingTalkProductionTransportTestFixtures.RecordingSender();
        DingTalkEndpointPolicy policy = policy(
            ipv6(0x2001, 0x4860, 0x4860, 0, 0, 0, 0, 0x8888)
        );
        DingTalkProductionTransport production = transport(binding, source, sender, policy);

        DingTalkTransportResponse response = production.exchange(
            context(),
            ConnectorOperation.ORGANIZATION_READ,
            openRequest()
        );

        assertEquals(DingTalkTransportResponse.State.RESPONDED, response.state());
        assertEquals(1, sender.invocationCount);
        assertEquals(1, source.secretUseCount);
        assertEquals(1, source.closeCount);
        assertTrue(source.materialZeroized);
    }

    @Test
    void headerCredentialEchoIsRemovedFromProviderRequestMetadata() throws Exception {
        CredentialBindingDescriptor binding = descriptor(CredentialBindingState.ACTIVE);
        var source = new DingTalkProductionTransportTestFixtures.FixtureMaterialSource(
            TOKEN_TEXT.getBytes(StandardCharsets.US_ASCII)
        );
        var sender = new DingTalkProductionTransportTestFixtures.RecordingSender();
        sender.response = DingTalkTransportResponse.responded(
            200,
            "request-" + TOKEN_TEXT + "-echo",
            "{}",
            NOW
        );
        DingTalkProductionTransport production = transport(
            binding,
            source,
            sender,
            policy(ipv6(0x2001, 0x4860, 0x4860, 0, 0, 0, 0, 0x8888))
        );

        DingTalkTransportResponse response = production.exchange(
            context(),
            ConnectorOperation.ORGANIZATION_READ,
            openRequest()
        );

        assertNull(response.providerRequestId());
        assertEquals(1, sender.invocationCount);
        assertTrue(source.materialZeroized);
    }

    @Test
    void percentEncodedCredentialEchoIsRemovedCaseInsensitively() throws Exception {
        CredentialBindingDescriptor binding = descriptor(CredentialBindingState.ACTIVE);
        var source = new DingTalkProductionTransportTestFixtures.FixtureMaterialSource(
            TOKEN_TEXT.getBytes(StandardCharsets.US_ASCII)
        );
        var sender = new DingTalkProductionTransportTestFixtures.RecordingSender();
        sender.response = DingTalkTransportResponse.responded(
            200,
            "request-test-token%2bone-echo",
            "{}",
            NOW
        );
        DingTalkProductionTransport production = transport(
            binding,
            source,
            sender,
            policy(ipv6(0x2001, 0x4860, 0x4860, 0, 0, 0, 0, 0x8888))
        );

        DingTalkTransportResponse response = production.exchange(
            context(),
            ConnectorOperation.IDENTITY_RESOLVE,
            legacyRequest()
        );

        assertNull(response.providerRequestId());
        assertEquals(1, sender.invocationCount);
        assertTrue(source.materialZeroized);
    }

    private static DingTalkEndpointPolicy policy(byte[] address) throws Exception {
        return new DingTalkEndpointPolicy(host -> new InetAddress[] {
            InetAddress.getByAddress(host, address)
        });
    }

    private static byte[] ipv6(int... segments) {
        if (segments.length != 8) {
            throw new IllegalArgumentException("IPv6 test address requires eight segments");
        }
        byte[] value = new byte[16];
        for (int index = 0; index < segments.length; index++) {
            int segment = segments[index];
            if (segment < 0 || segment > 0xffff) {
                throw new IllegalArgumentException("IPv6 segment is outside unsigned 16-bit range");
            }
            value[index * 2] = (byte) (segment >>> 8);
            value[index * 2 + 1] = (byte) segment;
        }
        return value;
    }
}
