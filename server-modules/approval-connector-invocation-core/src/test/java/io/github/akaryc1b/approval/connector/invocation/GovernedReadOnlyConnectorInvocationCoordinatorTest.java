package io.github.akaryc1b.approval.connector.invocation;

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
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenCoordinator;
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenEndpointPort;
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenEndpointRequest;
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenFailure;
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenKillSwitch;
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenLifecycleException;
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenPolicy;
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenRequest;
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenRouteGate;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .CompletionClassification;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .DispatchRequest;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .DispatchResponse;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .GateResult;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .InvocationPolicy;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .InvocationRequest;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .InvocationResult;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .ReadOnlyProviderResult;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .StableFailureCode;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteConfigurationSource;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.ProviderApiFamily;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteDefinition;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteIntent;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RoutePlan;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.TransportProfile;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteResolver;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteRevalidator;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernedReadOnlyConnectorInvocationCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-07-29T02:00:00Z");
    private static final String TENANT_A = "tenant-a";
    private final List<Fixture> fixtures = new ArrayList<>();

    @AfterEach
    void closeFixtures() {
        fixtures.forEach(Fixture::close);
    }

    @Test
    void organizationOpenApiReadDispatchesExactlyOnce() {
        Fixture fixture = fixture(RouteIntent.ORGANIZATION_READ_USER_BY_ID, ProviderApiFamily.OPEN_API_V1);

        InvocationResult result = fixture.invoke(TENANT_A);

        assertEquals(CompletionClassification.SUCCEEDED, result.evidence().completionClassification());
        assertEquals(1, result.evidence().dispatchCount());
        assertEquals(1, fixture.dispatch.count());
        assertEquals(ProviderApiFamily.OPEN_API_V1, result.evidence().apiFamily());
    }

    @Test
    void organizationLegacyReadIsWithinClosedMatrix() {
        Fixture fixture = fixture(
            RouteIntent.ORGANIZATION_READ_USER_BY_ID,
            ProviderApiFamily.LEGACY_OAPI
        );

        InvocationResult result = fixture.invoke(TENANT_A);

        assertEquals(CompletionClassification.SUCCEEDED, result.evidence().completionClassification());
        assertEquals(ConnectorOperation.ORGANIZATION_READ, result.evidence().connectorOperation());
    }

    @Test
    void identityResolutionAllowsLegacyOnly() {
        Fixture fixture = fixture(
            RouteIntent.IDENTITY_RESOLVE_DINGTALK_USERID,
            ProviderApiFamily.LEGACY_OAPI
        );

        InvocationResult result = fixture.invoke(TENANT_A);

        assertEquals(CompletionClassification.SUCCEEDED, result.evidence().completionClassification());
        assertEquals("dingtalk-userid", result.evidence().providerOperation());
    }

    @Test
    void unsupportedIdentityApiFamilyFailsBeforeDispatch() {
        Fixture fixture = fixture(
            RouteIntent.IDENTITY_RESOLVE_DINGTALK_USERID,
            ProviderApiFamily.OPEN_API_V1
        );

        InvocationResult result = fixture.invoke(TENANT_A);

        assertEquals(
            StableFailureCode.UNSUPPORTED_OPERATION,
            result.evidence().stableFailureCode()
        );
        assertEquals(0, fixture.dispatch.count());
    }

    @Test
    void trustedTenantMismatchDoesNotLeakRouteExistence() {
        Fixture fixture = fixture();

        InvocationResult result = fixture.invoke("tenant-b");

        assertEquals(StableFailureCode.ROUTE_MISSING, result.evidence().stableFailureCode());
        assertEquals(0, fixture.dispatch.count());
        assertFalse(result.evidence().canonicalJson().contains(TENANT_A));
    }

    @Test
    void disabledRouteFailsBeforeTokenAndTransport() {
        Fixture fixture = fixture();
        fixture.setRouteEnabled(TENANT_A, false);

        InvocationResult result = fixture.invoke(TENANT_A);

        assertEquals(StableFailureCode.ROUTE_DISABLED, result.evidence().stableFailureCode());
        assertEquals(0, fixture.endpoint.count());
        assertEquals(0, fixture.dispatch.count());
    }

    @Test
    void staleRouteBetweenResolutionAndRevalidationFailsBeforeToken() {
        Fixture fixture = fixture();
        fixture.invocationKillSwitch.onCall(1, () -> fixture.rotateRouteVersion(TENANT_A));

        InvocationResult result = fixture.invoke(TENANT_A);

        assertEquals(StableFailureCode.ROUTE_STALE, result.evidence().stableFailureCode());
        assertEquals(0, fixture.endpoint.count());
        assertEquals(0, fixture.dispatch.count());
    }

    @Test
    void revokedRouteCredentialFailsBeforeToken() {
        Fixture fixture = fixture();
        fixture.invocationKillSwitch.onCall(1, () -> fixture.setRouteCredentialState(
            TENANT_A,
            CredentialBindingState.REVOKED
        ));

        InvocationResult result = fixture.invoke(TENANT_A);

        assertEquals(
            StableFailureCode.CREDENTIAL_REVALIDATION_FAILED,
            result.evidence().stableFailureCode()
        );
        assertEquals(0, fixture.dispatch.count());
    }

    @Test
    void credentialVersionDriftIsRejectedByP6() {
        Fixture fixture = fixture();
        fixture.invocationKillSwitch.onCall(1, () -> fixture.rotateApplicationVersion(
            TENANT_A,
            "app-version-2"
        ));

        InvocationResult result = fixture.invoke(TENANT_A);

        assertEquals(
            StableFailureCode.TOKEN_ACQUISITION_FAILED,
            result.evidence().stableFailureCode()
        );
        assertEquals(0, fixture.dispatch.count());
    }

    @Test
    void tokenPolicyDriftFailsBeforeEndpoint() {
        Fixture fixture = fixture();
        fixture.tokenPolicyVersion = "token-policy-v2";

        InvocationResult result = fixture.invoke(TENANT_A);

        assertEquals(StableFailureCode.TOKEN_POLICY_DRIFT, result.evidence().stableFailureCode());
        assertEquals(0, fixture.endpoint.count());
        assertEquals(0, fixture.dispatch.count());
    }

    @Test
    void tokenAcquisitionFailureNeverDispatches() {
        Fixture fixture = fixture();
        fixture.endpoint.failure = DingTalkTokenFailure.ENDPOINT_UNAVAILABLE;

        InvocationResult result = fixture.invoke(TENANT_A);

        assertEquals(
            StableFailureCode.TOKEN_ACQUISITION_FAILED,
            result.evidence().stableFailureCode()
        );
        assertEquals(0, fixture.dispatch.count());
    }

    @Test
    void killSwitchBeforeTokenBlocksWithoutMaterialLease() {
        Fixture fixture = fixture();
        fixture.invocationKillSwitch.decisions.add(
            new DingTalkTokenKillSwitch.Decision(false, "kill-switch-v1", "blocked")
        );

        InvocationResult result = fixture.invoke(TENANT_A);

        assertEquals(StableFailureCode.KILL_SWITCH_BLOCKED, result.evidence().stableFailureCode());
        assertEquals(GateResult.BLOCKED, result.evidence().preDispatchGateResult());
        assertEquals(0, fixture.materialSource.openCount());
        assertEquals(0, fixture.dispatch.count());
    }

    @Test
    void killSwitchAfterTokenClosesLeaseAndDoesNotDispatch() {
        Fixture fixture = fixture();
        fixture.invocationKillSwitch.decisions.add(allowedDecision());
        fixture.invocationKillSwitch.decisions.add(
            new DingTalkTokenKillSwitch.Decision(false, "kill-switch-v1", "blocked")
        );

        InvocationResult result = fixture.invoke(TENANT_A);

        assertEquals(StableFailureCode.KILL_SWITCH_BLOCKED, result.evidence().stableFailureCode());
        assertEquals(1, fixture.materialSource.releaseCount());
        assertEquals(0, fixture.dispatch.count());
    }

    @Test
    void killSwitchRevisionDriftFailsClosed() {
        Fixture fixture = fixture();
        fixture.invocationKillSwitch.decisions.add(
            new DingTalkTokenKillSwitch.Decision(true, "kill-switch-v2", "enabled")
        );

        InvocationResult result = fixture.invoke(TENANT_A);

        assertEquals(
            StableFailureCode.KILL_SWITCH_REVISION_DRIFT,
            result.evidence().stableFailureCode()
        );
        assertEquals(0, fixture.dispatch.count());
    }

    @Test
    void everyPreDispatchFailureKeepsTransportCountZero() {
        Fixture fixture = fixture();
        fixture.endpoint.failure = DingTalkTokenFailure.ENDPOINT_MALFORMED;

        InvocationResult result = fixture.invoke(TENANT_A);

        assertEquals(0, result.evidence().dispatchCount());
        assertFalse(result.evidence().dispatchAttempted());
        assertEquals(0, fixture.dispatch.count());
    }

    @Test
    void successPathHasExactlyOneTransportRequestAndResponse() {
        Fixture fixture = fixture();

        InvocationResult result = fixture.invoke(TENANT_A);

        assertEquals(1, result.evidence().dispatchCount());
        assertEquals(1, fixture.dispatch.count());
        assertTrue(result.providerResult().isPresent());
    }

    @Test
    void transportTimeoutIsUnknownAfterDispatch() {
        Fixture fixture = fixture();
        fixture.dispatch.response = DispatchResponse.timeout();

        InvocationResult result = fixture.invoke(TENANT_A);

        assertEquals(
            CompletionClassification.UNKNOWN_AFTER_DISPATCH,
            result.evidence().completionClassification()
        );
        assertEquals(StableFailureCode.TRANSPORT_TIMEOUT, result.evidence().stableFailureCode());
        assertEquals(1, fixture.dispatch.count());
    }

    @Test
    void transportExceptionIsUnknownAfterDispatch() {
        Fixture fixture = fixture();
        fixture.dispatch.failure = new IllegalStateException("synthetic transport failure");

        InvocationResult result = fixture.invoke(TENANT_A);

        assertEquals(
            CompletionClassification.UNKNOWN_AFTER_DISPATCH,
            result.evidence().completionClassification()
        );
        assertEquals(
            StableFailureCode.TRANSPORT_EXCEPTION,
            result.evidence().stableFailureCode()
        );
    }

    @Test
    void transportFailureNeverRetriesAutomatically() {
        Fixture fixture = fixture();
        fixture.dispatch.failure = new IllegalStateException("synthetic transport failure");

        fixture.invoke(TENANT_A);

        assertEquals(1, fixture.dispatch.count());
    }

    @Test
    void tokenScopedCopyIsZeroizedAfterDispatch() {
        Fixture fixture = fixture();

        fixture.invoke(TENANT_A);

        assertNotNull(fixture.dispatch.lastToken);
        assertTrue(allZero(fixture.dispatch.lastToken));
    }

    @Test
    void materialLeaseIsAlwaysReleased() {
        Fixture fixture = fixture();

        fixture.invoke(TENANT_A);

        assertEquals(1, fixture.materialSource.openCount());
        assertEquals(1, fixture.materialSource.releaseCount());
    }

    @Test
    void evidenceAndToStringDoNotLeakSecretsOrRawAuthority() {
        Fixture fixture = fixture();

        InvocationResult result = fixture.invoke(TENANT_A);
        String evidence = result.evidence().canonicalJson();
        String rendered = fixture.coordinator.toString() + result.providerResult().orElseThrow();

        for (String forbidden : List.of(
            TENANT_A,
            "route-access-tenant-a",
            "application-reference-tenant-a",
            "app-secret",
            "fixture-token",
            "Authorization",
            "Bearer ",
            "Cookie"
        )) {
            assertFalse(evidence.contains(forbidden));
            assertFalse(rendered.contains(forbidden));
        }
    }

    @Test
    void resultAuthorityIsPermanentlyReadOnlyAndNonProduction() {
        Fixture fixture = fixture();

        InvocationResult result = fixture.invoke(TENANT_A);

        assertTrue(result.readOnly());
        assertFalse(result.approvalStateMutationAuthorized());
        assertFalse(result.productionExecutionAuthorized());
    }

    @Test
    void providerRejectionIsStableAndNotRetried() {
        Fixture fixture = fixture();
        fixture.dispatch.response = DispatchResponse.providerRejected("PROVIDER_DENIED");

        InvocationResult result = fixture.invoke(TENANT_A);

        assertEquals(
            CompletionClassification.PROVIDER_REJECTED,
            result.evidence().completionClassification()
        );
        assertEquals(StableFailureCode.PROVIDER_REJECTED, result.evidence().stableFailureCode());
        assertEquals(1, fixture.dispatch.count());
    }

    @Test
    void postTokenRouteDriftPreventsDispatch() {
        Fixture fixture = fixture();
        fixture.invocationKillSwitch.onCall(2, () -> fixture.rotateRouteVersion(TENANT_A));

        InvocationResult result = fixture.invoke(TENANT_A);

        assertEquals(
            StableFailureCode.POST_TOKEN_ROUTE_DRIFT,
            result.evidence().stableFailureCode()
        );
        assertEquals(0, fixture.dispatch.count());
        assertEquals(1, fixture.materialSource.releaseCount());
    }

    @Test
    void boundedResponseExcessIsUnknownAfterOneDispatch() {
        Fixture fixture = fixture();
        fixture.policy = new InvocationPolicy(
            "connector-invocation-policy-v1",
            65_536,
            64,
            Duration.ofSeconds(5),
            "kill-switch-v1",
            "token-policy-v1"
        );
        fixture.rebuildCoordinator();

        InvocationResult result = fixture.invoke(TENANT_A);

        assertEquals(StableFailureCode.RESPONSE_TOO_LARGE, result.evidence().stableFailureCode());
        assertEquals(1, result.evidence().dispatchCount());
    }

    @Test
    void nullTransportResponseIsUnknownAfterDispatch() {
        Fixture fixture = fixture();
        fixture.dispatch.response = null;

        InvocationResult result = fixture.invoke(TENANT_A);

        assertEquals(StableFailureCode.RESPONSE_INVALID, result.evidence().stableFailureCode());
        assertEquals(1, result.evidence().dispatchCount());
    }

    @Test
    void maximumRequestBytesFailsBeforeRouteAndDispatch() {
        Fixture fixture = fixture();
        fixture.policy = new InvocationPolicy(
            "connector-invocation-policy-v1",
            1,
            262_144,
            Duration.ofSeconds(5),
            "kill-switch-v1",
            "token-policy-v1"
        );
        fixture.rebuildCoordinator();

        InvocationResult result = fixture.invoke(TENANT_A);

        assertEquals(StableFailureCode.INVALID_REQUEST, result.evidence().stableFailureCode());
        assertEquals(0, fixture.dispatch.count());
    }

    @Test
    void coordinatorRejectsInvocationAfterClose() {
        Fixture fixture = fixture();
        fixture.coordinator.close();

        InvocationResult result = fixture.invoke(TENANT_A);

        assertEquals(StableFailureCode.COORDINATOR_CLOSED, result.evidence().stableFailureCode());
        assertEquals(0, fixture.dispatch.count());
    }

    @Test
    void concurrentTenantsRemainIsolatedByRouteAndCredentialVersion() throws Exception {
        Fixture fixture = fixture();
        fixture.addTenant(
            "tenant-b",
            RouteIntent.ORGANIZATION_READ_USER_BY_ID,
            ProviderApiFamily.LEGACY_OAPI,
            "app-version-b"
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<InvocationResult> first = executor.submit(() -> {
                await(start);
                return fixture.invoke(TENANT_A);
            });
            Future<InvocationResult> second = executor.submit(() -> {
                await(start);
                return fixture.invoke("tenant-b");
            });
            start.countDown();

            InvocationResult firstResult = first.get();
            InvocationResult secondResult = second.get();

            assertEquals(CompletionClassification.SUCCEEDED, firstResult.evidence()
                .completionClassification());
            assertEquals(CompletionClassification.SUCCEEDED, secondResult.evidence()
                .completionClassification());
            assertNotEquals(firstResult.evidence().tenantHash(), secondResult.evidence().tenantHash());
            assertNotEquals(
                firstResult.evidence().credentialVersionReference(),
                secondResult.evidence().credentialVersionReference()
            );
            assertEquals(2, fixture.dispatch.count());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void exactRequestDoesNotExposeHostPathOrCredentialInput() {
        InvocationRequest request = new InvocationRequest(
            RouteIntent.ORGANIZATION_READ_USER_BY_ID,
            "user-1",
            "correlation-1"
        );

        String rendered = request.toString();

        assertFalse(rendered.contains("user-1"));
        assertFalse(rendered.contains("http"));
        assertFalse(rendered.contains("credential"));
    }

    @Test
    void readOnlyProviderResultHashBindsReturnedFields() {
        ReadOnlyProviderResult first = ReadOnlyProviderResult.create(
            "user-1",
            Map.of("displayName", "User One")
        );
        ReadOnlyProviderResult second = ReadOnlyProviderResult.create(
            "user-1",
            Map.of("displayName", "User Two")
        );

        assertNotEquals(first.resultHash(), second.resultHash());
        assertFalse(first.toString().contains("User One"));
    }

    private Fixture fixture() {
        return fixture(
            RouteIntent.ORGANIZATION_READ_USER_BY_ID,
            ProviderApiFamily.OPEN_API_V1
        );
    }

    private Fixture fixture(RouteIntent intent, ProviderApiFamily family) {
        Fixture fixture = new Fixture(intent, family);
        fixtures.add(fixture);
        return fixture;
    }

    private static DingTalkTokenKillSwitch.Decision allowedDecision() {
        return new DingTalkTokenKillSwitch.Decision(true, "kill-switch-v1", "enabled");
    }

    private static String hash(String value) {
        return CanonicalPayloadHash.sha256Utf8(value);
    }

    private static byte[] frame(byte[] applicationKey, byte[] applicationSecret) {
        return ByteBuffer.allocate(Integer.BYTES * 2 + applicationKey.length + applicationSecret.length)
            .putInt(applicationKey.length)
            .put(applicationKey)
            .putInt(applicationSecret.length)
            .put(applicationSecret)
            .array();
    }

    private static boolean allZero(byte[] value) {
        return value != null && Arrays.equals(value, new byte[value.length]);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException problem) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", problem);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final MutableClock clock = new MutableClock(NOW);
        private final MutableCatalog catalog = new MutableCatalog();
        private final MutableRouteSource routeSource = new MutableRouteSource();
        private final FixtureMaterialSource materialSource = new FixtureMaterialSource();
        private final FixtureEndpoint endpoint = new FixtureEndpoint();
        private final DingTalkTokenKillSwitch tokenKillSwitch = (
            trustedTenantId,
            routePlanHash,
            expectedRevision
        ) -> allowedDecision();
        private final DingTalkTokenRouteGate tokenRouteGate = (
            trustedTenantId,
            routePlan,
            evaluatedAt
        ) -> new DingTalkTokenRouteGate.Result(true, "valid", hash("token-route-gate"));
        private final ScriptedInvocationKillSwitch invocationKillSwitch =
            new ScriptedInvocationKillSwitch();
        private final RecordingDispatch dispatch = new RecordingDispatch();
        private final Map<String, TenantProfile> profiles = new ConcurrentHashMap<>();
        private final DingTalkTokenPolicy tokenPolicy = new DingTalkTokenPolicy(
            "token-policy-v1",
            Duration.ofSeconds(10),
            Duration.ofSeconds(5),
            Duration.ofMinutes(5),
            Duration.ofSeconds(2),
            32
        );
        private final DingTalkTokenCoordinator tokenCoordinator = new DingTalkTokenCoordinator(
            catalog,
            materialSource,
            tokenRouteGate,
            tokenKillSwitch,
            endpoint,
            tokenPolicy,
            clock
        );
        private InvocationPolicy policy = new InvocationPolicy(
            "connector-invocation-policy-v1",
            65_536,
            262_144,
            Duration.ofSeconds(5),
            "kill-switch-v1",
            "token-policy-v1"
        );
        private String tokenPolicyVersion = "token-policy-v1";
        private GovernedReadOnlyConnectorInvocationCoordinator coordinator;

        Fixture(RouteIntent intent, ProviderApiFamily family) {
            addTenant(TENANT_A, intent, family, "app-version-1");
            rebuildCoordinator();
        }

        void addTenant(
            String tenant,
            RouteIntent intent,
            ProviderApiFamily family,
            String applicationVersion
        ) {
            CredentialReference routeReference = new CredentialReference(
                "dingtalk",
                "route-access-" + tenant
            );
            CredentialBindingDescriptor routeDescriptor = routeDescriptor(
                tenant,
                routeReference,
                intent,
                CredentialBindingState.ACTIVE
            );
            CredentialReference applicationReference = new CredentialReference(
                "dingtalk",
                "application-reference-" + tenant
            );
            CredentialBindingDescriptor applicationDescriptor = applicationDescriptor(
                tenant,
                applicationReference,
                applicationVersion,
                CredentialBindingState.ACTIVE
            );
            CredentialMaterialVersion materialVersion = new CredentialMaterialVersion(
                applicationVersion,
                NOW.minusSeconds(60),
                NOW.plusSeconds(3_600),
                hash(applicationVersion)
            );
            RouteDefinition definition = definition(
                tenant,
                intent,
                family,
                routeDescriptor,
                true,
                "route-v1"
            );
            TenantProfile profile = new TenantProfile(
                tenant,
                routeReference,
                applicationReference,
                routeDescriptor,
                applicationDescriptor,
                materialVersion,
                definition
            );
            profiles.put(tenant, profile);
            catalog.put(routeDescriptor);
            catalog.put(applicationDescriptor);
            refreshSnapshot();
        }

        InvocationResult invoke(String tenant) {
            return coordinator.invoke(
                tenant,
                new InvocationRequest(
                    profiles.getOrDefault(tenant, profiles.get(TENANT_A)).definition().intent(),
                    "subject-" + tenant,
                    "correlation-" + tenant
                )
            );
        }

        void rebuildCoordinator() {
            coordinator = new GovernedReadOnlyConnectorInvocationCoordinator(
                new TenantConnectorRouteResolver(routeSource, catalog),
                new TenantConnectorRouteRevalidator(routeSource, catalog),
                tokenCoordinator,
                invocationKillSwitch,
                this::tokenRequest,
                dispatch,
                policy,
                clock
            );
        }

        void setRouteEnabled(String tenant, boolean enabled) {
            TenantProfile profile = profiles.get(tenant);
            RouteDefinition updated = definition(
                tenant,
                profile.definition().intent(),
                profile.definition().apiFamily(),
                profile.routeDescriptor(),
                enabled,
                profile.definition().routeVersion()
            );
            profiles.put(tenant, profile.withDefinition(updated));
            refreshSnapshot();
        }

        void rotateRouteVersion(String tenant) {
            TenantProfile profile = profiles.get(tenant);
            RouteDefinition updated = definition(
                tenant,
                profile.definition().intent(),
                profile.definition().apiFamily(),
                profile.routeDescriptor(),
                true,
                profile.definition().routeVersion() + "-next"
            );
            profiles.put(tenant, profile.withDefinition(updated));
            refreshSnapshot();
        }

        void setRouteCredentialState(String tenant, CredentialBindingState state) {
            TenantProfile profile = profiles.get(tenant);
            CredentialBindingDescriptor updated = routeDescriptor(
                tenant,
                profile.routeReference(),
                profile.definition().intent(),
                state
            );
            catalog.put(updated);
            profiles.put(tenant, profile.withRouteDescriptor(updated));
        }

        void rotateApplicationVersion(String tenant, String version) {
            TenantProfile profile = profiles.get(tenant);
            CredentialBindingDescriptor updated = applicationDescriptor(
                tenant,
                profile.applicationReference(),
                version,
                CredentialBindingState.ACTIVE
            );
            catalog.put(updated);
            profiles.put(tenant, profile.withApplicationDescriptor(updated));
        }

        private DingTalkTokenRequest tokenRequest(String tenant, RoutePlan plan) {
            TenantProfile profile = profiles.get(tenant);
            CredentialMaterialRequest materialRequest = new CredentialMaterialRequest(
                profile.applicationReference(),
                tenant,
                "dingtalk",
                plan.planHash(),
                profile.applicationDescriptor().fingerprint(),
                profile.materialVersion(),
                CredentialMaterialType.APP_KEY_SECRET,
                plan.connectorOperation(),
                plan.transportProfile().name(),
                plan.capability().name(),
                CredentialMaterialEnvironment.NON_PRODUCTION,
                profile.applicationDescriptor().policyVersion()
            );
            return new DingTalkTokenRequest(
                tenant,
                plan,
                materialRequest,
                "kill-switch-v1",
                tokenPolicyVersion
            );
        }

        private void refreshSnapshot() {
            routeSource.snapshot = TenantConnectorRouteSnapshot.create(
                "snapshot-v" + routeSource.ordinal.incrementAndGet(),
                profiles.values().stream().map(TenantProfile::definition).toList()
            );
        }

        @Override
        public void close() {
            coordinator.close();
            tokenCoordinator.close();
        }
    }

    private record TenantProfile(
        String tenant,
        CredentialReference routeReference,
        CredentialReference applicationReference,
        CredentialBindingDescriptor routeDescriptor,
        CredentialBindingDescriptor applicationDescriptor,
        CredentialMaterialVersion materialVersion,
        RouteDefinition definition
    ) {
        TenantProfile withDefinition(RouteDefinition value) {
            return new TenantProfile(
                tenant,
                routeReference,
                applicationReference,
                routeDescriptor,
                applicationDescriptor,
                materialVersion,
                value
            );
        }

        TenantProfile withRouteDescriptor(CredentialBindingDescriptor value) {
            return new TenantProfile(
                tenant,
                routeReference,
                applicationReference,
                value,
                applicationDescriptor,
                materialVersion,
                definition
            );
        }

        TenantProfile withApplicationDescriptor(CredentialBindingDescriptor value) {
            return new TenantProfile(
                tenant,
                routeReference,
                applicationReference,
                routeDescriptor,
                value,
                materialVersion,
                definition
            );
        }
    }

    private static RouteDefinition definition(
        String tenant,
        RouteIntent intent,
        ProviderApiFamily family,
        CredentialBindingDescriptor routeDescriptor,
        boolean enabled,
        String routeVersion
    ) {
        return RouteDefinition.create(
            tenant,
            "dingtalk",
            intent.capability(),
            intent,
            family,
            TransportProfile.DINGTALK_JAVA21_FIXED_HTTPS_V1,
            routeDescriptor.reference(),
            CredentialMaterialType.ACCESS_TOKEN,
            routeVersion,
            "route-policy-v1",
            routeDescriptor.policyVersion(),
            routeDescriptor.fingerprint(),
            enabled,
            NOW.minusSeconds(60),
            NOW.plusSeconds(3_600)
        );
    }

    private static CredentialBindingDescriptor routeDescriptor(
        String tenant,
        CredentialReference reference,
        RouteIntent intent,
        CredentialBindingState state
    ) {
        return new CredentialBindingDescriptor(
            reference,
            tenant,
            "dingtalk",
            CredentialMaterialType.ACCESS_TOKEN,
            "route-key",
            "route-version-1",
            state,
            NOW.minusSeconds(60),
            NOW.plusSeconds(3_600),
            Set.of(intent.connectorOperation()),
            "route-credential-policy-v1",
            Map.of("ownerClass", "platform-security")
        );
    }

    private static CredentialBindingDescriptor applicationDescriptor(
        String tenant,
        CredentialReference reference,
        String version,
        CredentialBindingState state
    ) {
        return new CredentialBindingDescriptor(
            reference,
            tenant,
            "dingtalk",
            CredentialMaterialType.APP_KEY_SECRET,
            "application-key",
            version,
            state,
            NOW.minusSeconds(60),
            NOW.plusSeconds(3_600),
            Set.of(ConnectorOperation.ORGANIZATION_READ, ConnectorOperation.IDENTITY_RESOLVE),
            "application-policy-v1",
            Map.of("ownerClass", "platform-security")
        );
    }

    private static final class MutableRouteSource
        implements TenantConnectorRouteConfigurationSource {
        private final AtomicLong ordinal = new AtomicLong();
        private volatile TenantConnectorRouteSnapshot snapshot;

        @Override
        public TenantConnectorRouteSnapshot load() {
            return snapshot;
        }
    }

    private static final class MutableCatalog implements CredentialBindingCatalog {
        private final Map<CredentialReference, CredentialBindingDescriptor> descriptors =
            new ConcurrentHashMap<>();

        void put(CredentialBindingDescriptor descriptor) {
            descriptors.put(descriptor.reference(), descriptor);
        }

        @Override
        public Optional<CredentialBindingDescriptor> find(CredentialReference reference) {
            return Optional.ofNullable(descriptors.get(reference));
        }
    }

    private static final class FixtureMaterialSource implements CredentialMaterialSource {
        private final AtomicLong ordinal = new AtomicLong(10);
        private final AtomicInteger opens = new AtomicInteger();
        private final AtomicInteger releases = new AtomicInteger();

        @Override
        public CredentialMaterialLease openLease(CredentialMaterialRequest request) {
            opens.incrementAndGet();
            byte[] material = frame(
                "app-key".getBytes(StandardCharsets.US_ASCII),
                "app-secret".getBytes(StandardCharsets.US_ASCII)
            );
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
                releases::incrementAndGet
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
            return opens.get();
        }

        int releaseCount() {
            return releases.get();
        }
    }

    private static final class FixtureEndpoint implements DingTalkTokenEndpointPort {
        private final AtomicInteger invocations = new AtomicInteger();
        private volatile DingTalkTokenFailure failure = DingTalkTokenFailure.NONE;

        @Override
        public void acquire(
            DingTalkTokenEndpointRequest request,
            byte[] applicationKey,
            byte[] applicationSecret,
            ResponseUse responseUse
        ) {
            int ordinal = invocations.incrementAndGet();
            if (failure != DingTalkTokenFailure.NONE) {
                throw new DingTalkTokenLifecycleException(failure);
            }
            responseUse.accept(
                ("fixture-token-" + ordinal).getBytes(StandardCharsets.US_ASCII),
                30
            );
        }

        int count() {
            return invocations.get();
        }
    }

    private static final class ScriptedInvocationKillSwitch
        implements DingTalkTokenKillSwitch {
        private final List<Decision> decisions = new ArrayList<>();
        private final Map<Integer, Runnable> actions = new ConcurrentHashMap<>();
        private final AtomicInteger calls = new AtomicInteger();

        void onCall(int call, Runnable action) {
            actions.put(call, action);
        }

        @Override
        public synchronized Decision evaluate(
            String trustedTenantId,
            String routePlanHash,
            String expectedRevision
        ) {
            int call = calls.incrementAndGet();
            Runnable action = actions.get(call);
            if (action != null) {
                action.run();
            }
            return call <= decisions.size() ? decisions.get(call - 1) : allowedDecision();
        }
    }

    private static final class RecordingDispatch
        implements GovernedConnectorInvocationContracts.DingTalkReadOnlyDispatchPort {
        private final AtomicInteger invocations = new AtomicInteger();
        private volatile DispatchResponse response = DispatchResponse.succeeded(
            ReadOnlyProviderResult.create("user-1", Map.of("displayName", "User One")),
            128
        );
        private volatile RuntimeException failure;
        private volatile byte[] lastToken;
        private volatile DispatchRequest lastRequest;

        @Override
        public DispatchResponse dispatch(DispatchRequest request, byte[] accessToken) {
            invocations.incrementAndGet();
            lastRequest = request;
            lastToken = accessToken;
            if (failure != null) {
                throw failure;
            }
            return response;
        }

        int count() {
            return invocations.get();
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        MutableClock(Instant current) {
            this.current = new AtomicReference<>(current);
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
            return current.get();
        }
    }
}
