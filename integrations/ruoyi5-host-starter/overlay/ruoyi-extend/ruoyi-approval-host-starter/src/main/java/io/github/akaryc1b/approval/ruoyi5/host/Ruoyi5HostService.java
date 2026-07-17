package io.github.akaryc1b.approval.ruoyi5.host;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.domain.SysUserPost;
import org.dromara.system.domain.bo.SysPostBo;
import org.dromara.system.domain.bo.SysRoleBo;
import org.dromara.system.domain.bo.SysUserBo;
import org.dromara.system.domain.vo.SysDeptVo;
import org.dromara.system.domain.vo.SysPostVo;
import org.dromara.system.domain.vo.SysRoleVo;
import org.dromara.system.domain.vo.SysTenantVo;
import org.dromara.system.domain.vo.SysUserVo;
import org.dromara.system.mapper.SysUserPostMapper;
import org.dromara.system.service.ISysDeptService;
import org.dromara.system.service.ISysPostService;
import org.dromara.system.service.ISysRoleService;
import org.dromara.system.service.ISysTenantService;
import org.dromara.system.service.ISysUserService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

final class Ruoyi5HostService {

    private static final String NORMAL_STATUS = "0";
    private static final long NEVER_EXPIRES_EPOCH_SECOND = 253402300799L;

    private final ApprovalHostProperties properties;
    private final ISysUserService userService;
    private final ISysDeptService deptService;
    private final ISysRoleService roleService;
    private final ISysPostService postService;
    private final ISysTenantService tenantService;
    private final SysUserPostMapper userPostMapper;
    private final Ruoyi5HostSnapshotMapper mapper;

    Ruoyi5HostService(
        ApprovalHostProperties properties,
        ISysUserService userService,
        ISysDeptService deptService,
        ISysRoleService roleService,
        ISysPostService postService,
        ISysTenantService tenantService,
        SysUserPostMapper userPostMapper
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.userService = Objects.requireNonNull(userService, "userService must not be null");
        this.deptService = Objects.requireNonNull(deptService, "deptService must not be null");
        this.roleService = Objects.requireNonNull(roleService, "roleService must not be null");
        this.postService = Objects.requireNonNull(postService, "postService must not be null");
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
        this.userPostMapper = Objects.requireNonNull(userPostMapper, "userPostMapper must not be null");
        this.mapper = new Ruoyi5HostSnapshotMapper(properties, deptService, roleService, postService);
    }

    HostContractModels.AuthenticationResponse authenticate(
        String requestTenantId,
        HostContractModels.AuthenticationRequest request
    ) {
        Objects.requireNonNull(request, "request must not be null");
        String credentialType = requireText(request.credentialType(), "credentialType");
        if (!credentialType.equalsIgnoreCase("sa-token")
            && !credentialType.equalsIgnoreCase("bearer")) {
            throw Ruoyi5ProtocolException.invalid("credentialType must be sa-token or bearer");
        }
        String token = requireText(request.credential(), "credential");
        LoginUser loginUser = LoginHelper.getLoginUser(token);
        if (loginUser == null) {
            throw Ruoyi5ProtocolException.unauthorized("credential is not an active RuoYi session");
        }
        String tokenTenantId = requireText(loginUser.getTenantId(), "loginUser.tenantId");
        if (!tokenTenantId.equals(requestTenantId)) {
            throw Ruoyi5ProtocolException.unauthorized("credential tenant does not match request tenant");
        }

        SysUserVo user = userService.selectUserById(loginUser.getUserId());
        if (user == null || !NORMAL_STATUS.equals(user.getStatus())) {
            throw Ruoyi5ProtocolException.unauthorized("credential user is unavailable");
        }
        SysTenantVo tenant = tenantService.queryByTenantId(tokenTenantId);
        if (tenant == null || !NORMAL_STATUS.equals(tenant.getStatus())) {
            throw Ruoyi5ProtocolException.unauthorized("credential tenant is unavailable");
        }

        Set<String> permissions = new LinkedHashSet<>();
        if (loginUser.getMenuPermission() != null) {
            permissions.addAll(loginUser.getMenuPermission());
        }
        if (loginUser.getRolePermission() != null) {
            permissions.addAll(loginUser.getRolePermission());
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, "userType", loginUser.getUserType());
        put(attributes, "clientKey", loginUser.getClientKey());
        put(attributes, "deviceType", loginUser.getDeviceType());

        return new HostContractModels.AuthenticationResponse(
            mapper.user(user),
            mapper.tenant(tenant),
            Set.copyOf(permissions),
            resolveExpiry(token, loginUser),
            Map.copyOf(attributes)
        );
    }

