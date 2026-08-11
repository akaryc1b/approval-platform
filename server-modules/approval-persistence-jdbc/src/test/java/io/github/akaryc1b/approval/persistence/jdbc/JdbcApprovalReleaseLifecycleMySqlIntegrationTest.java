package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalEffectiveReleaseService;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseActivationService;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseDispositionService;
import io.github.akaryc1b.approval.application.ApprovalReleasePackageHasher;
import io.github.akaryc1b.approval.application.port.ApprovalEffectiveReleaseDeactivationPort;
import io.github.akaryc1b.approval.application.port.ApprovalEffectiveReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalEffectiveRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalProcessRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
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

class JdbcApprovalReleaseLifecycleMySqlIntegrationTest
    extends MySqlApprovalProjectionStoreIntegrationSupport {

    private static final Instant NOW = Instant.parse("2026-08-11T06:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Instant DIRECT_AT = Instant.parse(
        "2026-08-11T04:05:06.999999500Z"
    );
    private static final Instant SEED_ACTIVE_AT = Instant.parse(
        "2026-08-11T05:00:00Z"
    );

    private ApprovalProcessReleaseStore processReleases;
    private ApprovalEffectiveReleaseStore effectiveReleases;
    private ApprovalEffectiveReleaseDeactivationPort deactivation;
    private ApprovalReleasePackageStore releasePackages;
    private ApprovalReleaseDeploymentStore deployments;
    private ObjectMapper objectMapper;
    private JdbcTransactionManager transactionManager;

    @BeforeEach
    @Override
    void reset() {
        jdbc.update("delete from ap_command_idempotency");
        jdbc.update("delete from ap_audit_event");
        jdbc.update("delete from ap_audit_chain_state");
        jdbc.update("delete from ap_process_release_lifecycle_history");
        jdbc.update("delete from ap_process_release_lifecycle");
        jdbc.update("delete from ap_approval_effective_release");
        jdbc.update("delete from ap_approval_release_activation_history");
        jdbc.update("delete from ap_approval_release_deployment");
        super.reset();
        processReleases = JdbcApprovalProcessReleaseStoreFactory.create(dataSource);
        effectiveReleases = JdbcApprovalEffectiveReleaseStoreFactory.create(dataSource);
        deactivation = JdbcApprovalEffectiveReleaseDeactivationPortFactory.create(dataSource);
        releasePackages = JdbcApprovalReleasePackageStoreFactory.create(dataSource);
        deployments = JdbcApprovalReleaseDeploymentStoreFactory.create(dataSource);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        transactionManager = new JdbcTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);
    }

    @Test
    void lifecycleStoreRoundTripsHistoryTenantCasUuidTimeAndState() {
        assertInstanceOf(JdbcMySqlApprovalProcessReleaseStore.class, processReleases);
        ApprovalReleasePackage releasePackage = seedRelease(3, false);
        ApprovalProcessRelease published = seedPublishedLifecycle(releasePackage);

        ApprovalProcessRelease.Transition activate = transition(
            published,
            State.ACTIVE,
            "direct-activate-3",
            DIRECT_AT
        );
        ApprovalProcessRelease active = published.transitioned(activate);
        assertTrue(processReleases.transition(active, published.revision(), activate));

        ApprovalProcessRelease restoredActive = processReleases.find(
            TENANT,
            DEFINITION_KEY,
            3
        ).orElseThrow();
        assertEquals(State.ACTIVE, restoredActive.lifecycleState());
        assertEquals(
            AuditHashCanonicalizer.canonicalInstant(DIRECT_AT),
            restoredActive.activatedAt()
        );
        assertEquals(
            AuditHashCanonicalizer.canonicalInstant(DIRECT_AT),
            restoredActive.lastTransitionAt()
        );
        assertEquals(
            activate.transitionId(),
            processReleases.findTransitionByIdempotency(
                TENANT,
                activate.idempotencyKey()
            ).orElseThrow().transitionId()
        );
        assertFalse(processReleases.find(
            TENANT.toLowerCase(Locale.ROOT),
            DEFINITION_KEY,
            3
        ).isPresent());
        assertEquals(
            1,
            processReleases.findReleases(new ApprovalProcessReleaseStore.ReleaseCriteria(
                TENANT,
                DEFINITION_KEY,
                State.ACTIVE,
                10,
                0
            )).total()
        );

        ApprovalProcessRelease.Transition deprecate = transition(
            active,
            State.DEPRECATED,
            "direct-deprecate-3",
            DIRECT_AT.plusSeconds(10)
        );
        ApprovalProcessRelease deprecated = active.transitioned(deprecate);
        assertTrue(processReleases.transition(deprecated, active.revision(), deprecate));

        ApprovalProcessRelease.Transition staleTransition = transition(
            active,
            State.DEPRECATED,
            "direct-stale-deprecate-3",
            DIRECT_AT.plusSeconds(20)
        );
        ApprovalProcessRelease stale = active.transitioned(staleTransition);
        assertFalse(processReleases.transition(stale, active.revision(), staleTransition));
        assertTrue(processReleases.findActive(TENANT, DEFINITION_KEY).isEmpty());

        ApprovalProcessReleaseStore.TransitionPage history = processReleases.findHistory(
            new ApprovalProcessReleaseStore.TransitionCriteria(
                TENANT,
                DEFINITION_KEY,
                3,
                10,
                0
            )
        );
        assertEquals(3, history.total());
        assertEquals(
            List.of(State.DEPRECATED, State.ACTIVE, State.PUBLISHED),
            history.items().stream()
                .map(ApprovalProcessRelease.Transition::toState)
                .toList()
        );
    }

    @Test
    void effectiveStoreRoundTripsHistoryCasTenantUuidTimeAndDeactivation() {
        assertInstanceOf(JdbcMySqlApprovalEffectiveReleaseStore.class, effectiveReleases);
        assertInstanceOf(
            JdbcMySqlApprovalEffectiveReleaseDeactivationPort.class,
            deactivation
        );
        ApprovalReleasePackage releaseTwo = seedRelease(2, true);
        ApprovalReleaseDeployment deploymentTwo = deployment(2);
        ApprovalEffectiveRelease initial = effective(
            releaseTwo,
            deploymentTwo,
            null,
            1,
            DIRECT_AT
        );
        ApprovalEffectiveRelease.Activation initialActivation = activation(
            initial,
            ApprovalEffectiveRelease.Action.ACTIVATE
        );
        effectiveReleases.save(initial, initialActivation);

        ApprovalEffectiveRelease restored = effectiveReleases.find(
            TENANT,
            DEFINITION_KEY
        ).orElseThrow();
        assertEquals(2, restored.effectiveReleaseVersion());
        assertEquals(
            AuditHashCanonicalizer.canonicalInstant(DIRECT_AT),
            restored.activatedAt()
        );
        assertFalse(effectiveReleases.find(
            TENANT.toLowerCase(Locale.ROOT),
            DEFINITION_KEY
        ).isPresent());
        assertTrue(effectiveReleases.wasActivated(TENANT, DEFINITION_KEY, 2));

        ApprovalReleasePackage releaseThree = seedRelease(3, true);
        ApprovalReleaseDeployment deploymentThree = deployment(3);
        ApprovalEffectiveRelease next = effective(
            releaseThree,
            deploymentThree,
            2,
            2,
            DIRECT_AT.plusSeconds(10)
        );
        ApprovalEffectiveRelease.Activation nextActivation = activation(
            next,
            ApprovalEffectiveRelease.Action.ACTIVATE
        );
        assertTrue(effectiveReleases.update(next, 1, nextActivation));

        ApprovalReleasePackage releaseOne = seedRelease(1, true);
        ApprovalReleaseDeployment deploymentOne = deployment(1);
        ApprovalEffectiveRelease stale = effective(
            releaseOne,
            deploymentOne,
            2,
            2,
            DIRECT_AT.plusSeconds(20)
        );
        assertFalse(effectiveReleases.update(
            stale,
            1,
            activation(stale, ApprovalEffectiveRelease.Action.ACTIVATE)
        ));

        ApprovalEffectiveReleaseStore.ActivationPage history = effectiveReleases.findHistory(
            new ApprovalEffectiveReleaseStore.ActivationCriteria(
                TENANT,
                DEFINITION_KEY,
                10,
                0
            )
        );
        assertEquals(2, history.total());
        assertEquals(List.of(2L, 1L), history.items().stream()
            .map(ApprovalEffectiveRelease.Activation::revision)
            .toList());
        assertEquals(nextActivation.activationId(), history.items().getFirst().activationId());
        assertTrue(effectiveReleases.wasActivated(TENANT, DEFINITION_KEY, 3));
        assertFalse(deactivation.clear(TENANT, DEFINITION_KEY, 1));
        assertTrue(deactivation.clear(TENANT, DEFINITION_KEY, 2));
        assertTrue(effectiveReleases.find(TENANT, DEFINITION_KEY).isEmpty());
        assertEquals(2, effectiveReleases.findHistory(
            new ApprovalEffectiveReleaseStore.ActivationCriteria(
                TENANT,
                DEFINITION_KEY,
                10,
                0
            )
        ).total());
    }

    @Test
    void lifecycleAndEffectiveLocksRequireTransactionsBlockAndRemainDistinct()
        throws Exception {
        assertThrows(
            IllegalStateException.class,
            () -> processReleases.lock(TENANT, DEFINITION_KEY)
        );
        assertThrows(
            IllegalStateException.class,
            () -> effectiveReleases.lock(TENANT, DEFINITION_KEY)
        );

        assertLockBlocks(() -> processReleases.lock(TENANT, DEFINITION_KEY));
        assertLockBlocks(() -> effectiveReleases.lock(TENANT, DEFINITION_KEY));

        transactions.executeWithoutResult(status -> {
            processReleases.lock(TENANT, DEFINITION_KEY);
            effectiveReleases.lock(TENANT, DEFINITION_KEY);
        });
    }

    @Test
    void activationSwitchCommitsLifecycleEffectiveAuditAndIdempotencyTogether() {
        ApprovalReleasePackage current = seedRelease(1, true);
        seedActiveLifecycle(current);
        seedEffective(current, deployment(1), SEED_ACTIVE_AT);
        ApprovalReleasePackage target = seedRelease(2, true);
        seedPublishedLifecycle(target);
        ApprovalProcessReleaseActivationService service = activationService();
        var command = activationCommand("switch-release-two-g2", 2, 1L);

        var result = service.activate(command);
        var replay = service.activate(command);

        assertEquals(result, replay);
        assertEquals(State.DEPRECATED, lifecycle(1).lifecycleState());
        assertEquals(State.ACTIVE, lifecycle(2).lifecycleState());
        assertEquals(1, countActiveLifecycles());
        ApprovalEffectiveRelease effective = currentEffective();
        assertEquals(2, effective.effectiveReleaseVersion());
        assertEquals(1, effective.previousReleaseVersion());
        assertEquals(2, effective.revision());
        assertEquals(target.packageHash(), effective.releasePackageHash());
        assertEquals(5, count("ap_process_release_lifecycle_history"));
        assertEquals(2, count("ap_approval_release_activation_history"));
        assertEquals(2, count("ap_audit_event"));
        assertEquals(1, count("ap_audit_chain_state"));
        assertEquals(2, count("ap_command_idempotency"));
    }

    @Test
    void rollbackReactivatesPreviouslyActiveDeprecatedRelease() {
        ApprovalReleasePackage releaseOne = seedRelease(1, true);
        seedActiveLifecycle(releaseOne);
        seedEffective(releaseOne, deployment(1), SEED_ACTIVE_AT);
        ApprovalReleasePackage releaseTwo = seedRelease(2, true);
        seedPublishedLifecycle(releaseTwo);
        ApprovalProcessReleaseActivationService service = activationService();

        service.activate(activationCommand("activate-two-before-rollback", 2, 1L));
        var rollback = service.rollback(activationCommand("rollback-to-one-g2", 1, 2L));

        assertEquals(State.ACTIVE, rollback.activeRelease().lifecycleState());
        assertEquals(1, rollback.activeRelease().releaseVersion());
        assertEquals(State.DEPRECATED, lifecycle(2).lifecycleState());
        assertEquals(State.ACTIVE, lifecycle(1).lifecycleState());
        assertEquals(1, countActiveLifecycles());
        ApprovalEffectiveRelease effective = currentEffective();
        assertEquals(1, effective.effectiveReleaseVersion());
        assertEquals(2, effective.previousReleaseVersion());
        assertEquals(3, effective.revision());
        ApprovalEffectiveReleaseStore.ActivationPage history = effectiveReleases.findHistory(
            new ApprovalEffectiveReleaseStore.ActivationCriteria(
                TENANT,
                DEFINITION_KEY,
                10,
                0
            )
        );
        assertEquals(3, history.total());
        assertEquals(
            ApprovalEffectiveRelease.Action.ROLLBACK,
            history.items().getFirst().action()
        );
        assertEquals(7, count("ap_process_release_lifecycle_history"));
        assertEquals(4, count("ap_audit_event"));
        assertEquals(4, count("ap_command_idempotency"));
    }

    @Test
    void missingDeploymentRollsBackLifecycleAuditAndIdempotencyEvidence() {
        ApprovalReleasePackage current = seedRelease(1, true);
        seedActiveLifecycle(current);
        seedEffective(current, deployment(1), SEED_ACTIVE_AT);
        ApprovalReleasePackage target = seedRelease(2, false);
        seedPublishedLifecycle(target);
        ApprovalProcessReleaseActivationService service = activationService();

        assertThrows(
            ApprovalEffectiveReleaseService.DeploymentNotReadyException.class,
            () -> service.activate(activationCommand(
                "missing-target-deployment-g2",
                2,
                1L
            ))
        );

        assertEquals(State.ACTIVE, lifecycle(1).lifecycleState());
        assertEquals(State.PUBLISHED, lifecycle(2).lifecycleState());
        assertEquals(1, countActiveLifecycles());
        assertEquals(1, currentEffective().effectiveReleaseVersion());
        assertEquals(1, currentEffective().revision());
        assertEquals(3, count("ap_process_release_lifecycle_history"));
        assertEquals(1, count("ap_approval_release_activation_history"));
        assertEquals(0, count("ap_audit_event"));
        assertEquals(0, count("ap_audit_chain_state"));
        assertEquals(0, count("ap_command_idempotency"));
    }

    @Test
    void concurrentTargetsAllowExactlyOneGovernedSwitch() throws Exception {
        ApprovalReleasePackage current = seedRelease(1, true);
        seedActiveLifecycle(current);
        seedEffective(current, deployment(1), SEED_ACTIVE_AT);
        seedPublishedLifecycle(seedRelease(2, true));
        seedPublishedLifecycle(seedRelease(3, true));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Attempt> first = executor.submit(
                () -> activateAfterSignal(ready, start, "concurrent-two-g2", 2)
            );
            Future<Attempt> second = executor.submit(
                () -> activateAfterSignal(ready, start, "concurrent-three-g2", 3)
            );
            ready.await();
            start.countDown();

            Attempt firstResult = first.get();
            Attempt secondResult = second.get();
            assertEquals(
                1,
                (firstResult.success() ? 1 : 0) + (secondResult.success() ? 1 : 0)
            );
            RuntimeException failure = firstResult.success()
                ? secondResult.failure()
                : firstResult.failure();
            assertInstanceOf(
                ApprovalEffectiveReleaseService.ActivationConflictException.class,
                failure
            );
        }

        ApprovalEffectiveRelease effective = currentEffective();
        int winner = effective.effectiveReleaseVersion();
        int loser = winner == 2 ? 3 : 2;
        assertTrue(winner == 2 || winner == 3);
        assertEquals(2, effective.revision());
        assertEquals(State.DEPRECATED, lifecycle(1).lifecycleState());
        assertEquals(State.ACTIVE, lifecycle(winner).lifecycleState());
        assertEquals(State.PUBLISHED, lifecycle(loser).lifecycleState());
        assertEquals(1, countActiveLifecycles());
        assertEquals(6, count("ap_process_release_lifecycle_history"));
        assertEquals(2, count("ap_approval_release_activation_history"));
        assertEquals(2, count("ap_audit_event"));
        assertEquals(2, count("ap_command_idempotency"));
    }

    @Test
    void dispositionDeprecatesClearsEffectiveAndRetiresWithoutMutatingPackages() {
        ApprovalReleasePackage releaseOne = seedRelease(1, true);
        seedActiveLifecycle(releaseOne);
        seedEffective(releaseOne, deployment(1), SEED_ACTIVE_AT);
        ApprovalReleasePackage releaseTwo = seedRelease(2, false);
        seedPublishedLifecycle(releaseTwo);
        ApprovalProcessReleaseDispositionService service = dispositionService(deactivation);

        var deprecated = service.deprecate(dispositionCommand(
            "deprecate-one-g2",
            1,
            2,
            "Stop new starts after G2 release review"
        ));
        assertEquals(State.DEPRECATED, deprecated.lifecycle().lifecycleState());
        assertEquals(0, deprecated.runtimeUsageCount());
        assertTrue(effectiveReleases.find(TENANT, DEFINITION_KEY).isEmpty());
        assertEquals(1, count("ap_approval_release_activation_history"));

        var retiredDeprecated = service.retire(dispositionCommand(
            "retire-deprecated-one-g2",
            1,
            3,
            "Retire deprecated release after G2 review"
        ));
        assertEquals(State.RETIRED, retiredDeprecated.lifecycle().lifecycleState());

        var retiredPublished = service.retire(dispositionCommand(
            "retire-published-two-g2",
            2,
            1,
            "Retire unpublished-to-runtime release safely"
        ));
        assertEquals(State.RETIRED, retiredPublished.lifecycle().lifecycleState());
        assertEquals(2, countTenantRows("ap_approval_release_package"));
        assertEquals(3, count("ap_audit_event"));
        assertEquals(3, count("ap_command_idempotency"));
    }

    @Test
    void failedEffectiveClearRollsBackLifecycleAuditAndIdempotencyEvidence() {
        ApprovalReleasePackage releaseOne = seedRelease(1, true);
        seedActiveLifecycle(releaseOne);
        seedEffective(releaseOne, deployment(1), SEED_ACTIVE_AT);
        ApprovalEffectiveReleaseDeactivationPort failing = (tenant, key, revision) -> false;
        ApprovalProcessReleaseDispositionService service = dispositionService(failing);

        assertThrows(
            ApprovalProcessReleaseDispositionService.DispositionEvidenceConflictException.class,
            () -> service.deprecate(dispositionCommand(
                "deprecate-clear-conflict-g2",
                1,
                2,
                "Reject concurrent effective release change"
            ))
        );

        assertEquals(State.ACTIVE, lifecycle(1).lifecycleState());
        assertEquals(1, currentEffective().effectiveReleaseVersion());
        assertEquals(2, count("ap_process_release_lifecycle_history"));
        assertEquals(1, count("ap_approval_release_activation_history"));
        assertEquals(0, count("ap_audit_event"));
        assertEquals(0, count("ap_command_idempotency"));
    }

    private ApprovalProcessReleaseActivationService activationService() {
        var idempotency = new JdbcIdempotencyGuard(
            dataSource,
            objectMapper,
            transactionManager,
            CLOCK
        );
        AuditEventSink audit = JdbcAuditEventStoreFactory.create(
            dataSource,
            objectMapper,
            transactionManager
        );
        ApprovalEffectiveReleaseService effective = new ApprovalEffectiveReleaseService(
            idempotency,
            releasePackages,
            deployments,
            effectiveReleases,
            audit,
            CLOCK,
            UUID::randomUUID
        );
        return new ApprovalProcessReleaseActivationService(
            idempotency,
            processReleases,
            (command, operation) -> switch (operation) {
                case ACTIVATE -> effective.activate(command);
                case ROLLBACK -> effective.rollback(command);
            },
            audit,
            new ApprovalReleasePackageHasher(),
            CLOCK,
            UUID::randomUUID
        );
    }

    private ApprovalProcessReleaseDispositionService dispositionService(
        ApprovalEffectiveReleaseDeactivationPort deactivationPort
    ) {
        var idempotency = new JdbcIdempotencyGuard(
            dataSource,
            objectMapper,
            transactionManager,
            CLOCK
        );
        AuditEventSink audit = JdbcAuditEventStoreFactory.create(
            dataSource,
            objectMapper,
            transactionManager
        );
        return new ApprovalProcessReleaseDispositionService(
            idempotency,
            processReleases,
            effectiveReleases,
            deactivationPort,
            new JdbcApprovalRuntimeBindingStore(dataSource),
            audit,
            new ApprovalReleasePackageHasher(),
            CLOCK,
            UUID::randomUUID
        );
    }

    private ApprovalEffectiveReleaseService.ActivationCommand activationCommand(
        String idempotencyKey,
        int releaseVersion,
        Long expectedRevision
    ) {
        return new ApprovalEffectiveReleaseService.ActivationCommand(
            context(idempotencyKey),
            DEFINITION_KEY,
            releaseVersion,
            expectedRevision,
            "Activate reviewed release through governed G2 lifecycle"
        );
    }

    private ApprovalProcessReleaseDispositionService.DispositionCommand dispositionCommand(
        String idempotencyKey,
        int releaseVersion,
        long expectedRevision,
        String reason
    ) {
        return new ApprovalProcessReleaseDispositionService.DispositionCommand(
            context(idempotencyKey),
            DEFINITION_KEY,
            releaseVersion,
            expectedRevision,
            reason
        );
    }

    private RequestContext context(String idempotencyKey) {
        return new RequestContext(
            TENANT,
            "Operator-G2",
            "request-" + idempotencyKey,
            idempotencyKey,
            "trace-" + idempotencyKey
        );
    }

    private ApprovalReleasePackage seedRelease(int releaseVersion, boolean deployed) {
        ApprovalReleasePackage releasePackage;
        if (releaseVersion == MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION) {
            releasePackage = releasePackages.find(
                TENANT,
                DEFINITION_KEY,
                releaseVersion
            ).orElseThrow();
        } else {
            releasePackage = MySqlApprovalReleaseLifecycleFixture.seedRelease(
                jdbc,
                releasePackages,
                TENANT,
                DEFINITION_KEY,
                releaseVersion,
                packageHash(releaseVersion),
                DEFINITION_AT.plusSeconds(100L + releaseVersion)
            );
        }
        if (deployed && deployments.find(
            TENANT,
            DEFINITION_KEY,
            releaseVersion
        ).isEmpty()) {
            MySqlApprovalReleaseLifecycleFixture.seedDeployed(
                deployments,
                releasePackage,
                SEED_ACTIVE_AT.minusSeconds(100L - releaseVersion)
            );
        }
        return releasePackage;
    }

    private ApprovalReleaseDeployment deployment(int releaseVersion) {
        return deployments.find(TENANT, DEFINITION_KEY, releaseVersion).orElseThrow();
    }

    private ApprovalProcessRelease seedPublishedLifecycle(
        ApprovalReleasePackage releasePackage
    ) {
        ApprovalProcessRelease.Transition transition = new ApprovalProcessRelease.Transition(
            uuid("publish-" + releasePackage.releaseVersion()),
            releasePackage.tenantId(),
            releasePackage.definitionKey(),
            releasePackage.releaseVersion(),
            releasePackage.packageHash(),
            State.DRAFT,
            State.PUBLISHED,
            1,
            "Publish release for governed G2 lifecycle",
            "g2-publish-" + releasePackage.releaseVersion(),
            releasePackage.publishedBy(),
            "request-g2-publish-" + releasePackage.releaseVersion(),
            "trace-g2-publish-" + releasePackage.releaseVersion(),
            "audit-event:g2-publish-" + releasePackage.releaseVersion(),
            releasePackage.publishedAt()
        );
        ApprovalProcessRelease lifecycle = ApprovalProcessRelease.published(
            releasePackage,
            transition
        );
        processReleases.savePublished(lifecycle, transition);
        return lifecycle;
    }

    private ApprovalProcessRelease seedActiveLifecycle(ApprovalReleasePackage releasePackage) {
        ApprovalProcessRelease published = seedPublishedLifecycle(releasePackage);
        Instant activatedAt = SEED_ACTIVE_AT.plusSeconds(releasePackage.releaseVersion());
        ApprovalProcessRelease.Transition transition = transition(
            published,
            State.ACTIVE,
            "seed-active-" + releasePackage.releaseVersion(),
            activatedAt
        );
        ApprovalProcessRelease active = published.transitioned(transition);
        assertTrue(processReleases.transition(active, published.revision(), transition));
        return active;
    }

    private ApprovalProcessRelease.Transition transition(
        ApprovalProcessRelease current,
        State targetState,
        String idempotencyKey,
        Instant happenedAt
    ) {
        return new ApprovalProcessRelease.Transition(
            uuid(idempotencyKey),
            current.tenantId(),
            current.definitionKey(),
            current.releaseVersion(),
            current.releasePackageHash(),
            current.lifecycleState(),
            targetState,
            current.revision() + 1,
            "Transition release through governed G2 lifecycle",
            idempotencyKey,
            "Operator-G2",
            "request-" + idempotencyKey,
            "trace-" + idempotencyKey,
            "audit-event:" + idempotencyKey,
            happenedAt
        );
    }

    private void seedEffective(
        ApprovalReleasePackage releasePackage,
        ApprovalReleaseDeployment deployment,
        Instant activatedAt
    ) {
        ApprovalEffectiveRelease effective = effective(
            releasePackage,
            deployment,
            null,
            1,
            activatedAt
        );
        effectiveReleases.save(
            effective,
            activation(effective, ApprovalEffectiveRelease.Action.ACTIVATE)
        );
    }

    private ApprovalEffectiveRelease effective(
        ApprovalReleasePackage releasePackage,
        ApprovalReleaseDeployment deployment,
        Integer previousReleaseVersion,
        long revision,
        Instant activatedAt
    ) {
        return new ApprovalEffectiveRelease(
            releasePackage.tenantId(),
            releasePackage.definitionKey(),
            releasePackage.releaseVersion(),
            previousReleaseVersion,
            releasePackage.packageHash(),
            releasePackage.definitionVersion(),
            releasePackage.definitionHash(),
            releasePackage.formPackageVersion(),
            releasePackage.formPackageHash(),
            releasePackage.formVersion(),
            releasePackage.formHash(),
            releasePackage.uiSchemaVersion(),
            releasePackage.uiSchemaHash(),
            releasePackage.compilerVersion(),
            releasePackage.compiledArtifactHash(),
            releasePackage.bpmnHash(),
            releasePackage.deploymentMetadataHash(),
            deployment.engineDeploymentId(),
            deployment.engineDefinitionId(),
            deployment.engineVersion(),
            ApprovalEffectiveRelease.Status.ACTIVE,
            revision,
            "Operator-G2",
            activatedAt,
            "Activate reviewed release for G2",
            "request-effective-" + revision,
            "trace-effective-" + revision
        );
    }

    private ApprovalEffectiveRelease.Activation activation(
        ApprovalEffectiveRelease effective,
        ApprovalEffectiveRelease.Action action
    ) {
        return new ApprovalEffectiveRelease.Activation(
            uuid(
                "activation-"
                    + effective.effectiveReleaseVersion()
                    + '-'
                    + effective.revision()
                    + '-'
                    + action
            ),
            effective.tenantId(),
            effective.definitionKey(),
            effective.effectiveReleaseVersion(),
            effective.previousReleaseVersion(),
            effective.releasePackageHash(),
            effective.definitionVersion(),
            effective.formPackageVersion(),
            effective.compilerVersion(),
            effective.engineDeploymentId(),
            effective.engineDefinitionId(),
            effective.engineVersion(),
            action,
            effective.revision(),
            effective.activatedBy(),
            effective.activatedAt(),
            effective.changeReason(),
            effective.requestId(),
            effective.traceId()
        );
    }

    private ApprovalProcessRelease lifecycle(int releaseVersion) {
        return processReleases.find(TENANT, DEFINITION_KEY, releaseVersion).orElseThrow();
    }

    private ApprovalEffectiveRelease currentEffective() {
        return effectiveReleases.find(TENANT, DEFINITION_KEY).orElseThrow();
    }

    private int countActiveLifecycles() {
        return jdbc.queryForObject(
            "select count(*) from ap_process_release_lifecycle "
                + "where tenant_id = ? and definition_key = ? and lifecycle_state = 'ACTIVE'",
            Integer.class,
            TENANT,
            DEFINITION_KEY
        );
    }

    private int countTenantRows(String table) {
        return jdbc.queryForObject(
            "select count(*) from " + table + " where tenant_id = ?",
            Integer.class,
            TENANT
        );
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private Attempt activateAfterSignal(
        CountDownLatch ready,
        CountDownLatch start,
        String idempotencyKey,
        int releaseVersion
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            activationService().activate(activationCommand(
                idempotencyKey,
                releaseVersion,
                1L
            ));
            return new Attempt(true, null);
        } catch (RuntimeException exception) {
            return new Attempt(false, exception);
        }
    }

    private void assertLockBlocks(Runnable lock) throws Exception {
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempting = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> transactions.execute(status -> {
                lock.run();
                firstLocked.countDown();
                await(releaseFirst);
                throw new RollbackMarker();
            }));
            Future<Boolean> second = executor.submit(() -> {
                await(firstLocked);
                secondAttempting.countDown();
                return transactions.execute(status -> {
                    lock.run();
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

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while coordinating G2 lock test", exception);
        }
    }

    private static String packageHash(int releaseVersion) {
        return switch (releaseVersion) {
            case 1 -> "1".repeat(64);
            case 3 -> "3".repeat(64);
            default -> throw new IllegalArgumentException(
                "unsupported extra release version: " + releaseVersion
            );
        };
    }

    private static UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(
            ("mysql-g2:" + value).getBytes(StandardCharsets.UTF_8)
        );
    }

    private record Attempt(boolean success, RuntimeException failure) {
    }

    private static final class RollbackMarker extends RuntimeException {
    }
}
