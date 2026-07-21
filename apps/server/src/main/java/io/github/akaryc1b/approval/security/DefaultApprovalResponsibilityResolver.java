package io.github.akaryc1b.approval.security;

import io.github.akaryc1b.approval.api.ApprovalManagementPermission;
import io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement;
import io.github.akaryc1b.approval.security.ApprovalAuthorizationDecision.Code;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic enterprise role-to-capability and resource-scope resolver. */
public final class DefaultApprovalResponsibilityResolver
    implements ApprovalResponsibilityResolver {

    private static final Map<ApprovalEnterpriseRole, Set<Requirement>> CAPABILITIES =
        capabilityMatrix();

    private final Clock clock;

    public DefaultApprovalResponsibilityResolver(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public ApprovalAuthorizationDecision resolve(
        ApprovalPrincipal principal,
        Requirement requirement,
        ApprovalResource resource
    ) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(requirement, "requirement must not be null");
        Objects.requireNonNull(resource, "resource must not be null");

        if (!principal.tenantId().equals(resource.tenantId())) {
            return ApprovalAuthorizationDecision.denied(
                requirement,
                Code.DENIED_TENANT_MISMATCH
            );
        }
        if (principal.hasAuthority(ApprovalManagementPermission.ADMIN_AUTHORITY)
            || principal.hasAuthority(requirement.authority())) {
            return ApprovalAuthorizationDecision.direct(requirement);
        }

        Instant now = clock.instant();
        boolean capabilityOutsideScope = false;
        for (ApprovalResponsibilityAssignment assignment : orderedAssignments(principal)) {
            if (!assignment.activeAt(now)
                || !capabilities(assignment.role()).contains(requirement)) {
                continue;
            }
            if (assignment.scope().allows(resource)) {
                return ApprovalAuthorizationDecision.responsibility(
                    requirement,
                    assignment.role(),
                    assignment.scope().kind()
                );
            }
            capabilityOutsideScope = true;
        }
        return ApprovalAuthorizationDecision.denied(
            requirement,
            capabilityOutsideScope
                ? Code.DENIED_RESOURCE_SCOPE
                : Code.DENIED_INSUFFICIENT_PERMISSION
        );
    }

    static Set<Requirement> capabilities(ApprovalEnterpriseRole role) {
        Set<Requirement> requirements = CAPABILITIES.get(
            Objects.requireNonNull(role, "role must not be null")
        );
        if (requirements == null) {
            throw new IllegalStateException("enterprise role is absent from capability matrix");
        }
        return requirements;
    }

    static Map<ApprovalEnterpriseRole, Set<Requirement>> capabilityMatrixView() {
        return CAPABILITIES;
    }

    private static List<ApprovalResponsibilityAssignment> orderedAssignments(
        ApprovalPrincipal principal
    ) {
        return principal.responsibilities().stream()
            .sorted(Comparator
                .comparing(ApprovalResponsibilityAssignment::role)
                .thenComparing(ApprovalResponsibilityAssignment::sourceType)
                .thenComparing(ApprovalResponsibilityAssignment::sourceId)
                .thenComparingLong(ApprovalResponsibilityAssignment::version))
            .toList();
    }

    private static Map<ApprovalEnterpriseRole, Set<Requirement>> capabilityMatrix() {
        EnumMap<ApprovalEnterpriseRole, Set<Requirement>> matrix =
            new EnumMap<>(ApprovalEnterpriseRole.class);
        Set<Requirement> all = Set.copyOf(EnumSet.allOf(Requirement.class));
        matrix.put(ApprovalEnterpriseRole.PLATFORM_ADMIN, all);
        matrix.put(ApprovalEnterpriseRole.TENANT_ADMIN, all);
        matrix.put(
            ApprovalEnterpriseRole.PROCESS_DESIGNER,
            immutable(Requirement.READ, Requirement.DESIGN)
        );
        matrix.put(
            ApprovalEnterpriseRole.PROCESS_PUBLISHER,
            immutable(
                Requirement.READ,
                Requirement.PUBLISH,
                Requirement.DEPLOY,
                Requirement.ACTIVATE
            )
        );
        matrix.put(
            ApprovalEnterpriseRole.AUDITOR,
            immutable(
                Requirement.READ,
                Requirement.AUDIT_READ,
                Requirement.AUDIT_EXPORT,
                Requirement.AUDIT_VERIFY
            )
        );
        matrix.put(
            ApprovalEnterpriseRole.OPERATIONS,
            immutable(
                Requirement.READ,
                Requirement.CONSISTENCY_READ,
                Requirement.CONSISTENCY_RUN,
                Requirement.OPERATIONAL_FAILURE_READ,
                Requirement.OPERATIONAL_FAILURE_REPLAY
            )
        );
        matrix.put(
            ApprovalEnterpriseRole.DEPARTMENT_APPROVAL_ADMIN,
            immutable(Requirement.READ, Requirement.TRANSFER)
        );
        matrix.put(
            ApprovalEnterpriseRole.DATA_ARCHIVE_ADMIN,
            immutable(Requirement.READ)
        );
        matrix.put(
            ApprovalEnterpriseRole.CONNECTOR_ADMIN,
            immutable(Requirement.READ)
        );
        matrix.put(ApprovalEnterpriseRole.PARTICIPANT, Set.of());
        if (matrix.size() != ApprovalEnterpriseRole.values().length) {
            throw new IllegalStateException("enterprise role capability matrix is incomplete");
        }
        return Map.copyOf(matrix);
    }

    private static Set<Requirement> immutable(Requirement first, Requirement... remaining) {
        EnumSet<Requirement> values = EnumSet.of(first, remaining);
        return Set.copyOf(values);
    }
}
