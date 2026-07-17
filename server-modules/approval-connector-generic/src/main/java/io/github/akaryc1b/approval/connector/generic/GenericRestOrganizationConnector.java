package io.github.akaryc1b.approval.connector.generic;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.model.DepartmentSnapshot;
import io.github.akaryc1b.approval.connector.model.ExternalId;
import io.github.akaryc1b.approval.connector.model.PageRequest;
import io.github.akaryc1b.approval.connector.model.PageResult;
import io.github.akaryc1b.approval.connector.model.PositionSnapshot;
import io.github.akaryc1b.approval.connector.model.RoleSnapshot;
import io.github.akaryc1b.approval.connector.model.UserSnapshot;
import io.github.akaryc1b.approval.connector.port.OrganizationConnector;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class GenericRestOrganizationConnector implements OrganizationConnector {

    private static final String PREFIX = "/api/approval-connector/v1/organization";

    private final GenericRestTransport transport;
    private final GenericRestSnapshotMapper mapper;

    public GenericRestOrganizationConnector(GenericRestTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.mapper = new GenericRestSnapshotMapper();
    }

    @Override
    public Optional<UserSnapshot> findUser(ConnectorContext context, ExternalId userId) {
        return find(
            context,
            PREFIX + "/users/find",
            "organization.users.find.v1",
            Map.of("id", externalId(userId)),
            mapper::user
        );
    }

    @Override
    public PageResult<UserSnapshot> searchUsers(
        ConnectorContext context,
        UserQuery query,
        PageRequest pageRequest
    ) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(pageRequest, "pageRequest must not be null");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", userQuery(query));
        body.put("page", pageRequest(pageRequest));
        var response = transport.post(
            context,
            PREFIX + "/users/search",
            "organization.users.search.v1",
            body
        );
        response.requireSuccess("organization.users.search.v1");
        return mapper.userPage(response.body().path("data"));
    }

    @Override
    public Optional<DepartmentSnapshot> findDepartment(
        ConnectorContext context,
        ExternalId departmentId
    ) {
        return find(
            context,
            PREFIX + "/departments/find",
            "organization.departments.find.v1",
            Map.of("id", externalId(departmentId)),
            mapper::department
        );
    }

    @Override
    public Optional<RoleSnapshot> findRole(ConnectorContext context, String roleCode) {
        return find(
            context,
            PREFIX + "/roles/find",
            "organization.roles.find.v1",
            Map.of("code", requireText(roleCode, "roleCode")),
            mapper::role
        );
    }

    @Override
    public Optional<PositionSnapshot> findPosition(
        ConnectorContext context,
        String positionCode
    ) {
        return find(
            context,
            PREFIX + "/positions/find",
            "organization.positions.find.v1",
            Map.of("code", requireText(positionCode, "positionCode")),
            mapper::position
        );
    }

    @Override
    public List<UserSnapshot> resolveRoleMembers(
        ConnectorContext context,
        String roleCode
    ) {
        return users(
            context,
            PREFIX + "/roles/members",
            "organization.roles.members.v1",
            Map.of("code", requireText(roleCode, "roleCode"))
        );
    }

    @Override
    public List<UserSnapshot> resolvePositionMembers(
        ConnectorContext context,
        String positionCode
    ) {
        return users(
            context,
            PREFIX + "/positions/members",
            "organization.positions.members.v1",
            Map.of("code", requireText(positionCode, "positionCode"))
        );
    }

    @Override
    public List<UserSnapshot> resolveManagerChain(
        ConnectorContext context,
        ExternalId userId,
        int maximumLevels
    ) {
        if (maximumLevels < 1 || maximumLevels > 100) {
            throw new IllegalArgumentException("maximumLevels must be between 1 and 100");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", externalId(userId));
        body.put("maximumLevels", maximumLevels);
        return users(
            context,
            PREFIX + "/users/manager-chain",
            "organization.users.manager-chain.v1",
            body
        );
    }

    private List<UserSnapshot> users(
        ConnectorContext context,
        String path,
        String operation,
        Map<String, Object> body
    ) {
        var response = transport.post(context, path, operation, body);
        response.requireSuccess(operation);
        return mapper.users(response.body().path("data").path("items"));
    }

    private <T> Optional<T> find(
        ConnectorContext context,
        String path,
        String operation,
        Map<String, Object> body,
        NodeMapper<T> nodeMapper
    ) {
        Objects.requireNonNull(context, "context must not be null");
        var response = transport.post(context, path, operation, body);
        if (response.notFound()) {
            return Optional.empty();
        }
        response.requireSuccess(operation);
        JsonNode data = response.body().path("data");
        if (data.isMissingNode() || data.isNull()) {
            return Optional.empty();
        }
        return Optional.of(nodeMapper.map(data));
    }

    private static Map<String, Object> userQuery(UserQuery query) {
        Map<String, Object> value = new LinkedHashMap<>();
        if (query.keyword() != null) {
            value.put("keyword", query.keyword());
        }
        if (query.departmentId() != null) {
            value.put("departmentId", externalId(query.departmentId()));
        }
        if (query.roleCode() != null) {
            value.put("roleCode", query.roleCode());
        }
        if (query.positionCode() != null) {
            value.put("positionCode", query.positionCode());
        }
        if (query.active() != null) {
            value.put("active", query.active());
        }
        return value;
    }

    private static Map<String, Object> pageRequest(PageRequest pageRequest) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("page", pageRequest.page());
        value.put("size", pageRequest.size());
        if (pageRequest.cursor() != null) {
            value.put("cursor", pageRequest.cursor());
        }
        return value;
    }

    private static Map<String, Object> externalId(ExternalId id) {
        Objects.requireNonNull(id, "externalId must not be null");
        return Map.of(
            "source", id.source(),
            "objectType", id.objectType(),
            "value", id.value()
        );
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    @FunctionalInterface
    private interface NodeMapper<T> {
        T map(JsonNode node);
    }
}
