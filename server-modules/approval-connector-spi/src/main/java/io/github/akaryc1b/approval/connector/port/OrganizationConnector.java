package io.github.akaryc1b.approval.connector.port;

import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.model.DepartmentSnapshot;
import io.github.akaryc1b.approval.connector.model.ExternalId;
import io.github.akaryc1b.approval.connector.model.PageRequest;
import io.github.akaryc1b.approval.connector.model.PageResult;
import io.github.akaryc1b.approval.connector.model.PositionSnapshot;
import io.github.akaryc1b.approval.connector.model.RoleSnapshot;
import io.github.akaryc1b.approval.connector.model.UserSnapshot;

import java.util.List;
import java.util.Optional;

public interface OrganizationConnector {

    Optional<UserSnapshot> findUser(ConnectorContext context, ExternalId userId);

    PageResult<UserSnapshot> searchUsers(
        ConnectorContext context,
        UserQuery query,
        PageRequest pageRequest
    );

    Optional<DepartmentSnapshot> findDepartment(
        ConnectorContext context,
        ExternalId departmentId
    );

    Optional<RoleSnapshot> findRole(ConnectorContext context, String roleCode);

    Optional<PositionSnapshot> findPosition(ConnectorContext context, String positionCode);

    List<UserSnapshot> resolveRoleMembers(ConnectorContext context, String roleCode);

    List<UserSnapshot> resolvePositionMembers(ConnectorContext context, String positionCode);

    List<UserSnapshot> resolveManagerChain(
        ConnectorContext context,
        ExternalId userId,
        int maximumLevels
    );

    record UserQuery(
        String keyword,
        ExternalId departmentId,
        String roleCode,
        String positionCode,
        Boolean active
    ) {
        public UserQuery {
            keyword = normalize(keyword);
            roleCode = normalize(roleCode);
            positionCode = normalize(positionCode);
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }
}
