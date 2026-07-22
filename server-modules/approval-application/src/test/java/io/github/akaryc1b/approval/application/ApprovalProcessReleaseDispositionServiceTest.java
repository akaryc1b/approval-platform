package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalEffectiveReleaseDeactivationPort;
import io.github.akaryc1b.approval.application.port.ApprovalEffectiveReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalRuntimeBindingStore;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalEffectiveRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalProcessRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.definition.ApprovalRuntimeBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
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

class ApprovalProcessReleaseDispositionServiceTest {

    private static final String TENANT = "tenant-disposition";
    private static final String KEY = "purchasePayment";
    private static final Instant NOW = Instant.parse("2026-07-22T08:00:00Z");

    private InMemoryProcessReleaseStore releases;
    private InMemoryEffectiveStore effective;
    private InMemoryRuntimeBindingStore bindings;
    private List<AuditEvent> audits;
    private ApprovalProcessReleaseDispositionService service;

    @BeforeEach
    void setUp() {
        releases = new InMemoryProcessReleaseStore();
        effective = new InMemoryEffectiveStore();
        bindings = new InMemoryRuntimeBindingStore();
        audits = new ArrayList<>();
        service = service(audits::add, effective);
    }

    @Test
    void deprecatesActiveReleaseAndClearsOnlyTheMutableEffectiveProjection() {
        ApprovalProcessRelease active = activeRelease(1);
        releases.values.put(1, active);
        effective.current = effectiveRelease(active, 4);
        bindings.usage = 12;

        var result = service.deprecate(command(1, active.revision(), "Stop new starts safely"));

        assertEquals(State.DEPRECATED, result.lifecycle().lifecycleState());
        assertEquals(12, result.runtimeUsageCount());
        assertFalse(result.replayedExistingDisposition());
        assertTrue(effective.find(TENANT, KEY).isEmpty());
        assertEquals(12, bindings.usage);
        assertEquals(1, releases.history.size());
        assertEquals(1, audits.size());
        assertEquals("PROCESS_RELEASE_DEPRECATION_AUTHORIZED", audits.getFirst().action());
    }

    @Test
    void retiresPublishedAndDeprecatedReleasesWithoutDeletingRuntimeBindings() {
        ApprovalProcessRelease published = publishedRelease(1);
        ApprovalProcessRelease deprecated = deprecatedRelease(2);
        releases.values.put(1, published);
        releases.values.put(2, deprecated);
        bindings.usage = 7;

        var first = service.retire(command(1, published.revision(), "Retire unused release"));
        var second = service.retire(command(2, deprecated.revision(), "Retire legacy release"));

        assertEquals(State.RETIRED, first.lifecycle().lifecycleState());
        assertEquals(State.RETIRED, second.lifecycle().lifecycleState());
        assertEquals(7, first.runtimeUsageCount());
        assertEquals(7, second.runtimeUsageCount());
        assertEquals(7, bindings.usage);
        assertEquals(2, audits.size());
    }

    @Test
    void rejectsInvalidTransitionsAndEffectiveProjectionInconsistency() {
        ApprovalProcessRelease published = publishedRelease(1);
        releases.values.put(1, published);
        assertThrows(
            ApprovalProcessReleaseDispositionService.DispositionEvidenceConflictException.class,
            () -> service.deprecate(command(1, published.revision(), "Cannot deprecate draft"))
        );

        ApprovalProcessRelease active = activeRelease(2);
        releases.values.put(2, active);
        effective.current = effectiveRelease(active, 3);
        assertThrows(
            ApprovalProcessReleaseDispositionService.DispositionEvidenceConflictException.class,
            () -> service.retire(command(2, active.revision(), "Must deprecate before retire"))
        );

        ApprovalProcessRelease deprecated = deprecatedRelease(3);
        releases.values.put(3, deprecated);
        effective.current = effectiveRelease(deprecated, 5);
        assertThrows(
            ApprovalProcessReleaseDispositionService.DispositionEvidenceConflictException.class,
            () -> service.retire(command(3, deprecated.revision(), "Reject stale projection"))
        );
    }

    @Test
    void semanticReplayRequiresTheReleaseToBeAbsentFromEffectiveProjection() {
        ApprovalProcessRelease deprecated = deprecatedRelease(1);
        releases.values.put(1, deprecated);

        var replay = service.deprecate(command(
            1,
            deprecated.revision(),
            "Confirm deprecated evidence"
        ));
        assertTrue(replay.replayedExistingDisposition());
        assertEquals(0, audits.size());
        assertEquals(0, releases.history.size());

        effective.current = effectiveRelease(deprecated, 2);
        assertThrows(
            ApprovalProcessReleaseDispositionService.DispositionEvidenceConflictException.class,
            () -> service.deprecate(command(
                1,
                deprecated.revision(),
                "Reject inconsistent replay"
            ))
        );
    }

