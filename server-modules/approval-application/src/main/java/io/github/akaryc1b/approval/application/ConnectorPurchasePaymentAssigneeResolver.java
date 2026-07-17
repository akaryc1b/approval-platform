package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.UserIdentitySnapshot;
import io.github.akaryc1b.approval.application.port.PurchasePaymentAssigneeResolver;
import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.model.ExternalId;
import io.github.akaryc1b.approval.connector.model.UserSnapshot;
import io.github.akaryc1b.approval.connector.port.OrganizationConnector;
import io.github.akaryc1b.approval.domain.context.RequestContext;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic connector-backed implementation used before process start.
 */
public final class ConnectorPurchasePaymentAssigneeResolver
    implements PurchasePaymentAssigneeResolver {

    private final OrganizationConnector organizationConnector;
    private final Clock clock;

    public ConnectorPurchasePaymentAssigneeResolver(
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
    public AssigneeSnapshot resolve(RequestContext context, AssigneeRules rules) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(rules, "rules must not be null");
        ConnectorContext connectorContext = new ConnectorContext(
            rules.connectorKey(),
            context.tenantId(),
            context.requestId(),
            context.traceId(),
            clock.instant()
        );

        UserSnapshot initiator = organizationConnector.findUser(
            connectorContext,
            rules.initiatorUserId()
        ).filter(UserSnapshot::active).orElseThrow(() -> failure(
            "INITIATOR_NOT_FOUND",
            "initiator is missing or inactive"
        ));
        List<UserSnapshot> managerChain = organizationConnector.resolveManagerChain(
            connectorContext,
            initiator.id(),
            1
        );
        if (managerChain.isEmpty() || !managerChain.getFirst().active()) {
            throw failure("MANAGER_NOT_FOUND", "first-level manager is missing or inactive");
        }
        UserSnapshot manager = managerChain.getFirst();

        List<UserSnapshot> financeReviewers = activeDistinctSorted(
            organizationConnector.resolveRoleMembers(
                connectorContext,
                rules.financeReviewerRoleCode()
            )
        );
        if (financeReviewers.size() != 1) {
            throw failure(
                "FINANCE_REVIEWER_AMBIGUOUS",
                "finance reviewer role must resolve to exactly one active user"
            );
        }
        UserSnapshot financeReviewer = financeReviewers.getFirst();

        List<UserSnapshot> financeApprovers = activeDistinctSorted(
            organizationConnector.resolvePositionMembers(
                connectorContext,
                rules.financeApproverPositionCode()
            )
        );
        if (financeApprovers.isEmpty()) {
            throw failure(
                "FINANCE_APPROVERS_EMPTY",
                "finance approver position did not resolve active users"
            );
        }
        if (financeApprovers.size() > rules.maximumFinanceApprovers()) {
            throw failure(
                "FINANCE_APPROVERS_LIMIT_EXCEEDED",
                "finance approver count exceeds the configured maximum"
            );
        }

        Map<String, UserIdentitySnapshot> identities = new LinkedHashMap<>();
        putIdentity(identities, initiator);
        putIdentity(identities, manager);
        putIdentity(identities, financeReviewer);
        financeApprovers.forEach(user -> putIdentity(identities, user));

        return new AssigneeSnapshot(
            manager.id().value(),
            financeReviewer.id().value(),
            financeApprovers.stream().map(user -> user.id().value()).toList(),
            Map.of(
                "connectorKey", rules.connectorKey(),
                "initiatorExternalId", initiator.id().canonicalValue(),
                "managerRule", "FIRST_LEVEL_MANAGER",
                "financeReviewerRoleCode", rules.financeReviewerRoleCode(),
                "financeApproverPositionCode", rules.financeApproverPositionCode()
            ),
            Map.copyOf(identities)
        );
    }

    private static List<UserSnapshot> activeDistinctSorted(List<UserSnapshot> users) {
        Map<String, UserSnapshot> distinct = new LinkedHashMap<>();
        if (users != null) {
            users.stream()
                .filter(Objects::nonNull)
                .filter(UserSnapshot::active)
                .sorted(Comparator.comparing(user -> user.id().canonicalValue()))
                .forEach(user -> distinct.putIfAbsent(user.id().canonicalValue(), user));
        }
        return List.copyOf(new ArrayList<>(distinct.values()));
    }

    private static void putIdentity(
        Map<String, UserIdentitySnapshot> identities,
        UserSnapshot user
    ) {
        identities.putIfAbsent(user.id().canonicalValue(), identity(user));
    }

    private static UserIdentitySnapshot identity(UserSnapshot user) {
        return new UserIdentitySnapshot(
            user.id().canonicalValue(),
            user.username(),
            user.displayName(),
            user.email(),
            user.mobile(),
            user.departmentIds().stream().map(ExternalId::canonicalValue).toList(),
            user.roleCodes(),
            user.positionCodes(),
            user.attributes()
        );
    }

    private static AssigneeResolutionException failure(String code, String message) {
        return new AssigneeResolutionException(code, message);
    }
}
