package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalMigrationAttemptClaimService.ClaimCommand;
import io.github.akaryc1b.approval.application.ApprovalMigrationAttemptClaimService.RenewalCommand;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.ClaimRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.ClaimResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.RenewalRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.RenewalResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore.ProvisioningRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore.ProvisioningResult;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationClaimBatch;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationCommandFence;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalMigrationAttemptClaimServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-25T16:00:00Z");
    private static final String TENANT = "tenant-migration-claim";
    private static final UUID INTENT_ID = UUID.fromString(
        "88000000-0000-0000-0000-000000000001"
    );

    @Test
    void provisionsBeforeClaimWithServerOwnedWorkerAndExactRequestHash() {
        List<String> order = new ArrayList<>();
        RecordingProvisioning provisioning = new RecordingProvisioning(order);
        RecordingClaims claims = new RecordingClaims(order);
        ApprovalMigrationAttemptClaimService service = service(
            provisioning,
            claims,
            () -> "server-worker-01",
            Duration.ofMinutes(2)
        );

        ClaimResult result = service.claim(new ClaimCommand(
            TENANT,
            INTENT_ID,
            5,
            "request-claim-01",
            "trace-claim"
        ));

        assertEquals(List.of("provision", "claim"), order);
        assertEquals("server-worker-01", provisioning.request.workerId());
        assertEquals("server-worker-01", claims.claimRequest.workerId());
        assertEquals(provisioning.request.requestHash(), claims.claimRequest.requestHash());
        assertEquals(NOW, provisioning.request.happenedAt());
        assertEquals(NOW, claims.claimRequest.claimedAt());
        assertEquals(NOW.plusSeconds(120), claims.claimRequest.leaseUntil());
        assertSame(claims.result, result);
    }

    @Test
    void renewalUsesFreshServerOwnedWorkerAndBoundedLease() {
        RecordingProvisioning provisioning = new RecordingProvisioning(new ArrayList<>());
        RecordingClaims claims = new RecordingClaims(new ArrayList<>());
        ApprovalMigrationAttemptClaimService service = service(
            provisioning,
            claims,
            () -> "server-worker-renew",
            Duration.ofMinutes(3)
        );
        UUID attemptId = UUID.fromString("88000000-0000-0000-0000-000000000002");

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> service.renew(new RenewalCommand(
                TENANT,
                attemptId,
                "request-renew-01",
                "trace-renew"
            ))
        );

        assertEquals("stop after recording renewal", failure.getMessage());
        assertEquals(TENANT, claims.renewalRequest.tenantId());
        assertEquals(attemptId, claims.renewalRequest.attemptId());
        assertEquals("server-worker-renew", claims.renewalRequest.workerId());
        assertEquals(NOW, claims.renewalRequest.happenedAt());
        assertEquals(NOW.plusSeconds(180), claims.renewalRequest.leaseUntil());
    }

    @Test
    void rejectsUnboundedClaimAndLeaseConfiguration() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new ClaimCommand(TENANT, INTENT_ID, 0, "request-low", null)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new ClaimCommand(TENANT, INTENT_ID, 101, "request-high", null)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> service(
                new RecordingProvisioning(new ArrayList<>()),
                new RecordingClaims(new ArrayList<>()),
                () -> "server-worker",
                Duration.ofMinutes(16)
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> service(
                new RecordingProvisioning(new ArrayList<>()),
                new RecordingClaims(new ArrayList<>()),
                () -> "server-worker",
                Duration.ZERO
            )
        );
    }

    private static ApprovalMigrationAttemptClaimService service(
        ApprovalMigrationAttemptProvisioningStore provisioning,
        ApprovalMigrationAttemptClaimStore claims,
        java.util.function.Supplier<String> worker,
        Duration lease
    ) {
        return new ApprovalMigrationAttemptClaimService(
            provisioning,
            claims,
            new ApprovalReleasePackageHasher(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            worker,
            lease
        );
    }

    private static final class RecordingProvisioning
        implements ApprovalMigrationAttemptProvisioningStore {

        private final List<String> order;
        private ProvisioningRequest request;

        private RecordingProvisioning(List<String> order) {
            this.order = order;
        }

        @Override
        public ProvisioningResult ensureInitialAttempts(ProvisioningRequest value) {
            order.add("provision");
            request = value;
            return new ProvisioningResult(List.of(), 0);
        }
    }

    private static final class RecordingClaims implements ApprovalMigrationAttemptClaimStore {

        private final List<String> order;
        private ClaimRequest claimRequest;
        private RenewalRequest renewalRequest;
        private ClaimResult result;

        private RecordingClaims(List<String> order) {
            this.order = order;
        }

        @Override
        public ClaimResult claim(ClaimRequest request) {
            order.add("claim");
            claimRequest = request;
            ApprovalMigrationClaimBatch batch = new ApprovalMigrationClaimBatch(
                UUID.fromString("88000000-0000-0000-0000-000000000003"),
                request.tenantId(),
                request.intentId(),
                request.workerId(),
                request.limit(),
                List.of(),
                List.of(),
                request.requestHash(),
                request.claimedAt(),
                request.requestId(),
                request.traceId()
            );
            result = new ClaimResult(batch, List.of(), List.of(), false);
            return result;
        }

        @Override
        public RenewalResult renew(RenewalRequest request) {
            renewalRequest = request;
            throw new IllegalStateException("stop after recording renewal");
        }

        @Override
        public Optional<ApprovalMigrationCommandFence> findFence(
            String tenantId,
            UUID attemptId
        ) {
            assertTrue(tenantId.startsWith("tenant-"));
            return Optional.empty();
        }
    }
}
