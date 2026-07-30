package io.github.akaryc1b.approval.application.port;

import java.util.UUID;

/** Server-owned read of the exact current runtime-binding revision for one attempt. */
public interface ApprovalMigrationBindingRevisionReader {

    long currentRevision(String tenantId, UUID attemptId);
}
