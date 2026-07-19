package io.github.akaryc1b.approval.api;

/** Stable authorization failure that never exposes the caller's supplied authority set. */
public final class ApprovalManagementPermissionDeniedException extends RuntimeException {

    private final ApprovalManagementPermission.Requirement requirement;
    private final Reason reason;

    public ApprovalManagementPermissionDeniedException(
        ApprovalManagementPermission.Requirement requirement,
        Reason reason
    ) {
        super("operator is not permitted to perform this approval management operation");
        this.requirement = requirement;
        this.reason = reason;
    }

    public ApprovalManagementPermission.Requirement requirement() {
        return requirement;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        UNAUTHENTICATED,
        MISSING_TRUSTED_HEADER,
        MALFORMED_TRUSTED_HEADER,
        INSUFFICIENT_PERMISSION
    }
}
