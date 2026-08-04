package io.github.akaryc1b.approval.ai.openai;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialEnvironment;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialRequest;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialType;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialVersion;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class OpenAiResponsesSecureHttpSenderTestSupport {

    static final Instant NOW = Instant.parse("2026-08-04T01:45:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private OpenAiResponsesSecureHttpSenderTestSupport() {
    }

    static Fixture fixture(
        int statusCode,
        String requestId,
        String responseBody
    ) throws Exception {
        CredentialMaterialRequest credentialRequest = credentialRequest();
        MutableEnvironment environment = new MutableEnvironment("sk-test-value", "key-v1");
        AtomicLong ordinals = new AtomicLong();
        OpenAiEnvironmentCredentialMaterialSource source =
            new OpenAiEnvironmentCredentialMaterialSource(
                credentialRequest,
                environment,
                CLOCK,
                ordinals::incrementAndGet
            );
        AtomicReference<OpenAiResponsesTransportControls.KillSwitchSnapshot> killSwitch =
            new AtomicReference<>(killSwitch(true, 7));
        OpenAiResponsesTransportAdmission admission = admission(
            credentialRequest.tenantHash(),
            killSwitch
        );
        FakeSecureNetwork network = new FakeSecureNetwork(
            statusCode,
            requestId,
            responseBody.getBytes(StandardCharsets.UTF_8)
        );
        OpenAiResponsesSecureHttpSender sender = new OpenAiResponsesSecureHttpSender(
            OpenAiResponsesEndpointPolicy.exact(),
            source,
            credentialRequest,
            admission,
            network,
            () -> "client-request-0001",
            CLOCK
        );
        return new Fixture(sender, network, environment, killSwitch);
    }

    private static OpenAiResponsesTransportAdmission admission(
        String tenantHash,
        AtomicReference<OpenAiResponsesTransportControls.KillSwitchSnapshot> killSwitch
    ) {
        OpenAiResponsesTransportControls.KillSwitchSnapshot snapshot = killSwitch.get();
        return new OpenAiResponsesTransportAdmission(
            tenantHash,
            killSwitch::get,
            snapshot.generation(),
            snapshot.evidenceHash(),
            new OpenAiResponsesTransportControls.CircuitBreaker(
                3,
                Duration.ofSeconds(30)
            ),
            new OpenAiResponsesTransportControls.RateLimiter(
                10,
                100,
                100,
                Duration.ofMinutes(1)
            ),
            new OpenAiResponsesTransportControls.CostPolicy(
                "pricing-v1",
                OpenAiResponsesProtocol.MODEL_SNAPSHOT,
                1,
                2,
                1_000_000,
                NOW.minusSeconds(60),
                NOW.plusSeconds(600)
            ),
            CLOCK
        );
    }

    static OpenAiResponsesTransportControls.KillSwitchSnapshot killSwitch(
        boolean enabled,
        long generation
    ) {
        return new OpenAiResponsesTransportControls.KillSwitchSnapshot(
            OpenAiResponsesProtocol.PROVIDER_ID,
            OpenAiResponsesProtocol.PROVIDER_VERSION,
            generation,
            enabled,
            "kill-policy-v1"
        );
    }

    static OpenAiResponsesTransportPort.Request request() {
        byte[] body = requestBody().getBytes(StandardCharsets.UTF_8);
        return new OpenAiResponsesTransportPort.Request(
            body,
            OpenAiResponsesProtocol.sha256(body),
            Duration.ofSeconds(2),
            Duration.ofSeconds(10)
        );
    }

    static String requestBody() {
        return "{"
            + "\"background\":false,"
            + "\"input\":[{\"role\":\"user\",\"content\":[{"
            + "\"type\":\"input_text\",\"text\":\"{}\"}]}],"
            + "\"instructions\":\"bounded\","
            + "\"max_output_tokens\":100,"
            + "\"model\":\"" + OpenAiResponsesProtocol.MODEL_SNAPSHOT + "\","
            + "\"store\":false,"
            + "\"stream\":false,"
            + "\"text\":{\"format\":{"
            + "\"type\":\"json_schema\","
            + "\"name\":\"" + OpenAiResponsesProtocol.RESPONSE_FORMAT_NAME + "\","
            + "\"strict\":true,\"schema\":{}}},"
            + "\"tool_choice\":\"none\","
            + "\"tools\":[],"
            + "\"truncation\":\"disabled\""
            + "}";
    }

    private static CredentialMaterialRequest credentialRequest() {
        return new CredentialMaterialRequest(
            new CredentialReference(
                OpenAiEnvironmentCredentialMaterialSource.PROVIDER_KEY,
                OpenAiEnvironmentCredentialMaterialSource.CREDENTIAL_REFERENCE_ID
            ),
            "tenant-a",
            OpenAiEnvironmentCredentialMaterialSource.PROVIDER_KEY,
            hash("route-plan"),
            hash("credential-binding"),
            new CredentialMaterialVersion(
                "key-v1",
                NOW.minusSeconds(60),
                NOW.plusSeconds(600),
                hash("key-v1")
            ),
            CredentialMaterialType.API_KEY,
            ConnectorOperation.AI_ADVISORY_GENERATE,
            OpenAiEnvironmentCredentialMaterialSource.PROTOCOL_PROFILE,
            OpenAiEnvironmentCredentialMaterialSource.CAPABILITY,
            CredentialMaterialEnvironment.PRODUCTION,
            "m6-e-p6-openai-secret-v1"
        );
    }

    static void assertFailure(
        Fixture fixture,
        OpenAiResponsesTransportException.Failure expected
    ) {
        OpenAiResponsesTransportException failure = assertThrows(
            OpenAiResponsesTransportException.class,
            () -> fixture.sender.exchange(request())
        );
        assertEquals(expected, failure.failure());
        assertFalse(failure.toString().contains("sk-test-value"));
    }

    static boolean allZero(byte[] value) {
        return value != null && value.length > 0
            && Arrays.stream(toIntegers(value)).allMatch(number -> number == 0);
    }

    private static int[] toIntegers(byte[] value) {
        int[] output = new int[value.length];
        for (int index = 0; index < value.length; index++) {
            output[index] = value[index];
        }
        return output;
    }

    private static String hash(String value) {
        return CanonicalPayloadHash.sha256Utf8(value);
    }

    record Fixture(
        OpenAiResponsesSecureHttpSender sender,
        FakeSecureNetwork network,
        MutableEnvironment environment,
        AtomicReference<OpenAiResponsesTransportControls.KillSwitchSnapshot> killSwitch
    ) {
    }

    static final class MutableEnvironment
        implements OpenAiEnvironmentCredentialMaterialSource.EnvironmentVariableReader {

        volatile String secret;
        private final String version;
        final AtomicInteger secretReads = new AtomicInteger();
        final AtomicInteger versionReads = new AtomicInteger();

        private MutableEnvironment(String secret, String version) {
            this.secret = secret;
            this.version = version;
        }

        @Override
        public char[] readSecret(String variableName) {
            assertEquals(OpenAiEnvironmentCredentialMaterialSource.SECRET_VARIABLE, variableName);
            secretReads.incrementAndGet();
            return secret == null ? null : secret.toCharArray();
        }

        @Override
        public String readNonSecret(String variableName) {
            assertEquals(OpenAiEnvironmentCredentialMaterialSource.VERSION_VARIABLE, variableName);
            versionReads.incrementAndGet();
            return version;
        }
    }

    static final class FakeSecureNetwork
        implements OpenAiResponsesNetworkSupport.SecureNetwork {

        private final int statusCode;
        private final String requestId;
        private final byte[] responseBody;
        final AtomicInteger resolveCount = new AtomicInteger();
        final AtomicInteger connectCount = new AtomicInteger();
        final AtomicInteger exchangeCount = new AtomicInteger();
        volatile boolean unsafeResolution;
        volatile Instant resolvedAt = NOW;
        volatile Runnable afterConnect = () -> { };
        volatile byte[] lastSecret;

        private FakeSecureNetwork(
            int statusCode,
            String requestId,
            byte[] responseBody
        ) {
            this.statusCode = statusCode;
            this.requestId = requestId;
            this.responseBody = Arrays.copyOf(responseBody, responseBody.length);
        }

        @Override
        public OpenAiResponsesNetworkSupport.Resolution resolve(
            OpenAiResponsesEndpointPolicy endpoint,
            OpenAiResponsesTransportPort.Request request,
            OpenAiResponsesNetworkSupport.Deadline deadline
        ) {
            resolveCount.incrementAndGet();
            try {
                InetAddress address = InetAddress.getByName(
                    unsafeResolution ? "127.0.0.1" : "8.8.8.8"
                );
                return new OpenAiResponsesNetworkSupport.Resolution(
                    endpoint.endpointHash(),
                    List.of(address),
                    resolvedAt
                );
            } catch (java.net.UnknownHostException failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        public OpenAiResponsesNetworkSupport.SecureChannel connect(
            OpenAiResponsesEndpointPolicy endpoint,
            OpenAiResponsesNetworkSupport.Resolution resolution,
            OpenAiResponsesTransportPort.Request request,
            OpenAiResponsesNetworkSupport.Deadline deadline
        ) {
            connectCount.incrementAndGet();
            afterConnect.run();
            String connectedAddressHash = resolution.addressHashes().get(0);
            return new OpenAiResponsesNetworkSupport.SecureChannel() {
                @Override
                public OpenAiResponsesNetworkSupport.ExchangeResult exchange(
                    OpenAiResponsesTransportPort.Request transportRequest,
                    byte[] secret,
                    String clientRequestId,
                    OpenAiResponsesNetworkSupport.Deadline transportDeadline
                ) {
                    exchangeCount.incrementAndGet();
                    lastSecret = secret;
                    return new OpenAiResponsesNetworkSupport.ExchangeResult(
                        statusCode,
                        requestId,
                        responseBody,
                        OpenAiResponsesProtocol.sha256Utf8(clientRequestId)
                    );
                }

                @Override
                public String connectedAddressHash() {
                    return connectedAddressHash;
                }

                @Override
                public String tlsPeerHash() {
                    return hash("tls-peer");
                }

                @Override
                public boolean tlsVerified() {
                    return true;
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
