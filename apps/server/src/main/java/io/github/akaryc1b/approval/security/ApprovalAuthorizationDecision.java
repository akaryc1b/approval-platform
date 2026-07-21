package io.github.akaryc1b.approval.security;

import io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement;

import java.util.Objects;

/** Bounded authorization result that does not expose unrelated caller authorities. */
public record ApprovalAuthorizationDecision(
    boolean allowed,
    Code code,
    Requirement requirement,
    ApprovalEnterpriseRole matchedRole,
    ApprovalResourceScope.Kind matchedScope
) {

    public ApprovalAuthorizationDecision {
        code = Objects.requireNonNull(code, "code must not be null");
        requirement = Objects.requireNonNull(requirement, "requirement must not be null");
        if (allowed != code.allowed()) {
            throw new IllegalArgumentException(
                "allowed flag must match the authorization decision code"
            );
        }
    }

    public static ApprovalAuthorizationDecision direct(Requirement requirement) {
        return new ApprovalAuthorizationDecision(
            true,
            Code.ALLOWED_DIRECT_AUTHORITY,
            requirement,
            null,
            ApprovalResourceScope.Kind.TENANT
        );
    }

    public static ApprovalAuthorizationDecision responsibility(
        Requirement requirement,
        ApprovalEnterpriseRole role,
        ApprovalResourceScope.Kind scope
    ) {
        return new ApprovalAuthorizationDecision(
            true,
            Code.ALLOWED_RESPONSIBILITY,
            requirement,
            Objects.requireNonNull(role, "role must not be null"),
            Objects.requireNonNull(scope, "scope must not be null")
        );
    }

    public static ApprovalAuthorizationDecision denied(
        Requirement requirement,
        Code code
    ) {
        if (code.allowed()) {
            throw new IllegalArgumentException("denied decision requires a deny code");
        }
        return new ApprovalAuthorizationDecision(false, code, requirement, null, null);
    }

    public String roleMetricTag() {
        if (matchedRole != null) {
            return matchedRole.metricTag();
        }
        return allowed ? "direct-authority" : "none";
    }

    public enum Code {
        ALLOWED_DIRECT_AUTHORITY(true, "direct-authority"),
        ALLOWED_RESPONSIBILITY(true, "responsibility"),
        DENIED_TENANT_MISMATCH(false, "tenant-mismatch"),
        DENIED_RESOURCE_SCOPE(false, "resource-scope"),
        DENIED_INSUFFICIENT_PERMISSION(false, "insufficient-permission");

        private final boolean allowed;
        private final String metricTag;

        Code(boolean allowed, String metricTag) {
            this.allowed = allowed;
            this.metricTag = metricTag;
        }

        public boolean allowed() {
            return allowed;
        }

        public String metricTag() {
            return metricTag;
        }
    }
}
