package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.PurchasePaymentAssigneeResolver;
import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.model.DepartmentSnapshot;
import io.github.akaryc1b.approval.connector.model.ExternalId;
import io.github.akaryc1b.approval.connector.model.PageRequest;
import io.github.akaryc1b.approval.connector.model.PageResult;
import io.github.akaryc1b.approval.connector.model.PositionSnapshot;
import io.github.akaryc1b.approval.connector.model.RoleSnapshot;
import io.github.akaryc1b.approval.connector.model.UserSnapshot;
import io.github.akaryc1b.approval.connector.port.OrganizationConnector;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConnectorPurchasePaymentAssigneeResolverTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-07-18T00:00:00Z"),
        ZoneOffset.UTC
    );

    @Test
    void resolvesActiveUsersWithStableOrderingAndIdentitySnapshots() {
        StubOrganizationConnector connector = new StubOrganizationConnector();
        connector.initiator = user("100", "alice", true);
        connector.managers = List.of(user("200", "manager", true));
        connector.roleMembers = List.of(user("300", "reviewer", true));
        connector.positionMembers = List.of(
            user("500", "finance-b", true),
            user("400", "finance-a", true),
            user("400", "finance-a-duplicate", true),
            user("600", "inactive", false)
        );
        var resolver = new ConnectorPurchasePaymentAssigneeResolver(connector, CLOCK);

        var snapshot = resolver.resolve(context(), rules(10));

        assertEquals("200", snapshot.managerAssignee());
        assertEquals("300", snapshot.financeReviewer());
        assertEquals(List.of("400", "500"), snapshot.financeApprovers());
        assertEquals("generic-rest", snapshot.attributes().get("connectorKey"));
        assertEquals(5, snapshot.identities().size());
        assertEquals(
            "Alice 100",
            snapshot.identities().get("fixture:user:100").displayName()
        );
        assertEquals(
            Set.of("finance"),
            snapshot.identities().get("fixture:user:400").positionCodes()
        );
    }

    @Test
    void rejectsInitiatorThatDoesNotMatchAuthenticatedOperator() {
        StubOrganizationConnector connector = configuredConnector();
        var resolver = new ConnectorPurchasePaymentAssigneeResolver(connector, CLOCK);
        RequestContext otherOperator = new RequestContext(
            "tenant-a",
            "999",
            "request-1",
            "idempotency-1",
            "trace-1"
        );

        var failure = assertThrows(
            PurchasePaymentAssigneeResolver.AssigneeResolutionException.class,
            () -> resolver.resolve(otherOperator, rules(10))
        );

        assertEquals("INITIATOR_OPERATOR_MISMATCH", failure.code());
    }

    @Test
    void rejectsMissingOrInactiveManager() {
        StubOrganizationConnector connector = configuredConnector();
        connector.managers = List.of(user("200", "manager", false));
        var resolver = new ConnectorPurchasePaymentAssigneeResolver(connector, CLOCK);

        var failure = assertThrows(
            PurchasePaymentAssigneeResolver.AssigneeResolutionException.class,
            () -> resolver.resolve(context(), rules(10))
        );

        assertEquals("MANAGER_NOT_FOUND", failure.code());
    }

    @Test
    void rejectsAmbiguousFinanceReviewerRole() {
        StubOrganizationConnector connector = configuredConnector();
        connector.roleMembers = List.of(
            user("300", "reviewer-a", true),
            user("301", "reviewer-b", true)
        );
        var resolver = new ConnectorPurchasePaymentAssigneeResolver(connector, CLOCK);

        var failure = assertThrows(
            PurchasePaymentAssigneeResolver.AssigneeResolutionException.class,
            () -> resolver.resolve(context(), rules(10))
        );

        assertEquals("FINANCE_REVIEWER_AMBIGUOUS", failure.code());
    }

    @Test
    void rejectsEmptyAndOversizedCountersignGroups() {
        StubOrganizationConnector connector = configuredConnector();
        connector.positionMembers = List.of(user("400", "finance-a", false));
        var resolver = new ConnectorPurchasePaymentAssigneeResolver(connector, CLOCK);

        var empty = assertThrows(
            PurchasePaymentAssigneeResolver.AssigneeResolutionException.class,
            () -> resolver.resolve(context(), rules(10))
        );
        assertEquals("FINANCE_APPROVERS_EMPTY", empty.code());

        connector.positionMembers = List.of(
            user("400", "finance-a", true),
            user("500", "finance-b", true)
        );
        var oversized = assertThrows(
            PurchasePaymentAssigneeResolver.AssigneeResolutionException.class,
            () -> resolver.resolve(context(), rules(1))
        );
        assertEquals("FINANCE_APPROVERS_LIMIT_EXCEEDED", oversized.code());
    }

    private static StubOrganizationConnector configuredConnector() {
        StubOrganizationConnector connector = new StubOrganizationConnector();
        connector.initiator = user("100", "alice", true);
        connector.managers = List.of(user("200", "manager", true));
        connector.roleMembers = List.of(user("300", "reviewer", true));
        connector.positionMembers = List.of(user("400", "finance-a", true));
        return connector;
    }

    private static PurchasePaymentAssigneeResolver.AssigneeRules rules(int maximum) {
        return new PurchasePaymentAssigneeResolver.AssigneeRules(
            "generic-rest",
            id("100"),
            "finance-reviewer",
            "finance",
            maximum
        );
    }

    private static RequestContext context() {
        return new RequestContext(
            "tenant-a",
            "100",
            "request-1",
            "idempotency-1",
            "trace-1"
        );
    }

    private static UserSnapshot user(String value, String username, boolean active) {
        return new UserSnapshot(
            id(value),
            username,
            "Alice " + value,
            username + "@example.com",
            "+1000" + value,
            active,
            List.of(new ExternalId("fixture", "department", "10")),
            Set.of("employee"),
            Set.of("finance"),
            null,
            Map.of("source", "test")
        );
    }

    private static ExternalId id(String value) {
        return new ExternalId("fixture", "user", value);
    }

    private static final class StubOrganizationConnector implements OrganizationConnector {

        private UserSnapshot initiator;
        private List<UserSnapshot> managers = List.of();
        private List<UserSnapshot> roleMembers = List.of();
        private List<UserSnapshot> positionMembers = List.of();

        @Override
        public Optional<UserSnapshot> findUser(ConnectorContext context, ExternalId userId) {
            return Optional.ofNullable(initiator);
        }

        @Override
        public PageResult<UserSnapshot> searchUsers(
            ConnectorContext context,
            UserQuery query,
            PageRequest pageRequest
        ) {
            return new PageResult<>(List.of(), null, 0);
        }

        @Override
        public Optional<DepartmentSnapshot> findDepartment(
            ConnectorContext context,
            ExternalId departmentId
        ) {
            return Optional.empty();
        }

        @Override
        public Optional<RoleSnapshot> findRole(ConnectorContext context, String roleCode) {
            return Optional.empty();
        }

        @Override
        public Optional<PositionSnapshot> findPosition(
            ConnectorContext context,
            String positionCode
        ) {
            return Optional.empty();
        }

        @Override
        public List<UserSnapshot> resolveRoleMembers(
            ConnectorContext context,
            String roleCode
        ) {
            return roleMembers;
        }

        @Override
        public List<UserSnapshot> resolvePositionMembers(
            ConnectorContext context,
            String positionCode
        ) {
            return positionMembers;
        }

        @Override
        public List<UserSnapshot> resolveManagerChain(
            ConnectorContext context,
            ExternalId userId,
            int maximumLevels
        ) {
            assertEquals(1, maximumLevels);
            return managers;
        }
    }
}
