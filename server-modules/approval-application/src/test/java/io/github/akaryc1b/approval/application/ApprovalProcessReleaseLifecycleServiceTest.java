package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalDesignDraftStore;
import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionSimulator;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalDesignDraft;
import io.github.akaryc1b.approval.domain.definition.ApprovalProcessRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalProcessReleaseLifecycleServiceTest {

    private static final String TENANT = "tenant-release-lifecycle";
    private static final String DEFINITION_KEY = PurchasePaymentTemplate.DEFINITION_KEY;
    private static final UUID DRAFT_ID = UUID.fromString(
        "50000000-0000-0000-0000-000000000001"
    );
    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");
    private static final UUID AUDIT_ID = UUID.fromString(
        "60000000-0000-0000-0000-000000000001"
    );
    private static final UUID TRANSITION_ID = UUID.fromString(
        "60000000-0000-0000-0000-000000000002"
    );

    @Test
    void writesAuditBeforePublicationAndPersistsExactLifecycleEvidence() {
        List<String> order = new ArrayList<>();
        InMemoryProcessReleaseStore releases = new InMemoryProcessReleaseStore(order);
        ApprovalReleasePackage releasePackage = releasePackage();
        AtomicInteger publicationCalls = new AtomicInteger();
        List<io.github.akaryc1b.approval.domain.audit.AuditEvent> audits = new ArrayList<>();
        ApprovalProcessReleaseLifecycleService service = service(
            releases,
            command -> {
                publicationCalls.incrementAndGet();
                order.add("publish");
                return new ApprovalDesignResults.Publish(releasePackage, 2, false);
            },
            event -> {
                order.add("audit");
                audits.add(event);
            }
        );

        ApprovalProcessReleaseLifecycleService.PublishResult result = service.publish(command(
            context("request-publish", "idempotency-publish"),
            "Publish verified purchase approval release"
        ));

        assertEquals(List.of("lock", "audit", "publish", "lifecycle"), order);
        assertEquals(1, publicationCalls.get());
        assertFalse(result.replayedExistingLifecycle());
        assertEquals(State.PUBLISHED, result.lifecycle().lifecycleState());
        assertEquals("Publish verified purchase approval release", result.lifecycle()
            .lastTransitionReason());
        assertEquals("audit-event:" + AUDIT_ID, result.lifecycle().lastAuditChainReference());
        assertEquals(TRANSITION_ID, releases.history.getFirst().transitionId());
        assertEquals(1, audits.size());
        assertEquals("PROCESS_RELEASE_PUBLISH_AUTHORIZED", audits.getFirst().action());
        assertEquals(command(
            context("request-publish", "idempotency-publish"),
            "Publish verified purchase approval release"
        ).publication().preflightHash(), audits.getFirst().attributes().get("preflightHash"));
    }

    @Test
    void duplicateIdempotencyReturnsOriginalResultWithoutRepeatingEffects() {
        List<String> order = new ArrayList<>();
        InMemoryProcessReleaseStore releases = new InMemoryProcessReleaseStore(order);
        ApprovalReleasePackage releasePackage = releasePackage();
        AtomicInteger publicationCalls = new AtomicInteger();
        AtomicInteger auditCalls = new AtomicInteger();
        ApprovalProcessReleaseLifecycleService service = service(
            releases,
            command -> {
                publicationCalls.incrementAndGet();
                order.add("publish");
                return new ApprovalDesignResults.Publish(releasePackage, 2, false);
            },
            event -> {
                auditCalls.incrementAndGet();
                order.add("audit");
            }
        );
        var command = command(
            context("request-duplicate", "idempotency-duplicate"),
            "Publish release after governance review"
        );

        var first = service.publish(command);
        var replay = service.publish(command);

        assertEquals(first, replay);
        assertEquals(1, publicationCalls.get());
        assertEquals(1, auditCalls.get());
        assertEquals(1, releases.history.size());
        assertEquals(List.of("lock", "audit", "publish", "lifecycle"), order);
    }

    @Test
    void conflictingReasonReuseFailsClosed() {
        InMemoryProcessReleaseStore releases = new InMemoryProcessReleaseStore(new ArrayList<>());
        ApprovalReleasePackage releasePackage = releasePackage();
        ApprovalProcessReleaseLifecycleService service = service(
            releases,
            command -> new ApprovalDesignResults.Publish(releasePackage, 2, false),
            event -> {
            }
        );
        RequestContext context = context("request-conflict", "idempotency-conflict");
        service.publish(command(context, "Publish release after first review"));

        assertThrows(
            IdempotencyGuard.IdempotencyConflictException.class,
            () -> service.publish(command(context, "Publish release after changed review"))
        );
    }

    @Test
    void semanticReplayReturnsExistingLifecycleWithoutNewPublishAudit() {
        List<String> order = new ArrayList<>();
        InMemoryProcessReleaseStore releases = new InMemoryProcessReleaseStore(order);
        ApprovalReleasePackage releasePackage = releasePackage();
        ApprovalProcessRelease lifecycle = publishedLifecycle(releasePackage);
        releases.seed(lifecycle);
        AtomicInteger auditCalls = new AtomicInteger();
        ApprovalProcessReleaseLifecycleService service = service(
            releases,
            command -> {
                order.add("publish");
                return new ApprovalDesignResults.Publish(releasePackage, 2, true);
            },
            event -> auditCalls.incrementAndGet()
        );

        var result = service.publish(command(
            context("request-semantic", "idempotency-semantic"),
            "Confirm existing publication evidence"
        ));

        assertTrue(result.replayedExistingLifecycle());
        assertEquals(lifecycle, result.lifecycle());
        assertEquals(0, auditCalls.get());
        assertEquals(List.of("lock", "publish"), order);
        assertEquals(1, releases.history.size());
    }

    @Test
    void auditFailureStopsPublicationAndLifecycleMutation() {
        List<String> order = new ArrayList<>();
        InMemoryProcessReleaseStore releases = new InMemoryProcessReleaseStore(order);
        AtomicInteger publicationCalls = new AtomicInteger();
        ApprovalProcessReleaseLifecycleService service = service(
            releases,
            command -> {
                publicationCalls.incrementAndGet();
                return new ApprovalDesignResults.Publish(releasePackage(), 2, false);
            },
            event -> {
                order.add("audit");
                throw new IllegalStateException("audit unavailable");
            }
        );

        assertThrows(
            IllegalStateException.class,
            () -> service.publish(command(
                context("request-audit-failure", "idempotency-audit-failure"),
                "Publish only with durable audit evidence"
            ))
        );
        assertEquals(0, publicationCalls.get());
        assertTrue(releases.values.isEmpty());
        assertEquals(List.of("lock", "audit"), order);
    }

    @Test
    void crossTenantDraftReturnsNonDisclosingNotFoundBeforeAnySideEffect() {
        List<String> order = new ArrayList<>();
        InMemoryProcessReleaseStore releases = new InMemoryProcessReleaseStore(order);
        AtomicInteger publicationCalls = new AtomicInteger();
        ApprovalProcessReleaseLifecycleService service = service(
            releases,
            command -> {
                publicationCalls.incrementAndGet();
                return new ApprovalDesignResults.Publish(releasePackage(), 2, false);
            },
            event -> order.add("audit")
        );
        ApprovalDesignCommands.Publish publication = publication(
            new RequestContext(
                "other-tenant",
                "operator-release",
                "request-cross-tenant",
                "idempotency-cross-tenant",
                "trace-cross-tenant"
            )
        );

        assertThrows(
            ApprovalProcessReleaseLifecycleService.ReleaseDraftNotFoundException.class,
            () -> service.publish(new ApprovalProcessReleaseLifecycleService.PublishCommand(
                publication,
                "Publish cross tenant release evidence"
            ))
        );
        assertEquals(0, publicationCalls.get());
        assertTrue(order.isEmpty());
    }

    private static ApprovalProcessReleaseLifecycleService service(
        InMemoryProcessReleaseStore releases,
        ApprovalProcessReleaseLifecycleService.ReleasePublisher publisher,
        io.github.akaryc1b.approval.application.port.AuditEventSink auditEvents
    ) {
        Queue<UUID> identifiers = new ArrayDeque<>(List.of(AUDIT_ID, TRANSITION_ID));
        return new ApprovalProcessReleaseLifecycleService(
            new InMemoryIdempotencyGuard(),
            new SingleDraftStore(draft()),
            publisher,
            releases,
            auditEvents,
            new ApprovalReleasePackageHasher(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            identifiers::remove
        );
    }

    private static ApprovalProcessReleaseLifecycleService.PublishCommand command(
        RequestContext context,
        String reason
    ) {
        return new ApprovalProcessReleaseLifecycleService.PublishCommand(
            publication(context),
            reason
        );
    }

    private static ApprovalDesignCommands.Publish publication(RequestContext context) {
        return new ApprovalDesignCommands.Publish(
            context,
            DRAFT_ID,
            1,
            PurchasePaymentTemplate.PROCESS_VERSION,
            1,
            "default",
            "a".repeat(64),
            List.of("ASSIGNEE_SNAPSHOT_WARNING"),
            ApprovalDefinitionSimulator.Scenario.empty()
        );
    }

    private static RequestContext context(String requestId, String idempotencyKey) {
        return new RequestContext(
            TENANT,
            "operator-release",
            requestId,
            idempotencyKey,
            requestId + "-trace"
        );
    }

    private static ApprovalDesignDraft draft() {
        return new ApprovalDesignDraft(
            DRAFT_ID,
            TENANT,
            DEFINITION_KEY,
            "Purchase payment",
            PurchasePaymentTemplate.processDefinition(),
            new ApprovalDesignDraft.FormPackageReference(DEFINITION_KEY, 1, "b".repeat(64)),
            null,
            1,
            ApprovalDesignDraft.Status.VALIDATED,
            null,
            null,
            "operator-release",
            "operator-release",
            NOW.minusSeconds(60),
            NOW.minusSeconds(30)
        );
    }

    private static ApprovalReleasePackage releasePackage() {
        return new ApprovalReleasePackage(
            TENANT,
            DEFINITION_KEY,
            1,
            PurchasePaymentTemplate.PROCESS_VERSION,
            "c".repeat(64),
            1,
            "b".repeat(64),
            1,
            "d".repeat(64),
            1,
            "e".repeat(64),
            "compiler-v1",
            "purchase-payment.bpmn20.xml",
            "<definitions/>",
            "f".repeat(64),
            "0".repeat(64),
            null,
            null,
            "1".repeat(64),
            "2".repeat(64),
            DRAFT_ID,
            "operator-release",
            NOW
        );
    }

    private static ApprovalProcessRelease publishedLifecycle(
        ApprovalReleasePackage releasePackage
    ) {
        ApprovalProcessRelease.Transition transition = new ApprovalProcessRelease.Transition(
            TRANSITION_ID,
            TENANT,
            DEFINITION_KEY,
            1,
            releasePackage.packageHash(),
            State.DRAFT,
            State.PUBLISHED,
            1,
            "Original governed publication reason",
            "original-idempotency",
            "operator-release",
            "original-request",
            "original-trace",
            "audit-event:" + AUDIT_ID,
            NOW
        );
        return ApprovalProcessRelease.published(releasePackage, transition);
    }

    private static final class InMemoryIdempotencyGuard implements IdempotencyGuard {
        private final Map<String, StoredResult> results = new HashMap<>();

        @Override
        public <T> T execute(
            RequestContext context,
            String operation,
            String requestHash,
            Class<T> resultType,
            java.util.function.Supplier<T> action
        ) {
            String key = context.tenantId() + ':' + operation + ':' + context.idempotencyKey();
            StoredResult existing = results.get(key);
            if (existing != null) {
                if (!existing.requestHash.equals(requestHash)
                    || !existing.resultType.equals(resultType)) {
                    throw new IdempotencyConflictException(
                        "idempotency key was reused for a conflicting request"
                    );
                }
                return resultType.cast(existing.result);
            }
            T result = action.get();
            results.put(key, new StoredResult(requestHash, resultType, result));
            return result;
        }

        private record StoredResult(String requestHash, Class<?> resultType, Object result) {
        }
    }

    private record SingleDraftStore(ApprovalDesignDraft draft) implements ApprovalDesignDraftStore {
        @Override
        public void save(ApprovalDesignDraft value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ApprovalDesignDraft> find(String tenantId, UUID draftId) {
            return draft.tenantId().equals(tenantId) && draft.draftId().equals(draftId)
                ? Optional.of(draft)
                : Optional.empty();
        }

        @Override
        public DraftPage findDrafts(DraftCriteria criteria) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void lock(String tenantId, UUID draftId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean update(ApprovalDesignDraft value, long expectedRevision) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class InMemoryProcessReleaseStore implements ApprovalProcessReleaseStore {
        private final List<String> order;
        private final Map<String, ApprovalProcessRelease> values = new HashMap<>();
        private final List<ApprovalProcessRelease.Transition> history = new ArrayList<>();

        private InMemoryProcessReleaseStore(List<String> order) {
            this.order = order;
        }

        private void seed(ApprovalProcessRelease release) {
            values.put(key(release.tenantId(), release.definitionKey(), release.releaseVersion()), release);
            history.add(new ApprovalProcessRelease.Transition(
                TRANSITION_ID,
                release.tenantId(),
                release.definitionKey(),
                release.releaseVersion(),
                release.releasePackageHash(),
                State.DRAFT,
                State.PUBLISHED,
                1,
                release.lastTransitionReason(),
                release.lastIdempotencyKey(),
                release.lastTransitionBy(),
                release.lastRequestId(),
                release.lastTraceId(),
                release.lastAuditChainReference(),
                release.publishedAt()
            ));
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
                .filter(value -> value.tenantId().equals(criteria.tenantId()))
                .filter(value -> criteria.definitionKey() == null
                    || value.definitionKey().equals(criteria.definitionKey()))
                .filter(value -> criteria.lifecycleState() == null
                    || value.lifecycleState() == criteria.lifecycleState())
                .sorted(Comparator.comparingInt(ApprovalProcessRelease::releaseVersion).reversed())
                .toList();
            return new ReleasePage(items, items.size(), criteria.limit(), criteria.offset());
        }

        @Override
        public TransitionPage findHistory(TransitionCriteria criteria) {
            List<ApprovalProcessRelease.Transition> items = history.stream()
                .filter(value -> value.tenantId().equals(criteria.tenantId()))
                .filter(value -> value.definitionKey().equals(criteria.definitionKey()))
                .filter(value -> value.releaseVersion() == criteria.releaseVersion())
                .sorted(Comparator.comparingLong(
                    ApprovalProcessRelease.Transition::revision
                ).reversed())
                .toList();
            return new TransitionPage(items, items.size(), criteria.limit(), criteria.offset());
        }

        @Override
        public void savePublished(
            ApprovalProcessRelease release,
            ApprovalProcessRelease.Transition transition
        ) {
            String key = key(release.tenantId(), release.definitionKey(), release.releaseVersion());
            if (values.putIfAbsent(key, release) != null) {
                throw new IllegalStateException("release lifecycle already exists");
            }
            history.add(transition);
            order.add("lifecycle");
        }

        @Override
        public boolean transition(
            ApprovalProcessRelease release,
            long expectedRevision,
            ApprovalProcessRelease.Transition transition
        ) {
            throw new UnsupportedOperationException();
        }

        private static String key(String tenantId, String definitionKey, int releaseVersion) {
            return tenantId + ':' + definitionKey + ':' + releaseVersion;
        }
    }
}
