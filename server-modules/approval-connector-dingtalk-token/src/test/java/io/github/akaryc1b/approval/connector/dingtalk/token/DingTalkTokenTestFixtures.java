package io.github.akaryc1b.approval.connector.dingtalk.token;

import io.github.akaryc1b.approval.connector.ConnectorProvider;
import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingCatalog;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingDescriptor;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingState;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialDescriptor;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialEnvironment;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialLease;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialRequest;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialSource;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialType;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialVersion;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts
    .ProviderApiFamily;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteDefinition;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteIntent;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RoutePlan;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteRequest;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.TransportProfile;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class DingTalkTokenTestFixtures {

    static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");
    static final String TENANT = "tenant-a";

    private DingTalkTokenTestFixtures() {
    }

    static Fixture fixture() {
        return fixture(TENANT, "app-version-1", CredentialBindingState.ACTIVE);
    }

    static Fixture fixture(String tenant, String version, CredentialBindingState state) {
        CredentialReference routeReference = new CredentialReference(
            "dingtalk",
            "route-access-reference-" + tenant
        );
        CredentialReference applicationReference = new CredentialReference(
            "dingtalk",
            "application-reference-" + tenant
        );
        CredentialBindingDescriptor routeDescriptor = new CredentialBindingDescriptor(
            routeReference,
            tenant,
            "dingtalk",
            CredentialMaterialType.ACCESS_TOKEN,
            "route-key",
            "route-version-1",
            CredentialBindingState.ACTIVE,
            NOW.minusSeconds(60),
            NOW.plusSeconds(3_600),
            Set.of(ConnectorOperation.ORGANIZATION_READ),
            "route-credential-policy-v1",
            Map.of("ownerClass", "platform-security")
        );
        RouteDefinition definition = RouteDefinition.create(
            tenant,
            "dingtalk",
            ConnectorProvider.Capability.ORGANIZATION,
            RouteIntent.ORGANIZATION_READ_USER_BY_ID,
            ProviderApiFamily.OPEN_API_V1,
            TransportProfile.DINGTALK_JAVA21_FIXED_HTTPS_V1,
            routeReference,
            CredentialMaterialType.ACCESS_TOKEN,
            "route-v1",
            "route-policy-v1",
            routeDescriptor.policyVersion(),
            routeDescriptor.fingerprint(),
            true,
            NOW.minusSeconds(60),
            NOW.plusSeconds(3_600)
        );
        RouteRequest routeRequest = new RouteRequest(
            ConnectorProvider.Capability.ORGANIZATION,
            RouteIntent.ORGANIZATION_READ_USER_BY_ID,
            null,
            "correlation-a"
        );
        RoutePlan routePlan = RoutePlan.create(
            tenant,
            definition,
            hash("snapshot-v1"),
            routeRequest,
            routeDescriptor.referenceHash(),
            NOW
        );
        CredentialBindingDescriptor applicationDescriptor = new CredentialBindingDescriptor(
            applicationReference,
            tenant,
            "dingtalk",
            CredentialMaterialType.APP_KEY_SECRET,
            "application-key",
            version,
            state,
            NOW.minusSeconds(60),
            NOW.plusSeconds(3_600),
            Set.of(ConnectorOperation.ORGANIZATION_READ),
            "application-policy-v1",
            Map.of("ownerClass", "platform-security")
        );
        CredentialMaterialVersion materialVersion = new CredentialMaterialVersion(
            version,
            NOW.minusSeconds(60),
            NOW.plusSeconds(3_600),
            hash(version)
        );
        CredentialMaterialRequest materialRequest = new CredentialMaterialRequest(
            applicationReference,
            tenant,
            "dingtalk",
            routePlan.planHash(),
            applicationDescriptor.fingerprint(),
            materialVersion,
            CredentialMaterialType.APP_KEY_SECRET,
            ConnectorOperation.ORGANIZATION_READ,
            routePlan.transportProfile().name(),
            routePlan.capability().name(),
            CredentialMaterialEnvironment.NON_PRODUCTION,
            applicationDescriptor.policyVersion()
        );
        DingTalkTokenRequest tokenRequest = new DingTalkTokenRequest(
            tenant,
            routePlan,
            materialRequest,
            "kill-switch-v1",
            "token-policy-v1"
        );
        MutableClock clock = new MutableClock(NOW);
        MutableCatalog catalog = new MutableCatalog(applicationDescriptor);
        FixtureMaterialSource source = new FixtureMaterialSource(materialRequest);
        MutableRouteGate routeGate = new MutableRouteGate();
        MutableKillSwitch killSwitch = new MutableKillSwitch();
        FixtureEndpoint endpoint = new FixtureEndpoint();
        DingTalkTokenPolicy policy = new DingTalkTokenPolicy(
            "token-policy-v1",
            Duration.ofSeconds(10),
            Duration.ofSeconds(5),
            Duration.ofMinutes(5),
            Duration.ofSeconds(2),
            16
        );
        return new Fixture(
            tokenRequest,
            applicationDescriptor,
            clock,
            catalog,
            source,
            routeGate,
            killSwitch,
            endpoint,
            policy
        );
    }

    static DingTalkTokenCoordinator coordinator(Fixture fixture) {
        return new DingTalkTokenCoordinator(
            fixture.catalog(),
            fixture.source(),
            fixture.routeGate(),
            fixture.killSwitch(),
            fixture.endpoint(),
            fixture.policy(),
            fixture.clock()
        );
    }

    static String hash(String value) {
        return CanonicalPayloadHash.sha256Utf8(value);
    }

    static byte[] frame(byte[] applicationKey, byte[] applicationSecret) {
        ByteBuffer buffer = ByteBuffer.allocate(
            Integer.BYTES * 2 + applicationKey.length + applicationSecret.length
        );
        buffer.putInt(applicationKey.length)
            .put(applicationKey)
            .putInt(applicationSecret.length)
            .put(applicationSecret);
        return buffer.array();
    }

    static boolean allZero(byte[] value) {
        return value != null && Arrays.equals(value, new byte[value.length]);
    }

    record Fixture(
        DingTalkTokenRequest request,
        CredentialBindingDescriptor descriptor,
        MutableClock clock,
        MutableCatalog catalog,
        FixtureMaterialSource source,
        MutableRouteGate routeGate,
        MutableKillSwitch killSwitch,
        FixtureEndpoint endpoint,
        DingTalkTokenPolicy policy
    ) {
    }

    static final class MutableClock extends Clock {
        private Instant current;

        MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    static final class MutableCatalog implements CredentialBindingCatalog {
        private final Map<CredentialReference, CredentialBindingDescriptor> descriptors =
            new ConcurrentHashMap<>();

        MutableCatalog(CredentialBindingDescriptor descriptor) {
            add(descriptor);
        }

        void add(CredentialBindingDescriptor descriptor) {
            descriptors.put(descriptor.reference(), descriptor);
        }

        void update(CredentialBindingDescriptor descriptor) {
            add(descriptor);
        }

        @Override
        public Optional<CredentialBindingDescriptor> find(CredentialReference reference) {
            return Optional.ofNullable(descriptors.get(reference));
        }
    }

    static final class FixtureMaterialSource implements CredentialMaterialSource {
        private final Map<String, CredentialMaterialRequest> expected = new ConcurrentHashMap<>();
        private final AtomicLong ordinal = new AtomicLong(10);
        private final AtomicInteger openCount = new AtomicInteger();
        private final AtomicInteger releaseCount = new AtomicInteger();
        private volatile byte[] lastScoped;

        FixtureMaterialSource(CredentialMaterialRequest expectedRequest) {
            add(expectedRequest);
        }

        void add(CredentialMaterialRequest request) {
            expected.put(request.evidenceHash(), request);
        }

        @Override
        public CredentialMaterialLease openLease(CredentialMaterialRequest request) {
            openCount.incrementAndGet();
            if (!expected.containsKey(request.evidenceHash())) {
                throw new IllegalStateException("fixture request mismatch");
            }
            byte[] material = frame("app-key".getBytes(), "app-secret".getBytes());
            CredentialMaterialDescriptor descriptor = CredentialMaterialDescriptor.loaded(
                request,
                hash("fixture-source"),
                ordinal.getAndIncrement()
            );
            return CredentialMaterialLease.takeOwnership(
                request,
                descriptor,
                material,
                ordinal::getAndIncrement,
                releaseCount::incrementAndGet
            );
        }

        @Override
        public MaterialScope openMaterial(
            CredentialReference reference,
            String expectedKeyId,
            String expectedVersionId
        ) {
            throw new SourceUnavailableException();
        }

        int openCount() {
            return openCount.get();
        }

        int releaseCount() {
            return releaseCount.get();
        }

        void capture(byte[] scoped) {
            lastScoped = scoped;
        }

        byte[] lastScoped() {
            return lastScoped;
        }
    }

    static final class MutableRouteGate implements DingTalkTokenRouteGate {
        private volatile boolean valid = true;

        void setValid(boolean valid) {
            this.valid = valid;
        }

        @Override
        public Result revalidate(
            String trustedTenantId,
            RoutePlan routePlan,
            Instant evaluatedAt
        ) {
            return new Result(valid, valid ? "valid" : "stale", hash("route-gate"));
        }
    }

    static final class MutableKillSwitch implements DingTalkTokenKillSwitch {
        private volatile boolean allowed = true;
        private volatile String revision = "kill-switch-v1";

        void setAllowed(boolean allowed) {
            this.allowed = allowed;
        }

        void setRevision(String revision) {
            this.revision = revision;
        }

        @Override
        public Decision evaluate(
            String trustedTenantId,
            String routePlanHash,
            String expectedRevision
        ) {
            return new Decision(allowed, revision, allowed ? "enabled" : "disabled");
        }
    }

    static final class FixtureEndpoint implements DingTalkTokenEndpointPort {
        private final AtomicInteger invocationCount = new AtomicInteger();
        private volatile long lifetimeSeconds = 30;
        private volatile DingTalkTokenFailure scriptedFailure = DingTalkTokenFailure.NONE;
        private volatile CountDownLatch entered;
        private volatile CountDownLatch release;
        private volatile byte[] lastKey;
        private volatile byte[] lastSecret;
        private volatile byte[] lastTokenMaterial;

        @Override
        public void acquire(
            DingTalkTokenEndpointRequest request,
            byte[] applicationKey,
            byte[] applicationSecret,
            ResponseUse responseUse
        ) {
            invocationCount.incrementAndGet();
            lastKey = applicationKey;
            lastSecret = applicationSecret;
            CountDownLatch enteredLatch = entered;
            CountDownLatch releaseLatch = release;
            if (enteredLatch != null) {
                enteredLatch.countDown();
            }
            if (releaseLatch != null) {
                await(releaseLatch);
            }
            if (scriptedFailure != DingTalkTokenFailure.NONE) {
                throw new DingTalkTokenLifecycleException(scriptedFailure);
            }
            byte[] tokenMaterial = ("fixture-token-" + invocationCount.get()).getBytes();
            lastTokenMaterial = tokenMaterial;
            responseUse.accept(tokenMaterial, lifetimeSeconds);
        }

        void script(DingTalkTokenFailure failure) {
            scriptedFailure = failure;
        }

        void setLifetimeSeconds(long lifetimeSeconds) {
            this.lifetimeSeconds = lifetimeSeconds;
        }

        void block(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        int invocationCount() {
            return invocationCount.get();
        }

        byte[] lastKey() {
            return lastKey;
        }

        byte[] lastSecret() {
            return lastSecret;
        }

        byte[] lastTokenMaterial() {
            return lastTokenMaterial;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException problem) {
            Thread.currentThread().interrupt();
            throw new DingTalkTokenLifecycleException(DingTalkTokenFailure.ENDPOINT_CANCELLED);
        }
    }
}
