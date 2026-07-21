package io.github.akaryc1b.approval.security;

import io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement;

/** Unified management responsibility and resource-scope decision boundary. */
public interface ApprovalResponsibilityResolver {

    ApprovalAuthorizationDecision resolve(
        ApprovalPrincipal principal,
        Requirement requirement,
        ApprovalResource resource
    );

    default void invalidate(String tenantId, String operatorId) {
    }

    default void clear() {
    }
}
