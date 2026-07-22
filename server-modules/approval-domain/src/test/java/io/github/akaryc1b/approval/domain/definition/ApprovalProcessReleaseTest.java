package io.github.akaryc1b.approval.domain.definition;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalProcessReleaseTest {

    private static final String HASH = "a".repeat(64);
    private static final Instant PUBLISHED_AT = Instant.parse("2026-07-22T00:00:00Z");

    @Test
    void createsPublishedLifecycleFromImmutablePackageEvidence() {
        ApprovalReleasePackage releasePackage = releasePackage();
        ApprovalProcessRelease release = ApprovalProcessRelease.published(
            releasePackage,
            transition(State.DRAFT, State.PUBLISHED, 1, PUBLISHED_AT)
        );

        assertEquals(State.PUBLISHED, release.lifecycleState());
        assertEquals(1L, release.revision());
        assertEquals(releasePackage.packageHash(), release.releasePackageHash());
        assertEquals(releasePackage.publishedBy(), release.lastTransitionBy());
        assertNull(release.activatedAt());
    }

    @Test
    void preservesFirstActivationAcrossDeprecatedReactivation() {
        ApprovalProcessRelease published = ApprovalProcessRelease.published(
            releasePackage(),
            transition(State.DRAFT, State.PUBLISHED, 1, PUBLISHED_AT)
        );
        Instant firstActivation = PUBLISHED_AT.plusSeconds(60);
        Instant deprecation = PUBLISHED_AT.plusSeconds(120);
        Instant reactivation = PUBLISHED_AT.plusSeconds(180);

        ApprovalProcessRelease active = published.transitioned(
            transition(State.PUBLISHED, State.ACTIVE, 2, firstActivation)
        );
        ApprovalProcessRelease deprecated = active.transitioned(
            transition(State.ACTIVE, State.DEPRECATED, 3, deprecation)
        );
        ApprovalProcessRelease reactivated = deprecated.transitioned(
            transition(State.DEPRECATED, State.ACTIVE, 4, reactivation)
        );

        assertEquals(State.ACTIVE, reactivated.lifecycleState());
        assertEquals(4L, reactivated.revision());
        assertEquals(firstActivation, reactivated.activatedAt());
        assertEquals(deprecation, reactivated.deprecatedAt());
        assertEquals(reactivation, reactivated.lastTransitionAt());
    }

    @Test
    void retiresPublishedReleaseWithoutActivation() {
        ApprovalProcessRelease published = ApprovalProcessRelease.published(
            releasePackage(),
            transition(State.DRAFT, State.PUBLISHED, 1, PUBLISHED_AT)
        );
        Instant retiredAt = PUBLISHED_AT.plusSeconds(60);

        ApprovalProcessRelease retired = published.transitioned(
            transition(State.PUBLISHED, State.RETIRED, 2, retiredAt)
        );

        assertEquals(State.RETIRED, retired.lifecycleState());
        assertNull(retired.activatedAt());
        assertEquals(retiredAt, retired.retiredAt());
    }

    @Test
    void rejectsMismatchedIdentityRevisionAndBackwardsTime() {
        ApprovalProcessRelease published = ApprovalProcessRelease.published(
            releasePackage(),
            transition(State.DRAFT, State.PUBLISHED, 1, PUBLISHED_AT)
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> published.transitioned(new ApprovalProcessRelease.Transition(
                UUID.randomUUID(),
                "other-tenant",
                "purchasePayment",
                7,
                HASH,
                State.PUBLISHED,
                State.ACTIVE,
                2,
                "activate",
                "idempotency-2",
                "operator-a",
                "request-2",
                "trace-2",
                "audit-2",
                PUBLISHED_AT.plusSeconds(60)
            ))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> published.transitioned(
                transition(State.PUBLISHED, State.ACTIVE, 3, PUBLISHED_AT.plusSeconds(60))
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> published.transitioned(
                transition(State.PUBLISHED, State.ACTIVE, 2, PUBLISHED_AT.minusSeconds(1))
            )
        );
    }

    @Test
    void rejectsPublishEvidenceThatDoesNotMatchReleasePackage() {
        ApprovalProcessRelease.Transition transition = new ApprovalProcessRelease.Transition(
            UUID.randomUUID(),
            "tenant-a",
            "purchasePayment",
            7,
            HASH,
            State.DRAFT,
            State.PUBLISHED,
            1,
            "publish",
            "idempotency-1",
            "different-operator",
            "request-1",
            "trace-1",
            "audit-1",
            PUBLISHED_AT
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> ApprovalProcessRelease.published(releasePackage(), transition)
        );
    }

    private static ApprovalProcessRelease.Transition transition(
        State from,
        State to,
        long revision,
        Instant happenedAt
    ) {
        return new ApprovalProcessRelease.Transition(
            UUID.randomUUID(),
            "tenant-a",
            "purchasePayment",
            7,
            HASH,
            from,
            to,
            revision,
            to.name().toLowerCase(),
            "idempotency-" + revision,
            "operator-a",
            "request-" + revision,
            "trace-" + revision,
            "audit-" + revision,
            happenedAt
        );
    }

    private static ApprovalReleasePackage releasePackage() {
        return new ApprovalReleasePackage(
            "tenant-a",
            "purchasePayment",
            7,
            6,
            "b".repeat(64),
            5,
            "c".repeat(64),
            4,
            "d".repeat(64),
            3,
            "e".repeat(64),
            "compiler-v2",
            "purchase-payment.bpmn20.xml",
            "<definitions/>",
            "f".repeat(64),
            "0".repeat(64),
            null,
            null,
            "1".repeat(64),
            HASH,
            UUID.randomUUID(),
            "operator-a",
            PUBLISHED_AT
        );
    }
}