    HostContractModels.UserResponse findUser(HostContractModels.ExternalIdRequest id) {
        Long userId = numericExternalId(id, "user");
        SysUserVo user = userService.selectUserById(userId);
        return user == null ? null : mapper.user(user);
    }

    HostContractModels.UserPage searchUsers(HostContractModels.UserSearchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        HostContractModels.UserQuery query = request.query() == null
            ? new HostContractModels.UserQuery(null, null, null, null, null)
            : request.query();
        HostContractModels.PageRequest page = Objects.requireNonNull(request.page(), "page must not be null");
        if (page.page() < 0 || page.size() < 1 || page.size() > 500) {
            throw Ruoyi5ProtocolException.invalid("page and size are outside the supported range");
        }
        if (page.cursor() != null && !page.cursor().isBlank()) {
            throw Ruoyi5ProtocolException.invalid("RuoYi 5.X adapter does not support cursor pagination");
        }

        SysUserBo userQuery = new SysUserBo();
        if (query.keyword() != null && !query.keyword().isBlank()) {
            userQuery.setUserName(query.keyword().trim());
        }
        if (query.departmentId() != null) {
            userQuery.setDeptId(numericExternalId(query.departmentId(), "department"));
        }
        if (query.active() != null) {
            userQuery.setStatus(Boolean.TRUE.equals(query.active()) ? NORMAL_STATUS : "1");
        }

        Set<Long> candidateIds = resolveCandidateIds(query.roleCode(), query.positionCode());
        if (candidateIds != null) {
            if (candidateIds.isEmpty()) {
                return new HostContractModels.UserPage(List.of(), null, 0);
            }
            userQuery.setUserIds(candidateIds.stream()
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(",")));
        }