    @Test
    void auditFailureIsFailClosedBeforeLifecycleOrProjectionMutation() {
        ApprovalProcessRelease active = activeRelease(1);
        releases.values.put(1, active);
        effective.current = effectiveRelease(active, 2);
        ApprovalProcessReleaseDispositionService failing = service(event -> {
            throw new IllegalStateException("audit unavailable");
        }, effective);

        assertThrows(
            IllegalStateException.class,
            () -> failing.deprecate(command(1, active.revision(), "Audit must be durable"))
        );
        assertEquals(State.ACTIVE, releases.values.get(1).lifecycleState());
        assertTrue(effective.find(TENANT, KEY).isPresent());
        assertEquals(0, releases.history.size());
    }

    @Test
    void crossTenantAndStaleRevisionFailWithoutDisclosureOrMutation() {
        ApprovalProcessRelease published = publishedRelease(1);
        releases.values.put(1, published);

        assertThrows(
            ApprovalProcessReleaseDispositionService.ProcessReleaseNotFoundException.class,
            () -> service.retire(new ApprovalProcessReleaseDispositionService.DispositionCommand(
                context("other-tenant"),
                KEY,
                1,
                published.revision(),
                "Cross tenant retirement"
            ))
        );
        assertThrows(
            ApprovalProcessReleaseDispositionService.DispositionEvidenceConflictException.class,
            () -> service.retire(command(1, published.revision() + 1, "Reject stale revision"))
        );
        assertEquals(State.PUBLISHED, releases.values.get(1).lifecycleState());
        assertEquals(0, audits.size());
    }

    @Test
    void reasonIsNormalizedAndBoundedInsideTheApplicationCommand() {
        var command = command(1, 1, "  Ｒｅｔｉｒｅ reviewed release  ");
        assertEquals("Retire reviewed release", command.reason());
        assertThrows(
            IllegalArgumentException.class,
            () -> command(1, 1, "short")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> command(1, 1, "Invalid\nreason")
        );
    }

