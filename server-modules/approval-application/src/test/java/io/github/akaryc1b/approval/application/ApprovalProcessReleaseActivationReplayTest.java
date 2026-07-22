package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalEffectiveReleaseService.ActivationCommand;
import io.github.akaryc1b.approval.application.ApprovalEffectiveReleaseService.ActivationResult;
import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalEffectiveRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalProcessRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalProcessReleaseActivationReplayTest {

    private static final String TENANT = "tenant-replay";
    private static final String DEFINITION_KEY = "purchasePayment";
    private static final Instant NOW = Instant.parse("2026-07-22T02:00:00Z");

    @Test
    void activeLifecycleWithoutEffectiveReplayFailsClosed() {
        ApprovalProcessRelease active = activeRelease();
        AtomicInteger auditCalls = new AtomicInteger();
        AtomicInteger transitionCalls = new AtomicInteger();
        ApprovalProcessReleaseActivationService service =
            new ApprovalProcessReleaseActivationService(
                passThroughIdempotency(),
                store(active, transitionCalls),
                (command, operation) -> effective(active, false),
                event -> auditCalls.incrementAndGet(),
                new ApprovalReleasePackageHasher(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                UUID::randomUUID
            );

        assertThrows(
            ApprovalProcessReleaseActivationService.ActivationEvidenceConflictException.class,
            () -> service.activate(command())
        );
        assertEquals(0, auditCalls.get());
        assertEquals(0, transitionCalls.get());
    }

    private static IdempotencyGuard passThroughIdempotency() {
        return new IdempotencyGuard() {
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
        };
    }

    private static ApprovalProcessReleaseStore store(
        ApprovalProcessRelease active,
        AtomicInteger transitionCalls
    ) {
        return new ApprovalProcessReleaseStore() {
            @Override
            public void lock(String tenantId, String definitionKey) {
            }

            @Override
            public Optional<ApprovalProcessRelease> find(
                String tenantId,
                String definitionKey,
                int releaseVersion
            ) {
                return TENANT.equals(tenantId)
                    && DEFINITION_KEY.equals(definitionKey)
                    && releaseVersion == active.releaseVersion()
                    ? Optional.of(active)
                    : Optional.empty();
            }

            @Override
            public Optional<ApprovalProcessRelease> findActive(
                String tenantId,
                String definitionKey
            ) {
                return TENANT.equals(tenantId) && DEFINITION_KEY.equals(definitionKey)
                    ? Optional.of(active)
                    : Optional.empty();
            }

            @Override
            public Optional<ApprovalProcessRelease.Transition> findTransitionByIdempotency(
                String tenantId,
                String idempotencyKey
            ) {
                return Optional.empty();
            }

            @Override
            public ReleasePage findReleases(ReleaseCriteria criteria) {
                return new ReleasePage(List.of(active), 1, criteria.limit(), criteria.offset());
            }

            @Override
            public TransitionPage findHistory(TransitionCriteria criteria) {
                return new TransitionPage(List.of(), 0, criteria.limit(), criteria.offset());
            }

            @Override
            public void savePublished(
                ApprovalProcessRelease release,
                ApprovalProcessRelease.Transition transition
            ) {
                throw new AssertionError("publish is not expected");
            }

            @Override
            public boolean transition(
                ApprovalProcessRelease release,
                long expectedRevision,
                ApprovalProcessRelease.Transition transition
            ) {
                transitionCalls.incrementAndGet();
                return true;
            }
        };
    }

    private static ActivationCommand command() {
        return new ActivationCommand(
            new RequestContext(
                TENANT,
                "operator-replay",
                "request-replay",
                "idempotency-replay",
                "trace-replay"
            ),
            DEFINITION_KEY,
            1,
            1L,
            "Confirm existing active release evidence"
        );
    }

    private static ApprovalProcessRelease activeRelease() {
        ApprovalReleasePackage release = new ApprovalReleasePackage(
            TENANT,
            DEFINITION_KEY,
            1,
            1,
            "1".repeat(64),
            1,
            "2".repeat(64),
            1,
            "3".repeat(64),
            1,
            "4".repeat(64),
            "compiler-v1",
            "process.bpmn20.xml",
            "<definitions/>",
            "5".repeat(64),
            "6".repeat(64),
            null,
            null,
            "7".repeat(64),
            "8".repeat(64),
            UUID.fromString("80000000-0000-0000-0000-000000000001"),
            "publisher",
            NOW.minusSeconds(120)
        );
        ApprovalProcessRelease published = ApprovalProcessRelease.published(
            release,
            new ApprovalProcessRelease.Transition(
                UUID.fromString("80000000-0000-0000-0000-000000000002"),
                TENANT,
                DEFINITION_KEY,
                1,
                release.packageHash(),
                State.DRAFT,
                State.PUBLISHED,
                1,
                "Publish reviewed release",
                "publish-key",
                "publisher",
                "publish-request",
                "publish-trace",
                "audit-event:publish",
                release.publishedAt()
            )
        );
        return published.transitioned(new ApprovalProcessRelease.Transition(
            UUID.fromString("80000000-0000-0000-0000-000000000003"),
            TENANT,
            DEFINITION_KEY,
            1,
            release.packageHash(),
            State.PUBLISHED,
            State.ACTIVE,
            2,
            "Activate reviewed release",
            "activate-key",
            "operator-replay",
            "activate-request",
            "activate-trace",
            "audit-event:activate",
            NOW.minusSeconds(60)
        ));
    }

    private static ActivationResult effective(
        ApprovalProcessRelease active,
        boolean replayed
    ) {
        return new ActivationResult(
            new ApprovalEffectiveRelease(
                TENANT,
                DEFINITION_KEY,
                1,
                null,
                active.releasePackageHash(),
                1,
                "1".repeat(64),
                1,
                "2".repeat(64),
                1,
                "3".repeat(64),
                1,
                "4".repeat(64),
                "compiler-v1",
                "5".repeat(64),
                "6".repeat(64),
                "7".repeat(64),
                "engine-deployment-1",
                "engine-definition-1",
                1,
                ApprovalEffectiveRelease.Status.ACTIVE,
                1,
                "operator-replay",
                NOW,
                "activation evidence",
                "effective-request",
                "effective-trace"
            ),
            null,
            replayed
        );
    }
}
