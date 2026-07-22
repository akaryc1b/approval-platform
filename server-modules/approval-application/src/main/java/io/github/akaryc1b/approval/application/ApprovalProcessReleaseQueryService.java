package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore.ReleaseCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore.ReleasePage;
import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore.TransitionCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore.TransitionPage;
import io.github.akaryc1b.approval.domain.definition.ApprovalProcessRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;

import java.util.Objects;
import java.util.Optional;

/** Tenant-scoped read service for process release lifecycle and transition evidence. */
public final class ApprovalProcessReleaseQueryService {

    private final ApprovalProcessReleaseStore releases;

    public ApprovalProcessReleaseQueryService(ApprovalProcessReleaseStore releases) {
        this.releases = Objects.requireNonNull(releases, "releases must not be null");
    }

    public Optional<ApprovalProcessRelease> find(
        String tenantId,
        String definitionKey,
        int releaseVersion
    ) {
        return releases.find(tenantId, definitionKey, releaseVersion);
    }

    public ReleasePage findReleases(
        String tenantId,
        String definitionKey,
        State lifecycleState,
        int limit,
        int offset
    ) {
        return releases.findReleases(new ReleaseCriteria(
            tenantId,
            definitionKey,
            lifecycleState,
            limit,
            offset
        ));
    }

    public TransitionPage findHistory(
        String tenantId,
        String definitionKey,
        int releaseVersion,
        int limit,
        int offset
    ) {
        return releases.findHistory(new TransitionCriteria(
            tenantId,
            definitionKey,
            releaseVersion,
            limit,
            offset
        ));
    }
}
