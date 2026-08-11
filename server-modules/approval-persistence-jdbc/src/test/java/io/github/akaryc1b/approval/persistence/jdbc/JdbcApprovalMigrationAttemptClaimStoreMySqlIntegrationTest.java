package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalInstanceCommandFence;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.ClaimRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.ClaimResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.MigrationAttemptClaimConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.RenewalRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.RenewalResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore.ProvisioningRequest;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalRuntimeBindingStore;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.definition.ApprovalRuntimeBinding;
import io.github.akaryc1b.approval.domain.migration.ApprovalCommandOperation;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.IntentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationAttemptClaimStoreMySqlIntegrationTest
    extends MySqlApprovalProjectionStoreIntegrationSupport {

    private static final Instant NOW = Instant.parse("2026-08-11T09:30:00.123456500Z");
    private static final String WORKER = "worker-h3";
    private static final String SOURCE_BINDING_HASH = "9".repeat(64);
    private static final String TARGET_PACKAGE_HASH = "3".repeat(64);
    private static final String PLAN_HASH = "4".repeat(64);

    private ObjectMapper objectMapper;
    private JdbcTransactionManager transactionManager;
    private ApprovalReleasePackageStore releasePackages;
    private ApprovalReleaseDeploymentStore deployments;
    private ApprovalRuntimeBindingStore runtimeBindings;

    @BeforeEach
    @Override
    void reset() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        transactionManager = new JdbcTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        store = JdbcApprovalProjectionStoreFactory.create(dataSource, objectMapper);
        releasePackages = JdbcApprovalReleasePackageStoreFactory.create(dataSource);
        deployments = JdbcApprovalReleaseDeploymentStoreFactory.create(dataSource);
        runtimeBindings = JdbcApprovalRuntimeBindingStoreFactory.create(dataSource);
    }

    @Test
    void exactClaimReplaysTransitionsIntentAndFencesBusinessCommands() {
        Authority authority = seedPendingAuthority("Tenant-H3-Success");
        List<AuditEvent> audits = new ArrayList<>();
        ApprovalMigrationAttemptClaimStore claims = claimStore(audits);
        assertInstanceOf(JdbcMySqlApprovalMigrationAttemptClaimStore.class, claims);
        ClaimRequest request = claimRequest(
            authority,
            "worker-one",
            NOW.plusSeconds(10),
            NOW.plusSeconds(70),
            "request-h3-claim-one",
            "1".repeat(64)
        );

        ClaimResult first = claims.claim(request);
        ClaimResult replay = claims.claim(request);

        assertEquals(1, first.batch().claimedCount());
        assertTrue(replay.replayedExistingClaim());
        assertEquals(first.batch(), replay.batch());
        ApprovalMigrationAttempt claimed = first.attempts().getFirst();
        assertEquals(AttemptStatus.CLAIMED, claimed.status());
        assertEquals(2, claimed.revision());
        assertEquals("worker-one", claimed.leaseOwner());
        assertEquals(
            AuditHashCanonicalizer.canonicalInstant(request.leaseUntil()),
            claimed.leaseUntil()
        );
        assertEquals(1, first.fences().getFirst().revision());
        assertEquals("worker-one", first.fences().getFirst().leaseOwner());
        assertEquals(1, count("ap_process_migration_claim_batch", authority.tenantId()));
        assertEquals(1, count("ap_approval_instance_command_fence", authority.tenantId()));
        assertEquals(1, count("ap_approval_instance_command_fence_event", authority.tenantId()));
        assertEquals(2, count("ap_process_migration_attempt_event", authority.tenantId()));
        assertEquals(2, count("ap_process_migration_intent_event", authority.tenantId()));
        assertEquals(1, audits.size());

        ApprovalMigrationIntent durableIntent = intentPayload(authority);
        assertEquals(IntentStatus.RUNNING, durableIntent.status());
        assertEquals(2, durableIntent.revision());
        assertEquals("H3 bounded migration claim", durableIntent.operationReason());
        assertEquals(WORKER, durableIntent.requestedBy());
        assertEquals(
            AuditHashCanonicalizer.canonicalInstant(NOW.plusSeconds(3600)),
            durableIntent.expiresAt()
        );
        assertEquals("RUNNING", intentStatus(authority));
        assertEquals(2, intentRevision(authority));

        assertThrows(
            MigrationAttemptClaimConflictException.class,
            () -> claims.claim(claimRequest(
                authority,
                "worker-one",
                request.claimedAt(),
                request.leaseUntil(),
                request.requestId(),
                "2".repeat(64)
            ))
        );

        ApprovalInstanceCommandFence businessFence =
            JdbcApprovalInstanceCommandFenceFactory.create(dataSource);
        assertThrows(
            ApprovalInstanceCommandFence.InstanceCommandFencedException.class,
            () -> transactions.executeWithoutResult(status ->
                businessFence.guardBusinessCommand(
                    authority.tenantId(),
                    authority.instanceId(),
                    ApprovalCommandOperation.COMPLETE,
                    request.claimedAt().plusSeconds(1)
                )
            )
        );
        assertDoesNotThrow(() -> transactions.executeWithoutResult(status ->
            businessFence.guardBusinessCommand(
                authority.tenantId(),
                authority.instanceId(),
                ApprovalCommandOperation.COMPLETE,
                request.leaseUntil()
            )
        ));
    }

    @Test
    void renewsCurrentOwnerAllowsExpiryTakeoverAndRejectsStaleOwner() {
        Authority authority = seedPendingAuthority("Tenant-H3-Renew");
        ApprovalMigrationAttemptClaimStore claims = claimStore(new ArrayList<>());
        ClaimResult initial = claims.claim(claimRequest(
            authority,
            "worker-one",
            NOW.plusSeconds(10),
            NOW.plusSeconds(70),
            "request-h3-renew-claim",
            "3".repeat(64)
        ));

        RenewalResult renewed = claims.renew(new RenewalRequest(
            authority.tenantId(),
            authority.attemptId(),
            "worker-one",
            NOW.plusSeconds(20),
            NOW.plusSeconds(90),
            "request-h3-renew-one",
            "trace-h3-renew"
        ));
        RenewalResult takenOver = claims.renew(new RenewalRequest(
            authority.tenantId(),
            authority.attemptId(),
            "worker-two",
            NOW.plusSeconds(90),
            NOW.plusSeconds(150),
            "request-h3-takeover-two",
            "trace-h3-takeover"
        ));

        assertEquals(2, initial.attempts().getFirst().revision());
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
                authority.tenantId(),
                authority.attemptId(),
                "worker-one",
                NOW.plusSeconds(91),
                NOW.plusSeconds(160),
                "request-h3-stale-worker",
                "trace-h3-stale"
            ))
        );
        assertEquals(4, attemptRevision(authority));
        assertEquals("worker-two", attemptLeaseOwner(authority));
        assertEquals(3, fenceRevision(authority));
        assertEquals("worker-two", fenceLeaseOwner(authority));
        assertEquals(4, count("ap_process_migration_attempt_event", authority.tenantId()));
        assertEquals(3, count("ap_approval_instance_command_fence_event", authority.tenantId()));
    }

    @Test
    void concurrentClaimsHaveOneWinnerAndOneExactEmptyBatch() throws Exception {
        Authority authority = seedPendingAuthority("Tenant-H3-Concurrent");
        List<AuditEvent> audits = Collections.synchronizedList(new ArrayList<>());
        ApprovalMigrationAttemptClaimStore firstStore = claimStore(audits);
        ApprovalMigrationAttemptClaimStore secondStore = claimStore(audits);
        ClaimRequest firstRequest = claimRequest(
            authority,
            "worker-one",
            NOW.plusSeconds(10),
            NOW.plusSeconds(70),
            "request-h3-concurrent-one",
            "4".repeat(64)
        );
        ClaimRequest secondRequest = claimRequest(
            authority,
            "worker-two",
            NOW.plusSeconds(10),
            NOW.plusSeconds(70),
            "request-h3-concurrent-two",
            "5".repeat(64)
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ClaimResult> first = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return firstStore.claim(firstRequest);
            });
            Future<ClaimResult> second = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return secondStore.claim(secondRequest);
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<ClaimResult> results = List.of(first.get(), second.get());

            assertEquals(
                1,
                results.stream().mapToInt(result -> result.batch().claimedCount()).sum()
            );
            assertEquals(
                1,
                results.stream().filter(result -> result.batch().claimedCount() == 0).count()
            );
        }

        assertEquals(2, count("ap_process_migration_claim_batch", authority.tenantId()));
        assertEquals(1, count("ap_approval_instance_command_fence", authority.tenantId()));
        assertEquals(2, count("ap_process_migration_attempt_event", authority.tenantId()));
        assertEquals(2, audits.size());
    }

    @Test
    void tenantMismatchFailsClosedWithoutLeakingClaimAuthority() {
        Authority authority = seedPendingAuthority("Tenant-H3-Isolation");
        ApprovalMigrationAttemptClaimStore claims = claimStore(new ArrayList<>());
        ClaimRequest wrongTenant = new ClaimRequest(
            authority.tenantId().toLowerCase(),
            authority.intentId(),
            "worker-one",
            1,
            NOW.plusSeconds(10),
            NOW.plusSeconds(70),
            "request-h3-isolation",
            "trace-h3",
            "6".repeat(64)
        );

        assertThrows(MigrationAttemptClaimConflictException.class, () -> claims.claim(wrongTenant));
        assertEquals(AttemptStatus.PENDING, attemptPayload(authority).status());
        assertEquals(0, count("ap_process_migration_claim_batch", authority.tenantId()));
        assertEquals(0, count("ap_approval_instance_command_fence", authority.tenantId()));
    }

    @Test
    void claimAuditFailureRollsBackAttemptFenceIntentAndClaimBatch() {
        Authority authority = seedPendingAuthority("Tenant-H3-Claim-Rollback");
        ApprovalMigrationAttemptClaimStore claims = claimStore(event -> {
            throw new IllegalStateException("H3 claim audit unavailable");
        });

        assertThrows(
            IllegalStateException.class,
            () -> claims.claim(claimRequest(
                authority,
                "worker-audit",
                NOW.plusSeconds(10),
                NOW.plusSeconds(70),
                "request-h3-claim-rollback",
                "7".repeat(64)
            ))
        );

        assertEquals(AttemptStatus.PENDING, attemptPayload(authority).status());
        assertEquals(1, attemptRevision(authority));
        assertEquals("PENDING", intentStatus(authority));
        assertEquals(1, intentRevision(authority));
        assertEquals(0, count("ap_process_migration_claim_batch", authority.tenantId()));
        assertEquals(0, count("ap_approval_instance_command_fence", authority.tenantId()));
        assertEquals(0, count("ap_approval_instance_command_fence_event", authority.tenantId()));
        assertEquals(1, count("ap_process_migration_attempt_event", authority.tenantId()));
        assertEquals(1, count("ap_process_migration_intent_event", authority.tenantId()));
    }

    @Test
    void renewalAuditFailureRollsBackAttemptAndFenceLeaseMutation() {
        Authority authority = seedPendingAuthority("Tenant-H3-Renew-Rollback");
        ApprovalMigrationAttemptClaimStore claims = claimStore(new ArrayList<>());
        ClaimRequest initialRequest = claimRequest(
            authority,
            "worker-one",
            NOW.plusSeconds(10),
            NOW.plusSeconds(70),
            "request-h3-before-renew-rollback",
            "8".repeat(64)
        );
        claims.claim(initialRequest);
        ApprovalMigrationAttemptClaimStore failing = claimStore(event -> {
            throw new IllegalStateException("H3 renewal audit unavailable");
        });

        assertThrows(
            IllegalStateException.class,
            () -> failing.renew(new RenewalRequest(
                authority.tenantId(),
                authority.attemptId(),
                "worker-one",
                NOW.plusSeconds(20),
                NOW.plusSeconds(90),
                "request-h3-renew-rollback",
                "trace-h3-renew-rollback"
            ))
        );

        assertEquals(2, attemptRevision(authority));
        assertEquals("worker-one", attemptLeaseOwner(authority));
        assertEquals(
            AuditHashCanonicalizer.canonicalInstant(initialRequest.leaseUntil()),
            attemptPayload(authority).leaseUntil()
        );
        assertEquals(1, fenceRevision(authority));
        assertEquals("worker-one", fenceLeaseOwner(authority));
        assertEquals(2, count("ap_process_migration_attempt_event", authority.tenantId()));
        assertEquals(1, count("ap_approval_instance_command_fence_event", authority.tenantId()));
    }

    private ApprovalMigrationAttemptClaimStore claimStore(List<AuditEvent> audits) {
        return claimStore(audits::add);
    }

    private ApprovalMigrationAttemptClaimStore claimStore(
        io.github.akaryc1b.approval.application.port.AuditEventSink audit
    ) {
        return JdbcApprovalMigrationAttemptClaimStoreFactory.create(
            dataSource,
            objectMapper,
            transactionManager,
            audit,
            UUID::randomUUID
        );
    }

    private ClaimRequest claimRequest(
        Authority authority,
        String worker,
        Instant claimedAt,
        Instant leaseUntil,
        String requestId,
        String requestHash
    ) {
        return new ClaimRequest(
            authority.tenantId(),
            authority.intentId(),
            worker,
            1,
            claimedAt,
            leaseUntil,
            requestId,
            "trace-h3",
            requestHash
        );
    }

    private Authority seedPendingAuthority(String tenant) {
        UUID planId = uuid(tenant, "plan");
        UUID intentId = uuid(tenant, "intent");
        UUID instanceId = uuid(tenant, "instance");
        String businessKey = "business-" + tenant;
        String engineInstanceId = "engine-instance-" + tenant;

        MySqlApprovalProjectionProvenanceFixture.seed(
            jdbc,
            tenant,
            DEFINITION_KEY,
            DEFINITION_AT
        );
        ApprovalReleasePackage sourceRelease = releasePackages.find(
            tenant,
            DEFINITION_KEY,
            MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION
        ).orElseThrow();
        ApprovalReleaseDeployment sourceDeployment = MySqlApprovalReleaseLifecycleFixture.seedDeployed(
            deployments,
            sourceRelease,
            NOW.minusSeconds(300)
        );
        ApprovalReleasePackage targetRelease = MySqlApprovalReleaseLifecycleFixture.seedRelease(
            jdbc,
            releasePackages,
            tenant,
            DEFINITION_KEY,
            3,
            TARGET_PACKAGE_HASH,
            NOW.minusSeconds(240)
        );
        ApprovalReleaseDeployment targetDeployment = MySqlApprovalReleaseLifecycleFixture.seedDeployed(
            deployments,
            targetRelease,
            NOW.minusSeconds(180)
        );
        MySqlH2MigrationAttemptProvisioningFixture.seedActiveSourceRelease(
            dataSource,
            sourceRelease,
            WORKER,
            NOW
        );
        seedProjectionInstance(
            tenant,
            instanceId,
            businessKey,
            engineInstanceId,
            sourceRelease,
            sourceDeployment
        );
        runtimeBindings.save(binding(
            tenant,
            instanceId,
            businessKey,
            engineInstanceId,
            sourceRelease,
            sourceDeployment
        ));
        MySqlH3MigrationAttemptClaimAuthorityFixture.seed(
            jdbc,
            objectMapper,
            tenant,
            planId,
            intentId,
            instanceId,
            DEFINITION_KEY,
            WORKER,
            NOW,
            PLAN_HASH,
            SOURCE_BINDING_HASH,
            sourceRelease,
            targetRelease,
            targetDeployment
        );

        ApprovalMigrationAttemptProvisioningStore provisioning =
            JdbcApprovalMigrationAttemptProvisioningStoreFactory.create(
                dataSource,
                objectMapper,
                transactionManager,
                event -> {
                },
                UUID::randomUUID
            );
        var provisioned = provisioning.ensureInitialAttempts(new ProvisioningRequest(
            tenant,
            intentId,
            WORKER,
            NOW,
            "request-h3-provision",
            "trace-h3",
            "f".repeat(64)
        ));
        ApprovalMigrationAttempt pending = provisioned.initialAttempts().getFirst();
        assertEquals(1, provisioned.createdCount());
        assertEquals(AttemptStatus.PENDING, pending.status());
        assertEquals("PENDING", intentStatus(tenant, intentId));

        return new Authority(
            tenant,
            planId,
            intentId,
            instanceId,
            pending.attemptId(),
            sourceRelease,
            sourceDeployment,
            targetRelease,
            targetDeployment
        );
    }

    private void seedProjectionInstance(
        String tenant,
        UUID instanceId,
        String businessKey,
        String engineInstanceId,
        ApprovalReleasePackage sourceRelease,
        ApprovalReleaseDeployment sourceDeployment
    ) {
        ApprovalProjectionStore projection = JdbcApprovalProjectionStoreFactory.create(
            dataSource,
            objectMapper
        );
        transactions.executeWithoutResult(status -> {
            projection.lockDefinition(tenant, DEFINITION_KEY, 1);
            if (projection.findDefinition(tenant, DEFINITION_KEY, 1).isEmpty()) {
                projection.saveDefinition(definition(tenant));
            }
            projection.lockBusinessKey(tenant, businessKey);
            projection.createInstance(
                instance(tenant, instanceId, engineInstanceId, businessKey),
                List.of()
            );
        });
        jdbc.update("""
            update ap_approval_instance set
              release_version=?,release_package_hash=?,form_package_version=?,
              form_package_hash=?,ui_schema_version=?,ui_schema_hash=?,
              engine_definition_id=?
            where tenant_id=? and instance_id=?
            """,
            sourceRelease.releaseVersion(),
            sourceRelease.packageHash(),
            sourceRelease.formPackageVersion(),
            sourceRelease.formPackageHash(),
            sourceRelease.uiSchemaVersion(),
            sourceRelease.uiSchemaHash(),
            sourceDeployment.engineDefinitionId(),
            tenant,
            instanceId.toString()
        );
    }

    private ApprovalRuntimeBinding binding(
        String tenant,
        UUID instanceId,
        String businessKey,
        String engineInstanceId,
        ApprovalReleasePackage sourceRelease,
        ApprovalReleaseDeployment sourceDeployment
    ) {
        return new ApprovalRuntimeBinding(
            tenant,
            instanceId,
            businessKey,
            engineInstanceId,
            sourceRelease.definitionKey(),
            sourceRelease.releaseVersion(),
            sourceRelease.packageHash(),
            sourceRelease.definitionVersion(),
            sourceRelease.definitionHash(),
            sourceRelease.formPackageVersion(),
            sourceRelease.formPackageHash(),
            sourceRelease.formVersion(),
            sourceRelease.formHash(),
            sourceRelease.uiSchemaVersion(),
            sourceRelease.uiSchemaHash(),
            sourceRelease.compilerVersion(),
            sourceRelease.compiledArtifactHash(),
            sourceRelease.bpmnHash(),
            sourceRelease.deploymentMetadataHash(),
            sourceDeployment.engineDeploymentId(),
            sourceDeployment.engineDefinitionId(),
            sourceDeployment.engineVersion(),
            SOURCE_BINDING_HASH,
            WORKER,
            NOW.minusSeconds(120),
            "request-h3-binding",
            "trace-h3",
            "audit-event:h3-binding"
        );
    }

    private ApprovalMigrationAttempt attemptPayload(Authority authority) {
        String payload = jdbc.queryForObject(
            "select payload_json from ap_process_migration_attempt "
                + "where tenant_id=? and attempt_id=?",
            String.class,
            authority.tenantId(),
            authority.attemptId().toString()
        );
        return new JdbcApprovalMigrationJson(objectMapper).read(
            payload,
            ApprovalMigrationAttempt.class
        );
    }

    private ApprovalMigrationIntent intentPayload(Authority authority) {
        String payload = jdbc.queryForObject(
            "select payload_json from ap_process_migration_intent "
                + "where tenant_id=? and intent_id=?",
            String.class,
            authority.tenantId(),
            authority.intentId().toString()
        );
        return new JdbcApprovalMigrationJson(objectMapper).read(
            payload,
            ApprovalMigrationIntent.class
        );
    }

    private int count(String table, String tenant) {
        Integer value = jdbc.queryForObject(
            "select count(*) from " + table + " where tenant_id=?",
            Integer.class,
            tenant
        );
        return value == null ? 0 : value;
    }

    private long attemptRevision(Authority authority) {
        Long value = jdbc.queryForObject(
            "select revision from ap_process_migration_attempt "
                + "where tenant_id=? and attempt_id=?",
            Long.class,
            authority.tenantId(),
            authority.attemptId().toString()
        );
        return value == null ? -1 : value;
    }

    private String attemptLeaseOwner(Authority authority) {
        return jdbc.queryForObject(
            "select lease_owner from ap_process_migration_attempt "
                + "where tenant_id=? and attempt_id=?",
            String.class,
            authority.tenantId(),
            authority.attemptId().toString()
        );
    }

    private long fenceRevision(Authority authority) {
        Long value = jdbc.queryForObject(
            "select revision from ap_approval_instance_command_fence "
                + "where tenant_id=? and attempt_id=? and status='ACTIVE'",
            Long.class,
            authority.tenantId(),
            authority.attemptId().toString()
        );
        return value == null ? -1 : value;
    }

    private String fenceLeaseOwner(Authority authority) {
        return jdbc.queryForObject(
            "select lease_owner from ap_approval_instance_command_fence "
                + "where tenant_id=? and attempt_id=? and status='ACTIVE'",
            String.class,
            authority.tenantId(),
            authority.attemptId().toString()
        );
    }

    private String intentStatus(Authority authority) {
        return intentStatus(authority.tenantId(), authority.intentId());
    }

    private String intentStatus(String tenant, UUID intentId) {
        return jdbc.queryForObject(
            "select status from ap_process_migration_intent where tenant_id=? and intent_id=?",
            String.class,
            tenant,
            intentId.toString()
        );
    }

    private long intentRevision(Authority authority) {
        Long value = jdbc.queryForObject(
            "select revision from ap_process_migration_intent "
                + "where tenant_id=? and intent_id=?",
            Long.class,
            authority.tenantId(),
            authority.intentId().toString()
        );
        return value == null ? -1 : value;
    }

    private static UUID uuid(String tenant, String value) {
        return UUID.nameUUIDFromBytes(
            ("mysql-h3:" + tenant + ':' + value).getBytes(StandardCharsets.UTF_8)
        );
    }

    private record Authority(
        String tenantId,
        UUID planId,
        UUID intentId,
        UUID instanceId,
        UUID attemptId,
        ApprovalReleasePackage sourceRelease,
        ApprovalReleaseDeployment sourceDeployment,
        ApprovalReleasePackage targetRelease,
        ApprovalReleaseDeployment targetDeployment
    ) {
    }
}
