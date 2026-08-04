package io.github.akaryc1b.approval.ai.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesSecureHttpSenderTestSupport.Fixture;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesSecureHttpSenderTestSupport.NOW;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesSecureHttpSenderTestSupport.assertFailure;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesSecureHttpSenderTestSupport.fixture;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesSecureHttpSenderTestSupport.requestBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiResponsesSecureHttpSenderSecurityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void requestProfileDriftFailsBeforeAdmissionDnsOrSecret() throws Exception {
        Fixture fixture = fixture(200, "req_unused", "{}");
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        OpenAiResponsesTransportPort.Request invalid =
            new OpenAiResponsesTransportPort.Request(
                body,
                OpenAiResponsesProtocol.sha256(body),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10)
            );

        OpenAiResponsesTransportException failure = assertThrows(
            OpenAiResponsesTransportException.class,
            () -> fixture.sender().exchange(invalid)
        );
        assertEquals(
            OpenAiResponsesTransportException.Failure.REQUEST_INVALID,
            failure.failure()
        );
        assertEquals(0, fixture.network().resolveCount.get());
        assertEquals(0, fixture.environment().secretReads.get());
    }

    @Test
    void futureOrStaleDnsEvidenceFailsClosedBeforeTlsAndSecret() throws Exception {
        Fixture future = fixture(200, "req_unused", "{}");
        future.network().resolvedAt = NOW.plusSeconds(1);
        assertFailure(
            future,
            OpenAiResponsesTransportException.Failure.DNS_DRIFT
        );
        assertEquals(0, future.network().connectCount.get());
        assertEquals(0, future.environment().secretReads.get());

        Fixture stale = fixture(200, "req_unused", "{}");
        stale.network().resolvedAt = NOW.minusSeconds(31);
        assertFailure(
            stale,
            OpenAiResponsesTransportException.Failure.DNS_DRIFT
        );
        assertEquals(0, stale.network().connectCount.get());
        assertEquals(0, stale.environment().secretReads.get());
    }

    @Test
    void malformedApiKeyBytesCannotReachTheHttpHeader() {
        for (byte[] value : List.of(
            new byte[] {'s', 'k', '\r', 'x'},
            new byte[] {'s', 'k', '\n', 'x'},
            new byte[] {'s', 'k', 0, 'x'}
        )) {
            OpenAiResponsesTransportException failure = assertThrows(
                OpenAiResponsesTransportException.class,
                () -> OpenAiResponsesHttpCodec.requireApiKey(value)
            );
            assertEquals(
                OpenAiResponsesTransportException.Failure.SECRET_UNAVAILABLE,
                failure.failure()
            );
        }
    }

    @Test
    void nonTextOrNonStrictRequestProfileFailsBeforeNetwork() throws Exception {
        Fixture fixture = fixture(200, "req_unused", "{}");
        byte[] body = requestBody()
            .replace("\"input_text\"", "\"input_image\"")
            .getBytes(StandardCharsets.UTF_8);
        assertRequestRejectedBeforeEgress(fixture, body);
    }

    @Test
    void arbitraryOutputSchemaFailsBeforeAdmissionDnsOrSecret() throws Exception {
        Fixture fixture = fixture(200, "req_unused", "{}");
        ObjectNode root = (ObjectNode) MAPPER.readTree(requestBody());
        ObjectNode format = (ObjectNode) root.path("text").path("format");
        format.set("schema", MAPPER.createObjectNode());

        assertRequestRejectedBeforeEgress(
            fixture,
            MAPPER.writeValueAsBytes(root)
        );
    }

    @Test
    void embeddedPayloadDriftFailsBeforeAdmissionDnsOrSecret() throws Exception {
        Fixture unknown = fixture(200, "req_unused", "{}");
        ObjectNode unknownRoot = (ObjectNode) MAPPER.readTree(requestBody());
        ObjectNode unknownContent = (ObjectNode) unknownRoot.path("input")
            .path(0).path("content").path(0);
        ObjectNode unknownPayload = (ObjectNode) MAPPER.readTree(
            unknownContent.path("text").asText()
        );
        unknownPayload.put("unexpected", "forbidden");
        unknownContent.put("text", MAPPER.writeValueAsString(unknownPayload));
        assertRequestRejectedBeforeEgress(
            unknown,
            MAPPER.writeValueAsBytes(unknownRoot)
        );

        Fixture versionDrift = fixture(200, "req_unused", "{}");
        ObjectNode driftRoot = (ObjectNode) MAPPER.readTree(requestBody());
        ObjectNode driftContent = (ObjectNode) driftRoot.path("input")
            .path(0).path("content").path(0);
        ObjectNode driftPayload = (ObjectNode) MAPPER.readTree(
            driftContent.path("text").asText()
        );
        ((ObjectNode) driftPayload.path("output_schema"))
            .put("id", "forbidden-schema");
        driftContent.put("text", MAPPER.writeValueAsString(driftPayload));
        assertRequestRejectedBeforeEgress(
            versionDrift,
            MAPPER.writeValueAsBytes(driftRoot)
        );
    }

    @Test
    void specialPurposeAddressClassesAreRejected() throws Exception {
        for (String address : List.of(
            "0.0.0.0",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "192.0.2.1",
            "192.168.1.1",
            "198.18.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "::1",
            "fc00::1",
            "fe80::1",
            "2001::1",
            "2001:2::1",
            "2001:10::1",
            "2001:20::1",
            "2001:db8::1",
            "2002::1"
        )) {
            assertFalse(OpenAiResponsesNetworkSupport.isPublicAddress(
                InetAddress.getByName(address)
            ));
        }
        assertTrue(OpenAiResponsesNetworkSupport.isPublicAddress(
            InetAddress.getByName("8.8.8.8")
        ));
        assertTrue(OpenAiResponsesNetworkSupport.isPublicAddress(
            InetAddress.getByName("2606:4700:4700::1111")
        ));
    }

    private static void assertRequestRejectedBeforeEgress(
        Fixture fixture,
        byte[] body
    ) {
        OpenAiResponsesTransportPort.Request invalid =
            new OpenAiResponsesTransportPort.Request(
                body,
                OpenAiResponsesProtocol.sha256(body),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10)
            );
        OpenAiResponsesTransportException failure = assertThrows(
            OpenAiResponsesTransportException.class,
            () -> fixture.sender().exchange(invalid)
        );
        assertEquals(
            OpenAiResponsesTransportException.Failure.REQUEST_INVALID,
            failure.failure()
        );
        assertEquals(0, fixture.network().resolveCount.get());
        assertEquals(0, fixture.network().connectCount.get());
        assertEquals(0, fixture.environment().secretReads.get());
    }
}
