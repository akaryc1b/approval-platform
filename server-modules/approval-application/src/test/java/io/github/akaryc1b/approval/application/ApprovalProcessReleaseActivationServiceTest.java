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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalProcessReleaseActivationServiceTest {

    private static final String TENANT = "tenant-activation";
    private static final String DEFINITION_KEY = "purchasePayment";
    private static final Instant NOW = Instant.parse("2026-07-22T01:00:00Z");
    private static final UUID AUDIT_ID = uuid(1);
    private static final UUID FIRST_TRANSITION_ID = uuid(2);
    private static final UUID SECOND_TRANSITION_ID = uuid(3);

    @Test
    void firstActivationWritesAuditLifecycleThenEffectiveProjection() {
        Harness harness = harness(published(1, "a"));

        var outcome = harness.service.activate(command(
            "activation-request", "activation-key", 1, 0L,
            "Activate reviewed release version one"
        ));

        assertEquals(List.of("lock", "audit", "active-1", "effective-activate"), harness.order);
        assertEquals(1, harness.auditCalls.get());
        assertEquals(State.ACTIVE, outcome.activeRelease().lifecycleState());
        assertNull(outcome.deprecatedRelease());
        assertFalse(outcome.replayedExistingLifecycle());
        assertTrue(outcome.activeRelease().lastIdempotencyKey().startsWith("activate:"));
        assertNotEquals("activation-key", outcome.activeRelease().lastIdempotencyKey());
        assertEquals("audit-event:" + AUDIT_ID, outcome.activeRelease().lastAuditChainReference());
    }

    @Test
    void switchDeprecatesCurrentBeforeActivatingTarget() {
        Harness harness = harness(active(1, "a"), published(2, "b"));

        var outcome = harness.service.activate(command(
            "switch-request", "switch-key", 2, 1L,
            "Activate release two after production review"
        ));

        assertEquals(
            List.of("lock", "audit", "deprecated-1", "active-2", "effective-activate"),
            harness.order
        );
        assertEquals(State.DEPRECATED, outcome.deprecatedRelease().lifecycleState());
        assertEquals(State.ACTIVE, outcome.activeRelease().lifecycleState());
        assertNotEquals(
            outcome.deprecatedRelease().lastIdempotencyKey(),
            outcome.activeRelease().lastIdempotencyKey()
        );
        assertEquals(1L, harness.store.activeCount());
    }

    @Test
    void rollbackOnlyReactivatesDeprecatedTarget() {
        Harness harness = harness(deprecated(1, "a"), active(2, "b"));

        var outcome = harness.service.rollback(command(
            "rollback-request", "rollback-key", 1, 2L,
            "Rollback to verified release one"
        ));

        assertEquals(1, outcome.activeRelease().releaseVersion());
        assertEquals(State.ACTIVE, outcome.activeRelease().lifecycleState());
        assertEquals(State.DEPRECATED, outcome.deprecatedRelease().lifecycleState());
        assertEquals("effective-rollback", harness.order.getLast());

        Harness invalid = harness(published(3, "c"));
        assertThrows(
            ApprovalProcessReleaseActivationService.ActivationEvidenceConflictException.class,
            () -> invalid.service.rollback(command(
                "invalid-rollback", "invalid-rollback-key", 3, 2L,
                "Reject rollback to never active release"
            ))
        );
    }

    @Test
    void alreadyActiveTargetIsSemanticReplayWithoutNewEvidence() {
        Harness harness = harness(active(1, "a"));

        var outcome = harness.service.activate(command(
            "semantic-request", "semantic-key", 1, 1L,
            "Confirm already active release evidence"
        ));

        assertTrue(outcome.replayedExistingLifecycle());
        assertEquals(0, harness.auditCalls.get());
        assertEquals(List.of("lock", "effective-activate"), harness.order);
        assertEquals(0, harness.store.history.size());
    }

    @Test
    void duplicateIsIdempotentAndConflictingReuseFailsClosed() {
        Harness harness = harness(published(1, "a"));
        ActivationCommand command = command(
            "duplicate-request", "duplicate-key", 1, 0L,
            "Activate release with duplicate retry"
        );

        var first = harness.service.activate(command);
        var replay = harness.service.activate(command);

        assertEquals(first, replay);
        assertEquals(1, harness.auditCalls.get());
        assertEquals(1, harness.effectiveCalls.get());
        assertThrows(
            IdempotencyGuard.IdempotencyConflictException.class,
            () -> harness.service.activate(command(
                "duplicate-request", "duplicate-key", 1, 0L,
                "Activate release with changed reason"
            ))
        );
    }

    @Test
    void auditFailureStopsLifecycleAndEffectiveMutation() {
        Store store = new Store(new ArrayList<>());
        store.seed(published(1, "a"));
        AtomicInteger effectiveCalls = new AtomicInteger();
        ApprovalProcessReleaseActivationService service = service(
            store,
            (command, operation) -> {
                effectiveCalls.incrementAndGet();
                return effective(command.releaseVersion(), store.targetHash(command.releaseVersion()), false);
            },
            event -> {
                store.order.add("audit");
                throw new IllegalStateException("audit unavailable");
            }
        );

        assertThrows(
            IllegalStateException.class,
            () -> service.activate(command(
                "audit-failure", "audit-failure-key", 1, 0L,
                "Activate only with durable audit evidence"
            ))
        );
        assertEquals(State.PUBLISHED, store.find(TENANT, DEFINITION_KEY, 1)
            .orElseThrow().lifecycleState());
        assertEquals(0, effectiveCalls.get());
        assertEquals(List.of("lock", "audit"), store.order);
    }

    @Test
    void crossTenantReleaseReturnsNonDisclosingNotFound() {
        Harness harness = harness(published(1, "a"));
        ActivationCommand crossTenant = new ActivationCommand(
            new RequestContext(
                "other-tenant", "operator", "cross-request", "cross-key", "cross-trace"
            ),
            DEFINITION_KEY,
            1,
            0L,
            "Attempt cross tenant activation"
        );

        assertThrows(
            ApprovalProcessReleaseActivationService.ProcessReleaseNotFoundException.class,
            () -> harness.service.activate(crossTenant)
        );
        assertEquals(0, harness.auditCalls.get());
        assertEquals(0, harness.effectiveCalls.get());
    }

    private static Harness harness(ApprovalProcessRelease... releases) {
        List<String> order = new ArrayList<>();
        Store store = new Store(order);
        for (ApprovalProcessRelease release : releases) {
            store.seed(release);
        }
        AtomicInteger audits = new AtomicInteger();
        AtomicInteger effective = new AtomicInteger();
        ApprovalProcessReleaseActivationService service = service(
            store,
            (command, operation) -> {
                effective.incrementAndGet();
                order.add("effective-" + operation.name().toLowerCase());
                return effective(
                    command.releaseVersion(),
                    store.targetHash(command.releaseVersion()),
                    store.find(command.context().tenantId(), command.definitionKey(), command.releaseVersion())
                        .map(value -> value.lifecycleState() == State.ACTIVE)
                        .orElse(false)
                );
            },
            event -> {
                audits.incrementAndGet();
                order.add("audit");
            }
        );
        return new Harness(service, store, order, audits, effective);
    }

    private static ApprovalProcessReleaseActivationService service(
        Store store,
        ApprovalProcessReleaseActivationService.EffectiveReleaseActivator effective,
        io.github.akaryc1b.approval.application.port.AuditEventSink audit
    ) {
        Queue<UUID> identifiers = new ArrayDeque<>(List.of(
            AUDIT_ID, FIRST_TRANSITION_ID, SECOND_TRANSITION_ID
        ));
        return new ApprovalProcessReleaseActivationService(
            new MemoryIdempotency(),
            store,
            effective,
            audit,
            new ApprovalReleasePackageHasher(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            identifiers::remove
        );
    }

    private static ActivationCommand command(
        String requestId,
        String idempotencyKey,
        int releaseVersion,
        Long expectedRevision,
        String reason
    ) {
        return new ActivationCommand(
            new RequestContext(
                TENANT, "operator-activation", requestId, idempotencyKey, requestId + "-trace"
            ),
            DEFINITION_KEY,
            releaseVersion,
            expectedRevision,
            reason
        );
    }

    private static ApprovalProcessRelease published(int version, String hashCharacter) {
        ApprovalReleasePackage release = releasePackage(version, hashCharacter);
        return ApprovalProcessRelease.published(
            release,
            transition(release, State.DRAFT, State.PUBLISHED, 1, "publish-" + version,
                release.publishedAt())
        );
    }

    private static ApprovalProcessRelease active(int version, String hashCharacter) {
        ApprovalProcessRelease release = published(version, hashCharacter);
        return release.transitioned(new ApprovalProcessRelease.Transition(
            UUID.nameUUIDFromBytes(("active-" + version).getBytes()),
            TENANT, DEFINITION_KEY, version, release.releasePackageHash(),
            State.PUBLISHED, State.ACTIVE, 2, "activate-" + version,
            "original-active-" + version, "operator-original",
            "request-active-" + version, "trace-active-" + version,
            "audit-active-" + version, NOW.minusSeconds(60)
        ));
    }

    private static ApprovalProcessRelease deprecated(int version, String hashCharacter) {
        ApprovalProcessRelease release = active(version, hashCharacter);
        return release.transitioned(new ApprovalProcessRelease.Transition(
            UUID.nameUUIDFromBytes(("deprecated-" + version).getBytes()),
            TENANT, DEFINITION_KEY, version, release.releasePackageHash(),
            State.ACTIVE, State.DEPRECATED, 3, "deprecate-" + version,
            "original-deprecated-" + version, "operator-original",
            "request-deprecated-" + version, "trace-deprecated-" + version,
            "audit-deprecated-" + version, NOW.minusSeconds(30)
        ));
    }

    private static ApprovalProcessRelease.Transition transition(
        ApprovalReleasePackage release,
        State from,
        State to,
        long revision,
        String suffix,
        Instant happenedAt
    ) {
        return new ApprovalProcessRelease.Transition(
            UUID.nameUUIDFromBytes(suffix.getBytes()),
            release.tenantId(), release.definitionKey(), release.releaseVersion(),
            release.packageHash(), from, to, revision, suffix, suffix + "-key",
            release.publishedBy(), suffix + "-request", suffix + "-trace",
            suffix + "-audit", happenedAt
        );
    }

    private static ApprovalReleasePackage releasePackage(int version, String hashCharacter) {
        return new ApprovalReleasePackage(
            TENANT, DEFINITION_KEY, version, version,
            "1".repeat(64), 1, "2".repeat(64), 1, "3".repeat(64), 1, "4".repeat(64),
            "compiler-v1", "process.bpmn20.xml", "<definitions/>",
            "5".repeat(64), "6".repeat(64), null, null, "7".repeat(64),
            hashCharacter.repeat(64),
            UUID.nameUUIDFromBytes(("draft-" + version).getBytes()),
            "publisher", NOW.minusSeconds(120)
        );
    }

    private static ActivationResult effective(int version, String hash, boolean replayed) {
        return new ActivationResult(
            new ApprovalEffectiveRelease(
                TENANT, DEFINITION_KEY, version, null, hash, version,
                "1".repeat(64), 1, "2".repeat(64), 1, "3".repeat(64),
                1, "4".repeat(64), "compiler-v1", "5".repeat(64),
                "6".repeat(64), "7".repeat(64),
                "engine-deployment-" + version, "engine-definition-" + version, version,
                ApprovalEffectiveRelease.Status.ACTIVE, version,
                "operator-activation", NOW, "activation result",
                "effective-request-" + version, "effective-trace-" + version
            ),
            null,
            replayed
        );
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("70000000-0000-0000-0000-" + String.format("%012d", suffix));
    }

    private record Harness(
        ApprovalProcessReleaseActivationService service,
        Store store,
        List<String> order,
        AtomicInteger auditCalls,
        AtomicInteger effectiveCalls
    ) {
    }

    private static final class MemoryIdempotency implements IdempotencyGuard {
        private final Map<String, Stored> values = new HashMap<>();

        @Override
        public <T> T execute(
            RequestContext context,
            String operation,
            String requestHash,
            Class<T> resultType,
            java.util.function.Supplier<T> action
        ) {
            String key = context.tenantId() + ':' + operation + ':' + context.idempotencyKey();
            Stored existing = values.get(key);
            if (existing != null) {
                if (!existing.hash.equals(requestHash) || !existing.type.equals(resultType)) {
                    throw new IdempotencyConflictException("conflicting idempotency reuse");
                }
                return resultType.cast(existing.value);
            }
            T value = action.get();
            values.put(key, new Stored(requestHash, resultType, value));
            return value;
        }

        private record Stored(String hash, Class<?> type, Object value) {
        }
    }

    private static final class Store implements ApprovalProcessReleaseStore {
        private final Map<String, ApprovalProcessRelease> values = new HashMap<>();
        private final List<ApprovalProcessRelease.Transition> history = new ArrayList<>();
        private final List<String> order;

        private Store(List<String> order) {
            this.order = order;
        }

        private void seed(ApprovalProcessRelease release) {
            values.put(key(release.tenantId(), release.definitionKey(), release.releaseVersion()), release);
        }

        private long activeCount() {
            return values.values().stream()
                .filter(value -> value.lifecycleState() == State.ACTIVE)
                .count();
        }

        private String targetHash(int version) {
            return find(TENANT, DEFINITION_KEY, version).orElseThrow().releasePackageHash();
        }

        @Override
        public void lock(String tenantId, String definitionKey) {
            order.add("lock");
        }

        @Override
        public Optional<ApprovalProcessRelease> find(
            String tenantId,
            String definitionKey,
            int releaseVersion
        ) {
            return Optional.ofNullable(values.get(key(tenantId, definitionKey, releaseVersion)));
        }

        @Override
        public Optional<ApprovalProcessRelease> findActive(String tenantId, String definitionKey) {
            return values.values().stream()
                .filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> value.definitionKey().equals(definitionKey))
                .filter(value -> value.lifecycleState() == State.ACTIVE)
                .findFirst();
        }

        @Override
        public Optional<ApprovalProcessRelease.Transition> findTransitionByIdempotency(
            String tenantId,
            String idempotencyKey
        ) {
            return history.stream()
                .filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> value.idempotencyKey().equals(idempotencyKey))
                .findFirst();
        }

        @Override
        public ReleasePage findReleases(ReleaseCriteria criteria) {
            List<ApprovalProcessRelease> items = values.values().stream()
                .filter(value -> value.tenantId().equals(criteria.tenantId()))
                .sorted(Comparator.comparingInt(ApprovalProcessRelease::releaseVersion))
                .toList();
            return new ReleasePage(items, items.size(), criteria.limit(), criteria.offset());
        }

        @Override
        public TransitionPage findHistory(TransitionCriteria criteria) {
            return new TransitionPage(history, history.size(), criteria.limit(), criteria.offset());
        }

        @Override
        public void savePublished(
            ApprovalProcessRelease release,
            ApprovalProcessRelease.Transition transition
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean transition(
            ApprovalProcessRelease release,
            long expectedRevision,
            ApprovalProcessRelease.Transition transition
        ) {
            String key = key(release.tenantId(), release.definitionKey(), release.releaseVersion());
            ApprovalProcessRelease current = values.get(key);
            if (current == null || current.revision() != expectedRevision) {
                return false;
            }
            if (release.lifecycleState() == State.ACTIVE) {
                boolean anotherActive = values.values().stream().anyMatch(value ->
                    value.tenantId().equals(release.tenantId())
                        && value.definitionKey().equals(release.definitionKey())
                        && value.releaseVersion() != release.releaseVersion()
                        && value.lifecycleState() == State.ACTIVE
                );
                if (anotherActive) {
                    throw new IllegalStateException("another release remains active");
                }
            }
            values.put(key, release);
            history.add(transition);
            order.add(release.lifecycleState().name().toLowerCase() + '-' + release.releaseVersion());
            return true;
        }

        private static String key(String tenantId, String definitionKey, int releaseVersion) {
            return tenantId + ':' + definitionKey + ':' + releaseVersion;
        }
    }
}
