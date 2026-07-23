package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore;
import io.github.akaryc1b.approval.domain.definition.ApprovalProcessRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalProcessReleaseQueryServiceTest {

    @Test
    void delegatesTenantScopedReadCriteriaWithoutMutation() {
        RecordingStore store = new RecordingStore();
        ApprovalProcessReleaseQueryService service = new ApprovalProcessReleaseQueryService(store);

        service.find("tenant-a", "purchasePayment", 3);
        var releases = service.findReleases(
            "tenant-a",
            "purchasePayment",
            State.DEPRECATED,
            20,
            10
        );
        var history = service.findHistory(
            "tenant-a",
            "purchasePayment",
            3,
            50,
            0
        );

        assertEquals("tenant-a", store.foundTenant);
        assertEquals("purchasePayment", store.foundDefinitionKey);
        assertEquals(3, store.foundReleaseVersion);
        assertEquals(State.DEPRECATED, store.releaseCriteria.lifecycleState());
        assertEquals(20, releases.limit());
        assertEquals(10, releases.offset());
        assertEquals(3, store.transitionCriteria.releaseVersion());
        assertEquals(50, history.limit());
        assertFalse(store.mutated);
    }

    @Test
    void rejectsDraftAndInvalidPagingThroughCanonicalStoreCriteria() {
        ApprovalProcessReleaseQueryService service =
            new ApprovalProcessReleaseQueryService(new RecordingStore());

        assertThrows(
            IllegalArgumentException.class,
            () -> service.findReleases("tenant-a", "purchasePayment", State.DRAFT, 20, 0)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> service.findHistory("tenant-a", "purchasePayment", 3, 101, 0)
        );
    }

    private static final class RecordingStore implements ApprovalProcessReleaseStore {
        private String foundTenant;
        private String foundDefinitionKey;
        private int foundReleaseVersion;
        private ReleaseCriteria releaseCriteria;
        private TransitionCriteria transitionCriteria;
        private boolean mutated;

        @Override
        public void lock(String tenantId, String definitionKey) {
            mutated = true;
        }

        @Override
        public Optional<ApprovalProcessRelease> find(
            String tenantId,
            String definitionKey,
            int releaseVersion
        ) {
            foundTenant = tenantId;
            foundDefinitionKey = definitionKey;
            foundReleaseVersion = releaseVersion;
            return Optional.empty();
        }

        @Override
        public Optional<ApprovalProcessRelease> findActive(
            String tenantId,
            String definitionKey
        ) {
            return Optional.empty();
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
            releaseCriteria = criteria;
            return new ReleasePage(List.of(), 0, criteria.limit(), criteria.offset());
        }

        @Override
        public TransitionPage findHistory(TransitionCriteria criteria) {
            transitionCriteria = criteria;
            return new TransitionPage(List.of(), 0, criteria.limit(), criteria.offset());
        }

        @Override
        public void savePublished(
            ApprovalProcessRelease release,
            ApprovalProcessRelease.Transition transition
        ) {
            mutated = true;
        }

        @Override
        public boolean transition(
            ApprovalProcessRelease release,
            long expectedRevision,
            ApprovalProcessRelease.Transition transition
        ) {
            mutated = true;
            return false;
        }
    }
}