        PageQuery pageQuery = new PageQuery(page.size(), page.page() + 1);
        TableDataInfo<SysUserVo> result = userService.selectPageUserList(userQuery, pageQuery);
        List<HostContractModels.UserResponse> items = result.getRows() == null
            ? List.of()
            : result.getRows().stream().map(mapper::user).toList();
        return new HostContractModels.UserPage(items, null, result.getTotal());
    }

    HostContractModels.DepartmentResponse findDepartment(HostContractModels.ExternalIdRequest id) {
        SysDeptVo department = deptService.selectDeptById(numericExternalId(id, "department"));
        return department == null ? null : mapper.department(department);
    }

    HostContractModels.RoleResponse findRole(String code) {
        SysRoleVo role = resolveRole(code);
        return role == null ? null : mapper.role(role);
    }

    HostContractModels.PositionResponse findPosition(String code) {
        SysPostVo post = resolvePost(code);
        return post == null ? null : mapper.position(post);
    }

    HostContractModels.UserItems roleMembers(String code) {
        SysRoleVo role = requireRole(code);
        SysUserBo query = new SysUserBo();
        query.setRoleId(role.getRoleId());
        TableDataInfo<SysUserVo> result = userService.selectAllocatedList(
            query,
            new PageQuery(Integer.MAX_VALUE, 1)
        );
        return new HostContractModels.UserItems(mapUsers(result.getRows()));
    }

    HostContractModels.UserItems positionMembers(String code) {
        SysPostVo post = requirePost(code);
        Set<Long> userIds = positionMemberIds(post.getPostId());
        return new HostContractModels.UserItems(usersByIds(userIds));
    }

    HostContractModels.UserItems managerChain(
        HostContractModels.ExternalIdRequest userId,
        int maximumLevels
    ) {
        if (maximumLevels < 1 || maximumLevels > 100) {
            throw Ruoyi5ProtocolException.invalid("maximumLevels must be between 1 and 100");
        }
        SysUserVo currentUser = userService.selectUserById(numericExternalId(userId, "user"));
        if (currentUser == null) {
            throw Ruoyi5ProtocolException.missing("user was not found");
        }

        List<HostContractModels.UserResponse> managers = new ArrayList<>();
        Set<Long> visitedUsers = new LinkedHashSet<>();
        Set<Long> visitedDepartments = new LinkedHashSet<>();
        Long departmentId = currentUser.getDeptId();
        Long currentUserId = currentUser.getUserId();

        while (departmentId != null && departmentId != 0 && managers.size() < maximumLevels) {
            if (!visitedDepartments.add(departmentId)) {
                break;
            }
            SysDeptVo department = deptService.selectDeptById(departmentId);
            if (department == null) {
                break;
            }
            Long leaderId = department.getLeader();
            if (leaderId != null && !leaderId.equals(currentUserId) && visitedUsers.add(leaderId)) {
                SysUserVo leader = userService.selectUserById(leaderId);
                if (leader != null && NORMAL_STATUS.equals(leader.getStatus())) {
                    managers.add(mapper.user(leader));
                    currentUserId = leaderId;
                    if (leader.getDeptId() != null && !leader.getDeptId().equals(departmentId)) {
                        departmentId = leader.getDeptId();
                        continue;
                    }
                }
            }
            departmentId = department.getParentId();
        }
        return new HostContractModels.UserItems(List.copyOf(managers));
    }

    private Set<Long> resolveCandidateIds(String roleCode, String positionCode) {
        Set<Long> roleIds = roleCode == null || roleCode.isBlank()
            ? null
            : roleMemberIds(requireRole(roleCode).getRoleId());
        Set<Long> positionIds = positionCode == null || positionCode.isBlank()
            ? null
            : positionMemberIds(requirePost(positionCode).getPostId());
        if (roleIds == null) {
            return positionIds;
        }
        if (positionIds == null) {
            return roleIds;
        }
        roleIds.retainAll(positionIds);
        return roleIds;
    }

    private Set<Long> roleMemberIds(Long roleId) {
        SysUserBo query = new SysUserBo();
        query.setRoleId(roleId);
        TableDataInfo<SysUserVo> result = userService.selectAllocatedList(
            query,
            new PageQuery(Integer.MAX_VALUE, 1)
        );
        if (result.getRows() == null) {
            return new LinkedHashSet<>();
        }
        return result.getRows().stream()
            .map(SysUserVo::getUserId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<Long> positionMemberIds(Long postId) {
        return userPostMapper.selectList(
            Wrappers.<SysUserPost>lambdaQuery().eq(SysUserPost::getPostId, postId)
        ).stream()
            .map(SysUserPost::getUserId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<HostContractModels.UserResponse> usersByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return userService.selectUserByIds(ids.stream().sorted().toList(), null).stream()
            .map(mapper::user)
            .toList();
    }

    private List<HostContractModels.UserResponse> mapUsers(List<SysUserVo> users) {
        return users == null ? List.of() : users.stream().map(mapper::user).toList();
    }

    private SysRoleVo requireRole(String code) {
        SysRoleVo role = resolveRole(code);
        if (role == null) {
            throw Ruoyi5ProtocolException.missing("role was not found");
        }
        return role;
    }

    private SysRoleVo resolveRole(String code) {
        String normalized = requireText(code, "roleCode");
        SysRoleBo query = new SysRoleBo();
        query.setRoleKey(normalized);
        return roleService.selectRoleList(query).stream()
            .filter(role -> normalized.equals(role.getRoleKey()))
            .findFirst()
            .orElse(null);
    }

    private SysPostVo requirePost(String code) {
        SysPostVo post = resolvePost(code);
        if (post == null) {
            throw Ruoyi5ProtocolException.missing("position was not found");
        }
        return post;
    }

    private SysPostVo resolvePost(String code) {
        String normalized = requireText(code, "positionCode");
        SysPostBo query = new SysPostBo();
        query.setPostCode(normalized);
        return postService.selectPostList(query).stream()
            .filter(post -> normalized.equals(post.getPostCode()))
            .findFirst()
            .orElse(null);
    }

    private Long numericExternalId(HostContractModels.ExternalIdRequest id, String objectType) {
        Objects.requireNonNull(id, "external id must not be null");
        if (!properties.getSource().equals(id.source()) || !objectType.equals(id.objectType())) {
            throw Ruoyi5ProtocolException.invalid("external id source or object type is invalid");
        }
        try {
            return Long.valueOf(requireText(id.value(), "externalId.value"));
        } catch (NumberFormatException exception) {
            throw Ruoyi5ProtocolException.invalid("external id value must be numeric");
        }
    }

    private static Instant resolveExpiry(String token, LoginUser loginUser) {
        if (loginUser.getExpireTime() != null && loginUser.getExpireTime() > 0) {
            return Instant.ofEpochMilli(loginUser.getExpireTime());
        }
        SaSession session = StpUtil.getTokenSessionByToken(token);
        long timeout = session.getTimeout();
        return timeout < 0
            ? Instant.ofEpochSecond(NEVER_EXPIRES_EPOCH_SECOND)
            : Instant.now().plusSeconds(timeout);
    }

    private static void put(Map<String, String> attributes, String key, String value) {
        if (value != null && !value.isBlank()) {
            attributes.put(key, value);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw Ruoyi5ProtocolException.invalid(name + " must not be blank");
        }
        return value.trim();
    }
}
