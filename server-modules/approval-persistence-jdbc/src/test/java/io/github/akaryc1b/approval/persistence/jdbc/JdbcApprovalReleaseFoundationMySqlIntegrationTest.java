package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalReleaseFoundationMySqlIntegrationTest
    extends MySqlApprovalProjectionStoreIntegrationSupport {

    private static final UUID DEPLOYMENT_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000008701"
    );
    private static final UUID OTHER_DEPLOYMENT_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000008702"
    );
    private static final Instant PACKAGE_AT = Instant.parse(
        "2026-08-11T01:02:03.999999500Z"
    );
    private static final Instant DEPLOYMENT_AT = Instant.parse(
        "2026-08-11T02:03:04.999999500Z"
    );

    private ApprovalReleasePackageStore releasePackages;
    private ApprovalReleaseDeploymentStore deployments;

    @BeforeEach
    @Override
    void reset() {
        jdbc.update("delete from ap_approval_release_deployment");
        super.reset();
        releasePackages = JdbcApprovalReleasePackageStoreFactory.create(dataSource);
        deployments = JdbcApprovalReleaseDeploymentStoreFactory.create(dataSource);
    }

    @Test
    void releasePackageRoundTripsStrictImmutableIdentityTenantListingUuidAndTime() {
        assertInstanceOf(JdbcMySqlApprovalReleasePackageStore.class, releasePackages);
        ApprovalReleasePackage seeded = releasePackages.find(
            OTHER_TENANT,
            DEFINITION_KEY,
            MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION
        ).orElseThrow();
        jdbc.update(
            """
            delete from ap_approval_release_package
            where tenant_id = ? and definition_key = ? and release_version = ?
            """,
            OTHER_TENANT,
            DEFINITION_KEY,
            MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION
        );
        ApprovalReleasePackage candidate = withPublishedAt(seeded, PACKAGE_AT);

        releasePackages.save(candidate);

        ApprovalReleasePackage restored = releasePackages.find(
            OTHER_TENANT,
            DEFINITION_KEY,
            candidate.releaseVersion()
        ).orElseThrow();
        assertEquals(canonical(candidate), restored);
        assertEquals(
            restored,
            releasePackages.findLatest(OTHER_TENANT, DEFINITION_KEY).orElseThrow()
        );
        assertEquals(
            restored,
            releasePackages.findByDraft(OTHER_TENANT, candidate.sourceDraftId()).orElseThrow()
        );
        ApprovalReleasePackageStore.ReleasePage page = releasePackages.findReleases(
            new ApprovalReleasePackageStore.ReleaseCriteria(
                OTHER_TENANT,
                DEFINITION_KEY,
                10,
                0
            )
        );
        assertEquals(1, page.total());
        assertEquals(List.of(restored), page.items());
        assertFalse(releasePackages.find(
            OTHER_TENANT.toUpperCase(java.util.Locale.ROOT),
            DEFINITION_KEY,
            candidate.releaseVersion()
        ).isPresent());
        assertEquals(
            AuditHashCanonicalizer.canonicalInstant(PACKAGE_AT),
            restored.publishedAt()
        );
        assertThrows(DataAccessException.class, () -> releasePackages.save(candidate));
    }

    @Test
    void releasePackageLockRequiresTransactionBlocksCompetitorAndReleasesAfterRollback()
        throws Exception {
        assertThrows(
            IllegalStateException.class,
            () -> releasePackages.lockVersion(
                TENANT,
                DEFINITION_KEY,
                MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION
            )
        );
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempting = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> transactions.execute(status -> {
                releasePackages.lockVersion(
                    TENANT,
                    DEFINITION_KEY,
                    MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION
                );
                firstLocked.countDown();
                await(releaseFirst);
                throw new RollbackMarker();
            }));
            Future<Boolean> second = executor.submit(() -> {
                await(firstLocked);
                secondAttempting.countDown();
                return transactions.execute(status -> {
                    releasePackages.lockVersion(
                        TENANT,
                        DEFINITION_KEY,
                        MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION
                    );
                    return Boolean.TRUE;
                });
            });

            assertTrue(firstLocked.await(10, TimeUnit.SECONDS));
            assertTrue(secondAttempting.await(10, TimeUnit.SECONDS));
            assertThrows(
                TimeoutException.class,
                () -> second.get(500, TimeUnit.MILLISECONDS)
            );
            releaseFirst.countDown();
            ExecutionException rollback = assertThrows(
                ExecutionException.class,
                () -> first.get(20, TimeUnit.SECONDS)
            );
            assertInstanceOf(RollbackMarker.class, rollback.getCause());
            assertTrue(second.get(20, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void deploymentRoundTripsTenantCasStateTransitionsUuidAndCanonicalTime() {
        assertInstanceOf(JdbcMySqlApprovalReleaseDeploymentStore.class, deployments);
        ApprovalReleaseDeployment pending = pending(
            TENANT,
            DEPLOYMENT_ID,
            DEPLOYMENT_AT
        );
        deployments.save(pending);

        assertEquals(canonical(pending), deployments.find(
            TENANT,
            DEFINITION_KEY,
            MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION
        ).orElseThrow());
        assertFalse(deployments.find(
            TENANT.toLowerCase(),
            DEFINITION_KEY,
            MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION
        ).isPresent());
        assertFalse(deployments.update(failed(pending), 0));

        ApprovalReleaseDeployment failed = failed(pending);
        assertTrue(deployments.update(failed, 1));
        ApprovalReleaseDeployment retry = retryPending(failed);
        assertTrue(deployments.update(retry, 1));
        ApprovalReleaseDeployment deployed = deployed(retry);
        assertTrue(deployments.update(deployed, 2));

        ApprovalReleaseDeployment restored = deployments.find(
            TENANT,
            DEFINITION_KEY,
            MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION
        ).orElseThrow();
        assertEquals(canonical(deployed), restored);
        assertEquals(ApprovalReleaseDeployment.Status.DEPLOYED, restored.status());
        assertEquals(2, restored.attemptCount());
        assertEquals("engine-deployment-g1", restored.engineDeploymentId());
        assertEquals(
            AuditHashCanonicalizer.canonicalInstant(DEPLOYMENT_AT),
            restored.createdAt()
        );
        assertEquals(List.of(restored), deployments.findByDefinition(TENANT, DEFINITION_KEY));

        ApprovalReleaseDeployment other = pending(
            OTHER_TENANT,
            OTHER_DEPLOYMENT_ID,
            DEPLOYMENT_AT.plusSeconds(10)
        );
        deployments.save(other);
        assertTrue(deployments.find(
            OTHER_TENANT,
            DEFINITION_KEY,
            MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION
        ).isPresent());
        assertThrows(DataAccessException.class, () -> deployments.save(pending));
        assertThrows(DataAccessException.class, () -> deployments.save(pending(
            "tenant-without-package",
            UUID.fromString("00000000-0000-0000-0000-000000008703"),
            DEPLOYMENT_AT
        )));
    }

    @Test
    void deploymentLockBlocksCompetitorAndRollbackRemovesInsertAndReleasesLock()
        throws Exception {
        assertThrows(
            IllegalStateException.class,
            () -> deployments.lock(
                TENANT,
                DEFINITION_KEY,
                MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION
            )
        );
        ApprovalReleaseDeployment pending = pending(
            TENANT,
            DEPLOYMENT_ID,
            DEPLOYMENT_AT
        );
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempting = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> transactions.execute(status -> {
                deployments.lock(
                    TENANT,
                    DEFINITION_KEY,
                    MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION
                );
                deployments.save(pending);
                firstLocked.countDown();
                await(releaseFirst);
                throw new RollbackMarker();
            }));
            Future<Boolean> second = executor.submit(() -> {
                await(firstLocked);
                secondAttempting.countDown();
                return transactions.execute(status -> {
                    deployments.lock(
                        TENANT,
                        DEFINITION_KEY,
                        MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION
                    );
                    assertFalse(deployments.find(
                        TENANT,
                        DEFINITION_KEY,
                        MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION
                    ).isPresent());
                    deployments.save(pending);
                    return Boolean.TRUE;
                });
            });

            assertTrue(firstLocked.await(10, TimeUnit.SECONDS));
            assertTrue(secondAttempting.await(10, TimeUnit.SECONDS));
            assertThrows(
                TimeoutException.class,
                () -> second.get(500, TimeUnit.MILLISECONDS)
            );
            releaseFirst.countDown();
            ExecutionException rollback = assertThrows(
                ExecutionException.class,
                () -> first.get(20, TimeUnit.SECONDS)
            );
            assertInstanceOf(RollbackMarker.class, rollback.getCause());
            assertTrue(second.get(20, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
        }

        assertEquals(1, count(
            "select count(*) from ap_approval_release_deployment "
                + "where tenant_id = ? and definition_key = ? and release_version = ?",
            TENANT,
            DEFINITION_KEY,
            MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION
        ));
    }

    @Test
    void packageAndDeploymentLockNamespacesRemainDistinctWithinOneTransaction() {
        transactions.executeWithoutResult(status -> {
            releasePackages.lockVersion(
                TENANT,
                DEFINITION_KEY,
                MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION
            );
            deployments.lock(
                TENANT,
                DEFINITION_KEY,
                MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION
            );
            deployments.save(pending(TENANT, DEPLOYMENT_ID, DEPLOYMENT_AT));
        });
        assertTrue(deployments.find(
            TENANT,
            DEFINITION_KEY,
            MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION
        ).isPresent());
    }

    private static ApprovalReleasePackage withPublishedAt(
        ApprovalReleasePackage value,
        Instant publishedAt
    ) {
        return new ApprovalReleasePackage(
            value.tenantId(),
            value.definitionKey(),
            value.releaseVersion(),
            value.definitionVersion(),
            value.definitionHash(),
            value.formPackageVersion(),
            value.formPackageHash(),
            value.formVersion(),
            value.formHash(),
            value.uiSchemaVersion(),
            value.uiSchemaHash(),
            value.compilerVersion(),
            value.bpmnResourceName(),
            value.bpmnArtifact(),
            value.compiledArtifactHash(),
            value.bpmnHash(),
            value.dmnArtifact(),
            value.dmnHash(),
            value.deploymentMetadataHash(),
            value.packageHash(),
            value.sourceDraftId(),
            value.publishedBy(),
            publishedAt
        );
    }

    private static ApprovalReleasePackage canonical(ApprovalReleasePackage value) {
        return withPublishedAt(
            value,
            AuditHashCanonicalizer.canonicalInstant(value.publishedAt())
        );
    }

    private static ApprovalReleaseDeployment pending(
        String tenantId,
        UUID deploymentId,
        Instant createdAt
    ) {
        return new ApprovalReleaseDeployment(
            deploymentId,
            tenantId,
            DEFINITION_KEY,
            MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION,
            MySqlApprovalProjectionProvenanceFixture.RELEASE_PACKAGE_HASH,
            ApprovalReleaseDeployment.Status.PENDING,
            1,
            null,
            null,
            null,
            null,
            null,
            "Operator-G1",
            createdAt,
            createdAt,
            null
        );
    }

    private static ApprovalReleaseDeployment failed(ApprovalReleaseDeployment value) {
        return new ApprovalReleaseDeployment(
            value.deploymentRecordId(),
            value.tenantId(),
            value.definitionKey(),
            value.releaseVersion(),
            value.releasePackageHash(),
            ApprovalReleaseDeployment.Status.FAILED,
            value.attemptCount(),
            null,
            null,
            null,
            "ENGINE_FAILURE",
            "deployment failed",
            value.requestedBy(),
            value.createdAt(),
            value.updatedAt().plusSeconds(1),
            null
        );
    }

    private static ApprovalReleaseDeployment retryPending(ApprovalReleaseDeployment value) {
        return new ApprovalReleaseDeployment(
            value.deploymentRecordId(),
            value.tenantId(),
            value.definitionKey(),
            value.releaseVersion(),
            value.releasePackageHash(),
            ApprovalReleaseDeployment.Status.PENDING,
            value.attemptCount() + 1,
            null,
            null,
            null,
            null,
            null,
            value.requestedBy(),
            value.createdAt(),
            value.updatedAt().plusSeconds(1),
            null
        );
    }

    private static ApprovalReleaseDeployment deployed(ApprovalReleaseDeployment value) {
        Instant deployedAt = value.updatedAt().plusSeconds(1);
        return new ApprovalReleaseDeployment(
            value.deploymentRecordId(),
            value.tenantId(),
            value.definitionKey(),
            value.releaseVersion(),
            value.releasePackageHash(),
            ApprovalReleaseDeployment.Status.DEPLOYED,
            value.attemptCount(),
            "engine-deployment-g1",
            "engine-definition-g1",
            7,
            null,
            null,
            value.requestedBy(),
            value.createdAt(),
            deployedAt,
            deployedAt
        );
    }

    private static ApprovalReleaseDeployment canonical(ApprovalReleaseDeployment value) {
        return new ApprovalReleaseDeployment(
            value.deploymentRecordId(),
            value.tenantId(),
            value.definitionKey(),
            value.releaseVersion(),
            value.releasePackageHash(),
            value.status(),
            value.attemptCount(),
            value.engineDeploymentId(),
            value.engineDefinitionId(),
            value.engineVersion(),
            value.lastErrorCode(),
            value.lastErrorMessage(),
            value.requestedBy(),
            AuditHashCanonicalizer.canonicalInstant(value.createdAt()),
            AuditHashCanonicalizer.canonicalInstant(value.updatedAt()),
            value.deployedAt() == null
                ? null
                : AuditHashCanonicalizer.canonicalInstant(value.deployedAt())
        );
    }
}
