package io.github.akaryc1b.approval.connector.dingtalk.token;

import io.github.akaryc1b.approval.connector.credential.CredentialBindingDescriptor;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DingTalkTokenCoordinatorTest {

    @Test
    void onDemandAcquisitionCachesAndRefreshesWithoutBackgroundWork() {
        var fixture = DingTalkTokenTestFixtures.fixture();
        try (DingTalkTokenCoordinator coordinator =
                 DingTalkTokenTestFixtures.coordinator(fixture)) {
            try (DingTalkAccessTokenLease first = coordinator.acquire(fixture.request())) {
                assertEquals(DingTalkTokenOutcome.ACQUIRED, first.evidence().outcome());
            }
            try (DingTalkAccessTokenLease cached = coordinator.acquire(fixture.request())) {
                assertEquals(DingTalkTokenOutcome.CACHE_HIT, cached.evidence().outcome());
                assertFalse(cached.evidence().endpointAttempted());
            }
            assertEquals(1, fixture.endpoint().invocationCount());
            assertEquals(1, fixture.source().openCount());
            assertEquals(1, fixture.source().releaseCount());

            fixture.clock().advance(Duration.ofSeconds(21));
            try (DingTalkAccessTokenLease refreshed = coordinator.acquire(fixture.request())) {
                assertEquals(DingTalkTokenOutcome.REFRESHED, refreshed.evidence().outcome());
                assertNotEquals(
                    DingTalkTokenTestFixtures.hash("never-a-token"),
                    refreshed.evidence().tokenVersionReference()
                );
            }
            assertEquals(2, fixture.endpoint().invocationCount());
            assertEquals(2, fixture.source().releaseCount());
        }
    }

    @Test
    void concurrentSameBindingUsesOneSingleFlightEndpointAttempt() throws Exception {
        var fixture = DingTalkTokenTestFixtures.fixture();
        CountDownLatch endpointEntered = new CountDownLatch(1);
        CountDownLatch endpointRelease = new CountDownLatch(1);
        fixture.endpoint().block(endpointEntered, endpointRelease);
        List<DingTalkTokenOutcome> outcomes = Collections.synchronizedList(new ArrayList<>());
        try (DingTalkTokenCoordinator coordinator = DingTalkTokenTestFixtures.coordinator(fixture);
             var executor = Executors.newFixedThreadPool(8)) {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch completed = new CountDownLatch(8);
            for (int index = 0; index < 8; index++) {
                executor.submit(() -> {
                    await(start);
                    try (DingTalkAccessTokenLease lease = coordinator.acquire(fixture.request())) {
                        outcomes.add(lease.evidence().outcome());
                    } finally {
                        completed.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(endpointEntered.await(5, TimeUnit.SECONDS));
            endpointRelease.countDown();
            assertTrue(completed.await(5, TimeUnit.SECONDS));

            assertEquals(1, fixture.endpoint().invocationCount());
            assertEquals(1, fixture.source().openCount());
            assertEquals(8, outcomes.size());
            assertEquals(1, outcomes.stream().filter(
                outcome -> outcome == DingTalkTokenOutcome.ACQUIRED
            ).count());
            assertTrue(outcomes.stream().allMatch(
                outcome -> outcome == DingTalkTokenOutcome.ACQUIRED
                    || outcome == DingTalkTokenOutcome.SINGLE_FLIGHT_JOIN
                    || outcome == DingTalkTokenOutcome.CACHE_HIT
            ));
            assertEquals(0, coordinator.inFlightCount());
        }
    }

    @Test
    void tenantsNeverShareCacheKeysOrMaterial() {
        var tenantA = DingTalkTokenTestFixtures.fixture(
            "tenant-a",
            "version-a",
            CredentialBindingState.ACTIVE
        );
        var tenantB = DingTalkTokenTestFixtures.fixture(
            "tenant-b",
            "version-b",
            CredentialBindingState.ACTIVE
        );
        tenantA.catalog().add(tenantB.descriptor());
        tenantA.source().add(tenantB.request().applicationCredentialRequest());

        try (DingTalkTokenCoordinator coordinator =
                 DingTalkTokenTestFixtures.coordinator(tenantA)) {
            try (DingTalkAccessTokenLease ignored = coordinator.acquire(tenantA.request())) {
                assertTrue(ignored.active());
            }
            try (DingTalkAccessTokenLease ignored = coordinator.acquire(tenantB.request())) {
                assertTrue(ignored.active());
            }
            assertNotEquals(tenantA.request().cacheKeyHash(), tenantB.request().cacheKeyHash());
            assertEquals(2, tenantA.endpoint().invocationCount());
            assertEquals(2, coordinator.cachedEntryCount());
        }
    }

    @Test
    void credentialRotationUsesNewVersionAndNeverFallsBack() {
        var first = DingTalkTokenTestFixtures.fixture(
            DingTalkTokenTestFixtures.TENANT,
            "app-version-1",
            CredentialBindingState.ACTIVE
        );
        var rotated = DingTalkTokenTestFixtures.fixture(
            DingTalkTokenTestFixtures.TENANT,
            "app-version-2",
            CredentialBindingState.ACTIVE
        );
        first.catalog().update(rotated.descriptor());
        first.source().add(rotated.request().applicationCredentialRequest());

        try (DingTalkTokenCoordinator coordinator = DingTalkTokenTestFixtures.coordinator(first)) {
            first.catalog().update(first.descriptor());
            try (DingTalkAccessTokenLease ignored = coordinator.acquire(first.request())) {
                assertTrue(ignored.active());
            }
            first.catalog().update(rotated.descriptor());
            try (DingTalkAccessTokenLease refreshed = coordinator.acquire(rotated.request())) {
                assertEquals(DingTalkTokenOutcome.ACQUIRED, refreshed.evidence().outcome());
            }
            assertEquals(1, coordinator.cachedEntryCount());
            assertEquals(2, first.endpoint().invocationCount());

            DingTalkTokenLifecycleException stale = assertThrows(
                DingTalkTokenLifecycleException.class,
                () -> coordinator.acquire(first.request())
            );
            assertEquals(DingTalkTokenFailure.CREDENTIAL_VERSION_DRIFT, stale.failure());
        }
    }

    @Test
    void revocationKillSwitchAndRouteDriftInvalidateCachedMaterial() {
        var revokedFixture = DingTalkTokenTestFixtures.fixture();
        try (DingTalkTokenCoordinator coordinator = DingTalkTokenTestFixtures.coordinator(
            revokedFixture
        )) {
            coordinator.acquire(revokedFixture.request()).close();
            revokedFixture.catalog().update(copyState(
                revokedFixture.descriptor(),
                CredentialBindingState.REVOKED
            ));
            assertFailure(
                () -> coordinator.acquire(revokedFixture.request()),
                DingTalkTokenFailure.CREDENTIAL_REVOKED
            );
            assertEquals(0, coordinator.cachedEntryCount());
        }

        var switchFixture = DingTalkTokenTestFixtures.fixture();
        try (DingTalkTokenCoordinator coordinator = DingTalkTokenTestFixtures.coordinator(
            switchFixture
        )) {
            coordinator.acquire(switchFixture.request()).close();
            switchFixture.killSwitch().setAllowed(false);
            assertFailure(
                () -> coordinator.acquire(switchFixture.request()),
                DingTalkTokenFailure.KILL_SWITCH_DISABLED
            );
            assertEquals(0, coordinator.cachedEntryCount());
        }

        var routeFixture = DingTalkTokenTestFixtures.fixture();
        try (DingTalkTokenCoordinator coordinator = DingTalkTokenTestFixtures.coordinator(
            routeFixture
        )) {
            coordinator.acquire(routeFixture.request()).close();
            routeFixture.routeGate().setValid(false);
            assertFailure(
                () -> coordinator.acquire(routeFixture.request()),
                DingTalkTokenFailure.ROUTE_REVALIDATION_FAILED
            );
            assertEquals(0, coordinator.cachedEntryCount());
        }
    }

    @Test
    void endpointTimeoutAndMalformedLifetimeFailClosedAndReleaseCredentialLease() {
        var timeoutFixture = DingTalkTokenTestFixtures.fixture();
        timeoutFixture.endpoint().script(DingTalkTokenFailure.ENDPOINT_TIMEOUT);
        try (DingTalkTokenCoordinator coordinator = DingTalkTokenTestFixtures.coordinator(
            timeoutFixture
        )) {
            assertFailure(
                () -> coordinator.acquire(timeoutFixture.request()),
                DingTalkTokenFailure.ENDPOINT_TIMEOUT
            );
            assertEquals(1, timeoutFixture.source().releaseCount());
            assertEquals(0, coordinator.cachedEntryCount());
        }

        var lifetimeFixture = DingTalkTokenTestFixtures.fixture();
        lifetimeFixture.endpoint().setLifetimeSeconds(1);
        try (DingTalkTokenCoordinator coordinator = DingTalkTokenTestFixtures.coordinator(
            lifetimeFixture
        )) {
            assertFailure(
                () -> coordinator.acquire(lifetimeFixture.request()),
                DingTalkTokenFailure.TOKEN_LIFETIME_INVALID
            );
            assertEquals(1, lifetimeFixture.source().releaseCount());
            assertEquals(0, coordinator.cachedEntryCount());
        }
    }

    @Test
    void applicationCredentialsAndEndpointTokenCopiesAreZeroized() {
        var fixture = DingTalkTokenTestFixtures.fixture();
        try (DingTalkTokenCoordinator coordinator = DingTalkTokenTestFixtures.coordinator(fixture);
             DingTalkAccessTokenLease ignored = coordinator.acquire(fixture.request())) {
            assertTrue(DingTalkTokenTestFixtures.allZero(fixture.endpoint().lastKey()));
            assertTrue(DingTalkTokenTestFixtures.allZero(fixture.endpoint().lastSecret()));
            assertTrue(DingTalkTokenTestFixtures.allZero(
                fixture.endpoint().lastTokenMaterial()
            ));
            String rendering = coordinator + "\n" + ignored + "\n" + ignored.evidence();
            assertFalse(rendering.contains("app-secret"));
            assertFalse(rendering.contains("fixture-token"));
            assertFalse(rendering.contains(fixture.request().trustedTenantId()));
        }
    }

    @Test
    void coordinatorCloseIsIdempotentAndRejectsNewAcquisition() {
        var fixture = DingTalkTokenTestFixtures.fixture();
        DingTalkTokenCoordinator coordinator = DingTalkTokenTestFixtures.coordinator(fixture);
        coordinator.acquire(fixture.request()).close();
        coordinator.close();
        coordinator.close();

        assertFailure(
            () -> coordinator.acquire(fixture.request()),
            DingTalkTokenFailure.COORDINATOR_CLOSED
        );
        assertEquals(0, coordinator.cachedEntryCount());
    }

    private static CredentialBindingDescriptor copyState(
        CredentialBindingDescriptor source,
        CredentialBindingState state
    ) {
        return new CredentialBindingDescriptor(
            source.reference(),
            source.tenantId(),
            source.providerKey(),
            source.credentialType(),
            source.keyId(),
            source.versionId(),
            state,
            source.notBefore(),
            source.expiresAt(),
            Set.copyOf(source.allowedOperations()),
            source.policyVersion(),
            Map.copyOf(source.metadata())
        );
    }

    private static void assertFailure(
        Runnable operation,
        DingTalkTokenFailure expected
    ) {
        DingTalkTokenLifecycleException problem = assertThrows(
            DingTalkTokenLifecycleException.class,
            operation::run
        );
        assertEquals(expected, problem.failure());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test start timeout");
            }
        } catch (InterruptedException problem) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", problem);
        }
    }
}
