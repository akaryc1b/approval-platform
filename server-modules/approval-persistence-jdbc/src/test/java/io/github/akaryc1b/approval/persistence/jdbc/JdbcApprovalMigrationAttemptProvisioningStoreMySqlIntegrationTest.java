package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore.ProvisioningRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore.ProvisioningResult;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalRuntimeBindingStore;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.definition.ApprovalRuntimeBinding;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationAttemptProvisioningStoreMySqlIntegrationTest
    extends MySqlApprovalProjectionStoreIntegrationSupport {

    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00.123456500Z");
    private static final String WORKER = "worker-h2";
    private static final String SOURCE_BINDING_HASH = "9".repeat(64);
    private static final String DIFFERENT_BINDING_HASH = "1".repeat(64);
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
    void createsExactAttemptReplaysAndCanonicalizesTime() {
        Authority authority = seedAuthority("Tenant-H2-Success", SOURCE_BINDING_HASH);
        List<AuditEvent> audits = new ArrayList<>();
        ApprovalMigrationAttemptProvisioningStore provisioning = provisioningStore(audits);
        assertInstanceOf(JdbcMySqlApprovalMigrationAttemptProvisioningStore.class, provisioning);
        ProvisioningRequest request = request(authority, "request-h2-success", "7".repeat(64));

        ProvisioningResult first = provisioning.ensureInitialAttempts(request);
        ProvisioningResult replay = provisioning.ensureInitialAttempts(request);

        assertEquals(1, first.createdCount());
        assertEquals(0, replay.createdCount());
        assertTrue(replay.replayedExistingProvisioning());
        assertEquals(first.initialAttempts(), replay.initialAttempts());
        ApprovalMigrationAttempt attempt = first.initialAttempts().getFirst();
        assertEquals(authority.instanceId(), attempt.approvalInstanceId());
        assertEquals(authority.intentId(), attempt.intentId());
        assertEquals(AttemptStatus.PENDING, attempt.status());
        assertEquals(1, attempt.revision());
        assertEquals(1, attempt.attemptNumber());
        assertEquals(SOURCE_BINDING_HASH, attempt.expectedBindingEvidenceHash());
        assertEquals(
            authority.sourceDeployment().engineDefinitionId(),
            attempt.sourceEngineDefinitionId()
        );
        assertEquals(
            authority.targetDeployment().engineDefinitionId(),
            attempt.targetEngineDefinitionId()
        );
        assertEquals(AuditHashCanonicalizer.canonicalInstant(NOW), attempt.createdAt());
        assertEquals(AuditHashCanonicalizer.canonicalInstant(NOW), storedCreatedAt(authority));
        assertEquals(1, count("ap_process_migration_attempt", authority.tenantId()));
        assertEquals(1, count("ap_process_migration_attempt_event", authority.tenantId()));
        assertEquals(1, audits.size());
    }

    @Test
    void concurrentProvisioningHasOneCreatorAndOneAuthoritativeReplay() throws Exception {
        Authority authority = seedAuthority("Tenant-H2-Concurrent", SOURCE_BINDING_HASH);
        List<AuditEvent> audits = Collections.synchronizedList(new ArrayList<>());
        ApprovalMigrationAttemptProvisioningStore firstStore = provisioningStore(audits);
        ApprovalMigrationAttemptProvisioningStore secondStore = provisioningStore(audits);
        ProvisioningRequest firstRequest = request(
            authority,
            "request-h2-concurrent-one",
            "8".repeat(64)
        );
        ProvisioningRequest secondRequest = request(
            authority,
            "request-h2-concurrent-two",
            "a".repeat(64)
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ProvisioningResult> first = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return firstStore.ensureInitialAttempts(firstRequest);
            });
            Future<ProvisioningResult> second = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return secondStore.ensureInitialAttempts(secondRequest);
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<ProvisioningResult> results = List.of(first.get(), second.get());

            assertEquals(1, results.stream().mapToInt(ProvisioningResult::createdCount).sum());
            assertEquals(
                1,
                results.stream()
                    .filter(ProvisioningResult::replayedExistingProvisioning)
                    .count()
            );
            assertEquals(results.get(0).initialAttempts(), results.get(1).initialAttempts());
        }

        assertEquals(1, count("ap_process_migration_attempt", authority.tenantId()));
        assertEquals(1, count("ap_process_migration_attempt_event", authority.tenantId()));
        assertEquals(1, audits.size());
    }

    @Test
    void runtimeBindingDriftFailsClosedBeforeAttemptCreation() {
        Authority authority = seedAuthority("Tenant-H2-Drift", DIFFERENT_BINDING_HASH);
        ApprovalMigrationAttemptProvisioningStore provisioning = provisioningStore(
            new ArrayList<>()
        );

        assertThrows(
            ApprovalMigrationAttemptProvisioningStore.MigrationAttemptProvisioningConflictException.class,
            () -> provisioning.ensureInitialAttempts(request(
                authority,
                "request-h2-drift",
                "b".repeat(64)
            ))
        );

        assertEquals(0, count("ap_process_migration_attempt", authority.tenantId()));
        assertEquals(0, count("ap_process_migration_attempt_event", authority.tenantId()));
    }

    @Test
    void tenantMismatchFailsClosedWithoutLeakingAuthority() {
        Authority authority = seedAuthority("Tenant-H2-Isolation", SOURCE_BINDING_HASH);
        ApprovalMigrationAttemptProvisioningStore provisioning = provisioningStore(
            new ArrayList<>()
        );
        ProvisioningRequest wrongTenant = new ProvisioningRequest(
            authority.tenantId().toLowerCase(),
            authority.intentId(),
            WORKER,
            NOW,
            "request-h2-isolation",
            "trace-h2",
            "c".repeat(64)
        );

        assertThrows(
            ApprovalMigrationAttemptProvisioningStore.MigrationAttemptProvisioningConflictException.class,
            () -> provisioning.ensureInitialAttempts(wrongTenant)
        );
        assertEquals(0, count("ap_process_migration_attempt", authority.tenantId()));
    }

    @Test
    void auditFailureRollsBackRealAttemptAndEventInserts() {
        Authority authority = seedAuthority("Tenant-H2-Rollback", SOURCE_BINDING_HASH);
        ApprovalMigrationAttemptProvisioningStore provisioning =
            JdbcApprovalMigrationAttemptProvisioningStoreFactory.create(
                dataSource,
                objectMapper,
                transactionManager,
                event -> {
                    throw new IllegalStateException("H2 provisioning audit unavailable");
                },
                UUID::randomUUID
            );

        assertThrows(
            IllegalStateException.class,
            () -> provisioning.ensureInitialAttempts(request(
                authority,
                "request-h2-rollback",
                "d".repeat(64)
            ))
        );

        assertEquals(0, count("ap_process_migration_attempt", authority.tenantId()));
        assertEquals(0, count("ap_process_migration_attempt_event", authority.tenantId()));
    }

    private ApprovalMigrationAttemptProvisioningStore provisioningStore(List<AuditEvent> audits) {
        return JdbcApprovalMigrationAttemptProvisioningStoreFactory.create(
            dataSource,
            objectMapper,
            transactionManager,
            audits::add,
            UUID::randomUUID
        );
    }

    private ProvisioningRequest request(Authority authority, String requestId, String requestHash) {
        return new ProvisioningRequest(
            authority.tenantId(),
            authority.intentId(),
            WORKER,
            NOW,
            requestId,
            "trace-h2",
            requestHash
        );
    }

    private Authority seedAuthority(String tenant, String expectedBindingHash) {
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
        ApprovalReleaseDeployment sourceDeployment =
            MySqlApprovalReleaseLifecycleFixture.seedDeployed(
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
        ApprovalReleaseDeployment targetDeployment =
            MySqlApprovalReleaseLifecycleFixture.seedDeployed(
                deployments,
                targetRelease,
                NOW.minusSeconds(180)
            );
        MySqlH2MigrationAttemptProvisioningFixture.seedActiveSourceRelease(
            dataSource,
            sourceRelease,
            WORKER
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
        MySqlH1MigrationPlanAuthorityFixture.seed(
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
            expectedBindingHash,
            sourceRelease,
            targetRelease,
            targetDeployment
        );
        return new Authority(
            tenant,
            planId,
            intentId,
            instanceId,
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
            "request-h2-binding",
            "trace-h2",
            "audit-event:h2-binding"
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

    private Instant storedCreatedAt(Authority authority) {
        LocalDateTime value = jdbc.queryForObject(
            "select created_at from ap_process_migration_attempt "
                + "where tenant_id=? and intent_id=?",
            LocalDateTime.class,
            authority.tenantId(),
            authority.intentId().toString()
        );
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static UUID uuid(String tenant, String value) {
        return UUID.nameUUIDFromBytes(
            ("mysql-h2:" + tenant + ':' + value).getBytes(StandardCharsets.UTF_8)
        );
    }

    private record Authority(
        String tenantId,
        UUID planId,
        UUID intentId,
        UUID instanceId,
        ApprovalReleasePackage sourceRelease,
        ApprovalReleaseDeployment sourceDeployment,
        ApprovalReleasePackage targetRelease,
        ApprovalReleaseDeployment targetDeployment
    ) {
    }
}
