package io.github.akaryc1b.approval.security;

/** Closed enterprise responsibility roles. Roles are not business-task participation evidence. */
public enum ApprovalEnterpriseRole {
    PLATFORM_ADMIN("platform-admin"),
    TENANT_ADMIN("tenant-admin"),
    PROCESS_DESIGNER("process-designer"),
    PROCESS_PUBLISHER("process-publisher"),
    AUDITOR("auditor"),
    OPERATIONS("operations"),
    DEPARTMENT_APPROVAL_ADMIN("department-approval-admin"),
    DATA_ARCHIVE_ADMIN("data-archive-admin"),
    CONNECTOR_ADMIN("connector-admin"),
    PARTICIPANT("participant");

    private final String metricTag;

    ApprovalEnterpriseRole(String metricTag) {
        this.metricTag = metricTag;
    }

    public String metricTag() {
        return metricTag;
    }
}
