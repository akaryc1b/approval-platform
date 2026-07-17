package io.github.akaryc1b.approval.ruoyi5.host;

import io.github.akaryc1b.approval.ruoyi5.host.HostContractModels.ExternalIdRequest;
import io.github.akaryc1b.approval.ruoyi5.host.HostContractModels.ManagerChainRequest;
import org.dromara.system.domain.SysUserPost;
import org.dromara.system.domain.vo.SysDeptVo;
import org.dromara.system.domain.vo.SysPostVo;
import org.dromara.system.domain.vo.SysRoleVo;
import org.dromara.system.domain.vo.SysUserVo;
import org.dromara.system.mapper.SysUserPostMapper;
import org.dromara.system.service.ISysDeptService;
import org.dromara.system.service.ISysPostService;
import org.dromara.system.service.ISysRoleService;
import org.dromara.system.service.ISysTenantService;
import org.dromara.system.service.ISysUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Ruoyi5ApprovalHostServiceTest {

    private ISysUserService userService;
    private ISysDeptService deptService;
    private ISysRoleService roleService;
    private ISysPostService postService;
    private ISysTenantService tenantService;
    private SysUserPostMapper userPostMapper;
    private Ruoyi5ApprovalHostService service;

    @BeforeEach
    void setUp() {
        userService = mock(ISysUserService.class);
        deptService = mock(ISysDeptService.class);
        roleService = mock(ISysRoleService.class);
        postService = mock(ISysPostService.class);
        tenantService = mock(ISysTenantService.class);
        userPostMapper = mock(SysUserPostMapper.class);
        ApprovalHostProperties properties = new ApprovalHostProperties();
        properties.setSource("ruoyi5");
        service = new Ruoyi5ApprovalHostService(
            properties,
            userService,
            deptService,
            roleService,
            postService,
            tenantService,
            userPostMapper
        );
    }

    @Test
    void mapsUserRolesPositionsAndDepartmentLeader() {
        SysUserVo user = user(1L, 10L, "alice", "Alice");
        SysRoleVo role = new SysRoleVo();
        role.setRoleKey("finance-manager");
        SysPostVo post = new SysPostVo();
        post.setPostCode("accountant");
        SysDeptVo dept = department(10L, 2L);

        when(userService.selectUserById(1L)).thenReturn(user);
        when(roleService.selectRolesByUserId(1L)).thenReturn(List.of(role));
        when(postService.selectPostsByUserId(1L)).thenReturn(List.of(post));
        when(deptService.selectDeptById(10L)).thenReturn(dept);

        var result = service.findUser(
            "tenant-a",
            new ExternalIdRequest("ruoyi5", "user", "1")
        );

        assertEquals("ruoyi5:user:1", canonical(result.id()));
        assertEquals("ruoyi5:department:10", canonical(result.departmentIds().get(0)));
        assertEquals("ruoyi5:user:2", canonical(result.managerId()));
        assertEquals(List.of("finance-manager"), result.roleCodes().stream().sorted().toList());
        assertEquals(List.of("accountant"), result.positionCodes().stream().sorted().toList());
    }

    @Test
    void managerChainStopsWhenDepartmentLeadersFormCycle() {
        SysUserVo user = user(1L, 10L, "alice", "Alice");
        SysUserVo manager = user(2L, 20L, "bob", "Bob");
        when(userService.selectUserById(1L)).thenReturn(user);
        when(userService.selectUserById(2L)).thenReturn(manager);
        when(deptService.selectDeptById(10L)).thenReturn(department(10L, 2L));
        when(deptService.selectDeptById(20L)).thenReturn(department(20L, 1L));
        when(roleService.selectRolesByUserId(any())).thenReturn(List.of());
        when(postService.selectPostsByUserId(any())).thenReturn(List.of());

        var result = service.managerChain(
            "tenant-a",
            new ManagerChainRequest(
                new ExternalIdRequest("ruoyi5", "user", "1"),
                10
            )
        );

        assertEquals(1, result.size());
        assertEquals("bob", result.get(0).username());
    }

    @Test
    void positionMembersUseRelationTableAndTenantFilter() {
        SysPostVo post = new SysPostVo();
        post.setPostId(90L);
        post.setPostCode("engineer");
        post.setPostName("Engineer");
        post.setStatus("0");
        SysUserPost relation = new SysUserPost();
        relation.setPostId(90L);
        relation.setUserId(1L);

        when(postService.selectPostList(any())).thenReturn(List.of(post));
        when(userPostMapper.selectList(any())).thenReturn(List.of(relation));
        when(userService.selectUserByIds(eq(List.of(1L)), eq(null))).thenReturn(
            List.of(user(1L, 10L, "alice", "Alice"))
        );
        when(roleService.selectRolesByUserId(1L)).thenReturn(List.of());
        when(postService.selectPostsByUserId(1L)).thenReturn(List.of(post));
        when(deptService.selectDeptById(10L)).thenReturn(department(10L, 1L));

        var result = service.positionMembers("tenant-a", "engineer");

        assertEquals(1, result.size());
        assertEquals("alice", result.get(0).username());
        assertNull(result.get(0).managerId());
    }

    private static SysUserVo user(
        Long userId,
        Long deptId,
        String username,
        String displayName
    ) {
        SysUserVo user = new SysUserVo();
        user.setUserId(userId);
        user.setTenantId("tenant-a");
        user.setDeptId(deptId);
        user.setUserName(username);
        user.setNickName(displayName);
        user.setStatus("0");
        return user;
    }

    private static SysDeptVo department(Long deptId, Long leader) {
        SysDeptVo dept = new SysDeptVo();
        dept.setDeptId(deptId);
        dept.setDeptName("Department " + deptId);
        dept.setLeader(leader);
        dept.setStatus("0");
        return dept;
    }

    private static String canonical(HostContractModels.ExternalIdResponse id) {
        return id.source() + ':' + id.objectType() + ':' + id.value();
    }
}
