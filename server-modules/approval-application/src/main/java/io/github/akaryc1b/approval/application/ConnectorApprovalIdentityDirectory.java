package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory;
import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.model.ExternalId;
import io.github.akaryc1b.approval.connector.model.PageRequest;
import io.github.akaryc1b.approval.connector.model.UserSnapshot;
import io.github.akaryc1b.approval.connector.port.OrganizationConnector;

import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Connector-backed implementation of the approval identity directory. */
public final class ConnectorApprovalIdentityDirectory implements ApprovalIdentityDirectory {

    private final OrganizationConnector organizationConnector;
    private final Clock clock;

    public ConnectorApprovalIdentityDirectory(
        OrganizationConnector organizationConnector,
        Clock clock
    ) {
        this.organizationConnector = Objects.requireNonNull(
            organizationConnector,
            "organizationConnector must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public List<IdentityCandidate> search(IdentitySearch search) {
        Objects.requireNonNull(search, "search must not be null");
        try {
            Map<String, IdentityCandidate> distinct = new LinkedHashMap<>();
            organizationConnector.searchUsers(
                context(
                    search.tenantId(),
                    search.connectorKey(),
                    search.requestId(),
                    search.traceId()
                ),
                new OrganizationConnector.UserQuery(
                    search.keyword(),
                    null,
                    null,
                    null,
                    true
                ),
                PageRequest.first(search.limit())
            ).items().stream()
                .filter(Objects::nonNull)
                .filter(UserSnapshot::active)
                .sorted(Comparator.comparing(user -> user.id().canonicalValue()))
                .map(ConnectorApprovalIdentityDirectory::candidate)
                .forEach(item -> distinct.putIfAbsent(
                    item.reference().canonicalValue(),
                    item
                ));
            return List.copyOf(distinct.values());
        } catch (IdentityResolutionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IdentityResolutionException(
                "IDENTITY_DIRECTORY_UNAVAILABLE",
                "organization identity directory is unavailable",
                true
            );
        }
    }

    @Override
    public IdentityCandidate requireUser(IdentityLookup lookup) {
        Objects.requireNonNull(lookup, "lookup must not be null");
        ExternalId requested = externalId(lookup.reference());
        UserSnapshot user;
        try {
            user = organizationConnector.findUser(
                context(
                    lookup.tenantId(),
                    lookup.connectorKey(),
                    lookup.requestId(),
                    lookup.traceId()
                ),
                requested
            ).orElseThrow(() -> new IdentityResolutionException(
                "IDENTITY_NOT_FOUND",
                "requested approval identity was not found",
                false
            ));
        } catch (IdentityResolutionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IdentityResolutionException(
                "IDENTITY_DIRECTORY_UNAVAILABLE",
                "organization identity directory is unavailable",
                true
            );
        }
        if (!requested.equals(user.id())) {
            throw new IdentityResolutionException(
                "IDENTITY_MISMATCH",
                "organization connector returned a different identity",
                false
            );
        }
        if (lookup.requireActive() && !user.active()) {
            throw new IdentityResolutionException(
                "IDENTITY_INACTIVE",
                "requested approval identity is inactive",
                false
            );
        }
        return candidate(user);
    }

    private ConnectorContext context(
        String tenantId,
        String connectorKey,
        String requestId,
        String traceId
    ) {
        return new ConnectorContext(
            connectorKey,
            tenantId,
            requestId,
            traceId,
            clock.instant()
        );
    }

    private static ExternalId externalId(IdentityReference reference) {
        return new ExternalId(
            reference.source(),
            reference.objectType(),
            reference.value()
        );
    }

    private static IdentityCandidate candidate(UserSnapshot user) {
        return new IdentityCandidate(
            new IdentityReference(
                user.id().source(),
                user.id().objectType(),
                user.id().value()
            ),
            user.id().value(),
            user.username(),
            user.displayName(),
            user.email(),
            user.mobile(),
            user.active(),
            user.departmentIds().stream()
                .map(ExternalId::canonicalValue)
                .sorted()
                .toList(),
            user.roleCodes().stream().sorted().toList(),
            user.positionCodes().stream().sorted().toList()
        );
    }
}
