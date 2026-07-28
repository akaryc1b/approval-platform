package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalInstanceCommandFence.InstanceCommandFencedException;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.ClaimRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.ClaimResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.MigrationAttemptClaimConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.RenewalRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.RenewalResult;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalCommandOperation;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.IntentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.ATTEMPT_ID;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.INSTANCE_ID;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.INTENT_ID;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.NOW;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.TENANT;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.attempt;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.hash;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.initialAttemptEvent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.initialIntentEvent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.intent;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationAttemptClaimStoreIntegrationTest
    extends AbstractJdbcApprovalMigrationProtocolIntegrationTest {

    @Test
    void exactClaimReplaysAndFencesBusinessCommandsUntilLeaseExpiry() {
        seedPendingAttempt();
        List<AuditEvent> audits = new ArrayList<>();
        JdbcApprovalMigrationAttemptClaimStore claims = claimStore(audits);
        ClaimRequest request = claimRequest(
            "worker-one",
            NOW.plusSeconds(10),
            NOW.plusSeconds(70),
            "request-claim-one",
            hash('1')
        );

        ClaimResult first = claims.claim(request);
        ClaimResult replay = claims.claim(request);

        assertFalse(first.replayedExistingClaim());
        assertTrue(replay.replayedExistingClaim());
        assertEquals(first.batch(), replay.batch());
        assertEquals(1, first.batch().claimedCount());
        assertEquals(AttemptStatus.CLAIMED, first.attempts().getFirst().status());
        assertEquals("worker-one", first.attempts().getFirst().leaseOwner());
        assertEquals("worker-one", first.fences().getFirst().leaseOwner());
        assertEquals(1, count("ap_process_migration_claim_batch"));
        assertEquals(1, count("ap_approval_instance_command_fence"));
        assertEquals(1, count("ap_approval_instance_command_fence_event"));
        assertEquals(2, count("ap_process_migration_attempt_event"));
        assertEquals(1, audits.size());
        assertEquals(
            IntentStatus.RUNNING,
            store.findIntent(TENANT, INTENT_ID).orElseThrow().status()
        );

        assertThrows(
            MigrationAttemptClaimConflictException.class,
            () -> claims.claim(claimRequest(
                "worker-one",
                request.claimedAt(),
                request.leaseUntil(),
                request.requestId(),
                hash('2')
            ))
        );

        JdbcApprovalInstanceCommandFence businessFence = new JdbcApprovalInstanceCommandFence(
            dataSource
        );
        TransactionTemplate transactions = new TransactionTemplate(
            new JdbcTransactionManager(dataSource)
        );
        assertThrows(
            InstanceCommandFencedException.class,
            () -> transactions.executeWithoutResult(status ->
                businessFence.guardBusinessCommand(
                    TENANT,
                    INSTANCE_ID,
                    ApprovalCommandOperation.COMPLETE,
                    request.claimedAt().plusSeconds(1)
                )
            )
        );
        assertDoesNotThrow(() -> transactions.executeWithoutResult(status ->
            businessFence.guardBusinessCommand(
                TENANT,
                INSTANCE_ID,
                ApprovalCommandOperation.COMPLETE,
                request.leaseUntil()
            )
        ));
    }

    @Test
    void renewsCurrentOwnerAllowsExpiryTakeoverAndRejectsStaleOwner() {
        seedPendingAttempt();
        List<AuditEvent> audits = new ArrayList<>();
        JdbcApprovalMigrationAttemptClaimStore claims = claimStore(audits);
        ClaimResult claimed = claims.claim(claimRequest(
            "worker-one",
            NOW.plusSeconds(10),
            NOW.plusSeconds(70),
            "request-claim-renew",
            hash('3')
        ));

        RenewalResult renewed = claims.renew(new RenewalRequest(
            TENANT,
            ATTEMPT_ID,
            "worker-one",
            NOW.plusSeconds(20),
            NOW.plusSeconds(90),
            "request-renew-one",
            "trace-renew"
        ));
        RenewalResult takenOver = claims.renew(new RenewalRequest(
            TENANT,
            ATTEMPT_ID,
            "worker-two",
            NOW.plusSeconds(90),
            NOW.plusSeconds(150),
            "request-takeover-two",
            "trace-takeover"
        ));

        assertEquals(2, claimed.attempts().getFirst().revision());
        assertEquals(3, renewed.attempt().revision());
        assertEquals(2, renewed.fence().revision());
        assertEquals("worker-one", renewed.fence().leaseOwner());
        assertEquals(4, takenOver.attempt().revision());
        assertEquals(3, takenOver.fence().revision());
        assertEquals("worker-two", takenOver.attempt().leaseOwner());
        assertEquals("worker-two", takenOver.fence().leaseOwner());

        assertThrows(
            IllegalArgumentException.class,
            () -> claims.renew(new RenewalRequest(
                TENANT,
                ATTEMPT_ID,
                "worker-one",
                NOW.plusSeconds(91),
                NOW.plusSeconds(160),
                "request-stale-worker",
                "trace-stale"
            ))
        );

        ApprovalMigrationAttempt durable = store.findAttempt(TENANT, ATTEMPT_ID).orElseThrow();
        assertEquals("worker-two", durable.leaseOwner());
        assertEquals(NOW.plusSeconds(150), durable.leaseUntil());
        assertEquals(4, count("ap_process_migration_attempt_event"));
        assertEquals(3, count("ap_approval_instance_command_fence_event"));
        assertEquals(3, audits.size());
    }

    @Test
    void concurrentClaimsHaveOneWinnerAndOneExactEmptyBatch() throws Exception {
        seedPendingAttempt();
        List<AuditEvent> audits = Collections.synchronizedList(new ArrayList<>());
        JdbcApprovalMigrationAttemptClaimStore firstStore = claimStore(audits);
        JdbcApprovalMigrationAttemptClaimStore secondStore = claimStore(audits);
        ClaimRequest firstRequest = claimRequest(
            "worker-one",
            NOW.plusSeconds(10),
            NOW.plusSeconds(70),
            "request-concurrent-one",
            hash('4')
        );
        ClaimRequest secondRequest = claimRequest(
            "worker-two",
            NOW.plusSeconds(10),
            NOW.plusSeconds(70),
            "request-concurrent-two",
            hash('5')
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ClaimResult> first = executor.submit(
                () -> gatedClaim(firstStore, firstRequest, ready, start)
            );
            Future<ClaimResult> second = executor.submit(
                () -> gatedClaim(secondStore, secondRequest, ready, start)
            );
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<ClaimResult> results = List.of(
                first.get(20, TimeUnit.SECONDS),
                second.get(20, TimeUnit.SECONDS)
            );

            assertEquals(
                1,
                results.stream().mapToInt(result -> result.batch().claimedCount()).sum()
            );
            assertEquals(
                1,
                results.stream().filter(result -> result.batch().claimedCount() == 0).count()
            );
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertEquals(2, count("ap_process_migration_claim_batch"));
        assertEquals(1, count("ap_approval_instance_command_fence"));
        assertEquals(1, count("ap_approval_instance_command_fence_event"));
        assertEquals(2, count("ap_process_migration_attempt_event"));
        assertEquals(2, audits.size());
    }

    @Test
    void auditFailureRollsBackAttemptFenceIntentAndClaimBatch() {
        seedPendingAttempt();
        JdbcApprovalMigrationAttemptClaimStore claims = new JdbcApprovalMigrationAttemptClaimStore(
            dataSource,
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
            new JdbcTransactionManager(dataSource),
            event -> {
                throw new IllegalStateException("claim audit persistence failed");
            },
            UUID::randomUUID
        );

        assertThrows(
            IllegalStateException.class,
            () -> claims.claim(claimRequest(
                "worker-audit",
                NOW.plusSeconds(10),
                NOW.plusSeconds(70),
                "request-audit-failure",
                hash('6')
            ))
        );

        assertEquals(
            AttemptStatus.PENDING,
            store.findAttempt(TENANT, ATTEMPT_ID).orElseThrow().status()
        );
        assertEquals(
            IntentStatus.PENDING,
            store.findIntent(TENANT, INTENT_ID).orElseThrow().status()
        );
        assertEquals(0, count("ap_process_migration_claim_batch"));
        assertEquals(0, count("ap_approval_instance_command_fence"));
        assertEquals(0, count("ap_approval_instance_command_fence_event"));
        assertEquals(1, count("ap_process_migration_attempt_event"));
    }

    private void seedPendingAttempt() {
        ApprovalMigrationIntent migrationIntent = intent();
        store.createIntent(
            migrationIntent,
            initialIntentEvent(migrationIntent, "claim-intent")
        );
        ApprovalMigrationAttempt pending = attempt();
        store.createAttempt(pending, initialAttemptEvent(pending, "claim-attempt"));
    }

    private JdbcApprovalMigrationAttemptClaimStore claimStore(List<AuditEvent> audits) {
        return new JdbcApprovalMigrationAttemptClaimStore(
            dataSource,
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
            new JdbcTransactionManager(dataSource),
            audits::add,
            UUID::randomUUID
        );
    }

    private static ClaimRequest claimRequest(
        String worker,
        java.time.Instant claimedAt,
        java.time.Instant leaseUntil,
        String requestId,
        String requestHash
    ) {
        return new ClaimRequest(
            TENANT,
            INTENT_ID,
            worker,
            1,
            claimedAt,
            leaseUntil,
            requestId,
            "trace-claim",
            requestHash
        );
    }

    private static ClaimResult gatedClaim(
        JdbcApprovalMigrationAttemptClaimStore claims,
        ClaimRequest request,
        CountDownLatch ready,
        CountDownLatch start
    ) throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return claims.claim(request);
    }
}
