package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.ResponsibilityChange;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstancePage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Tenant-scoped operational SLA queries with stable ordering. */
public interface ApprovalSlaManagementQuery {

    SlaInstancePage findUpcoming(String tenantId, Instant observedAt, Instant dueBefore, int limit, int offset);

    SlaInstancePage findOverdue(String tenantId, Instant observedAt, int limit, int offset);

    SlaInstancePage findByRequestId(String tenantId, String requestId, int limit, int offset);

    List<ResponsibilityChange> findResponsibilityChanges(
        String tenantId,
        UUID slaInstanceId,
        int limit
    );
}
