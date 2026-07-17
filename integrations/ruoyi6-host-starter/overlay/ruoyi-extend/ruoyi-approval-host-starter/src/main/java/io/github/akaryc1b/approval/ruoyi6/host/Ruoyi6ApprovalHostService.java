package io.github.akaryc1b.approval.ruoyi6.host;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.AuthenticationRequest;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.AuthenticationResponse;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.DepartmentResponse;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.ExternalIdRequest;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.ExternalIdResponse;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.ManagerChainRequest;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.PageRequest;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.PositionResponse;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.RoleResponse;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.TenantResponse;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.UserPage;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.UserQuery;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.UserResponse;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.domain.PostDTO;
import org.dromara.system.api.domain.RoleDTO;
import org.dromara.system.api.model.LoginUser;
import org.dromara.system.domain.SysUserPost;
import org.dromara.system.domain.bo.SysPostBo;
import org.dromara.system.domain.bo.SysRoleBo;
import org.dromara.system.domain.bo.SysUserBo;
import org.dromara.system.domain.vo.SysDeptVo;
import org.dromara.system.domain.vo.SysPostVo;
import org.dromara.system.domain.vo.SysRoleVo;
import org.dromara.system.domain.vo.SysUserVo;
import org.dromara.system.mapper.SysUserPostMapper;
import org.dromara.system.service.ISysDeptService;
import org.dromara.system.service.ISysPostService;
import org.dromara.system.service.ISysRoleService;
import org.dromara.system.service.ISysUserService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class Ruoyi6ApprovalHostService {

    private static final String ACTIVE = "0";
    private static final int MAX_MEMBER_RESULTS = 20_000;

    private final ApprovalHostProperties properties;
    private final ISysUserService userService;
    private final ISysDeptService deptService;
    private final ISysRoleService roleService;
    private final ISysPostService postService;
    private final SysUserPostMapper userPostMapper;

    Ruoyi6ApprovalHostService(
        ApprovalHostProperties properties,
        ISysUserService userService,
        ISysDeptService deptService,
        ISysRoleService roleService,
        ISysPostService postService,
        SysUserPostMapper userPostMapper
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.userService = Objects.requireNonNull(userService, "userService must not be null");
        this.deptService = Objects.requireNonNull(deptService, "deptService must not be null");
        this.roleService = Objects.requireNonNull(roleService, "roleService must not be null");
        this.postService = Objects.requireNonNull(postService, "postService must not be null");
        this.userPostMapper = Objects.requireNonNull(userPostMapper, "userPostMapper must not be null");
    }

    AuthenticationResponse authenticate(
        Ruoyi6TenantBridge.TenantDescriptor tenant,
        AuthenticationRequest request
    ) {
        Objects.requireNonNull(tenant, "tenant must not be null");
        requireCredentialType(request == null ? null : request.credentialType());
        String token = requireText(request.credential(), "credential");
        LoginUser loginUser = LoginHelper.getLoginUser(token);
        if (loginUser == null) {
            throw new Ruoyi6HostException(401, "INVALID_CREDENTIAL", "credential is invalid", false);
        }

        SysUserVo user = userService.selectUserById(loginUser.getUserId());
        if (user == null || !ACTIVE.equals(user.getStatus())) {
            throw new Ruoyi6HostException(401, "USER_DISABLED", "user is missing or disabled", false);
        }

        Set<String> permissions = new LinkedHashSet<>();
        if (loginUser.getMenuPermission() != null) {
            permissions.addAll(loginUser.getMenuPermission());
        }
        if (loginUser.getRolePermission() != null) {
            permissions.addAll(loginUser.getRolePermission());
        }

        long timeoutSeconds = StpUtil.getStpLogic().getTokenTimeout(token);
        if (timeoutSeconds <= 0) {
            throw new Ruoyi6HostException(401, "CREDENTIAL_EXPIRED", "credential has expired", false);
        }

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("userType", nullToEmpty(loginUser.getUserType()));
        attributes.put("clientKey", nullToEmpty(loginUser.getClientKey()));
        attributes.put("deviceType", nullToEmpty(loginUser.getDeviceType()));
        attributes.put("tenantMode", tenant.attributes().getOrDefault("mode", "custom"));

        return new AuthenticationResponse(
            mapLoginUser(tenant.id(), loginUser, user),
            mapTenant(tenant),
            Set.copyOf(permissions),
            Instant.now().plusSeconds(timeoutSeconds),
            Map.copyOf(attributes)
        );
    }

    UserResponse findUser(String tenantId, ExternalIdRequest id) {
        long userId = parseExternalId(id, "user");
        SysUserVo user = userService.selectUserById(userId);
        return user == null ? null : mapUser(tenantId, user);
    }

    UserPage searchUsers(String tenantId, UserQuery requestQuery, PageRequest page) {
        UserQuery query = requestQuery == null
            ? new UserQuery(null, null, null, null, null)
            : requestQuery;
        validatePage(page);
        Set<Long> positionUserIds = resolvePositionUserIds(query.positionCode());

        if (query.roleCode() != null && query.positionCode() != null) {
            List<SysUserVo> filtered = allRoleMembers(query.roleCode()).stream()
                .filter(user -> positionUserIds.contains(user.getUserId()))
                .filter(user -> matchesUserQuery(user, query))
                .toList();
            return manualPage(tenantId, filtered, page);
        }

        SysUserBo userBo = buildUserQuery(query);
        if (query.positionCode() != null) {
            if (positionUserIds.isEmpty()) {
                return new UserPage(List.of(), null, 0);
            }
            userBo.setUserIds(joinIds(positionUserIds));
        }

        PageResult<SysUserVo> result;
        PageQuery pageQuery = new PageQuery(page.size(), page.page() + 1);
        if (query.roleCode() != null) {
            SysRoleVo role = findRoleEntity(query.roleCode());
            if (role == null) {
                return new UserPage(List.of(), null, 0);
            }
            userBo.setRoleId(role.getRoleId());
            result = userService.selectAllocatedList(userBo, pageQuery);
        } else {
            result = userService.selectPageUserList(userBo, pageQuery);
        }

        List<UserResponse> items = safeRows(result).stream()
            .map(user -> mapUser(tenantId, user))
            .toList();
        return new UserPage(items, null, result == null ? 0 : result.getTotal());
    }

    DepartmentResponse findDepartment(ExternalIdRequest id) {
        long deptId = parseExternalId(id, "department");
        SysDeptVo dept = deptService.selectDeptById(deptId);
        return dept == null ? null : mapDepartment(dept);
    }

    RoleResponse findRole(String roleCode) {
        SysRoleVo role = findRoleEntity(roleCode);
        return role == null ? null : mapRole(role);
    }

    PositionResponse findPosition(String positionCode) {
        SysPostVo post = findPositionEntity(positionCode);
        return post == null ? null : mapPosition(post);
    }

    List<UserResponse> roleMembers(String tenantId, String roleCode) {
        return allRoleMembers(roleCode).stream()
            .map(user -> mapUser(tenantId, user))
            .toList();
    }

    List<UserResponse> positionMembers(String tenantId, String positionCode) {
        Set<Long> userIds = resolvePositionUserIds(positionCode);
        if (userIds.isEmpty()) {
            return List.of();
        }
        return userService.selectUserByIds(List.copyOf(userIds), null).stream()
            .map(user -> mapUser(tenantId, user))
            .toList();
    }

    List<UserResponse> managerChain(
        String tenantId,
        ManagerChainRequest request
    ) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.maximumLevels() < 1 || request.maximumLevels() > 100) {
            throw new Ruoyi6HostException(
                400,
                "INVALID_REQUEST",
                "maximumLevels must be between 1 and 100",
                false
            );
        }
        long userId = parseExternalId(request.userId(), "user");
        SysUserVo current = userService.selectUserById(userId);
        if (current == null) {
            return List.of();
        }

        List<UserResponse> managers = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        visited.add(userId);
        for (int level = 0; level < request.maximumLevels(); level++) {
            if (current.getDeptId() == null) {
                break;
            }
            SysDeptVo dept = deptService.selectDeptById(current.getDeptId());
            if (dept == null || dept.getLeader() == null || !visited.add(dept.getLeader())) {
                break;
            }
            SysUserVo manager = userService.selectUserById(dept.getLeader());
            if (manager == null) {
                break;
            }
            managers.add(mapUser(tenantId, manager));
            current = manager;
        }
        return List.copyOf(managers);
    }

    private UserResponse mapLoginUser(String tenantId, LoginUser loginUser, SysUserVo user) {
        List<ExternalIdResponse> departmentIds = loginUser.getDeptId() == null
            ? List.of()
            : List.of(externalId("department", loginUser.getDeptId()));
        Set<String> roleCodes = loginUser.getRoles() == null
            ? Set.of()
            : loginUser.getRoles().stream()
                .map(RoleDTO::getRoleKey)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> positionCodes = loginUser.getPosts() == null
            ? Set.of()
            : loginUser.getPosts().stream()
                .map(PostDTO::getPostCode)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new UserResponse(
            externalId("user", loginUser.getUserId()),
            loginUser.getUsername(),
            chooseDisplayName(loginUser.getNickname(), loginUser.getUsername()),
            user.getEmail(),
            user.getPhoneNumber(),
            ACTIVE.equals(user.getStatus()),
            departmentIds,
            roleCodes,
            positionCodes,
            managerId(user.getDeptId(), user.getUserId()),
            Map.of("tenantId", tenantId)
        );
    }

    private UserResponse mapUser(String tenantId, SysUserVo user) {
        List<SysRoleVo> roles = roleService.selectRolesByUserId(user.getUserId());
        List<SysPostVo> posts = postService.selectPostsByUserId(user.getUserId());
        List<ExternalIdResponse> departmentIds = user.getDeptId() == null
            ? List.of()
            : List.of(externalId("department", user.getDeptId()));
        Set<String> roleCodes = roles.stream()
            .map(SysRoleVo::getRoleKey)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> positionCodes = posts.stream()
            .map(SysPostVo::getPostCode)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new UserResponse(
            externalId("user", user.getUserId()),
            user.getUserName(),
            chooseDisplayName(user.getNickName(), user.getUserName()),
            user.getEmail(),
            user.getPhoneNumber(),
            ACTIVE.equals(user.getStatus()),
            departmentIds,
            roleCodes,
            positionCodes,
            managerId(user.getDeptId(), user.getUserId()),
            Map.of("tenantId", tenantId)
        );
    }

    private TenantResponse mapTenant(Ruoyi6TenantBridge.TenantDescriptor tenant) {
        return new TenantResponse(
            externalId("tenant", tenant.id()),
            tenant.name(),
            tenant.active(),
            tenant.attributes()
        );
    }

    private DepartmentResponse mapDepartment(SysDeptVo dept) {
        return new DepartmentResponse(
            externalId("department", dept.getDeptId()),
            dept.getDeptName(),
            dept.getParentId() == null || dept.getParentId() == 0
                ? null
                : externalId("department", dept.getParentId()),
            dept.getLeader() == null ? null : externalId("user", dept.getLeader()),
            ACTIVE.equals(dept.getStatus()),
            Map.of("category", nullToEmpty(dept.getDeptCategory()))
        );
    }

    private RoleResponse mapRole(SysRoleVo role) {
        return new RoleResponse(
            externalId("role", role.getRoleId()),
            role.getRoleKey(),
            role.getRoleName(),
            ACTIVE.equals(role.getStatus()),
            Map.of()
        );
    }

    private PositionResponse mapPosition(SysPostVo post) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("departmentId", post.getDeptId() == null ? "" : post.getDeptId().toString());
        attributes.put("category", nullToEmpty(post.getPostCategory()));
        return new PositionResponse(
            externalId("position", post.getPostId()),
            post.getPostCode(),
            post.getPostName(),
            ACTIVE.equals(post.getStatus()),
            Map.copyOf(attributes)
        );
    }

    private ExternalIdResponse managerId(Long deptId, Long userId) {
        if (deptId == null) {
            return null;
        }
        SysDeptVo dept = deptService.selectDeptById(deptId);
        if (dept == null || dept.getLeader() == null || dept.getLeader().equals(userId)) {
            return null;
        }
        return externalId("user", dept.getLeader());
    }

    private SysUserBo buildUserQuery(UserQuery query) {
        SysUserBo bo = new SysUserBo();
        if (query.keyword() != null && !query.keyword().isBlank()) {
            bo.setUserName(query.keyword());
        }
        if (query.departmentId() != null) {
            bo.setDeptId(parseExternalId(query.departmentId(), "department"));
        }
        if (query.active() != null) {
            bo.setStatus(query.active() ? ACTIVE : "1");
        }
        return bo;
    }

    private boolean matchesUserQuery(SysUserVo user, UserQuery query) {
        if (query.active() != null && query.active() != ACTIVE.equals(user.getStatus())) {
            return false;
        }
        if (query.departmentId() != null
            && !Objects.equals(user.getDeptId(), parseExternalId(query.departmentId(), "department"))) {
            return false;
        }
        if (query.keyword() != null && !query.keyword().isBlank()) {
            String keyword = query.keyword().toLowerCase();
            boolean usernameMatch = user.getUserName() != null
                && user.getUserName().toLowerCase().contains(keyword);
            boolean nicknameMatch = user.getNickName() != null
                && user.getNickName().toLowerCase().contains(keyword);
            return usernameMatch || nicknameMatch;
        }
        return true;
    }

    private List<SysUserVo> allRoleMembers(String roleCode) {
        SysRoleVo role = findRoleEntity(roleCode);
        if (role == null) {
            return List.of();
        }
        List<SysUserVo> users = new ArrayList<>();
        int page = 1;
        while (users.size() < MAX_MEMBER_RESULTS) {
            SysUserBo bo = new SysUserBo();
            bo.setRoleId(role.getRoleId());
            PageResult<SysUserVo> result = userService.selectAllocatedList(
                bo,
                new PageQuery(500, page)
            );
            List<SysUserVo> rows = safeRows(result);
            users.addAll(rows);
            if (rows.size() < 500 || result == null || users.size() >= result.getTotal()) {
                break;
            }
            page++;
        }
        if (users.size() >= MAX_MEMBER_RESULTS) {
            throw new Ruoyi6HostException(
                413,
                "RESULT_LIMIT_EXCEEDED",
                "role member result exceeds 20000",
                false
            );
        }
        return List.copyOf(users);
    }

    private Set<Long> resolvePositionUserIds(String positionCode) {
        if (positionCode == null) {
            return Set.of();
        }
        SysPostVo post = findPositionEntity(positionCode);
        if (post == null) {
            return Set.of();
        }
        List<SysUserPost> relations = userPostMapper.selectList(
            Wrappers.<SysUserPost>lambdaQuery().eq(SysUserPost::getPostId, post.getPostId())
        );
        return relations.stream()
            .map(SysUserPost::getUserId)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private SysRoleVo findRoleEntity(String roleCode) {
        requireText(roleCode, "roleCode");
        SysRoleBo bo = new SysRoleBo();
        bo.setRoleKey(roleCode);
        return roleService.selectRoleList(bo).stream()
            .filter(role -> roleCode.equals(role.getRoleKey()))
            .findFirst()
            .orElse(null);
    }

    private SysPostVo findPositionEntity(String positionCode) {
        requireText(positionCode, "positionCode");
        SysPostBo bo = new SysPostBo();
        bo.setPostCode(positionCode);
        return postService.selectPostList(bo).stream()
            .filter(post -> positionCode.equals(post.getPostCode()))
            .findFirst()
            .orElse(null);
    }

    private UserPage manualPage(String tenantId, List<SysUserVo> users, PageRequest page) {
        int from = Math.min(Math.multiplyExact(page.page(), page.size()), users.size());
        int to = Math.min(from + page.size(), users.size());
        List<UserResponse> items = users.subList(from, to).stream()
            .map(user -> mapUser(tenantId, user))
            .toList();
        return new UserPage(items, null, users.size());
    }

    private long parseExternalId(ExternalIdRequest id, String objectType) {
        Objects.requireNonNull(id, "externalId must not be null");
        if (!properties.getSource().equals(id.source()) || !objectType.equals(id.objectType())) {
            throw new Ruoyi6HostException(
                400,
                "INVALID_EXTERNAL_ID",
                "external ID source or type is invalid",
                false
            );
        }
        try {
            return Long.parseLong(requireText(id.value(), "externalId.value"));
        } catch (NumberFormatException exception) {
            throw new Ruoyi6HostException(
                400,
                "INVALID_EXTERNAL_ID",
                "external ID value must be numeric",
                false
            );
        }
    }

    private ExternalIdResponse externalId(String objectType, Object value) {
        return new ExternalIdResponse(properties.getSource(), objectType, value.toString());
    }

    private static void validatePage(PageRequest page) {
        Objects.requireNonNull(page, "page must not be null");
        if (page.page() < 0 || page.size() < 1 || page.size() > 500) {
            throw new Ruoyi6HostException(
                400,
                "INVALID_PAGE",
                "page must be >= 0 and size between 1 and 500",
                false
            );
        }
        if (page.cursor() != null && !page.cursor().isBlank()) {
            throw new Ruoyi6HostException(
                400,
                "CURSOR_UNSUPPORTED",
                "RuoYi 6.X adapter does not support cursor paging",
                false
            );
        }
    }

    private static void requireCredentialType(String credentialType) {
        String value = requireText(credentialType, "credentialType");
        if (!"BEARER".equalsIgnoreCase(value) && !"SA_TOKEN".equalsIgnoreCase(value)) {
            throw new Ruoyi6HostException(
                400,
                "UNSUPPORTED_CREDENTIAL",
                "credentialType must be BEARER or SA_TOKEN",
                false
            );
        }
    }

    private static String joinIds(Collection<Long> identifiers) {
        return identifiers.stream()
            .map(String::valueOf)
            .collect(java.util.stream.Collectors.joining(","));
    }

    private static List<SysUserVo> safeRows(PageResult<SysUserVo> result) {
        if (result == null || result.getRows() == null) {
            return List.of();
        }
        return List.copyOf(result.getRows());
    }

    private static String chooseDisplayName(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? requireText(fallback, "fallback") : preferred;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new Ruoyi6HostException(
                400,
                "INVALID_REQUEST",
                name + " must not be blank",
                false
            );
        }
        return value;
    }
}
