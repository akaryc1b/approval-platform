package io.github.akaryc1b.approval.ruoyi5.host;

import org.dromara.system.domain.vo.SysDeptVo;
import org.dromara.system.domain.vo.SysPostVo;
import org.dromara.system.domain.vo.SysRoleVo;
import org.dromara.system.domain.vo.SysTenantVo;
import org.dromara.system.domain.vo.SysUserVo;
import org.dromara.system.service.ISysDeptService;
import org.dromara.system.service.ISysPostService;
import org.dromara.system.service.ISysRoleService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

final class Ruoyi5HostSnapshotMapper {

    private static final String NORMAL_STATUS = "0";

    private final ApprovalHostProperties properties;
    private final ISysDeptService deptService;
    private final ISysRoleService roleService;
    private final ISysPostService postService;

    Ruoyi5HostSnapshotMapper(
        ApprovalHostProperties properties,
        ISysDeptService deptService,
        ISysRoleService roleService,
        ISysPostService postService
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.deptService = Objects.requireNonNull(deptService, "deptService must not be null");
        this.roleService = Objects.requireNonNull(roleService, "roleService must not be null");
        this.postService = Objects.requireNonNull(postService, "postService must not be null");
    }

    HostContractModels.UserResponse user(SysUserVo user) {
        Objects.requireNonNull(user, "user must not be null");
        List<SysRoleVo> roles = roleService.selectRolesByUserId(user.getUserId());
        List<SysPostVo> posts = postService.selectPostsByUserId(user.getUserId());
        SysDeptVo department = user.getDeptId() == null
            ? null
            : deptService.selectDeptById(user.getDeptId());
        HostContractModels.ExternalIdResponse managerId = department == null
            || department.getLeader() == null
            || department.getLeader().equals(user.getUserId())
            ? null
            : externalId("user", department.getLeader());

        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, "tenantId", user.getTenantId());
        put(attributes, "departmentName", user.getDeptName());
        put(attributes, "userType", user.getUserType());

        return new HostContractModels.UserResponse(
            externalId("user", user.getUserId()),
            requireText(user.getUserName(), "userName"),
            displayName(user),
            normalize(user.getEmail()),
            normalize(user.getPhonenumber()),
            NORMAL_STATUS.equals(user.getStatus()),
            user.getDeptId() == null
                ? List.of()
                : List.of(externalId("department", user.getDeptId())),
            roles.stream()
                .map(SysRoleVo::getRoleKey)
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet()),
            posts.stream()
                .map(SysPostVo::getPostCode)
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet()),
            managerId,
            Map.copyOf(attributes)
        );
    }

    HostContractModels.TenantResponse tenant(SysTenantVo tenant) {
        Objects.requireNonNull(tenant, "tenant must not be null");
        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, "domain", tenant.getDomain());
        put(attributes, "contactUserName", tenant.getContactUserName());
        return new HostContractModels.TenantResponse(
            externalId("tenant", tenant.getTenantId()),
            requireText(tenant.getCompanyName(), "companyName"),
            NORMAL_STATUS.equals(tenant.getStatus()),
            Map.copyOf(attributes)
        );
    }

    HostContractModels.DepartmentResponse department(SysDeptVo department) {
        Objects.requireNonNull(department, "department must not be null");
        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, "category", department.getDeptCategory());
        put(attributes, "leaderName", department.getLeaderName());
        return new HostContractModels.DepartmentResponse(
            externalId("department", department.getDeptId()),
            requireText(department.getDeptName(), "deptName"),
            department.getParentId() == null || department.getParentId() == 0
                ? null
                : externalId("department", department.getParentId()),
            department.getLeader() == null ? null : externalId("user", department.getLeader()),
            NORMAL_STATUS.equals(department.getStatus()),
            Map.copyOf(attributes)
        );
    }

    HostContractModels.RoleResponse role(SysRoleVo role) {
        Objects.requireNonNull(role, "role must not be null");
        return new HostContractModels.RoleResponse(
            externalId("role", role.getRoleId()),
            requireText(role.getRoleKey(), "roleKey"),
            requireText(role.getRoleName(), "roleName"),
            NORMAL_STATUS.equals(role.getStatus()),
            Map.of("dataScope", Objects.toString(role.getDataScope(), ""))
        );
    }

    HostContractModels.PositionResponse position(SysPostVo post) {
        Objects.requireNonNull(post, "post must not be null");
        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, "departmentId", post.getDeptId() == null ? null : post.getDeptId().toString());
        put(attributes, "category", post.getPostCategory());
        return new HostContractModels.PositionResponse(
            externalId("position", post.getPostId()),
            requireText(post.getPostCode(), "postCode"),
            requireText(post.getPostName(), "postName"),
            NORMAL_STATUS.equals(post.getStatus()),
            Map.copyOf(attributes)
        );
    }

    private HostContractModels.ExternalIdResponse externalId(String objectType, Object value) {
        return new HostContractModels.ExternalIdResponse(
            properties.getSource(),
            objectType,
            Objects.toString(Objects.requireNonNull(value, "external id value must not be null"))
        );
    }

    private static String displayName(SysUserVo user) {
        String nickname = normalize(user.getNickName());
        return nickname == null ? requireText(user.getUserName(), "userName") : nickname;
    }

    private static void put(Map<String, String> values, String key, String value) {
        String normalized = normalize(value);
        if (normalized != null) {
            values.put(key, normalized);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String requireText(String value, String name) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
