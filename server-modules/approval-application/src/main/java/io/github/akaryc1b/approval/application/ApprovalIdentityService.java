package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityCandidate;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityLookup;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityReference;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentitySearch;

import java.util.List;
import java.util.Objects;

/** Read facade for connector-backed approval identity selection. */
public final class ApprovalIdentityService {

    private final ApprovalIdentityDirectory identities;

    public ApprovalIdentityService(ApprovalIdentityDirectory identities) {
        this.identities = Objects.requireNonNull(identities, "identities must not be null");
    }

    public List<IdentityCandidate> search(
        String tenantId,
        String connectorKey,
        String requestId,
        String traceId,
        String keyword,
        int limit
    ) {
        return identities.search(new IdentitySearch(
            tenantId,
            connectorKey,
            requestId,
            traceId,
            keyword,
            limit
        ));
    }

    public IdentityCandidate requireActive(
        String tenantId,
        String connectorKey,
        String requestId,
        String traceId,
        IdentityReference reference
    ) {
        return identities.requireUser(new IdentityLookup(
            tenantId,
            connectorKey,
            requestId,
            traceId,
            reference,
            true
        ));
    }
}
