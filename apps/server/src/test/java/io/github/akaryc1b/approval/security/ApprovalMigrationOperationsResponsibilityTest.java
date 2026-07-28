package io.github.akaryc1b.approval.security;

import io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalMigrationOperationsResponsibilityTest {

    @Test
    void migrationOperationsVisibilityIsReadOnlyAndRoleSpecific() {
        Map<ApprovalEnterpriseRole, Set<Requirement>> matrix =
            DefaultApprovalResponsibilityResolver.capabilityMatrixView();

        for (ApprovalEnterpriseRole role : Set.of(
            ApprovalEnterpriseRole.PLATFORM_ADMIN,
            ApprovalEnterpriseRole.TENANT_ADMIN,
            ApprovalEnterpriseRole.PROCESS_PUBLISHER,
            ApprovalEnterpriseRole.AUDITOR,
            ApprovalEnterpriseRole.OPERATIONS
        )) {
            assertTrue(
                matrix.get(role).contains(Requirement.MIGRATION_OPERATIONS_READ),
                role.name()
            );
        }
        for (ApprovalEnterpriseRole role : Set.of(
            ApprovalEnterpriseRole.PROCESS_DESIGNER,
            ApprovalEnterpriseRole.DEPARTMENT_APPROVAL_ADMIN,
            ApprovalEnterpriseRole.DATA_ARCHIVE_ADMIN,
            ApprovalEnterpriseRole.CONNECTOR_ADMIN,
            ApprovalEnterpriseRole.PARTICIPANT
        )) {
            assertFalse(
                matrix.get(role).contains(Requirement.MIGRATION_OPERATIONS_READ),
                role.name()
            );
        }
        assertFalse(Requirement.MIGRATION_OPERATIONS_READ.requiresReason());
    }

    @Test
    void operationsRoleCannotCrossTenantAndParticipantCannotRead() {
        DefaultApprovalResponsibilityResolver resolver = new DefaultApprovalResponsibilityResolver(
            Clock.fixed(Instant.parse("2026-07-28T00:00:00Z"), ZoneOffset.UTC)
        );
        ApprovalPrincipal operations = principal(ApprovalEnterpriseRole.OPERATIONS);
        ApprovalPrincipal participant = principal(ApprovalEnterpriseRole.PARTICIPANT);

        assertTrue(resolver.resolve(
            operations,
            Requirement.MIGRATION_OPERATIONS_READ,
            ApprovalResource.tenant("tenant-a")
        ).allowed());
        assertFalse(resolver.resolve(
            operations,
            Requirement.MIGRATION_OPERATIONS_READ,
            ApprovalResource.tenant("tenant-b")
        ).allowed());
        assertFalse(resolver.resolve(
            participant,
            Requirement.MIGRATION_OPERATIONS_READ,
            ApprovalResource.tenant("tenant-a")
        ).allowed());
    }

    private static ApprovalPrincipal principal(ApprovalEnterpriseRole role) {
        return ApprovalPrincipal.active(
            "tenant-a",
            "operator-a",
            Set.of(),
            Set.of(new ApprovalResponsibilityAssignment(
                role,
                ApprovalResponsibilitySourceType.ROLE,
                "role-" + role.name().toLowerCase(java.util.Locale.ROOT),
                ApprovalResourceScope.tenant()
            )),
            null
        );
    }
}
