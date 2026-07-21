package io.github.akaryc1b.approval.security;

import io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement;
import io.github.akaryc1b.approval.domain.context.RequestContext;

/** Writes idempotent authorization evidence before a high-risk management operation executes. */
@FunctionalInterface
public interface ApprovalManagementGovernanceRecorder {

    void recordAuthorized(
        ApprovalPrincipal principal,
        Requirement requirement,
        ApprovalResource resource,
        ApprovalAuthorizationDecision decision,
        String reason,
        RequestContext context
    );

    static ApprovalManagementGovernanceRecorder noOp() {
        return (principal, requirement, resource, decision, reason, context) -> {
        };
    }
}
