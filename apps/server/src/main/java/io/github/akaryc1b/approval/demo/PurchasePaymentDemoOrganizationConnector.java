package io.github.akaryc1b.approval.demo;

import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.model.DepartmentSnapshot;
import io.github.akaryc1b.approval.connector.model.ExternalId;
import io.github.akaryc1b.approval.connector.model.PageRequest;
import io.github.akaryc1b.approval.connector.model.PageResult;
import io.github.akaryc1b.approval.connector.model.PositionSnapshot;
import io.github.akaryc1b.approval.connector.model.RoleSnapshot;
import io.github.akaryc1b.approval.connector.model.UserSnapshot;
import io.github.akaryc1b.approval.connector.port.OrganizationConnector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only organization connector backed by the governed local demo manifest.
 */
public final class PurchasePaymentDemoOrganizationConnector implements OrganizationConnector {

    private static final String USER_OBJECT_TYPE = "user";
    private final PurchasePaymentDemoScenario scenario;

    public PurchasePaymentDemoOrganizationConnector(PurchasePaymentDemoScenario scenario) {
        this.scenario = Objects.requireNonNull(scenario, "scenario must not be null");
    }

    @Override
    public Optional<UserSnapshot> findUser(
        ConnectorContext context,
        ExternalId userId
    ) {
        validateContext(context);
        if (!isDemoExternalId(userId, USER_OBJECT_TYPE)) {
            return Optional.empty();
        }
        return scenario.findUser(userId.value()).map(scenario::snapshot);
    }

    @Override
    public PageResult<UserSnapshot> searchUsers(
        ConnectorContext context,
        UserQuery query,
        PageRequest pageRequest
    ) {
        validateContext(context);
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(pageRequest, "pageRequest must not be null");
        if (query.departmentId() != null) {
            return PageResult.empty();
        }

        List<UserSnapshot> matches = scenario.users().stream()
            .map(scenario::snapshot)
            .filter(user -> query.active() == null || user.active() == query.active())
            .filter(user -> query.roleCode() == null
                || user.roleCodes().contains(query.roleCode()))
            .filter(user -> query.positionCode() == null
                || user.positionCodes().contains(query.positionCode()))
            .filter(user -> query.keyword() == null
                || matchesKeyword(user, query.keyword()))
            .sorted(Comparator.comparing(user -> user.id().canonicalValue()))
            .toList();

        int offset = offset(pageRequest);
        if (offset >= matches.size()) {
            return new PageResult<>(List.of(), null, matches.size());
        }
        int end = Math.min(matches.size(), offset + pageRequest.size());
        String nextCursor = end < matches.size() ? Integer.toString(end) : null;
        return new PageResult<>(matches.subList(offset, end), nextCursor, matches.size());
    }

    @Override
    public Optional<DepartmentSnapshot> findDepartment(
        ConnectorContext context,
        ExternalId departmentId
    ) {
        validateContext(context);
        return Optional.empty();
    }

    @Override
    public Optional<RoleSnapshot> findRole(ConnectorContext context, String roleCode) {
        validateContext(context);
        String normalized = requireText(roleCode, "roleCode");
        boolean exists = scenario.users().stream()
            .anyMatch(user -> user.roleCodes().contains(normalized));
        if (!exists) {
            return Optional.empty();
        }
        return Optional.of(new RoleSnapshot(
            new ExternalId(scenario.source(), "role", normalized),
            normalized,
            normalized,
            true,
            java.util.Map.of("demo", "true")
        ));
    }

    @Override
    public Optional<PositionSnapshot> findPosition(
        ConnectorContext context,
        String positionCode
    ) {
        validateContext(context);
        String normalized = requireText(positionCode, "positionCode");
        boolean exists = scenario.users().stream()
            .anyMatch(user -> user.positionCodes().contains(normalized));
        if (!exists) {
            return Optional.empty();
        }
        return Optional.of(new PositionSnapshot(
            new ExternalId(scenario.source(), "position", normalized),
            normalized,
            normalized,
            true,
            java.util.Map.of("demo", "true")
        ));
    }

    @Override
    public List<UserSnapshot> resolveRoleMembers(
        ConnectorContext context,
        String roleCode
    ) {
        validateContext(context);
        String normalized = requireText(roleCode, "roleCode");
        return scenario.users().stream()
            .filter(user -> user.roleCodes().contains(normalized))
            .map(scenario::snapshot)
            .sorted(Comparator.comparing(user -> user.id().canonicalValue()))
            .toList();
    }

    @Override
    public List<UserSnapshot> resolvePositionMembers(
        ConnectorContext context,
        String positionCode
    ) {
        validateContext(context);
        String normalized = requireText(positionCode, "positionCode");
        return scenario.users().stream()
            .filter(user -> user.positionCodes().contains(normalized))
            .map(scenario::snapshot)
            .sorted(Comparator.comparing(user -> user.id().canonicalValue()))
            .toList();
    }

    @Override
    public List<UserSnapshot> resolveManagerChain(
        ConnectorContext context,
        ExternalId userId,
        int maximumLevels
    ) {
        validateContext(context);
        if (maximumLevels < 1 || maximumLevels > 100) {
            throw new IllegalArgumentException("maximumLevels must be between 1 and 100");
        }
        if (!isDemoExternalId(userId, USER_OBJECT_TYPE)) {
            return List.of();
        }

        PurchasePaymentDemoScenario.DemoUser current =
            scenario.findUser(userId.value()).orElse(null);
        if (current == null) {
            return List.of();
        }

        List<UserSnapshot> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(current.id());
        for (int level = 0; level < maximumLevels && current.managerId() != null; level++) {
            if (!visited.add(current.managerId())) {
                throw new IllegalStateException("demo manager hierarchy contains a cycle");
            }
            current = scenario.requireUser(current.managerId());
            result.add(scenario.snapshot(current));
        }
        return List.copyOf(result);
    }

    private void validateContext(ConnectorContext context) {
        Objects.requireNonNull(context, "context must not be null");
        if (!scenario.connectorKey().equals(context.connectorKey())) {
            throw new IllegalArgumentException("unexpected demo connector key");
        }
        if (!scenario.tenantId().equals(context.tenantId())) {
            throw new IllegalArgumentException("unexpected demo tenant");
        }
    }

    private boolean isDemoExternalId(ExternalId value, String objectType) {
        return value != null
            && scenario.source().equals(value.source())
            && objectType.equals(value.objectType());
    }

    private static boolean matchesKeyword(UserSnapshot user, String keyword) {
        String normalized = keyword.toLowerCase(Locale.ROOT);
        return user.id().value().toLowerCase(Locale.ROOT).contains(normalized)
            || user.username().toLowerCase(Locale.ROOT).contains(normalized)
            || user.displayName().toLowerCase(Locale.ROOT).contains(normalized);
    }

    private static int offset(PageRequest pageRequest) {
        if (pageRequest.cursor() != null) {
            try {
                int value = Integer.parseInt(pageRequest.cursor());
                if (value < 0) {
                    throw new IllegalArgumentException("cursor must be zero or greater");
                }
                return value;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("cursor must be a decimal offset", exception);
            }
        }
        long value = (long) pageRequest.page() * pageRequest.size();
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) value;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