    private ApprovalProcessReleaseDispositionService service(
        io.github.akaryc1b.approval.application.port.AuditEventSink audit,
        ApprovalEffectiveReleaseDeactivationPort deactivation
    ) {
        return new ApprovalProcessReleaseDispositionService(
            passThroughIdempotency(),
            releases,
            effective,
            deactivation,
            bindings,
            audit,
            new ApprovalReleasePackageHasher(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            UUID::randomUUID
        );
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

    private static ApprovalProcessReleaseDispositionService.DispositionCommand command(
        int releaseVersion,
        long expectedRevision,
        String reason
    ) {
        return new ApprovalProcessReleaseDispositionService.DispositionCommand(
            context(TENANT),
            KEY,
            releaseVersion,
            expectedRevision,
            reason
        );
    }

    private static RequestContext context(String tenant) {
        return new RequestContext(
            tenant,
            "operator-disposition",
            "request-" + tenant,
            "idempotency-" + tenant,
            "trace-disposition"
        );
    }

    private static ApprovalProcessRelease publishedRelease(int version) {
        ApprovalReleasePackage release = releasePackage(version);
        ApprovalProcessRelease.Transition publish = transition(
            release,
            State.DRAFT,
            State.PUBLISHED,
            1,
            release.publishedAt(),
            "publish-" + version
        );
        return ApprovalProcessRelease.published(release, publish);
    }

    private static ApprovalProcessRelease activeRelease(int version) {
        ApprovalProcessRelease published = publishedRelease(version);
        ApprovalProcessRelease.Transition activate = transition(
            releasePackage(version),
            State.PUBLISHED,
            State.ACTIVE,
            2,
            NOW.minusSeconds(120),
            "activate-" + version
        );
        return published.transitioned(activate);
    }

    private static ApprovalProcessRelease deprecatedRelease(int version) {
        ApprovalProcessRelease active = activeRelease(version);
        ApprovalProcessRelease.Transition deprecate = transition(
            releasePackage(version),
            State.ACTIVE,
            State.DEPRECATED,
            3,
            NOW.minusSeconds(60),
            "deprecate-" + version
        );
        return active.transitioned(deprecate);
    }

    private static ApprovalProcessRelease.Transition transition(
        ApprovalReleasePackage release,
        State from,
        State to,
        long revision,
        Instant happenedAt,
        String suffix
    ) {
        return new ApprovalProcessRelease.Transition(
            UUID.nameUUIDFromBytes(suffix.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            release.tenantId(),
            release.definitionKey(),
            release.releaseVersion(),
            release.packageHash(),
            from,
            to,
            revision,
            "Lifecycle reason " + suffix,
            "idempotency-" + suffix,
            "operator-disposition",
            "request-" + suffix,
            "trace-disposition",
            "audit-event:" + suffix,
            happenedAt
        );
    }

    private static ApprovalReleasePackage releasePackage(int version) {
        return new ApprovalReleasePackage(
            TENANT,
            KEY,
            version,
            version,
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
            Integer.toHexString(version).repeat(64).substring(0, 64),
            new UUID(5, version),
            "publisher",
            NOW.minusSeconds(300)
        );
    }

    private static ApprovalEffectiveRelease effectiveRelease(
        ApprovalProcessRelease lifecycle,
        long revision
    ) {
        return new ApprovalEffectiveRelease(
            lifecycle.tenantId(),
            lifecycle.definitionKey(),
            lifecycle.releaseVersion(),
            null,
            lifecycle.releasePackageHash(),
            lifecycle.releaseVersion(),
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
            "engine-deployment-" + lifecycle.releaseVersion(),
            "engine-definition-" + lifecycle.releaseVersion(),
            1,
            ApprovalEffectiveRelease.Status.ACTIVE,
            revision,
            "operator-disposition",
            NOW.minusSeconds(30),
            "Effective release reason",
            "request-effective",
            "trace-effective"
        );
    }

    private static final class InMemoryProcessReleaseStore
        implements ApprovalProcessReleaseStore {
        private final Map<Integer, ApprovalProcessRelease> values = new HashMap<>();
        private final List<ApprovalProcessRelease.Transition> history = new ArrayList<>();

        @Override
        public void lock(String tenantId, String definitionKey) {
        }

        @Override
        public Optional<ApprovalProcessRelease> find(
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
        public Optional<ApprovalProcessRelease> findActive(
            String tenantId,
            String definitionKey
        ) {
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
            values.put(release.releaseVersion(), release);
            history.add(transition);
        }

        @Override
        public boolean transition(
            ApprovalProcessRelease release,
            long expectedRevision,
            ApprovalProcessRelease.Transition transition
        ) {
            ApprovalProcessRelease current = values.get(release.releaseVersion());
            if (current == null || current.revision() != expectedRevision) {
                return false;
            }
            values.put(release.releaseVersion(), release);
            history.add(transition);
            return true;
        }
    }

    private static final class InMemoryEffectiveStore
        implements ApprovalEffectiveReleaseStore, ApprovalEffectiveReleaseDeactivationPort {
        private ApprovalEffectiveRelease current;

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
        }

        @Override
        public boolean update(
            ApprovalEffectiveRelease effectiveRelease,
            long expectedRevision,
            ApprovalEffectiveRelease.Activation activation
        ) {
            return false;
        }

        @Override
        public boolean wasActivated(String tenantId, String definitionKey, int releaseVersion) {
            return current != null && current.effectiveReleaseVersion() == releaseVersion;
        }

        @Override
        public ActivationPage findHistory(ActivationCriteria criteria) {
            return new ActivationPage(List.of(), 0, criteria.limit(), criteria.offset());
        }

        @Override
        public boolean clear(String tenantId, String definitionKey, long expectedRevision) {
            if (current == null || current.revision() != expectedRevision) {
                return false;
            }
            current = null;
            return true;
        }
    }

    private static final class InMemoryRuntimeBindingStore
        implements ApprovalRuntimeBindingStore {
        private long usage;

        @Override
        public Optional<ApprovalRuntimeBinding> find(String tenantId, UUID approvalInstanceId) {
            return Optional.empty();
        }

        @Override
        public Optional<ApprovalRuntimeBinding> findByEngineInstance(
            String tenantId,
            String engineInstanceId
        ) {
            return Optional.empty();
        }

        @Override
        public BindingPage findByRelease(BindingCriteria criteria) {
            return new BindingPage(List.of(), usage, criteria.limit(), criteria.offset());
        }

        @Override
        public long countReleaseUsage(
            String tenantId,
            String definitionKey,
            int releaseVersion
        ) {
            return TENANT.equals(tenantId) && KEY.equals(definitionKey) ? usage : 0;
        }

        @Override
        public void save(ApprovalRuntimeBinding binding) {
            throw new AssertionError("Disposition must never mutate runtime bindings");
        }
    }
}
