package io.github.akaryc1b.approval.ai.openai;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialEnvironment;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialRequest;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialType;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialVersion;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class OpenAiResponsesP7FaultTestSupport {

    static final Instant NOW = Instant.parse("2026-08-06T04:00:00Z");
    static final String TENANT_HASH = hash("tenant-a");

    private OpenAiResponsesP7FaultTestSupport() {
    }

    static Fixture fixture() throws Exception {
        return fixture(3, 10, 100, 100, 1_000_000);
    }

    static Fixture fixture(
        int circuitThreshold,
        int tenantLimit,
        int globalLimit,
        int maximumTenants,
        long maximumRequestMicros
    ) throws Exception {
        MutableClock clock = new MutableClock(NOW);
        CredentialMaterialRequest credentialRequest = credentialRequest(
            TENANT_HASH,
            NOW.minusSeconds(60),
            NOW.plusSeconds(600)
        );
        MutableEnvironment environment = new MutableEnvironment("sk-p7-secret", "key-v1");
        AtomicLong ordinals = new AtomicLong();
        OpenAiEnvironmentCredentialMaterialSource source =
            new OpenAiEnvironmentCredentialMaterialSource(
                credentialRequest,
                environment,
                clock,
                ordinals::incrementAndGet
            );
        AtomicReference<OpenAiResponsesTransportControls.KillSwitchSnapshot> killSwitch =
            new AtomicReference<>(killSwitch(true, 7));
        OpenAiResponsesTransportControls.CircuitBreaker circuit =
            new OpenAiResponsesTransportControls.CircuitBreaker(
                circuitThreshold,
                Duration.ofSeconds(30)
            );
        OpenAiResponsesTransportControls.RateLimiter rate =
            new OpenAiResponsesTransportControls.RateLimiter(
                tenantLimit,
                globalLimit,
                maximumTenants,
                Duration.ofMinutes(1)
            );
        OpenAiResponsesTransportControls.CostPolicy cost = costPolicy(
            maximumRequestMicros,
            NOW.minusSeconds(60),
            NOW.plusSeconds(600)
        );
        OpenAiResponsesRuntimeUsageLedger usage = new OpenAiResponsesRuntimeUsageLedger(
            tenantLimit,
            globalLimit,
            maximumTenants,
            Duration.ofMinutes(1),
            maximumRequestMicros
        );
        OpenAiResponsesTransportAdmission admission =
            new OpenAiResponsesTransportAdmission(
                TENANT_HASH,
                killSwitch::get,
                7,
                killSwitch.get().evidenceHash(),
                circuit,
                rate,
                cost,
                usage,
                clock
            );
        FaultNetwork network = new FaultNetwork();
        OpenAiResponsesSecureHttpSender sender = new OpenAiResponsesSecureHttpSender(
            OpenAiResponsesEndpointPolicy.exact(),
            source,
            credentialRequest,
            admission,
            network,
            () -> "p7-client-request-0001",
            clock
        );
        return new Fixture(
            sender,
            admission,
            network,
            environment,
            killSwitch,
            circuit,
            rate,
            usage,
            clock,
            credentialRequest
        );
    }

    static CredentialMaterialRequest credentialRequest(
        String tenantHash,
        Instant effectiveFrom,
        Instant expiresAt
    ) {
        return new CredentialMaterialRequest(
            new CredentialReference(
                OpenAiEnvironmentCredentialMaterialSource.PROVIDER_KEY,
                OpenAiEnvironmentCredentialMaterialSource.CREDENTIAL_REFERENCE_ID
            ),
            tenantHash,
            OpenAiEnvironmentCredentialMaterialSource.PROVIDER_KEY,
            hash("p7-route-plan"),
            hash("p7-credential-binding"),
            new CredentialMaterialVersion(
                "key-v1",
                effectiveFrom,
                expiresAt,
                hash("key-v1")
            ),
            CredentialMaterialType.API_KEY,
            ConnectorOperation.AI_ADVISORY_GENERATE,
            OpenAiEnvironmentCredentialMaterialSource.PROTOCOL_PROFILE,
            OpenAiEnvironmentCredentialMaterialSource.CAPABILITY,
            CredentialMaterialEnvironment.PRODUCTION,
            "m6-f-p7-openai-secret-v1"
        );
    }

    static OpenAiResponsesTransportControls.CostPolicy costPolicy(
        long maximumRequestMicros,
        Instant effectiveFrom,
        Instant expiresAt
    ) {
        return new OpenAiResponsesTransportControls.CostPolicy(
            "p7-pricing-v1",
            OpenAiResponsesProtocol.MODEL_SNAPSHOT,
            1,
            2,
            maximumRequestMicros,
            effectiveFrom,
            expiresAt
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
            "p7-kill-policy-v1"
        );
    }

    static String hash(String value) {
        return CanonicalPayloadHash.sha256Utf8(value);
    }

    record Fixture(
        OpenAiResponsesSecureHttpSender sender,
        OpenAiResponsesTransportAdmission admission,
        FaultNetwork network,
        MutableEnvironment environment,
        AtomicReference<OpenAiResponsesTransportControls.KillSwitchSnapshot> killSwitch,
        OpenAiResponsesTransportControls.CircuitBreaker circuit,
        OpenAiResponsesTransportControls.RateLimiter rate,
        OpenAiResponsesRuntimeUsageLedger usage,
        MutableClock clock,
        CredentialMaterialRequest credentialRequest
    ) {
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        MutableClock(Instant initial) {
            this.instant = new AtomicReference<>(Objects.requireNonNull(initial));
        }

        void set(Instant value) {
            instant.set(Objects.requireNonNull(value));
        }

        void advance(Duration duration) {
            instant.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("P7 fault clock is UTC-only");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }

    static final class MutableEnvironment
        implements OpenAiEnvironmentCredentialMaterialSource.EnvironmentVariableReader {

        volatile String secret;
        volatile String version;
        final AtomicInteger secretReads = new AtomicInteger();
        final AtomicInteger versionReads = new AtomicInteger();

        MutableEnvironment(String secret, String version) {
            this.secret = secret;
            this.version = version;
        }

        @Override
        public char[] readSecret(String variableName) {
            secretReads.incrementAndGet();
            return secret == null ? null : secret.toCharArray();
        }

        @Override
        public String readNonSecret(String variableName) {
            versionReads.incrementAndGet();
            return version;
        }
    }

    static final class FaultNetwork implements OpenAiResponsesNetworkSupport.SecureNetwork {
        final AtomicInteger resolveCount = new AtomicInteger();
        final AtomicInteger connectCount = new AtomicInteger();
        final AtomicInteger exchangeCount = new AtomicInteger();
        volatile OpenAiResponsesTransportException.Failure resolveFailure;
        volatile OpenAiResponsesTransportException.Failure connectFailure;
        volatile OpenAiResponsesTransportException.Failure exchangeFailure;
        volatile boolean tlsVerified = true;
        volatile boolean connectedAddressDrift;
        volatile Instant resolvedAt = NOW;
        volatile int statusCode = 200;
        volatile byte[] responseBody = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        volatile byte[] lastSecret;

        @Override
        public OpenAiResponsesNetworkSupport.Resolution resolve(
            OpenAiResponsesEndpointPolicy endpoint,
            OpenAiResponsesTransportPort.Request request,
            OpenAiResponsesNetworkSupport.Deadline deadline
        ) {
            resolveCount.incrementAndGet();
            if (resolveFailure != null) {
                throw new OpenAiResponsesTransportException(resolveFailure);
            }
            try {
                return new OpenAiResponsesNetworkSupport.Resolution(
                    endpoint.endpointHash(),
                    List.of(InetAddress.getByName("8.8.8.8")),
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
            if (connectFailure != null) {
                throw new OpenAiResponsesTransportException(connectFailure);
            }
            String exactAddressHash = resolution.addressHashes().getFirst();
            String connectedHash = connectedAddressDrift
                ? hash("different-address")
                : exactAddressHash;
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
                    if (exchangeFailure != null) {
                        throw new OpenAiResponsesTransportException(exchangeFailure);
                    }
                    return new OpenAiResponsesNetworkSupport.ExchangeResult(
                        statusCode,
                        "p7-provider-request-id",
                        Arrays.copyOf(responseBody, responseBody.length),
                        OpenAiResponsesProtocol.sha256Utf8(clientRequestId)
                    );
                }

                @Override
                public String connectedAddressHash() {
                    return connectedHash;
                }

                @Override
                public String tlsPeerHash() {
                    return hash("p7-tls-peer");
                }

                @Override
                public boolean tlsVerified() {
                    return tlsVerified;
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
