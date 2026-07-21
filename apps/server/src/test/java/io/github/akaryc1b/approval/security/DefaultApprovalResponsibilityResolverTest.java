package io.github.akaryc1b.approval.security;

import io.github.akaryc1b.approval.api.ApprovalManagementPermission;
import io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement;
import io.github.akaryc1b.approval.security.ApprovalAuthorizationDecision.Code;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultApprovalResponsibilityResolverTest {

    private static final Instant NOW = Instant.parse("2026-07-21T06:00:00Z");
    private static final DefaultApprovalResponsibilityResolver RESOLVER =
        new DefaultApprovalResponsibilityResolver(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void capabilityMatrixIsClosedCompleteAndRoleSpecific() {
        Map<ApprovalEnterpriseRole, Set<Requirement>> matrix =
            DefaultApprovalResponsibilityResolver.capabilityMatrixView();

        assertEquals(ApprovalEnterpriseRole.values().length, matrix.size());
        assertEquals(
            Set.copyOf(EnumSet.allOf(Requirement.class)),
            matrix.get(ApprovalEnterpriseRole.PLATFORM_ADMIN)
        );
        assertEquals(
            Set.copyOf(EnumSet.allOf(Requirement.class)),
            matrix.get(ApprovalEnterpriseRole.TENANT_ADMIN)
        );
        assertEquals(
            Set.of(Requirement.READ, Requirement.DESIGN),
            matrix.get(ApprovalEnterpriseRole.PROCESS_DESIGNER)
        );
        assertTrue(matrix.get(ApprovalEnterpriseRole.PARTICIPANT).isEmpty());
        matrix.forEach((role, requirements) -> {
            assertNotNull(role);
            assertNotNull(requirements);
            assertEquals(requirements.size(), Set.copyOf(requirements).size());
            requirements.forEach(requirement -> assertNotNull(requirement));
        });
    }

    @Test
    void departmentAdministratorCannotCrossDepartmentOrTenantScope() {
        ApprovalPrincipal principal = principal(
            assignment(
                ApprovalEnterpriseRole.DEPARTMENT_APPROVAL_ADMIN,
                ApprovalResourceScope.departments(Set.of("department-a"))
            )
        );

        ApprovalAuthorizationDecision allowed = RESOLVER.resolve(
            principal,
            Requirement.TRANSFER,
            ApprovalResource.department("tenant-a", "department-a")
        );
        assertTrue(allowed.allowed());
        assertEquals(ApprovalEnterpriseRole.DEPARTMENT_APPROVAL_ADMIN, allowed.matchedRole());

        ApprovalAuthorizationDecision wrongDepartment = RESOLVER.resolve(
            principal,
            Requirement.TRANSFER,
            ApprovalResource.department("tenant-a", "department-b")
        );
        assertFalse(wrongDepartment.allowed());
        assertEquals(Code.DENIED_RESOURCE_SCOPE, wrongDepartment.code());

        ApprovalAuthorizationDecision tenantWide = RESOLVER.resolve(
            principal,
            Requirement.TRANSFER,
            ApprovalResource.tenant("tenant-a")
        );
        assertFalse(tenantWide.allowed());
        assertEquals(Code.DENIED_RESOURCE_SCOPE, tenantWide.code());

        ApprovalAuthorizationDecision crossTenant = RESOLVER.resolve(
            principal,
            Requirement.TRANSFER,
            ApprovalResource.department("tenant-b", "department-a")
        );
        assertFalse(crossTenant.allowed());
        assertEquals(Code.DENIED_TENANT_MISMATCH, crossTenant.code());
    }

    @Test
    void managementRoleDoesNotCreateTaskAuthorityAndParticipantGetsNoManagement() {
        ApprovalPrincipal tenantAdmin = principal(
            assignment(ApprovalEnterpriseRole.TENANT_ADMIN, ApprovalResourceScope.tenant())
        );
        assertFalse(tenantAdmin.hasAuthority("approval.task.complete"));
        assertFalse(tenantAdmin.hasAuthority(Requirement.DESIGN.authority()));
        assertTrue(RESOLVER.resolve(
            tenantAdmin,
            Requirement.DESIGN,
            ApprovalResource.tenant("tenant-a")
        ).allowed());

        ApprovalPrincipal participant = principal(
            assignment(ApprovalEnterpriseRole.PARTICIPANT, ApprovalResourceScope.tenant())
        );
        ApprovalAuthorizationDecision decision = RESOLVER.resolve(
            participant,
            Requirement.READ,
            ApprovalResource.tenant("tenant-a")
        );
        assertFalse(decision.allowed());
        assertEquals(Code.DENIED_INSUFFICIENT_PERMISSION, decision.code());
    }

    @Test
    void inactiveResponsibilityIsIgnoredAndDirectCapabilityRemainsCompatible() {
        ApprovalResponsibilityAssignment expired = new ApprovalResponsibilityAssignment(
            ApprovalEnterpriseRole.PROCESS_DESIGNER,
            ApprovalResponsibilitySourceType.USER_GROUP,
            "group-designers",
            ApprovalResourceScope.tenant(),
            NOW.minusSeconds(600),
            NOW,
            2
        );
        ApprovalPrincipal expiredPrincipal = principal(expired);
        assertFalse(RESOLVER.resolve(
            expiredPrincipal,
            Requirement.DESIGN,
            ApprovalResource.tenant("tenant-a")
        ).allowed());

        ApprovalPrincipal direct = ApprovalPrincipal.active(
            "tenant-a",
            "operator-a",
            Set.of(ApprovalManagementPermission.Requirement.DESIGN.authority()),
            null
        );
        ApprovalAuthorizationDecision decision = RESOLVER.resolve(
            direct,
            Requirement.DESIGN,
            ApprovalResource.tenant("tenant-a")
        );
        assertTrue(decision.allowed());
        assertEquals(Code.ALLOWED_DIRECT_AUTHORITY, decision.code());
    }

    private static ApprovalPrincipal principal(
        ApprovalResponsibilityAssignment assignment
    ) {
        return ApprovalPrincipal.active(
            "tenant-a",
            "operator-a",
            Set.of(),
            Set.of(assignment),
            null
        );
    }

    private static ApprovalResponsibilityAssignment assignment(
        ApprovalEnterpriseRole role,
        ApprovalResourceScope scope
    ) {
        return new ApprovalResponsibilityAssignment(
            role,
            ApprovalResponsibilitySourceType.ROLE,
            "role-" + role.name().toLowerCase(java.util.Locale.ROOT),
            scope
        );
    }
}
