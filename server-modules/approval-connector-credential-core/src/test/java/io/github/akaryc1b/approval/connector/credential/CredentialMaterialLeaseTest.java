package io.github.akaryc1b.approval.connector.credential;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialMaterialLeaseTest {

    private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");

    @Test
    void normalCloseZeroizesOwnedInputAndEveryScopedCopy() {
        Fixture fixture = fixture();
        byte[] owned = "test-owned-material".getBytes();
        AtomicReference<byte[]> captured = new AtomicReference<>();
        CredentialMaterialLease lease = lease(fixture, owned, () -> { });

        assertTrue(DeterministicCredentialMaterialSource.allZero(owned));
        lease.useMaterial(material -> {
            captured.set(material);
            assertFalse(DeterministicCredentialMaterialSource.allZero(material));
        });
        assertTrue(DeterministicCredentialMaterialSource.allZero(captured.get()));

        lease.close();
        assertTrue(lease.closed());
        assertTrue(lease.auditEvidence().closed());
        assertFalse(lease.auditEvidence().releaseFailed());
    }

    @Test
    void callbackFailureStillClosesAndZeroizes() {
        Fixture fixture = fixture();
        DeterministicCredentialMaterialSource source = fixture.source();
        AtomicReference<byte[]> captured = new AtomicReference<>();

        assertThrows(IllegalArgumentException.class, () -> CredentialMaterialLeaseSupport.withLease(
            source,
            fixture.request(),
            lease -> lease.useMaterial(material -> {
                captured.set(material);
                throw new IllegalArgumentException("caller failure");
            })
        ));

        assertTrue(DeterministicCredentialMaterialSource.allZero(captured.get()));
        assertEquals(1, source.openCount());
        assertEquals(1, source.releaseCount());
    }

    @Test
    void timeoutAndCancellationPathsCloseTheLease() {
        assertPathCloses(new CredentialMaterialLeaseException(CredentialMaterialFailure.TIMEOUT));
        assertPathCloses(new CredentialMaterialLeaseException(CredentialMaterialFailure.CANCELLED));
    }

    @Test
    void duplicateCloseIsSafeAndUseAfterCloseIsRejected() {
        Fixture fixture = fixture();
        CredentialMaterialLease lease = fixture.source().openLease(fixture.request());
        lease.close();
        lease.close();

        CredentialMaterialLeaseException failure = assertThrows(
            CredentialMaterialLeaseException.class,
            () -> lease.useMaterial(material -> { })
        );
        assertEquals(CredentialMaterialFailure.LEASE_CLOSED, failure.failure());
    }

    @Test
    void concurrentUseIsRejectedWithoutASecondMaterialCopy() throws Exception {
        Fixture fixture = fixture();
        CredentialMaterialLease lease = fixture.source().openLease(fixture.request());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                lease.useMaterial(material -> {
                    entered.countDown();
                    await(release);
                });
            } catch (Throwable failure) {
                workerFailure.set(failure);
            }
        });
        worker.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        CredentialMaterialLeaseException failure = assertThrows(
            CredentialMaterialLeaseException.class,
            () -> lease.useMaterial(material -> { })
        );
        assertEquals(CredentialMaterialFailure.CONCURRENT_ACCESS_REJECTED, failure.failure());
        release.countDown();
        worker.join(5_000);
        assertTrue(workerFailure.get() == null);
        lease.close();
    }

    @Test
    void closeDuringUseDefersReleaseButPreventsAnotherUse() throws Exception {
        Fixture fixture = fixture();
        DeterministicCredentialMaterialSource source = fixture.source();
        CredentialMaterialLease lease = source.openLease(fixture.request());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread worker = new Thread(() -> lease.useMaterial(material -> {
            entered.countDown();
            await(release);
        }));
        worker.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        lease.close();
        assertFalse(lease.active());
        assertTrue(lease.auditEvidence().closeRequested());
        assertThrows(
            CredentialMaterialLeaseException.class,
            () -> lease.useMaterial(material -> { })
        );

        release.countDown();
        worker.join(5_000);
        assertTrue(lease.closed());
        assertEquals(1, source.releaseCount());
    }

    @Test
    void releaseFailureLeavesLeaseClosedAndRedactsBackendText() {
        Fixture fixture = fixture();
        DeterministicCredentialMaterialSource source = fixture.source();
        source.failRelease();
        CredentialMaterialLease lease = source.openLease(fixture.request());

        CredentialMaterialLeaseException failure = assertThrows(
            CredentialMaterialLeaseException.class,
            lease::close
        );
        assertEquals(CredentialMaterialFailure.RELEASE_FAILED, failure.failure());
        assertFalse(failure.getMessage().contains("test-only"));
        assertTrue(lease.closed());
        assertTrue(lease.auditEvidence().releaseFailed());
        lease.close();
    }

    @Test
    void exceptionDescriptorAuditAndRenderingContainNoMaterial() {
        Fixture fixture = fixture();
        DeterministicCredentialMaterialSource source = fixture.source();
        CredentialMaterialLease lease = source.openLease(fixture.request());
        String rendering = lease + "\n" + lease.descriptor() + "\n" + lease.auditEvidence();
        lease.close();

        assertFalse(rendering.contains("deterministic-test-material"));
        assertFalse(rendering.contains(fixture.request().tenantId()));
        assertFalse(rendering.contains(fixture.request().credentialReference().referenceId()));
    }

    private static void assertPathCloses(RuntimeException expected) {
        Fixture fixture = fixture();
        DeterministicCredentialMaterialSource source = fixture.source();
        RuntimeException actual = assertThrows(
            expected.getClass(),
            () -> CredentialMaterialLeaseSupport.withLease(
                source,
                fixture.request(),
                lease -> {
                    throw expected;
                }
            )
        );
        assertEquals(expected.getMessage(), actual.getMessage());
        assertEquals(1, source.releaseCount());
    }

    private static CredentialMaterialLease lease(
        Fixture fixture,
        byte[] material,
        CredentialMaterialLease.CredentialMaterialRelease release
    ) {
        CredentialMaterialDescriptor descriptor = CredentialMaterialDescriptor.loaded(
            fixture.request(),
            CanonicalPayloadHash.sha256Utf8("source-evidence"),
            10
        );
        return CredentialMaterialLease.takeOwnership(
            fixture.request(),
            descriptor,
            material,
            () -> 11,
            release
        );
    }

    private static Fixture fixture() {
        CredentialReference reference = new CredentialReference("dingtalk", "credential-fixture");
        CredentialBindingDescriptor descriptor = new CredentialBindingDescriptor(
            reference,
            "tenant-fixture",
            "dingtalk",
            CredentialMaterialType.APP_KEY_SECRET,
            "key-fixture",
            "version-1",
            CredentialBindingState.ACTIVE,
            NOW.minusSeconds(60),
            NOW.plusSeconds(600),
            Set.of(ConnectorOperation.ORGANIZATION_READ),
            "policy-1",
            Map.of("ownerClass", "platform-security")
        );
        CredentialMaterialVersion version = new CredentialMaterialVersion(
            "version-1",
            NOW.minusSeconds(60),
            NOW.plusSeconds(600),
            CanonicalPayloadHash.sha256Utf8("version-1")
        );
        CredentialMaterialRequest request = new CredentialMaterialRequest(
            reference,
            "tenant-fixture",
            "dingtalk",
            CanonicalPayloadHash.sha256Utf8("route-plan"),
            descriptor.fingerprint(),
            version,
            CredentialMaterialType.APP_KEY_SECRET,
            ConnectorOperation.ORGANIZATION_READ,
            "DINGTALK_JAVA21_FIXED_HTTPS_V1",
            "ORGANIZATION",
            CredentialMaterialEnvironment.NON_PRODUCTION,
            "policy-1"
        );
        return new Fixture(
            request,
            descriptor,
            new DeterministicCredentialMaterialSource(request, descriptor, NOW)
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timeout");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", failure);
        }
    }

    private record Fixture(
        CredentialMaterialRequest request,
        CredentialBindingDescriptor descriptor,
        DeterministicCredentialMaterialSource source
    ) {
    }
}
