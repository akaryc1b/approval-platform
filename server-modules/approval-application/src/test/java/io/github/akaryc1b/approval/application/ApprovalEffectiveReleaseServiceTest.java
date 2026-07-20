package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalEffectiveReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalEffectiveRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalEffectiveReleaseServiceTest {

    private static final String TENANT = "tenant-a";
    private static final String KEY = "purchase-payment";
    private static final Instant NOW = Instant.parse("2026-07-19T03:00:00Z");

    private InMemoryReleaseStore releases;
    private InMemoryDeploymentStore deployments;
    private InMemoryEffectiveStore effectiveReleases;
    private List<AuditEvent> audits;
    private ApprovalEffectiveReleaseService service;

    @BeforeEach
    void setUp() {
        releases = new InMemoryReleaseStore();
        deployments = new InMemoryDeploymentStore();
        effectiveReleases = new InMemoryEffectiveStore();
        audits = new ArrayList<>();
        Supplier<UUID> identifiers = new Supplier<>() {
            private long value;

            @Override
            public UUID get() {
                value++;
                return new UUID(0, value);
            }
        };
        service = new ApprovalEffectiveReleaseService(
            new IdempotencyGuard() {
                @Override
                public <T> T execute(
                    RequestContext context,
                    String operation,
                    String requestHash,
                    Class<T> resultType,
                    Supplier<T> action
                ) {
                    return action.get();
                }
            },
            releases,
            deployments,
            effectiveReleases,
            audits::add,
            Clock.fixed(NOW, ZoneOffset.UTC),
            identifiers
        );
    }

    @Test
    void activatesSwitchesAndRollsBackToAnActivatedDeployedRelease() {
        seedDeployed(1, "8");
        seedDeployed(2, "9");

        var first = service.activate(command(1, 0L, "initial activation", "one"));
        var second = service.activate(command(2, 1L, "roll forward", "two"));
        var rollback = service.rollback(command(1, 2L, "incident rollback", "three"));

        assertEquals(1, first.effectiveRelease().effectiveReleaseVersion());
        assertEquals(2, second.effectiveRelease().effectiveReleaseVersion());
        assertEquals(1, rollback.effectiveRelease().effectiveReleaseVersion());
        assertEquals(2, rollback.effectiveRelease().previousReleaseVersion());
        assertEquals(3, rollback.effectiveRelease().revision());
        assertEquals(ApprovalEffectiveRelease.Action.ROLLBACK, rollback.activation().action());
        assertEquals("engine-definition-1", rollback.effectiveRelease().engineDefinitionId());
        assertEquals(3, effectiveReleases.history.size());
        assertEquals(3, audits.size());
    }

    @Test
    void rejectsUndeployedFailedAndStaleConcurrentActivation() {
        releases.values.put(1, release(1, "8"));
        assertThrows(
            ApprovalEffectiveReleaseService.DeploymentNotReadyException.class,
            () -> service.activate(command(1, 0L, "not deployed", "one"))
        );

        deployments.values.put(1, failedDeployment(1, "8"));
        assertThrows(
            ApprovalEffectiveReleaseService.DeploymentNotReadyException.class,
            () -> service.activate(command(1, 0L, "failed deployment", "two"))
        );

        deployments.values.put(1, deployed(1, "8"));
        service.activate(command(1, 0L, "activate one", "three"));
        seedDeployed(2, "9");
        assertThrows(
            ApprovalEffectiveReleaseService.ActivationConflictException.class,
            () -> service.activate(command(2, 0L, "stale switch", "four"))
        );
    }

    @Test
    void semanticallyReplaysTheAlreadyEffectiveTarget() {
        seedDeployed(1, "8");

        var first = service.activate(command(1, 0L, "activate", "one"));
        var replay = service.activate(command(1, 0L, "activate again", "two"));

        assertFalse(first.replayedExistingActivation());
        assertTrue(replay.replayedExistingActivation());
        assertEquals(first.effectiveRelease(), replay.effectiveRelease());
        assertEquals(1, effectiveReleases.history.size());
        assertEquals(1, audits.size());
    }

    @Test
    void rollbackRequiresAPreviouslyActivatedTarget() {
        seedDeployed(1, "8");
        seedDeployed(2, "9");
        service.activate(command(2, 0L, "activate two", "one"));

        assertThrows(
            ApprovalEffectiveReleaseService.ActivationConflictException.class,
            () -> service.rollback(command(1, 1L, "invalid rollback", "two"))
        );
    }

    private void seedDeployed(int version, String packageCharacter) {
        releases.values.put(version, release(version, packageCharacter));
        deployments.values.put(version, deployed(version, packageCharacter));
    }

    private static ApprovalEffectiveReleaseService.ActivationCommand command(
        int releaseVersion,
        Long expectedRevision,
        String reason,
        String idempotencyKey
    ) {
        return new ApprovalEffectiveReleaseService.ActivationCommand(
            new RequestContext(
                TENANT,
                "operator-a",
                "request-" + idempotencyKey,
                idempotencyKey,
                "trace-a"
            ),
            KEY,
            releaseVersion,
            expectedRevision,
            reason
        );
    }

    private static ApprovalReleasePackage release(int version, String packageCharacter) {
        return new ApprovalReleasePackage(
            TENANT,
            KEY,
            version,
            version,
            "1".repeat(64),
            version,
            "2".repeat(64),
            version,
            "3".repeat(64),
            version,
            "4".repeat(64),
            "compiler-" + version,
            KEY + ".bpmn20.xml",
            "<definitions />",
            "5".repeat(64),
            "6".repeat(64),
            null,
            null,
            "7".repeat(64),
            packageCharacter.repeat(64),
            new UUID(1, version),
            "publisher-a",
            NOW.minusSeconds(100 - version)
        );
    }

    private static ApprovalReleaseDeployment deployed(
        int version,
        String packageCharacter
    ) {
        return new ApprovalReleaseDeployment(
            new UUID(2, version),
            TENANT,
            KEY,
            version,
            packageCharacter.repeat(64),
            ApprovalReleaseDeployment.Status.DEPLOYED,
            1,
            "engine-deployment-" + version,
            "engine-definition-" + version,
            version,
            null,
            null,
            "deployer-a",
            NOW.minusSeconds(20),
            NOW.minusSeconds(10),
            NOW.minusSeconds(10)
        );
    }

    private static ApprovalReleaseDeployment failedDeployment(
        int version,
        String packageCharacter
    ) {
        return new ApprovalReleaseDeployment(
            new UUID(2, version),
            TENANT,
            KEY,
            version,
            packageCharacter.repeat(64),
            ApprovalReleaseDeployment.Status.FAILED,
            1,
            null,
            null,
            null,
            "ENGINE_FAILED",
            "deployment failed",
            "deployer-a",
            NOW.minusSeconds(20),
            NOW.minusSeconds(10),
            null
        );
    }

    private static final class InMemoryReleaseStore implements ApprovalReleasePackageStore {
        private final Map<Integer, ApprovalReleasePackage> values = new HashMap<>();

        @Override
        public void lockVersion(String tenantId, String definitionKey, int releaseVersion) {
        }

        @Override
        public Optional<ApprovalReleasePackage> find(
            String tenantId,
            String definitionKey,
            int releaseVersion
        ) {
            if (!TENANT.equals(tenantId) || !KEY.equals(definitionKey)) {
                return Optional.empty();
            }
            return Optional.ofNullable(values.get(releaseVersion));
        }

        @Override
        public Optional<ApprovalReleasePackage> findLatest(
            String tenantId,
            String definitionKey
        ) {
            return values.values().stream()
                .max(java.util.Comparator.comparingInt(ApprovalReleasePackage::releaseVersion));
        }

        @Override
        public Optional<ApprovalReleasePackage> findByDraft(String tenantId, UUID draftId) {
            return Optional.empty();
        }

        @Override
        public ReleasePage findReleases(ReleaseCriteria criteria) {
            return new ReleasePage(List.copyOf(values.values()), values.size(), 100, 0);
        }

        @Override
        public void save(ApprovalReleasePackage releasePackage) {
            values.put(releasePackage.releaseVersion(), releasePackage);
        }
    }

    private static final class InMemoryDeploymentStore
        implements ApprovalReleaseDeploymentStore {
        private final Map<Integer, ApprovalReleaseDeployment> values = new HashMap<>();

        @Override
        public void lock(String tenantId, String definitionKey, int releaseVersion) {
        }

        @Override
        public Optional<ApprovalReleaseDeployment> find(
            String tenantId,
            String definitionKey,
            int releaseVersion
        ) {
            if (!TENANT.equals(tenantId) || !KEY.equals(definitionKey)) {
                return Optional.empty();
            }
            return Optional.ofNullable(values.get(releaseVersion));
        }

        @Override
        public void save(ApprovalReleaseDeployment deployment) {
            values.put(deployment.releaseVersion(), deployment);
        }

        @Override
        public boolean update(ApprovalReleaseDeployment deployment, int expectedAttemptCount) {
            values.put(deployment.releaseVersion(), deployment);
            return true;
        }
    }

    private static final class InMemoryEffectiveStore
        implements ApprovalEffectiveReleaseStore {
        private ApprovalEffectiveRelease current;
        private final List<ApprovalEffectiveRelease.Activation> history = new ArrayList<>();

        @Override
        public void lock(String tenantId, String definitionKey) {
        }

        @Override
        public Optional<ApprovalEffectiveRelease> find(String tenantId, String definitionKey) {
            if (!TENANT.equals(tenantId) || !KEY.equals(definitionKey)) {
                return Optional.empty();
            }
            return Optional.ofNullable(current);
        }

        @Override
        public void save(
            ApprovalEffectiveRelease effectiveRelease,
            ApprovalEffectiveRelease.Activation activation
        ) {
            current = effectiveRelease;
            history.add(activation);
        }

        @Override
        public boolean update(
            ApprovalEffectiveRelease effectiveRelease,
            long expectedRevision,
            ApprovalEffectiveRelease.Activation activation
        ) {
            if (current == null || current.revision() != expectedRevision) {
                return false;
            }
            current = effectiveRelease;
            history.add(activation);
            return true;
        }

        @Override
        public boolean wasActivated(String tenantId, String definitionKey, int releaseVersion) {
            return history.stream().anyMatch(value -> value.releaseVersion() == releaseVersion);
        }

        @Override
        public ActivationPage findHistory(ActivationCriteria criteria) {
            return new ActivationPage(List.copyOf(history), history.size(), 100, 0);
        }
    }
}
